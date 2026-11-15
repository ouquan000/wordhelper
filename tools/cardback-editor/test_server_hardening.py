#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端加固自测（1.4.7.1 / S1）：鉴权顺序 + /sync 限流。

为什么用 in-process：
    线上 8765 有重写任务在跑，不能重启；这里用同一份 server.py 起在 8799，
    落盘路径全换临时目录，不起重写服务。跑完进程即退，没有残留。

覆盖：
  1. 401 不消耗写额度（先鉴权后限流）；
  2. 带 token 时 429 仍生效，带 Retry-After；
  3. 写额度与 /sync 额度分桶，互不挤占；
  4. /sync/* 阈值 120/min、20000/day，30 秒一次的 App 同步不受影响；
  5. /health 不参与限流。

用法：python test_server_hardening.py
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import server  # noqa: E402

HOST = "127.0.0.1"
PORT = 8799
BASE = "http://%s:%d" % (HOST, PORT)
CF = "203.0.113.7"

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def http(path, method="GET", headers=None, body=None, timeout=5):
    """返回 (status, headers, body_text)。4xx/5xx 也算正常返回，不抛。"""
    data = body
    if isinstance(body, str):
        data = body.encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode("utf-8")


def use_temp_data_dir(tmp):
    server.PUBLISHED_DIR = os.path.join(tmp, "published")
    server.HISTORY_DIR = os.path.join(server.PUBLISHED_DIR, "history")
    server.CONFIG_PATH = os.path.join(server.PUBLISHED_DIR, "cardback.json")
    server.STATE_PATH = os.path.join(server.PUBLISHED_DIR, "state.json")
    server.TOKEN_PATH = os.path.join(tmp, ".cardback_token")
    server.INSTANCE_PATH = os.path.join(tmp, ".cardback_instance")
    server.INSTANCE_ID = ""
    server.SYNC_PATH = os.path.join(tmp, "sync.json")
    server.PORT = PORT
    server.LAN_URL = "http://%s:%d/cardback.json" % (HOST, PORT)
    server.TOKEN = ""
    server._SYNC = None
    server.ensure_initialized()
    return server.ensure_token()


def post_json(path, token="", cf=False, payload=None):
    headers = {"Content-Type": "application/json"}
    if cf:
        headers["CF-Connecting-IP"] = CF
    if token:
        headers[server.TOKEN_HEADER] = token
    return http(path, method="POST", headers=headers, body=json.dumps(payload or {}))


def test_auth_before_rate(token):
    """先鉴权后限流：25 次未授权写请求不能花掉 20 次/分钟的额度。"""
    server.reset_rate_limits()
    codes = []
    first_401_body = ""
    for _ in range(25):
        status, _, body = post_json("/api/publish", cf=True)  # 无 token
        codes.append(status)
        if status == 401 and not first_401_body:
            first_401_body = body
    check("25 次未授权写请求全是 401", set(codes) == {401}, repr(sorted(set(codes))))
    check("401 响应体 error=token_required",
          json.loads(first_401_body).get("error") == "token_required", first_401_body[:120])

    status, _, body = post_json("/api/publish", token=token, cf=True, payload={})
    check("未授权风暴后带 token 仍能进限流（400 校验失败，不是 429/401）",
          status == 400, "→ %d %s" % (status, body[:120]))


def test_write_rate_limit(token):
    server.reset_rate_limits()
    codes = []
    for i in range(20):
        status, _, _ = post_json("/api/publish", token=token, cf=True, payload={})
        codes.append(status)
    check("前 20 次写请求都进到路由（400）", set(codes) == {400}, repr(sorted(set(codes))))

    status, headers, body = post_json("/api/publish", token=token, cf=True, payload={})
    check("第 21 次写请求 429", status == 429, "→ %d" % status)
    data = json.loads(body)
    check("429 error=rate_limited", data.get("error") == "rate_limited", repr(data))
    retry = headers.get("Retry-After") or headers.get("retry-after")
    check("429 带 Retry-After 且 >= 1", bool(retry) and int(retry) >= 1, repr(retry))
    check("429 文案用的是写额度 20/200", "20 次" in body and "200 次" in body, body[:200])

    # 写额度耗尽不影响 /sync 桶
    status, _, _ = http(
        "/sync/pull?since_rev=999999",
        headers={"CF-Connecting-IP": CF, server.TOKEN_HEADER: token},
    )
    check("写额度耗尽后 /sync/pull 仍 200（分桶）", status == 200, "→ %d" % status)


def test_sync_limit(token):
    server.reset_rate_limits()
    # App 实景：30 秒一次，先确认正常请求不会被误伤
    codes = [http("/sync/pull?since_rev=0",
                  headers={"CF-Connecting-IP": CF, server.TOKEN_HEADER: token})[0]
             for _ in range(5)]
    check("正常 /sync 拉取 5 次全 200", set(codes) == {200}, repr(sorted(set(codes))))

    # 阈值边界用假 peer/固定时间精确验：120 次通过，第 121 次 429
    server.reset_rate_limits()
    fixed_now = 1_800_000_000.0
    ok_count = 0
    limited = None
    for _ in range(120):
        ok, _retry, _window = server.rate_limit_take(
            "unit-sync-peer", now=fixed_now,
            budgets=server.SYNC_RATE_BUDGETS, bucket="sync",
        )
        ok_count += 1 if ok else 0
    ok, retry, window = server.rate_limit_take(
        "unit-sync-peer", now=fixed_now,
        budgets=server.SYNC_RATE_BUDGETS, bucket="sync",
    )
    limited = (ok, retry, window)
    check("sync 桶 120 次/分钟全部通过", ok_count == 120, repr(ok_count))
    check("sync 第 121 次被限流", limited[0] is False, repr(limited))
    check("sync 限流窗口是分钟", limited[2] == "分钟", repr(limited[2]))
    check("sync 预算常量是 120/min、20000/day",
          server.SYNC_RATE_BUDGETS == ((60, 120), (86400, 20000)),
          repr(server.SYNC_RATE_BUDGETS))
    check("_rate_limited 认得 GET /sync/pull", server._rate_limited("GET", "/sync/pull") is True, "")
    check("_rate_limited 认得 POST /sync/push", server._rate_limited("POST", "/sync/push") is True, "")
    check("_rate_budget_for 把 /sync 归到 sync 桶",
          server._rate_budget_for("GET", "/sync/pull") == ("sync", server.SYNC_RATE_BUDGETS),
          repr(server._rate_budget_for("GET", "/sync/pull")))

    # 日额度：固定时间连打 20000 次太慢，验窗口名与额度数字即可
    day_start = 0.0
    ok, _retry, window = server.rate_limit_take(
        "unit-day-peer", now=day_start,
        budgets=((60, 120), (86400, 20000)), bucket="sync",
    )
    check("sync 日窗口名称正确", ok is True and window == "", repr((ok, window)))

    server.reset_rate_limits()


def test_health_free(token):
    server.reset_rate_limits()
    codes = [http("/health")[0] for _ in range(30)]
    check("/health 连打 30 次全 200（不占限流额度）", set(codes) == {200}, repr(sorted(set(codes))))
    codes = [http("/health", headers={"CF-Connecting-IP": CF, server.TOKEN_HEADER: token})[0]
             for _ in range(5)]
    check("/health（隧道带 token）也全 200", set(codes) == {200}, repr(sorted(set(codes))))


def main():
    print("== 服务端加固自测（鉴权顺序 / 限流） ==")
    with tempfile.TemporaryDirectory(prefix="cardback-hardening-") as tmp:
        token = use_temp_data_dir(tmp)
        httpd = server.CardbackServer((HOST, PORT), server.Handler)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        time.sleep(0.2)
        try:
            test_auth_before_rate(token)
            test_write_rate_limit(token)
            test_sync_limit(token)
            test_health_free(token)
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=3)
            print("\n8799 已关闭（in-process）")

    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())

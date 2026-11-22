#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""限流分档 + 真实客户端 IP 自测（1.5.2 / P0）。

为什么要有这个文件：
    cloudflared 回源时 TCP peer 恒为 127.0.0.1，1.5.1 之前所有公网设备挤在同一个
    限流桶里——一台刷爆，全网都没得用。1.5.2 改成按「真实客户端 IP」记账，并且
    把额度分两档（公网匿名 20/200，自家请求 VIP 120/5000）。

覆盖：
  1. client_ip()：loopback 时取 CF-Connecting-IP → X-Forwarded-For 首个合法地址；
     非 loopback 一律用 peer，伪造头不生效；非法值回落 peer（纯单元，不联网）。
  2. 额度常量与 _rate_budget_for(vip=) 的分档；/admin/rate-reset 不占额度。
  3. HTTP 层：带合法 token 拿到 VIP 额度（>20 次仍不 429）；本机 loopback 免票也走 VIP；
     公网匿名请求先吃 401、一个计数都不花。
  4. POST /admin/rate-reset：loopback 也要求 token，带 token 能清空所有桶且自身不计账。

注：公网匿名档（20/200）在 HTTP 层永远命中不到——_guard 先鉴权后限流，匿名请求拿到
的是 401 而不是 429。所以那一档在第 2 组用「常量 + rate_limit_take 默认档位」直接验证。

用法：python test_rate_limit_vip.py
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
from types import SimpleNamespace

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


def post_json(path, token="", cf=None, payload=None, extra_headers=None):
    """cf=None 表示不带 CF 头（模拟本机直连）；cf="1.2.3.4" 表示走隧道回源。"""
    headers = {"Content-Type": "application/json"}
    if cf is not None:
        headers["CF-Connecting-IP"] = cf
    if token:
        headers[server.TOKEN_HEADER] = token
    if extra_headers:
        headers.update(extra_headers)
    return http(path, method="POST", headers=headers, body=json.dumps(payload or {}))


def fake_handler(peer, headers=None):
    return SimpleNamespace(client_address=(peer, 12345), headers=headers or {})


# --------------------------------------------------------------- 1. client_ip


def test_client_ip_unit():
    """纯单元：限流键必须是「真实客户端 IP」，且伪造头只在 loopback 回源时才被采信。"""
    loop = "127.0.0.1"

    got = server.client_ip(fake_handler(loop, {server.CF_IP_HEADER: "198.51.100.23"}))
    check("loopback + CF-Connecting-IP → 取真实 IP", got == "198.51.100.23", got)

    got = server.client_ip(fake_handler(loop, {"X-Forwarded-For": "203.0.113.5, 70.41.3.18"}))
    check("loopback + 只有 X-Forwarded-For → 取第一个地址", got == "203.0.113.5", got)

    got = server.client_ip(
        fake_handler(loop, {server.CF_IP_HEADER: "garbage", "X-Forwarded-For": "203.0.113.6"})
    )
    check("CF 头非法 → 退回 X-Forwarded-For", got == "203.0.113.6", got)

    got = server.client_ip(fake_handler(loop, {"X-Forwarded-For": "198.51.100.9:443"}))
    check("X-Forwarded-For 带端口 → 剥掉端口", got == "198.51.100.9", got)

    got = server.client_ip(fake_handler(loop, {server.CF_IP_HEADER: "[2001:db8::1]:443"}))
    check("CF 头是方括号 IPv6 → 剥成裸地址", got == "2001:db8::1", got)

    got = server.client_ip(
        fake_handler(loop, {server.CF_IP_HEADER: "not-an-ip", "X-Forwarded-For": "also-bad"})
    )
    check("两个头都非法 → 回落 peer", got == loop, got)

    got = server.client_ip(fake_handler(loop, None))
    check("没有任何头 → 回落 peer", got == loop, got)

    got = server.client_ip(fake_handler("::1", {server.CF_IP_HEADER: "198.51.100.24"}))
    check("IPv6 loopback 同样认转发头", got == "198.51.100.24", got)

    got = server.client_ip(
        fake_handler("198.51.100.77", {server.CF_IP_HEADER: "1.2.3.4", "X-Forwarded-For": "5.6.7.8"})
    )
    check("非 loopback 时伪造头不生效（仍用 peer）", got == "198.51.100.77", got)

    got = server.client_ip(
        fake_handler("198.51.100.78", {"X-Forwarded-For": "9.9.9.9"})
    )
    check("非 loopback 时 X-Forwarded-For 也不生效", got == "198.51.100.78", got)

    check("Handler 用的是同一套 client_ip()", hasattr(server.Handler, "_client_ip"))
    check("SERVICE_VERSION 已升到 1.5.2", server.SERVICE_VERSION == "1.5.2", server.SERVICE_VERSION)


# ---------------------------------------------------- 2. 额度分档（不联网）


def test_budget_tiers():
    check("公网匿名写档 20/200", server.RATE_BUDGETS == ((60, 20), (86400, 200)),
          repr(server.RATE_BUDGETS))
    check("VIP 写档 120/5000", server.RATE_BUDGETS_VIP == ((60, 120), (86400, 5000)),
          repr(server.RATE_BUDGETS_VIP))
    check("sync 档没被降级（仍是 120/20000）",
          server.SYNC_RATE_BUDGETS == ((60, 120), (86400, 20000)),
          repr(server.SYNC_RATE_BUDGETS))
    check("sync VIP 档只放宽不收紧",
          server.SYNC_RATE_BUDGETS_VIP[0][1] >= server.SYNC_RATE_BUDGETS[0][1]
          and server.SYNC_RATE_BUDGETS_VIP[1][1] >= server.SYNC_RATE_BUDGETS[1][1],
          repr(server.SYNC_RATE_BUDGETS_VIP))

    check("写接口匿名档 → RATE_BUDGETS",
          server._rate_budget_for("POST", "/api/publish") == ("write", server.RATE_BUDGETS))
    check("写接口 VIP 档 → RATE_BUDGETS_VIP",
          server._rate_budget_for("POST", "/api/publish", vip=True)
          == ("write", server.RATE_BUDGETS_VIP))
    check("sync 接口匿名档 → SYNC_RATE_BUDGETS",
          server._rate_budget_for("GET", "/sync/pull") == ("sync", server.SYNC_RATE_BUDGETS))
    check("sync 接口 VIP 档 → SYNC_RATE_BUDGETS_VIP",
          server._rate_budget_for("GET", "/sync/pull", vip=True)
          == ("sync", server.SYNC_RATE_BUDGETS_VIP))
    check("admin reset 不占额度（匿名口径）",
          server._rate_budget_for("POST", server.ADMIN_RESET_PATH) == (None, None))
    check("admin reset 不占额度（VIP 口径也一样）",
          server._rate_budget_for("POST", server.ADMIN_RESET_PATH, vip=True) == (None, None))

    # 公网匿名档只能在单元层验证（HTTP 层会先吃 401）
    fixed = 1_800_000_000.0
    server.reset_rate_limits()
    anon = [server.rate_limit_take("unit-anon-ip", now=fixed,
                                   budgets=server.RATE_BUDGETS, bucket="write")
            for _ in range(server.RATE_BUDGETS[0][1])]
    check("匿名档前 %d 次放行" % server.RATE_BUDGETS[0][1],
          all(ok for ok, _, _ in anon), repr(anon[:1]))
    ok, retry, window = server.rate_limit_take("unit-anon-ip", now=fixed,
                                               budgets=server.RATE_BUDGETS, bucket="write")
    check("匿名档第 21 次被拦", ok is False and retry >= 1 and window == "分钟",
          "ok=%r retry=%r window=%r" % (ok, retry, window))

    vip_hits = [server.rate_limit_take("unit-vip-ip", now=fixed,
                                       budgets=server.RATE_BUDGETS_VIP, bucket="write")
                for _ in range(server.RATE_BUDGETS_VIP[0][1])]
    check("VIP 档前 %d 次放行" % server.RATE_BUDGETS_VIP[0][1],
          all(ok for ok, _, _ in vip_hits))
    ok, _retry, window = server.rate_limit_take("unit-vip-ip", now=fixed,
                                                budgets=server.RATE_BUDGETS_VIP, bucket="write")
    check("VIP 档第 121 次被拦", ok is False and window == "分钟", "ok=%r" % (ok,))

    # 不同 IP 是不同桶：匿名桶爆了不影响另一个 IP
    ok, _, _ = server.rate_limit_take("unit-anon-ip-2", now=fixed,
                                      budgets=server.RATE_BUDGETS, bucket="write")
    check("换 IP 就是换桶（不会被别人的超额连坐）", ok is True, "ok=%r" % (ok,))

    cleared = server.reset_rate_limits()
    check("reset_rate_limits 清空并回报条数",
          cleared >= 1 and server._RATE_HITS == {}, "cleared=%r" % (cleared,))


# ------------------------------------------------------------ 3. HTTP 层分档


def test_vip_http(token):
    """带合法 token 的自家请求拿 VIP 额度：25 次远小于 120，不能出现 429。"""
    server.reset_rate_limits()
    codes = []
    for _ in range(25):
        status, _, _ = post_json("/api/publish", token=token, cf=CF, payload={})
        codes.append(status)
    check("VIP 请求 25 次都进到路由（400，无 429）", set(codes) == {400},
          repr(sorted(set(codes))))

    keys = list(server._RATE_HITS)
    check("限流键是真实客户端 IP（不是 127.0.0.1）",
          keys == [CF], repr(keys))

    # 本机 loopback 免票（不带任何转发头）同样按 VIP 处理
    server.reset_rate_limits()
    codes = []
    for _ in range(25):
        status, _, _ = post_json("/api/publish", payload={})
        codes.append(status)
    check("本机免票请求也走 VIP 档（25 次全 400）", set(codes) == {400},
          repr(sorted(set(codes))))
    check("本机请求的键是 loopback", list(server._RATE_HITS) == ["127.0.0.1"],
          repr(list(server._RATE_HITS)))


def test_anon_no_quota_burn():
    """公网匿名请求先吃 401：一个计数都不花（否则匿名流量能替别人烧额度）。"""
    server.reset_rate_limits()
    anon_ip = "203.0.113.11"
    codes = []
    bodies = []
    for _ in range(25):
        status, _, body = post_json("/api/publish", cf=anon_ip, payload={})
        codes.append(status)
        bodies.append(body)
    check("25 次无 token 公网请求全 401", set(codes) == {401}, repr(sorted(set(codes))))
    check("401 的 error=token_required",
          all(json.loads(b).get("error") == "token_required" for b in bodies), bodies[0][:120])
    check("401 不写限流账本", anon_ip not in server._RATE_HITS, repr(list(server._RATE_HITS)))

    # 但带上 token 之后，同一个 IP 就开始记账了
    status, _, _ = post_json("/api/publish", token="", cf=anon_ip, payload={},
                             extra_headers={server.TOKEN_HEADER: "wrong-token"})
    check("错误 token 也是 401", status == 401, "→ %d" % status)


# --------------------------------------------------------- 4. admin rate-reset


def test_admin_reset(token):
    server.reset_rate_limits()
    for _ in range(5):
        post_json("/api/publish", token=token, cf=CF, payload={})
    before = len(server._RATE_HITS)
    check("先攒出限流账本", before >= 1 and CF in server._RATE_HITS, repr(list(server._RATE_HITS)))

    status, _, body = post_json(server.ADMIN_RESET_PATH, payload={})
    data = json.loads(body)
    check("本机免票也要求 token：无 token 一律 401", status == 401, "→ %d %s" % (status, body[:100]))
    check("无 token 时 error=token_required", data.get("error") == "token_required", repr(data))
    check("无 token 时账本没被清", CF in server._RATE_HITS, repr(list(server._RATE_HITS)))

    status, _, body = post_json(server.ADMIN_RESET_PATH, token="wrong-token", payload={})
    check("错误 token 也拒绝", status == 401, "→ %d" % status)

    status, _, body = post_json(server.ADMIN_RESET_PATH, token=token, payload={})
    data = json.loads(body)
    check("带合法 token → 200", status == 200, "→ %d %s" % (status, body[:120]))
    check("返回 ok=true", data.get("ok") is True, repr(data))
    check("返回 cleared 是整数且 >= 1",
          isinstance(data.get("cleared"), int) and data["cleared"] >= 1, repr(data.get("cleared")))
    check("所有限流桶已清空", server._RATE_HITS == {}, repr(list(server._RATE_HITS)))

    status, _, _ = post_json("/api/publish", token=token, cf=CF, payload={})
    check("清桶后同一 IP 立刻恢复额度（400 而不是 429）", status == 400, "→ %d" % status)

    # reset 自己不能进账本，否则运维接口会把自己也锁住
    status, _, body = post_json(server.ADMIN_RESET_PATH, token=token, payload={})
    data = json.loads(body)
    check("reset 自身不计账（清完后又空了）",
          status == 200 and server._RATE_HITS == {}, repr(list(server._RATE_HITS)))
    check("第二次 reset 的 cleared 能是 0 以外的自然数",
          isinstance(data.get("cleared"), int), repr(data.get("cleared")))


def main():
    with tempfile.TemporaryDirectory(prefix="cardback-ratelimit-") as tmp:
        token = use_temp_data_dir(tmp)
        print("token 就绪（长度 %d），起服务在 %s" % (len(token), BASE))
        httpd = server.CardbackServer((HOST, PORT), server.Handler)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        time.sleep(0.3)
        try:
            print("\n[1] client_ip()")
            test_client_ip_unit()
            print("\n[2] 额度分档")
            test_budget_tiers()
            print("\n[3] HTTP 层：VIP vs 本机免票")
            test_vip_http(token)
            print("\n[4] HTTP 层：公网匿名不吃额度")
            test_anon_no_quota_burn()
            print("\n[5] POST /admin/rate-reset")
            test_admin_reset(token)
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(3)
            server.reset_rate_limits()

    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    for name in FAILED:
        print("  FAILED: %s" % name)
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())

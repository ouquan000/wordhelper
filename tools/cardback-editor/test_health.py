#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GET /health 自测（1.4.7 / A1）。

为什么不直接 `python server.py --port 8799`：
    那样会连 published/、.cardback_token、sync.json，还会 _start_rewrite() 起第二个
    重写服务进程去抢 tools/rewrite-service/data 里的队列表 —— 正在跑的 8765（PID 32740）
    有正在跑的重写任务，不能碰。

所以这里用同一份 server.py 模块、真起 HTTP、真走 do_GET/_guard，只把落盘路径和端口
换成临时目录 + 8799；**不起重写服务**。跑完本进程直接退出，不留后台进程。

用法：python test_health.py
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

import server  # noqa: E402 - 先把 sys.path 摆好

HOST = "127.0.0.1"
PORT = 8799
BASE = "http://%s:%d" % (HOST, PORT)

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def http_get(path, headers=None, timeout=5):
    """返回 (status, headers, body_text)。4xx/5xx 也算正常返回，不抛。"""
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode("utf-8")


def use_temp_data_dir(tmp):
    """把模块级的落盘路径全指到临时目录，绝不碰真实 published/。"""
    server.PUBLISHED_DIR = os.path.join(tmp, "published")
    server.HISTORY_DIR = os.path.join(server.PUBLISHED_DIR, "history")
    server.CONFIG_PATH = os.path.join(server.PUBLISHED_DIR, "cardback.json")
    server.STATE_PATH = os.path.join(server.PUBLISHED_DIR, "state.json")
    server.TOKEN_PATH = os.path.join(tmp, ".cardback_token")
    server.SYNC_PATH = os.path.join(tmp, "sync.json")
    server.PORT = PORT
    server.LAN_URL = "http://%s:%d/cardback.json" % (HOST, PORT)
    server.TOKEN = ""
    server.ensure_initialized()
    return server.ensure_token()


def main():
    print("== /health 自测 ==")
    print("server.SERVER_VERSION = %s" % server.SERVER_VERSION)
    print("server.CAPABILITIES  = %s" % (list(server.CAPABILITIES),))

    with tempfile.TemporaryDirectory(prefix="cardback-health-") as tmp:
        token = use_temp_data_dir(tmp)
        # 起服务：只用编辑器 Handler，不起 _start_rewrite()
        httpd = server.CardbackServer((HOST, PORT), server.Handler)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        time.sleep(0.2)  # 让 serve_forever 进循环

        try:
            run_checks(token)
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=3)
            print("\n8799 已关闭（in-process，本进程退出即无残留）")

    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    return 0 if not FAILED else 1


def run_checks(token):
    # ---------- 1. 本机免票（既不带 CF 头，也不带口令）
    status, headers, body = http_get("/health")
    print("\n[1] 本机 GET /health（无 token）→ %d" % status)
    print(body)
    check("本机免票 200", status == 200, "→ %d" % status)

    data = json.loads(body)
    check("ok == True", data.get("ok") is True, repr(data.get("ok")))
    check("service == cardback-editor", data.get("service") == server.SERVICE_NAME, repr(data.get("service")))
    check(
        "version 与 server_version 同源",
        data.get("version") == server.SERVICE_VERSION
        and data.get("server") == server.SERVER_VERSION
        and server.Handler.server_version == server.SERVER_VERSION,
        "%r / %r / Handler=%r"
        % (data.get("version"), data.get("server"), server.Handler.server_version),
    )
    check("protocol 是整数", isinstance(data.get("protocol"), int), repr(data.get("protocol")))
    check(
        "started_at 是 ISO8601",
        isinstance(data.get("started_at"), str) and "T" in data["started_at"],
        repr(data.get("started_at")),
    )
    check("uptime_s 是非负整数", isinstance(data.get("uptime_s"), int) and data["uptime_s"] >= 0,
          repr(data.get("uptime_s")))
    check("pid == 本进程", data.get("pid") == os.getpid(), "%r vs %r" % (data.get("pid"), os.getpid()))
    caps = data.get("capabilities")
    check(
        "capabilities 是非空字符串数组",
        isinstance(caps, list) and caps and all(isinstance(c, str) and c for c in caps),
        repr(caps),
    )
    check("capabilities 里没有 skip/outbox（本地能力，服务端无接口）",
          "skip" not in caps and "outbox" not in caps, repr(caps))
    check("口令没回显", token not in body, "token 长度 %d" % len(token))

    # ---------- 2. Server 头仍用同一常量
    check(
        "Server 头以 %s 开头" % server.SERVER_VERSION,
        headers.get("Server", "").startswith(server.SERVER_VERSION),
        headers.get("Server", ""),
    )
    # ---------- 3. 模拟 Cloudflare 回源：有 CF-Connecting-IP = 不是本机
    status, _, body = http_get("/health", {"CF-Connecting-IP": "203.0.113.7"})
    print("\n[2] CF 头 + 无 token → %d" % status)
    print(body)
    check("CF 头无 token 401", status == 401, "→ %d" % status)
    check("401 里没有口令", token not in body, "")

    status, _, body = http_get(
        "/health", {"CF-Connecting-IP": "203.0.113.7", server.TOKEN_HEADER: token}
    )
    print("\n[3] CF 头 + token → %d" % status)
    print(body)
    check("CF 头带 token 200", status == 200 and json.loads(body).get("ok") is True, "→ %d" % status)

    # ---------- 4. 其它路由不受影响
    status, _, body = http_get("/cardback.json")
    check("/cardback.json 仍 200", status == 200 and json.loads(body).get("schemaVersion") == 1,
          "→ %d" % status)
    status, _, _ = http_get("/health/")
    check("/health/ 不是我的路由（404）", status == 404, "→ %d" % status)

    # ---------- 5. 不占限流额度：连打 30 次都是 200
    codes = [http_get("/health")[0] for _ in range(30)]
    check("连打 30 次 /health 全 200（不走限流）", set(codes) == {200}, repr(sorted(set(codes))))


if __name__ == "__main__":
    sys.exit(main())

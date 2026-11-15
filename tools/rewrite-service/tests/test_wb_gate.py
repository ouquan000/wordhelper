#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""G01：WB 统一串行槽 + 共享冷却 + 前台优先级。

全部用本地假 WB / 临时 gate 文件，不碰真实 WB，也不改生产配置。
"""

import json
import os
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.path.dirname(HERE)
if SERVICE not in sys.path:
    sys.path.insert(0, SERVICE)

import providers  # noqa: E402
import store as store_mod  # noqa: E402
import wb_gate  # noqa: E402


class _FakeWb(BaseHTTPRequestHandler):
    # 每次请求关连接，避免 Windows 上 keep-alive + 未读 body 造成 WinError 10053，
    # 让 503 冷却路径偶发没执行（test_wb_gate 的 flake）。
    protocol_version = "HTTP/1.0"
    lock = threading.Lock()
    active = 0
    max_active = 0
    calls = 0
    mode = "429"
    fail_once = True

    def do_POST(self):  # noqa: N802
        # 必须读掉请求体：Windows 上客户端还在写时服务端先回包/关连接会触发
        # WinError 10053，urllib 把它当 URLError 重试，503/429 的冷却路径就偶发没执行。
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        type(self).calls += 1
        with type(self).lock:
            type(self).active += 1
            type(self).max_active = max(type(self).max_active, type(self).active)
        try:
            # 每个用例自己控制“第一次失败”，不要依赖全局 calls 计数：
            # 全套跑时前一个用例的 keep-alive 连接可能让计数错位。
            if type(self).fail_once:
                type(self).fail_once = False
                if type(self).mode == "429":
                    body = b'{"error":"rate limited"}'
                    self.send_response(429)
                    self.send_header("Retry-After", "1")
                else:
                    body = b'{"error":"model_busy"}'
                    self.send_response(503)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                return
            body = json.dumps({
                "choices": [{"message": {"content": "通了"}}]
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        finally:
            with type(self).lock:
                type(self).active -= 1

    def log_message(self, fmt, *args):
        pass


class WbGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_gate = os.environ.get("REWRITE_WB_GATE")
        self.old_phase = os.environ.get("REWRITE_WB_PHASE_FILE")
        self.old_base = providers.PROVIDER_BASE.get("wb")
        os.environ["REWRITE_WB_GATE"] = os.path.join(self.tmp.name, "wb_gate.json")
        os.environ["REWRITE_WB_PHASE_FILE"] = os.path.join(self.tmp.name, "wb_phase")
        wb_gate.reset()

    def tearDown(self):
        providers.PROVIDER_BASE["wb"] = self.old_base
        if self.old_gate is None:
            os.environ.pop("REWRITE_WB_GATE", None)
        else:
            os.environ["REWRITE_WB_GATE"] = self.old_gate
        if self.old_phase is None:
            os.environ.pop("REWRITE_WB_PHASE_FILE", None)
        else:
            os.environ["REWRITE_WB_PHASE_FILE"] = self.old_phase
        self.tmp.cleanup()

    def test_serial_slot(self):
        active = 0
        max_active = 0
        lock = threading.Lock()

        def worker(i):
            nonlocal active, max_active
            with wb_gate.acquire(priority=100, timeout=5):
                with lock:
                    active += 1
                    max_active = max(max_active, active)
                time.sleep(0.12)
                with lock:
                    active -= 1

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(max_active, 1, "串行槽被并发穿透")

    def test_stale_active_slot_is_recovered(self):
        # 模拟上次进程被强杀：gate 文件 active=true，但是很久以前。
        path = os.environ["REWRITE_WB_GATE"]
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({
                "active": True,
                "holder": "pid=999999,pri=100",
                "acquiredAt": time.time() - wb_gate.STALE_ACTIVE_SECONDS - 10,
                "cooldownUntil": 0.0,
            }, fh)
        with wb_gate.acquire(priority=100, timeout=2):
            pass
        self.assertFalse(wb_gate.status()["active"])

    def test_429_cooldown_then_recover(self):
        _FakeWb.calls = 0
        _FakeWb.max_active = 0
        _FakeWb.mode = "429"
        _FakeWb.fail_once = True
        server = ThreadingHTTPServer(("127.0.0.1", 0), _FakeWb)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        providers.PROVIDER_BASE["wb"] = "http://127.0.0.1:%d/v1" % server.server_port
        try:
            with self.assertRaises(providers.ProviderError):
                providers.complete("ds-v41-flash", "s", "u", timeout=5)
            self.assertGreater(wb_gate.cooldown_remaining(), 0.0)
            time.sleep(1.2)
            out = providers.complete("ds-v41-flash", "s", "u", timeout=5)
            self.assertEqual(out, "通了")
            self.assertEqual(_FakeWb.max_active, 1)
            self.assertEqual(wb_gate.cooldown_remaining(), 0.0)
        finally:
            server.shutdown()
            server.server_close()

    def test_503_quota_sets_longer_cooldown(self):
        _FakeWb.calls = 0
        _FakeWb.mode = "503"
        _FakeWb.fail_once = True
        server = ThreadingHTTPServer(("127.0.0.1", 0), _FakeWb)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        providers.PROVIDER_BASE["wb"] = "http://127.0.0.1:%d/v1" % server.server_port
        try:
            with self.assertRaises(providers.ProviderError):
                providers.complete("ds-v41-flash", "s", "u", timeout=5)
            self.assertGreaterEqual(wb_gate.cooldown_remaining(), 10.0)
            self.assertIn("503", wb_gate.status()["cooldownReason"])
        finally:
            server.shutdown()
            server.server_close()

    def test_phase_foreground_and_batch(self):
        wb_gate.set_phase("batch")
        with self.assertRaises(wb_gate.GateBusy):
            with wb_gate.acquire(priority=100, timeout=1):
                pass
        with wb_gate.acquire(priority=10, timeout=1):
            pass
        wb_gate.set_phase("foreground")
        with self.assertRaises(wb_gate.GateBusy):
            with wb_gate.acquire(priority=10, timeout=1):
                pass
        wb_gate.set_phase("auto")

    def test_store_claim_prefers_foreground(self):
        data_dir = os.path.join(self.tmp.name, "store")
        os.makedirs(data_dir)
        store = store_mod.Store(data_dir).load()
        store.add_marks([
            {"clientId": "c-batch", "word": "batch", "model": "ds-v41-flash", "priority": 10},
            {"clientId": "c-user", "word": "user", "model": "ds-v41-flash", "priority": 100},
        ])
        first = store.claim(1)
        self.assertEqual(first["word"], "user")
        store.close()


if __name__ == "__main__":
    unittest.main()

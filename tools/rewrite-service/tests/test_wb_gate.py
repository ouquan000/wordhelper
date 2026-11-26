#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WB 网关 N 槽池 + 共享冷却降档 + 前台优先级。

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
        self.old_slots = os.environ.get("WB_GATE_SLOTS")
        self.old_wait = os.environ.get("WB_GATE_WAIT_TIMEOUT")
        self.old_recovery = os.environ.get("WB_GATE_RECOVERY_SUCCESSES")
        self.old_base = providers.PROVIDER_BASE.get("wb")
        os.environ["REWRITE_WB_GATE"] = os.path.join(self.tmp.name, "wb_gate.json")
        os.environ["REWRITE_WB_PHASE_FILE"] = os.path.join(self.tmp.name, "wb_phase")
        # 每个用例从默认值出发，避免宿主环境把槽位数/超时带进来。
        os.environ.pop("WB_GATE_SLOTS", None)
        os.environ.pop("WB_GATE_WAIT_TIMEOUT", None)
        os.environ.pop("WB_GATE_RECOVERY_SUCCESSES", None)
        wb_gate.reset()

    def tearDown(self):
        providers.PROVIDER_BASE["wb"] = self.old_base
        for key, value in (
            ("REWRITE_WB_GATE", self.old_gate),
            ("REWRITE_WB_PHASE_FILE", self.old_phase),
            ("WB_GATE_SLOTS", self.old_slots),
            ("WB_GATE_WAIT_TIMEOUT", self.old_wait),
            ("WB_GATE_RECOVERY_SUCCESSES", self.old_recovery),
        ):
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        self.tmp.cleanup()

    def _run_pool_probe(self, slots, threads_count, hold=0.3):
        os.environ["WB_GATE_SLOTS"] = str(slots)
        wb_gate.reset()  # effectiveLimit 跟随本用例的 N，而不是上一个用例的 N
        active = 0
        max_active = 0
        lock = threading.Lock()
        errors = []

        def worker(i):
            nonlocal active, max_active
            try:
                with wb_gate.acquire(priority=100, timeout=15):
                    with lock:
                        active += 1
                        max_active = max(max_active, active)
                    time.sleep(hold)
                    with lock:
                        active -= 1
            except Exception as exc:  # noqa: BLE001 - 线程里的异常要带回主线程断言
                errors.append(exc)

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(threads_count)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(30)
        self.assertFalse(errors, "并发槽位探测有异常：%r" % errors)
        self.assertFalse(any(thread.is_alive() for thread in threads), "并发线程未退出")
        return max_active

    def test_default_slots_is_two(self):
        self.assertEqual(wb_gate.slot_count(), 2)
        state = wb_gate.status()
        self.assertEqual(state["slots"], 2)
        self.assertEqual(state["effectiveLimit"], 2)
        self.assertEqual(state["waitTimeout"], 300.0)

    def test_pool_allows_three_parallel_slots(self):
        max_active = self._run_pool_probe(slots=3, threads_count=6)
        self.assertEqual(max_active, 3, "N=3 的槽池并发度不是 3")
        gate = os.environ["REWRITE_WB_GATE"]
        for index in range(3):
            self.assertTrue(
                os.path.exists("%s.slot_%d.lock" % (gate, index)),
                "缺少槽锁文件 slot_%d" % index,
            )
        self.assertEqual(wb_gate.status()["activeCount"], 0)
        self.assertFalse(wb_gate.status()["active"])

    def test_two_gate_paths_do_not_share_slots(self):
        # N=1 时如果两个 gate 路径共用一个进程内信号量，B 会拿不到槽。
        os.environ["WB_GATE_SLOTS"] = "1"
        gate_a = os.environ["REWRITE_WB_GATE"]
        gate_b = os.path.join(self.tmp.name, "other_gate.json")
        with wb_gate.acquire(priority=100, timeout=2):
            self.assertEqual(wb_gate.status()["activeCount"], 1)
            os.environ["REWRITE_WB_GATE"] = gate_b
            try:
                started = time.time()
                with wb_gate.acquire(priority=100, timeout=1):
                    self.assertLess(time.time() - started, 1.0, "不同 gate 路径串槽了")
                    self.assertEqual(wb_gate.status()["activeCount"], 1)
                self.assertEqual(wb_gate.status()["activeCount"], 0)
            finally:
                os.environ["REWRITE_WB_GATE"] = gate_a
        self.assertEqual(wb_gate.status()["activeCount"], 0)

    def test_queue_timeout_raises_gatebusy(self):
        os.environ["WB_GATE_SLOTS"] = "1"
        result = {}

        def contender():
            try:
                with wb_gate.acquire(priority=100, timeout=0.4):
                    result["acquired"] = True
            except wb_gate.GateBusy as exc:
                result["busy"] = str(exc)

        with wb_gate.acquire(priority=100, timeout=2):
            thread = threading.Thread(target=contender)
            started = time.time()
            thread.start()
            thread.join(5)
        elapsed = time.time() - started
        self.assertFalse(thread.is_alive(), "等槽线程没有按 timeout 退出")
        self.assertIn("busy", result, "等槽超时没有抛 GateBusy")
        self.assertNotIn("acquired", result)
        self.assertLess(elapsed, 3.0, "等槽超时没有按显式 timeout 生效")

    def test_429_lowers_effective_limit_then_successes_recover(self):
        os.environ["WB_GATE_SLOTS"] = "3"
        wb_gate.note_http(429, retry_after="1", body='{"error":"rate limited"}')
        state = wb_gate.status()
        self.assertEqual(state["effectiveLimit"], 2, "429 后没有降到 2")
        self.assertGreater(state["cooldownRemaining"], 0.0)
        self.assertIn("429", state["cooldownReason"])

        for _ in range(5):
            wb_gate.note_success()
        state = wb_gate.status()
        self.assertEqual(state["effectiveLimit"], 3, "连续成功 5 次后没有恢复到 N=3")
        self.assertEqual(state["successStreak"], 0)
        self.assertEqual(state["cooldownRemaining"], 0.0)

    def test_429_effective_limit_not_below_two(self):
        os.environ["WB_GATE_SLOTS"] = "5"
        wb_gate.note_http(429, retry_after="1")
        self.assertEqual(wb_gate.status()["effectiveLimit"], 2)

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
            self.assertEqual(wb_gate.status()["effectiveLimit"], 2)
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
            self.assertEqual(wb_gate.status()["effectiveLimit"], 2)
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

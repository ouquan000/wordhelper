#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.5.3 版本与接口暴露面回归。

前端/脚本靠这些字段判断「这台服务是不是 1.5.3」，所以版本号必须能从
/rewrite/status、/models 和 worker status 三处读到，且新接口必须真的挂上去。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import service  # noqa: E402
import worker  # noqa: E402
from store import Store  # noqa: E402


class VersionTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self._saved = (service.DATA_DIR, service.LOG_PATH, dict(service._state))
        os.makedirs(os.path.join(self.dir, "logs"), exist_ok=True)
        service.DATA_DIR = self.dir
        service.LOG_PATH = os.path.join(self.dir, "logs", "service.log")
        worker.configure(self.dir)
        self.store = Store(self.dir).load()
        service._state.update({"store": self.store, "ready": True, "startedAt": "test"})

    def tearDown(self):
        worker._state["store"] = None
        data_dir, log_path, old_state = self._saved
        service.DATA_DIR = data_dir
        service.LOG_PATH = log_path
        service._state.clear()
        service._state.update(old_state)
        self._tmp.cleanup()

    def _call(self, method, path, payload=None):
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        status, _ctype, raw = service.respond(method, path, body)
        return status, json.loads(raw.decode("utf-8"))

    def test_service_version_constant(self):
        self.assertEqual(service.SERVICE_VERSION, "1.5.3")

    def test_status_carries_version_and_worker(self):
        status, payload = self._call("GET", "/rewrite/status")
        self.assertEqual(status, 200)
        self.assertEqual(payload["version"], "1.5.3")
        self.assertIn("worker", payload)
        self.assertFalse(payload["worker"]["enabled"])
        # 老字段一个都不能少
        for key in ("pending", "running", "concurrency", "startedAt", "judge", "wbGate"):
            self.assertIn(key, payload)

    def test_models_carries_version(self):
        status, payload = self._call("GET", "/models")
        self.assertEqual(status, 200)
        self.assertEqual(payload["version"], "1.5.3")
        self.assertIsInstance(payload["models"], (dict, list))

    def test_new_endpoints_are_routed(self):
        status, payload = self._call("GET", "/rewrite/locks")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])

        status, payload = self._call("GET", "/rewrite/worker/status")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["version"], "1.5.3")

        status, payload = self._call("POST", "/rewrite/lock", {"wordId": 5, "bookId": 2})
        self.assertEqual(status, 200)
        self.assertTrue(payload["locked"])

        status, payload = self._call("POST", "/rewrite/worker/toggle", {"enabled": False})
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])

    def test_unknown_route_still_404s(self):
        # 未知路由返回 None，宿主 server.py 据此回 404 —— 新加的路由不许顺手接住它
        self.assertIsNone(service.respond("GET", "/rewrite/not-a-thing"))


if __name__ == "__main__":
    unittest.main()

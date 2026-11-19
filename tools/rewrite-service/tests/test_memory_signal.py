#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""POST /rewrite/memory-signal：校验、幂等、30 分钟冷却、画像权重确实变化。"""

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import profile  # noqa: E402
import service  # noqa: E402
from store import Store  # noqa: E402


class MemorySignalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name).load()
        self.old_state = dict(service._state)
        self.old_data_dir = service.DATA_DIR
        self.old_log_path = service.LOG_PATH
        service.DATA_DIR = self.tmp.name
        service.LOG_PATH = os.path.join(self.tmp.name, "logs", "service.log")
        service._state.update({"store": self.store, "ready": True, "startedAt": "test"})

    def tearDown(self):
        self.store.close()
        service._state.clear()
        service._state.update(self.old_state)
        service.DATA_DIR = self.old_data_dir
        service.LOG_PATH = self.old_log_path
        self.tmp.cleanup()

    def post(self, payload):
        status, _ctype, raw = service.respond(
            "POST", "/rewrite/memory-signal", json.dumps(payload).encode("utf-8")
        )
        return status, json.loads(raw.decode("utf-8"))

    def payload(self, **overrides):
        data = {
            "clientId": "c1",
            "word": "cathedral",
            "bookId": "book-1",
            "method": "shape",
            "event": "retained",
            "stability": 12.5,
            "difficulty": 6.0,
            "lapses": 1,
        }
        data.update(overrides)
        return data

    def alpha_beta(self, method):
        row = profile.get_profile(os.path.join(self.tmp.name, "profile.json")).snapshot()["methods"][method]
        return row["alpha"], row["beta"]

    def test_first_retained_applies_and_updates_alpha(self):
        before = self.alpha_beta("shape")
        status, out = self.post(self.payload())
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])
        self.assertTrue(out["applied"])
        self.assertNotIn("reason", out)
        after = self.alpha_beta("shape")
        self.assertAlmostEqual(after[0], before[0] + 1.5)
        self.assertEqual(after[1], before[1])

    def test_first_lapsed_applies_and_updates_beta(self):
        before = self.alpha_beta("shape")
        status, out = self.post(self.payload(event="lapsed"))
        self.assertEqual(status, 200)
        self.assertTrue(out["applied"])
        after = self.alpha_beta("shape")
        self.assertAlmostEqual(after[1], before[1] + 1.5)
        self.assertEqual(after[0], before[0])

    def test_duplicate_client_id_is_idempotent(self):
        first = self.post(self.payload())
        second = self.post(self.payload())
        self.assertTrue(first[1]["applied"])
        self.assertFalse(second[1]["applied"])
        self.assertIn("重复", second[1].get("reason", ""))
        # 只加过一次权重
        self.assertAlmostEqual(self.alpha_beta("shape")[0], 4.5)

    def test_lapsed_has_thirty_minute_cooldown_per_word_method(self):
        first = self.post(self.payload(clientId="c1", event="lapsed"))
        second = self.post(self.payload(clientId="c2", event="lapsed"))
        self.assertTrue(first[1]["applied"])
        self.assertFalse(second[1]["applied"])
        self.assertIn("冷却", second[1].get("reason", ""))
        self.assertAlmostEqual(self.alpha_beta("shape")[1], 3.5)

    def test_retained_is_not_cooled(self):
        first = self.post(self.payload(clientId="c1", event="retained"))
        second = self.post(self.payload(clientId="c2", event="retained"))
        self.assertTrue(first[1]["applied"])
        self.assertTrue(second[1]["applied"])
        self.assertAlmostEqual(self.alpha_beta("shape")[0], 6.0)

    def test_different_book_is_a_different_signal(self):
        self.assertTrue(self.post(self.payload(bookId="book-1"))[1]["applied"])
        self.assertTrue(self.post(self.payload(bookId="book-2"))[1]["applied"])

    def test_invalid_event_is_rejected(self):
        status, out = self.post(self.payload(event="forgotten"))
        self.assertEqual(status, 400)
        self.assertIn("event", out["error"])

    def test_invalid_method_is_rejected(self):
        status, out = self.post(self.payload(method="magic"))
        self.assertEqual(status, 400)
        self.assertIn("method", out["error"])

    def test_missing_word_is_rejected(self):
        status, out = self.post(self.payload(word=""))
        self.assertEqual(status, 400)
        self.assertIn("word", out["error"])

    def test_signal_survives_store_reload(self):
        self.post(self.payload(clientId="persist-1"))
        reloaded = Store(self.tmp.name).load()
        try:
            service._state["store"] = reloaded
            status, out = self.post(self.payload(clientId="persist-1"))
            self.assertEqual(status, 200)
            self.assertFalse(out["applied"])
            self.assertIn("重复", out["reason"])
        finally:
            reloaded.close()
            service._state["store"] = self.store


if __name__ == "__main__":
    unittest.main()

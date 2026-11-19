#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""POST /rewrite/feedback 的画像接线：只让 store 真正接受的反馈进入 Beta。"""

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


class FeedbackProfileTest(unittest.TestCase):
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

    def post(self, items):
        status, _ctype, raw = service.respond(
            "POST", "/rewrite/feedback", json.dumps({"items": items}).encode("utf-8")
        )
        return status, json.loads(raw.decode("utf-8"))

    def alpha_beta(self, method):
        row = profile.get_profile(os.path.join(self.tmp.name, "profile.json")).snapshot()["methods"][method]
        return row["alpha"], row["beta"]

    def item(self, client_id="f1", rating="up", method="sound"):
        return {
            "clientId": client_id,
            "word": "cathedral",
            "bookId": "book-1",
            "method": method,
            "rating": rating,
            "approach": ["① 这版是用户点过赞的卡片。", "② 每一步都能顺着读下来。", "③ 收在词义上 ——> 大教堂。"],
        }

    def test_up_and_down_update_profile(self):
        before = self.alpha_beta("sound")
        status, out = self.post([self.item()])
        self.assertEqual(status, 200)
        self.assertEqual(out["accepted"], ["f1"])
        self.assertAlmostEqual(self.alpha_beta("sound")[0], before[0] + 1.0)

        before = self.alpha_beta("sound")
        status, out = self.post([self.item(client_id="f2", rating="down")])
        self.assertEqual(status, 200)
        self.assertAlmostEqual(self.alpha_beta("sound")[1], before[1] + 1.2)

    def test_duplicate_client_id_updates_profile_once(self):
        payload = [self.item(client_id="same")]
        self.assertEqual(self.post(payload)[0], 200)
        self.assertEqual(self.post(payload)[0], 200)
        self.assertAlmostEqual(self.alpha_beta("sound")[0], 7.0)

    def test_rejected_feedback_does_not_update_profile(self):
        before = self.alpha_beta("sound")
        status, out = self.post([self.item(rating="maybe")])
        self.assertEqual(status, 200)
        self.assertEqual(len(out["rejected"]), 1)
        self.assertEqual(self.alpha_beta("sound"), before)

    def test_missing_client_id_does_not_update_profile(self):
        before = self.alpha_beta("sound")
        bad = self.item()
        bad.pop("clientId")
        status, out = self.post([bad])
        self.assertEqual(status, 200)
        self.assertEqual(len(out["rejected"]), 1)
        self.assertEqual(self.alpha_beta("sound"), before)


if __name__ == "__main__":
    unittest.main()

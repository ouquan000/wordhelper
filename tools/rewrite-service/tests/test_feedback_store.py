#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W1-A 回归：手机端上传的 approachRev 必须原样落进 feedback.jsonl。

旧 bug 是 DTO 漏传、服务端只看到 clientId 里的 -rN；这里锁住「服务端不吞字段」。
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

from store import Store  # noqa: E402


class FeedbackStoreTest(unittest.TestCase):

    def test_add_feedback_keeps_approach_rev(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback([
                {
                    "clientId": "f73-1-down-r3",
                    "wordId": 73,
                    "bookId": 1,
                    "word": "cathedral",
                    "rating": "down",
                    "approach": "旧思路",
                    "method": "sound",
                    "model": "ds-v41-flash",
                    "instruction": "谐音太牵强",
                    "approachRev": 3,
                    "createdAt": "2026-11-10T11:00:00+08:00",
                }
            ])
            self.assertEqual(accepted, ["f73-1-down-r3"])
            self.assertEqual(rejected, [])
            with open(os.path.join(tmp, "feedback.jsonl"), encoding="utf-8") as fh:
                rows = [json.loads(line) for line in fh if line.strip()]
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["approachRev"], 3)

    def test_add_feedback_is_idempotent_by_client_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            item = {
                "clientId": "f7-1-up-r2",
                "wordId": 7,
                "bookId": 1,
                "word": "chronic",
                "rating": "up",
                "approach": "x",
                "method": "root",
                "model": "ds-v41-flash",
                "instruction": "",
                "approachRev": 2,
                "createdAt": "2026-11-10T11:00:00+08:00",
            }
            store.add_feedback([item])
            accepted, _ = store.add_feedback([item])
            self.assertEqual(accepted, ["f7-1-up-r2"])
            with open(os.path.join(tmp, "feedback.jsonl"), encoding="utf-8") as fh:
                self.assertEqual(len([line for line in fh if line.strip()]), 1)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""活水正反例：近 7 天优先、隔夜打脸踢出、反馈坏行跳过。"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import rewrite_protection  # noqa: E402
import taste_flow  # noqa: E402


class TasteFlowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def _vote(self, word, rating, stamp):
        with open(os.path.join(self.dir, "feedback.jsonl"), "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"clientId": word + rating, "word": word, "rating": rating,
                                 "createdAt": stamp}, ensure_ascii=False) + "\n")

    def test_recent_positive_outranks_old(self):
        now = 1_800_000_000.0
        data = {"pos": [
            {"word": "old", "note": "两个月前", "at": "2026-09-01T00:00:00Z"},
            {"word": "fresh", "note": "前天", "at": "2026-11-24T00:00:00Z"},
        ], "neg": []}
        refined = taste_flow.refine(data, data_dir=self.dir, now=now)
        self.assertEqual([row["word"] for row in refined["pos"]], ["fresh", "old"])

    def test_overnight_flip_becomes_negative(self):
        self._vote("alpha", "up", "2026-11-20T10:00:00+08:00")
        self._vote("alpha", "down", "2026-11-21T09:00:00+08:00")
        data = {"pos": [{"word": "alpha", "note": "先点赞的那版", "kind": "pos"}], "neg": []}
        refined = taste_flow.refine(data, data_dir=self.dir)
        self.assertEqual(refined["pos"], [])
        self.assertEqual(len(refined["neg"]), 1)
        self.assertEqual(refined["neg"][0]["kind"], "neg")
        self.assertTrue(refined["neg"][0]["flipped"])
        self.assertIn("隔夜打脸", refined["neg"][0]["note"])

    def test_negative_with_later_up_vote_is_dropped(self):
        self._vote("beta", "down", "2026-11-20T10:00:00+08:00")
        self._vote("beta", "up", "2026-11-22T10:00:00+08:00")
        data = {"pos": [], "neg": [{"word": "beta", "note": "早先的雷"}]}
        refined = taste_flow.refine(data, data_dir=self.dir)
        self.assertEqual(refined["neg"], [])

    def test_latest_up_keeps_positive(self):
        self._vote("gamma", "up", "2026-11-20T10:00:00+08:00")
        self._vote("gamma", "down", "2026-11-21T10:00:00+08:00")
        self._vote("gamma", "up", "2026-11-22T10:00:00+08:00")
        data = {"pos": [{"word": "gamma", "note": "最后又点赞"}], "neg": []}
        refined = taste_flow.refine(data, data_dir=self.dir)
        self.assertEqual([row["word"] for row in refined["pos"]], ["gamma"])

    def test_feedback_rows_skips_broken_lines(self):
        path = os.path.join(self.dir, "feedback.jsonl")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write('{"word": "ok", "rating": "up"}\n')
            fh.write("\n")
            fh.write("{broken json\n")
            fh.write('["not", "a", "dict"]\n')
            fh.write('{"word": "ok2", "rating": "down"}\n')
        rows = rewrite_protection.feedback_rows(self.dir)
        self.assertEqual([row.get("word") for row in rows], ["ok", "ok2"])
        self.assertEqual(rewrite_protection.feedback_rows(os.path.join(self.dir, "nope")), [])

    def test_non_dict_data_is_returned_untouched(self):
        self.assertEqual(taste_flow.refine(None), None)
        self.assertEqual(taste_flow.refine({"pos": "bad"})["pos"], [])


if __name__ == "__main__":
    unittest.main()

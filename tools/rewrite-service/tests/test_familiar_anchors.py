#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真熟词锚点：筛选、检索、注入、硬规则白名单放行（全离线）。"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from unittest import mock

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import familiar_anchors  # noqa: E402
import pipeline  # noqa: E402
import rules_hard  # noqa: E402


def _row(word, book="六级记忆卡", stability=None, reps=0, lapses=0):
    row = {"w": word, "b": book, "reps": reps, "fsrs_lapses": lapses,
           "fsrs_state": 2, "device": "test"}
    if stability is not None:
        row["fsrs_stability"] = stability
    return row


class FamiliarAnchorsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        self.config = os.path.join(self.dir, "worker.json")
        self.sync = os.path.join(self.dir, "sync.json")
        with open(self.config, "w", encoding="utf-8") as fh:
            json.dump({"sync_path": self.sync}, fh)
        self.env = mock.patch.dict(os.environ, {
            "REWRITE_WORKER_CONFIG": self.config,
            "REWRITE_DATA_DIR": self.dir,
        })
        self.env.start()
        familiar_anchors.clear_cache()

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()
        familiar_anchors.clear_cache()

    def _write(self, rows):
        payload = {"rev": 1, "epoch": 0, "seen": {},
                   "rows": {"%s\u0000%s" % (row["b"], row["w"]): row for row in rows}}
        with open(self.sync, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False)

    def _seed(self):
        self._write([
            _row("inherent", stability=12.727588, reps=2),
            _row("import", stability=20.0, reps=5),
            _row("expedition", stability=0.40255, reps=0),
            _row("legacy", reps=4),
            _row("shaky", stability=15.0, reps=6, lapses=2),
            _row("radiance", book="考研词汇", stability=30.0, reps=9),
        ])

    def test_filters_stable_words_only(self):
        self._seed()
        keys = set(familiar_anchors.index())
        self.assertIn("inherent", keys)
        self.assertIn("import", keys)
        self.assertIn("legacy", keys)          # 老数据没有 fsrs，reps>=3 且无 lapses
        self.assertNotIn("expedition", keys)   # 稳定度不够
        self.assertNotIn("shaky", keys)        # 有过 lapses
        self.assertNotIn("radiance", keys)     # 不是这本书
        self.assertNotIn("", keys)

    def test_candidates_exclude_self_and_cap_at_five(self):
        self._seed()
        words = familiar_anchors.candidates("inherently")
        self.assertEqual([item["word"] for item in words], ["inherent"])
        self.assertLessEqual(len(words), familiar_anchors.MAX_ANCHORS)
        self.assertNotIn("inherently", [item["word"] for item in words])

    def test_no_match_returns_empty(self):
        self._seed()
        self.assertEqual(familiar_anchors.candidates("zzzzqqq"), [])
        self.assertEqual(familiar_anchors.prompt_block("zzzzqqq"), "")

    def test_missing_sync_degrades_silently(self):
        os.remove(self.sync) if os.path.exists(self.sync) else None
        self.assertEqual(familiar_anchors.candidates("inherent"), [])
        self.assertEqual(familiar_anchors.whitelist("inherent"), set())

    def test_corrupt_sync_degrades_silently(self):
        with open(self.sync, "w", encoding="utf-8") as fh:
            fh.write('{"rows": {broken')
        familiar_anchors.clear_cache()
        self.assertEqual(familiar_anchors.candidates("inherent"), [])

    def test_latest_down_vote_excludes_word(self):
        self._seed()
        with open(os.path.join(self.dir, "feedback.jsonl"), "w", encoding="utf-8") as fh:
            fh.write(json.dumps({"clientId": "c1", "word": "inherent", "rating": "down",
                                 "createdAt": "2026-11-20T10:00:00+08:00"}, ensure_ascii=False) + "\n")
        familiar_anchors.clear_cache()
        self.assertEqual(familiar_anchors.candidates("inherently"), [])

    def test_prompt_and_judge_blocks(self):
        self._seed()
        prompt = familiar_anchors.prompt_block("inherently")
        self.assertIn("用户已背熟的可用锚点（可自然借力，无须硬用）", prompt)
        self.assertIn("inherent", prompt)
        judge = familiar_anchors.judge_block("inherently")
        self.assertIn("动态真熟词白名单", judge)
        self.assertIn("has_unknown_part", judge)
        self.assertEqual(familiar_anchors.whitelist("inherently"), {"inherent"})

    def test_pipeline_briefs_include_blocks(self):
        self._seed()
        familiar_anchors.clear_cache()
        brief = pipeline._brief({"word": "inherently", "meaning": "天生地"})
        self.assertIn("用户已背熟的可用锚点", brief)
        judge_brief = pipeline._judge_brief({"word": "inherently", "meaning": "天生地"})
        self.assertIn("动态真熟词白名单", judge_brief)

    def test_rules_hard_whitelist_allows_dole(self):
        derivation = [
            "dole 是古英语里「分发」那一截，doleful 就是心里被分走了",
            "dole 本来指发给穷人的那份救济",
            "——> 悲哀的，愁苦的",
        ]
        with mock.patch.object(familiar_anchors, "whitelist", return_value=set()):
            blocked = rules_hard.check(derivation, elements=["dole"], target_word="doleful")
        self.assertFalse(blocked.ok)
        self.assertTrue(any("dole" in hit for hit in blocked.hits))
        with mock.patch.object(familiar_anchors, "whitelist", return_value={"dole"}):
            allowed = rules_hard.check(derivation, elements=["dole"], target_word="doleful")
        self.assertTrue(allowed.ok, allowed.hits)


if __name__ == "__main__":
    unittest.main()

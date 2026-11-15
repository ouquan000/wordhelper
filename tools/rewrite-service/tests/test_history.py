#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""同词历史派生自测（W1）：旧 feedback 正文缺 approachRev 时从 clientId 还原。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import history  # noqa: E402
import pipeline  # noqa: E402


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = self.dir.name
        os.makedirs(os.path.join(self.root, "results"), exist_ok=True)
        rows = [
            {"clientId": "f73-1-down-r3", "word": "cathedral", "wordId": 73, "bookId": 1,
             "rating": "down", "approachRev": 0, "method": "root", "instruction": "别再用词根了，看不懂",
             "approach": "cathedr 是椅子……", "createdAt": "2026-11-01T10:00:00+08:00"},
            {"clientId": "f73-1-up-r2", "word": "cathedral", "wordId": 73, "bookId": 1,
             "rating": "up", "approachRev": 0, "method": "sound", "instruction": "",
             "approach": "cat 坐在教堂里", "createdAt": "2026-11-02T10:00:00+08:00"},
        ]
        with open(os.path.join(self.root, "feedback.jsonl"), "w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        record = {"word": "cathedral", "entries": [
            {"seq": 1, "rev": 2, "status": "ok", "method": "root", "unverified": True,
             "reason": "两轮都没过评审判定", "approach": ["旧稿"]},
        ]}
        with open(os.path.join(self.root, "results", "cathedral.json"), "w", encoding="utf-8") as fh:
            json.dump(record, fh, ensure_ascii=False)

    def tearDown(self):
        self.dir.cleanup()

    def test_old_body_rev_falls_back_to_client_id(self):
        self.assertEqual(history.revision_of({"clientId": "f73-1-down-r3", "approachRev": 0}), 3)
        self.assertEqual(history.revision_of({"clientId": "f73-1-down-r3", "approachRev": 4}), 4)

    def test_build_contains_complaint_upvote_and_failure(self):
        text = history.build(self.root, "cathedral")
        self.assertIn("第3版", text)
        self.assertIn("别再用词根了", text)
        self.assertIn("被点赞", text)
        self.assertIn("未复核", text)
        self.assertLessEqual(len(text), history.MAX_TEXT)

    def test_other_word_does_not_leak(self):
        self.assertEqual(history.build(self.root, "referee"), "")

    def test_queue_instruction_is_included_even_when_feedback_instruction_empty(self):
        with open(os.path.join(self.root, "queue.json"), "w", encoding="utf-8") as fh:
            json.dump({"items": [{
                "word": "cathedral",
                "instruction": "别再用词根了 根本看不懂",
                "createdAt": "2026-11-03T10:00:00+08:00",
            }]}, fh, ensure_ascii=False)
        text = history.build(self.root, "cathedral")
        self.assertIn("用户原话", text)
        self.assertIn("别再用词根了", text)

    def test_name_only_feedback_is_preserved_but_not_used_by_either_same_named_book(self):
        feedback_path = os.path.join(self.root, "feedback.jsonl")
        rows = [
            {"word": "anchor", "book": "shared", "rating": "down", "instruction": "legacy complaint"},
            {"word": "anchor", "book": "shared", "bookId": 11, "rating": "down", "instruction": "first complaint"},
            {"word": "anchor", "book": "shared", "bookId": 12, "rating": "down", "instruction": "second complaint"},
        ]
        with open(feedback_path, "w", encoding="utf-8") as fh:
            fh.writelines(json.dumps(row) + "\n" for row in rows)
        before = Path(feedback_path).read_bytes()
        first = history.build(self.root, "anchor", scope={"book": "shared", "bookId": 11})
        second = history.build(self.root, "anchor", scope={"book": "shared", "bookId": 12})
        old = history.build(self.root, "anchor", scope={"book": "shared"})
        self.assertIn("first complaint", first)
        self.assertIn("second complaint", second)
        self.assertIn("legacy complaint", old)
        self.assertNotIn("second complaint", first)
        self.assertNotIn("first complaint", second)
        self.assertNotIn("legacy complaint", first)
        self.assertNotIn("legacy complaint", second)
        self.assertNotIn("first complaint", old)
        self.assertNotIn("second complaint", old)
        self.assertEqual(Path(feedback_path).read_bytes(), before)

    def test_pipeline_brief_carries_history(self):
        item = {"word": "cathedral", "phonetic": "/kəˈθiːdrəl/", "pos": "n.", "meaning": "大教堂",
                "history": "【这个词以前被否掉的路线】\n- 第3版 被点踩：别再用词根了"}
        brief = pipeline._brief(item)
        self.assertIn("这个词的历史", brief)
        self.assertIn("别再用词根了", brief)

    def test_judge_brief_carries_history_even_without_new_instruction(self):
        item = {"word": "obscure", "phonetic": "/əbˈskjʊr/", "pos": "adj.", "meaning": "晦涩的",
                "currentApproach": ["① 旧稿"],
                "history": "【这个词以前被否掉的路线】\n- 第5版 被点踩：不要词根词缀"}
        brief = pipeline._judge_brief(item)
        self.assertIn("这个词的历史", brief)
        self.assertIn("不要词根词缀", brief)
        self.assertIn("same_method", brief)


if __name__ == "__main__":
    unittest.main()

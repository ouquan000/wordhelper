#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""C4 回归：feedback.jsonl 的 reasons/comment 真实反哺生成 Prompt。

覆盖两件事：
1. 差评行的 reasons（list 或 JSON 字符串）+ comment 进入 history.build() 的 Prompt 文本；
2. reasons 含「太烂了」时，自动追加「整套推翻 / 换一种记忆方法」硬约束文本。
另加一条 worker 侧断言：候选聚合把 reasons/comment 并进 instruction，喂进 pipeline。
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

import history  # noqa: E402
import worker  # noqa: E402
from store import Store  # noqa: E402

HARD_CONSTRAINT = "整套推翻，严禁沿用原拆法，必须切换另一种记忆方法重来"


def _write_feedback(root, rows):
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, "feedback.jsonl"), "w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def _down_row(word="cathedral", word_id=73, **extra):
    row = {
        "clientId": "c1-down-r2",
        "word": word,
        "wordId": word_id,
        "bookId": 1,
        "rating": "down",
        "method": "meaning",
        "instruction": "",
        "createdAt": "2026-11-20T10:00:00+08:00",
        "receivedAt": "2026-11-20T10:00:00+08:00",
    }
    row.update(extra)
    return row


class HistoryBackfeedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_reasons_and_comment_really_appear_in_prompt(self):
        _write_feedback(self.root, [
            _down_row(reasons=["谐音不准", "绕弯注水"], comment="听不懂在说啥",
                      instruction="别再用词根了"),
        ])
        text = history.build(self.root, "cathedral")
        self.assertIn("谐音不准", text)
        self.assertIn("绕弯注水", text)
        self.assertIn("听不懂在说啥", text)
        self.assertIn("别再用词根了", text)
        self.assertLessEqual(len(text), history.MAX_TEXT)

    def test_json_string_reasons_are_parsed_and_tailan_triggers_hard_constraint(self):
        _write_feedback(self.root, [
            _down_row(reasons='["太烂了", "不像人话"]', comment=""),
        ])
        text = history.build(self.root, "cathedral")
        self.assertIn("太烂了", text)
        self.assertIn("不像人话", text)
        self.assertIn(HARD_CONSTRAINT, text)
        self.assertLessEqual(len(text), history.MAX_TEXT)

    def test_tailan_in_list_reasons_triggers_hard_constraint(self):
        _write_feedback(self.root, [
            _down_row(reasons=["太烂了"], comment="换一种方法"),
        ])
        text = history.build(self.root, "cathedral")
        self.assertIn("太烂了", text)
        self.assertIn("换一种方法", text)
        self.assertIn(HARD_CONSTRAINT, text)

    def test_all_empty_falls_back_to_plain_downvote(self):
        _write_feedback(self.root, [_down_row()])
        text = history.build(self.root, "cathedral")
        self.assertIn("只点了踩，没写原因", text)
        self.assertNotIn(HARD_CONSTRAINT, text)


class WorkerBackfeedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        worker.configure(self.root)
        self.store = Store(self.root).load()

    def tearDown(self):
        worker._state["store"] = None
        self.tmp.cleanup()

    def test_candidate_instruction_merges_reasons_and_comment(self):
        _write_feedback(self.root, [
            _down_row(word="enough", word_id=11, reasons=["太烂了"], comment="像废话"),
            _down_row(word="enough", word_id=11, reasons=["太烂了"], comment="像废话"),
        ])
        config = worker.normalize_config({"new_word_fallback_limit": 0, "include_review": True})
        config["sync_path"] = os.path.join(self.root, "snapshot.json")
        worker.store_mod._write_json(config["sync_path"], {"rows": {
            "e": {"t": "progress", "b": "六级记忆卡", "w": "enough", "nrt": 1}}})
        from unittest.mock import patch
        with patch.object(worker, "_word_index", {"loaded": True, "by_word": {"enough": {"word": "enough"}}}):
            buckets = worker.candidates(self.store, config)
        self.assertEqual(len(buckets), 1)
        instruction = buckets[0]["instruction"]
        self.assertIn("太烂了", instruction)
        self.assertIn("像废话", instruction)


if __name__ == "__main__":
    unittest.main()

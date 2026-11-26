#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量工人接线：成功落库，坏词只回退单条。"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import batch_pipeline  # noqa: E402
import pipeline  # noqa: E402
import service  # noqa: E402
from _isolation import guard_network, isolate  # noqa: E402

isolate(service)
guard_network()


class FakeStore:
    def __init__(self, data_dir):
        self.data_dir = data_dir
        self.finished = []

    def set_stage(self, client_id, stage):
        pass

    def find_reusable_result(self, item, prompt_hash, instruction_hash):
        return None

    def finish_ok(self, item, approach, meta):
        self.finished.append((item["word"], approach, meta))
        return {"seq": len(self.finished)}


class BatchServiceTest(unittest.TestCase):
    def test_batch_finishes_good_word_and_falls_back_bad_ones(self):
        items = [
            {"clientId": "c1", "word": "a", "model": "m", "instruction": "", "bookId": 0, "book": ""},
            {"clientId": "c2", "word": "b", "model": "m", "instruction": "", "bookId": 0, "book": ""},
            {"clientId": "c3", "word": "c", "model": "m", "instruction": "", "bookId": 0, "book": ""},
        ]
        drafts = {word: {"method": "root", "derivation": ["x", "y", "——> z"]} for word in "abc"}
        judged = {
            "a": pipeline.JudgeResult({d: 9 for d in pipeline.JUDGE_DIMENSIONS}, [], "other", False, False),
            "c": pipeline.JudgeResult({d: 9 for d in pipeline.JUDGE_DIMENSIONS}, [], "other", False, False),
        }
        with tempfile.TemporaryDirectory() as tmp:
            store = FakeStore(tmp)
            old_store = service._state.get("store")
            service._state["store"] = store
            try:
                with mock.patch.object(service, "REUSE_HISTORY", False), \
                     mock.patch.object(service.history, "build", return_value=[]), \
                     mock.patch.object(service.pipeline, "prompt_hash", return_value="p"), \
                     mock.patch.object(service.pipeline, "instruction_hash", return_value="i"), \
                     mock.patch.object(service.pipeline, "banned_terms", return_value=[]), \
                     mock.patch.object(service.pipeline, "polish", side_effect=lambda item, draft: ["line"] if item["word"] != "b" else (_ for _ in ()).throw(RuntimeError("polish bad"))), \
                     mock.patch.object(service.pipeline.rules_hard, "check", return_value=SimpleNamespace(ok=True, feedback="", hits=[])), \
                     mock.patch.object(service.batch_pipeline, "generate_batch", return_value=drafts), \
                     mock.patch.object(service.batch_pipeline, "judge_batch", return_value=judged), \
                     mock.patch.object(service.pipeline, "_passed", side_effect=lambda item, *a, **k: item["word"] == "a"), \
                     mock.patch.object(service, "_work") as fallback:
                    service._work_batch(items)
            finally:
                service._state["store"] = old_store
        self.assertEqual([row[0] for row in store.finished], ["a"])
        self.assertEqual([call.args[0]["word"] for call in fallback.call_args_list], ["b", "c"])

    def test_batch_mode_is_off_by_default(self):
        if os.environ.get("BATCH_MODE") is not None:
            self.skipTest("BATCH_MODE 已由外部环境指定")
        self.assertFalse(service.BATCH_ENABLED)

    def test_default_concurrency_follows_two_gate_slots(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(service._default_concurrency(), 2)

    def test_batch_sets_thread_priority_from_current_items(self):
        items = [
            {"clientId": "p1", "word": "a", "model": "m", "instruction": "", "bookId": 0, "book": "", "priority": 10},
            {"clientId": "p2", "word": "b", "model": "m", "instruction": "", "bookId": 0, "book": "", "priority": 20},
        ]
        previous = service.providers.task_priority()
        # 模拟上一单残留：线程里还挂着前台优先级，批处理必须按本轮任务重设。
        service.providers.set_task_priority(100)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                store = FakeStore(tmp)
                old_store = service._state.get("store")
                service._state["store"] = store
                try:
                    with mock.patch.object(service, "REUSE_HISTORY", False), \
                         mock.patch.object(service.history, "build", return_value=[]), \
                         mock.patch.object(service.pipeline, "prompt_hash", return_value="p"), \
                         mock.patch.object(service.pipeline, "instruction_hash", return_value="i"), \
                         mock.patch.object(service.batch_pipeline, "generate_batch", return_value={}), \
                         mock.patch.object(service, "_work") as fallback:
                        service._work_batch(items)
                        self.assertEqual(service.providers.task_priority(), 20)
                finally:
                    service._state["store"] = old_store
            self.assertEqual([call.args[0]["word"] for call in fallback.call_args_list], ["a", "b"])
        finally:
            service.providers.set_task_priority(previous)


if __name__ == "__main__":
    unittest.main()

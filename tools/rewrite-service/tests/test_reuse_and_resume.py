#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""吞吐改造的纯单元测试：JSON 边界、断点续跑、历史复用指纹。"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pipeline  # noqa: E402
import providers  # noqa: E402
import store as store_mod  # noqa: E402
from store import Store  # noqa: E402


ITEM = {
    "word": "alpha",
    "phonetic": "/ˈælfə/",
    "meaning": "阿尔法",
    "example": "alpha test",
    "instruction": "",
    "model": "ds-v41-flash",
}


class ExtractJsonTest(unittest.TestCase):
    def test_accepts_trailing_prose_and_extra_braces(self):
        raw = '{"method":"root","derivation":["a","b"]} 说明：不要照抄 {示例}'
        self.assertEqual(pipeline.extract_json(raw)["method"], "root")

    def test_accepts_fenced_json_with_prose_after(self):
        raw = '```json\n{"method":"sound"}\n```\n以上。'
        self.assertEqual(pipeline.extract_json(raw), {"method": "sound"})

    def test_returns_none_for_non_object(self):
        self.assertIsNone(pipeline.extract_json("没有 JSON"))
        self.assertIsNone(pipeline.extract_json("[1,2]"))


class ResumeTest(unittest.TestCase):
    def _run(self, polish_error=False, judge_error=False):
        calls = {"generate": 0, "polish": 0, "judge": 0}
        draft = {"method": "root", "split": ["a"], "elements": [], "derivation": ["x", "y", "——> z"]}
        approach = ["x", "y", "——> z"]
        scores = {name: 9 for name in pipeline.JUDGE_DIMENSIONS}

        def gen(*args, **kwargs):
            calls["generate"] += 1
            return dict(draft)

        def pol(*args, **kwargs):
            calls["polish"] += 1
            if polish_error and calls["polish"] == 1:
                raise providers.ProviderError("network")
            return list(approach)

        def jud(*args, **kwargs):
            calls["judge"] += 1
            if judge_error and calls["judge"] == 1:
                raise providers.ProviderError("network")
            return scores, [], "keep", False, False

        with patch.object(pipeline, "generate", side_effect=gen), \
             patch.object(pipeline, "polish", side_effect=pol), \
             patch.object(pipeline, "judge", side_effect=jud), \
             patch.object(pipeline.rules_hard, "check", return_value=SimpleNamespace(ok=True, feedback="", hits=[])), \
             patch.object(pipeline, "_passed", return_value=True):
            out = pipeline.run(dict(ITEM))
        return calls, out

    def test_polish_network_error_resumes_at_polish(self):
        calls, out = self._run(polish_error=True)
        self.assertEqual(calls, {"generate": 1, "polish": 2, "judge": 1})
        self.assertEqual(out["timings"]["generate_2"], 0.0)

    def test_judge_network_error_resumes_at_judge(self):
        calls, _ = self._run(judge_error=True)
        self.assertEqual(calls, {"generate": 1, "polish": 1, "judge": 2})
        # 第二轮不重烧生成/润色
        self.assertEqual(calls["generate"], 1)


class ReuseTest(unittest.TestCase):
    def test_find_reusable_requires_all_fingerprints(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp).load()
            mark = {"clientId": "r1", "word": "alpha", "model": "ds-v41-flash", "instruction": ""}
            store.add_marks([dict(mark)])
            item = store.claim(timeout=1)
            self.assertIsNotNone(item)
            store.finish_ok(item, ["line1", "line2"], {
                "promptHash": "p1", "instructionHash": "i1", "method": "root",
            })
            probe = dict(item, instruction="")
            found = store.find_reusable_result(probe, "p1", "i1")
            self.assertIsNotNone(found)
            self.assertEqual(found["approach"], ["line1", "line2"])
            self.assertEqual(found["reuseOf"] if "reuseOf" in found else None, None)
            self.assertIsNone(store.find_reusable_result(probe, "p2", "i1"))
            self.assertIsNone(store.find_reusable_result(dict(probe, model="other"), "p1", "i1"))
            store.close()


if __name__ == "__main__":
    unittest.main()

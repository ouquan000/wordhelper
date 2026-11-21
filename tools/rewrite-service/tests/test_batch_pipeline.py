#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小批量通道：正常批量、逐词回退、整批回退、批大小夹取、批评审缺词回退。

全部用 fake providers.complete，不碰真实 WB，也不发任何网络请求。
"""

import json
import os
import re
import sys
import unittest
from unittest import mock

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import batch_pipeline  # noqa: E402
import pipeline  # noqa: E402
import providers  # noqa: E402


SIX_9 = {dim: 9 for dim in pipeline.JUDGE_DIMENSIONS}


def draft_obj(word, marker="BATCH", method="sound"):
    return {
        "word": word,
        "method": method,
        "split": [word],
        "elements": [{"part": word, "method": method, "reasoning": "fake 元素"}],
        "derivation": [
            "① %s 第一步：%s" % (marker, word),
            "② %s 第二步" % marker,
            "③ %s 收束 ——> 词义" % marker,
        ],
    }


def judge_obj(word, score=9, **overrides):
    data = {
        "word": word,
        "intent": "other",
        "same_method": False,
        "history_repeat": False,
        "has_unknown_part": False,
        "is_robot_tone": False,
        "is_logic_broken": False,
        "phonetic_drift_unwarned": False,
        "violates_user_intent": False,
        "scores": dict(SIX_9),
        "issues": [],
    }
    data["scores"] = {dim: score for dim in pipeline.JUDGE_DIMENSIONS}
    data.update(overrides)
    return data


def words_in_batch_prompt(text):
    """从批 user 消息里抠出每个词的 word 标记，按出现顺序返回。"""
    return re.findall(r"word 必须原样写：([^\s）)]+)", text)


class FakeLLM:
    """按 system prompt 分流四类调用；没有配置的调用直接报错，防止静默联网。"""

    def __init__(self):
        self.calls = []
        self.handlers = {}

    @staticmethod
    def kind(system):
        if system.startswith("你是刚背完几个词"):
            return "generate_batch"
        if system.startswith("你是刚背完这个词"):
            return "generate"
        if system.startswith("你是记忆卡的批量排雷员"):
            return "judge_batch"
        if system.startswith("你是记忆卡的排雷员"):
            return "judge"
        raise AssertionError("未知 system prompt：%s" % system[:60])

    def complete(self, model_id, system, user, **kwargs):
        kind = self.kind(system)
        self.calls.append((kind, user))
        handler = self.handlers.get(kind)
        if handler is None:
            raise AssertionError("测试没有配置 %s 的假响应" % kind)
        if isinstance(handler, BaseException):
            raise handler
        return handler(user) if callable(handler) else handler

    def count(self, kind):
        return sum(1 for item_kind, _ in self.calls if item_kind == kind)


class BatchPipelineTest(unittest.TestCase):
    def setUp(self):
        self.env = mock.patch.dict(os.environ, {}, clear=False)
        self.env.start()
        os.environ.pop("BATCH_SIZE", None)
        self.addCleanup(self.env.stop)

        self.llm = FakeLLM()
        self.patch_complete = mock.patch.object(providers, "complete", side_effect=self.llm.complete)
        self.patch_complete.start()
        self.addCleanup(self.patch_complete.stop)

        self.patch_target = mock.patch.object(
            providers, "judge_target", side_effect=lambda generator: "fake-judge"
        )
        self.patch_target.start()
        self.addCleanup(self.patch_target.stop)

    def items(self, words, instruction=""):
        return [
            {
                "word": word,
                "model": "fake",
                "phonetic": "/f/",
                "pos": "n.",
                "meaning": "词义",
                "instruction": instruction,
            }
            for word in words
        ]

    def generate_handler(self, entries):
        return json.dumps(entries, ensure_ascii=False)

    def single_generate(self, marker="SINGLE"):
        def handler(user):
            word = re.search(r"单词：(\S+)", user).group(1)
            return json.dumps(draft_obj(word, marker), ensure_ascii=False)

        return handler

    def single_judge(self, score=9):
        def handler(user):
            word = re.search(r"单词：(\S+)", user).group(1)
            return json.dumps(judge_obj(word, score), ensure_ascii=False)

        return handler

    # ------------------------------------------------------------ generate

    def test_three_words_one_batch_call(self):
        items = self.items(["alpha", "beta", "gamma"])
        self.llm.handlers["generate_batch"] = lambda user: self.generate_handler(
            [draft_obj(word) for word in words_in_batch_prompt(user)]
        )

        out = batch_pipeline.generate_batch(items)

        self.assertEqual(list(out), ["alpha", "beta", "gamma"])
        self.assertEqual(out.stats["batch_calls"], 1)
        self.assertEqual(out.stats["fallbacks"], 0)
        self.assertEqual(self.llm.count("generate_batch"), 1)
        self.assertEqual(self.llm.count("generate"), 0)
        for word, draft in out.items():
            self.assertEqual(
                set(draft), {"method", "split", "elements", "derivation", "violations"}
            )
            self.assertEqual(draft["method"], "sound")
            self.assertIn("——>", draft["derivation"][-1])
            self.assertEqual(draft["violations"], [])

    def test_second_word_missing_field_only_it_falls_back(self):
        items = self.items(["alpha", "beta", "gamma"])
        broken = draft_obj("beta")
        broken.pop("derivation")
        self.llm.handlers["generate_batch"] = lambda user: self.generate_handler(
            [draft_obj("alpha"), broken, draft_obj("gamma")]
        )
        self.llm.handlers["generate"] = self.single_generate("SINGLE")

        out = batch_pipeline.generate_batch(items)

        self.assertEqual(set(out), {"alpha", "beta", "gamma"})
        self.assertEqual(out.stats["fallbacks"], 1)
        self.assertEqual(out.stats["fallback_words"][0]["word"], "beta")
        self.assertIn("SINGLE", out["beta"]["derivation"][0])
        self.assertIn("BATCH", out["alpha"]["derivation"][0])
        self.assertIn("BATCH", out["gamma"]["derivation"][0])
        self.assertEqual(self.llm.count("generate"), 1)

    def test_batch_exception_falls_back_every_word(self):
        items = self.items(["alpha", "beta", "gamma"])
        self.llm.handlers["generate_batch"] = providers.ProviderError("batch down")
        self.llm.handlers["generate"] = self.single_generate("SINGLE")

        out = batch_pipeline.generate_batch(items)

        self.assertEqual(set(out), {"alpha", "beta", "gamma"})
        self.assertEqual(out.stats["batch_calls"], 1)
        self.assertEqual(out.stats["fallbacks"], 3)
        self.assertEqual(self.llm.count("generate"), 3)
        for draft in out.values():
            self.assertIn("SINGLE", draft["derivation"][0])

    def test_batch_invalid_json_falls_back_every_word(self):
        items = self.items(["alpha", "beta"])
        self.llm.handlers["generate_batch"] = "这不是 JSON 数组"
        self.llm.handlers["generate"] = self.single_generate("SINGLE")

        out = batch_pipeline.generate_batch(items)

        self.assertEqual(set(out), {"alpha", "beta"})
        self.assertEqual(out.stats["fallbacks"], 2)
        self.assertEqual(self.llm.count("generate"), 2)

    def test_banned_method_from_batch_falls_back_that_word(self):
        items = self.items(["alpha"], instruction="不要用词根")
        self.llm.handlers["generate_batch"] = lambda user: self.generate_handler(
            [draft_obj("alpha", "BATCH", method="root")]
        )
        self.llm.handlers["generate"] = self.single_generate("SINGLE")

        out = batch_pipeline.generate_batch(items)

        self.assertEqual(out.stats["fallbacks"], 1)
        self.assertEqual(out["alpha"]["method"], "sound")
        self.assertIn("SINGLE", out["alpha"]["derivation"][0])

    def test_batch_size_clamped_to_2_4(self):
        cases = (
            ("4", 4),
            ("3", 3),
            ("2", 2),
            ("99", 4),
            ("1", 2),
            ("0", 2),
            ("-5", 2),
            ("abc", 3),
            ("", 3),
        )
        for raw, expected in cases:
            with self.subTest(raw=raw):
                with mock.patch.dict(os.environ, {"BATCH_SIZE": raw}):
                    self.assertEqual(batch_pipeline.batch_size(), expected)
        os.environ.pop("BATCH_SIZE", None)
        self.assertEqual(batch_pipeline.batch_size(), 3)

    def test_batch_size_chunks_five_words_into_three_calls(self):
        os.environ["BATCH_SIZE"] = "2"
        items = self.items(["a1", "a2", "a3", "a4", "a5"])
        self.llm.handlers["generate_batch"] = lambda user: self.generate_handler(
            [draft_obj(word) for word in words_in_batch_prompt(user)]
        )

        out = batch_pipeline.generate_batch(items)

        self.assertEqual(len(out), 5)
        self.assertEqual(out.stats["batch_calls"], 3)
        self.assertEqual(out.stats["fallbacks"], 0)
        self.assertEqual(self.llm.count("generate_batch"), 3)

    # --------------------------------------------------------------- judge

    def test_judge_batch_missing_word_falls_back_only_it(self):
        items = self.items(["alpha", "beta", "gamma"])
        drafts = {word: draft_obj(word) for word in ("alpha", "beta", "gamma")}
        approaches = [["① a", "② b", "③ c ——> 词义"]] * 3
        self.llm.handlers["judge_batch"] = lambda user: self.generate_handler(
            [judge_obj("alpha", 8), judge_obj("beta", 8)]
        )
        self.llm.handlers["judge"] = self.single_judge(9)

        out = batch_pipeline.judge_batch(items, drafts, approaches)

        self.assertEqual(set(out), {"alpha", "beta", "gamma"})
        self.assertEqual(out.stats["batch_calls"], 1)
        self.assertEqual(out.stats["fallbacks"], 1)
        self.assertEqual(out.stats["fallback_words"][0]["word"], "gamma")
        self.assertEqual(out["alpha"][0]["拆分成立"], 8)
        self.assertEqual(out["gamma"][0]["拆分成立"], 9)
        self.assertIsInstance(out["alpha"], pipeline.JudgeResult)
        self.assertEqual(self.llm.count("judge"), 1)
        # 逐词结果仍走 pipeline._passed 的语义：五道全 false → 过。
        for word in ("alpha", "beta", "gamma"):
            result = out[word]
            self.assertTrue(
                pipeline._passed(
                    items[0], result[0], result[2], result[3], result[4], binary=result.binary
                )
            )

    def test_judge_batch_exception_falls_back_every_word(self):
        items = self.items(["alpha", "beta"])
        drafts = {word: draft_obj(word) for word in ("alpha", "beta")}
        self.llm.handlers["judge_batch"] = providers.ProviderError("judge down")
        self.llm.handlers["judge"] = self.single_judge(9)

        out = batch_pipeline.judge_batch(items, drafts, [["① a"]] * 2)

        self.assertEqual(set(out), {"alpha", "beta"})
        self.assertEqual(out.stats["fallbacks"], 2)
        self.assertEqual(self.llm.count("judge"), 2)

    def test_judge_batch_single_entry_missing_scores_falls_back(self):
        items = self.items(["alpha"])
        drafts = {"alpha": draft_obj("alpha")}
        broken = judge_obj("alpha")
        broken.pop("scores")
        self.llm.handlers["judge_batch"] = lambda user: self.generate_handler([broken])
        self.llm.handlers["judge"] = self.single_judge(9)

        out = batch_pipeline.judge_batch(items, drafts, [["① a"]])

        self.assertEqual(out.stats["fallbacks"], 1)
        self.assertEqual(out["alpha"][0]["拆分成立"], 9)


if __name__ == "__main__":
    unittest.main()

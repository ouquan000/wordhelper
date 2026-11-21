#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""新版五道二值排雷的通过条件（不调用 LLM）。"""

import os
import sys
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import pipeline  # noqa: E402


def clean_binary():
    return {key: False for key in pipeline.BINARY_FIELDS}


class BinaryPassTest(unittest.TestCase):
    def item(self, instruction=""):
        return {"word": "cathedral", "instruction": instruction}

    def test_all_false_passes_even_without_scores(self):
        self.assertTrue(pipeline._passed(self.item(), {}, binary=clean_binary()))

    def test_empty_instruction_intent_none_counts_as_not_applicable(self):
        # 用户没留原话时 judge.txt 不要求输出 violates_user_intent，
        # pipeline.judge() 会把它解析成 None；此时应视为“不适用”而放行。
        binary = clean_binary()
        binary["violates_user_intent"] = None
        self.assertTrue(pipeline._passed(self.item(), {}, binary=binary))

    def test_empty_instruction_intent_true_still_blocks(self):
        binary = clean_binary()
        binary["violates_user_intent"] = True
        self.assertFalse(pipeline._passed(self.item(), {}, binary=binary))

    def test_empty_instruction_any_other_flag_true_blocks(self):
        binary = clean_binary()
        binary["violates_user_intent"] = None
        binary["is_logic_broken"] = True
        self.assertFalse(pipeline._passed(self.item(), {}, binary=binary))

    def test_instruction_present_intent_none_blocks(self):
        binary = clean_binary()
        binary["violates_user_intent"] = None
        self.assertFalse(
            pipeline._passed(self.item("换成谐音法"), {}, binary=binary)
        )

    def test_instruction_present_intent_true_blocks(self):
        binary = clean_binary()
        binary["violates_user_intent"] = True
        self.assertFalse(
            pipeline._passed(self.item("换成谐音法"), {}, binary=binary)
        )

    def test_any_true_blocks(self):
        binary = clean_binary()
        binary["is_robot_tone"] = True
        self.assertFalse(pipeline._passed(self.item(), {}, binary=binary))

    def test_missing_field_blocks(self):
        binary = clean_binary()
        binary.pop("phonetic_drift_unwarned")
        self.assertFalse(pipeline._passed(self.item(), {}, binary=binary))

    def test_hard_hit_blocks(self):
        self.assertFalse(
            pipeline._passed(self.item(), {}, binary=clean_binary(), hard_ok=False)
        )

    def test_history_repeat_blocks(self):
        self.assertFalse(
            pipeline._passed(
                self.item(), {}, binary=clean_binary(), history_repeat=True
            )
        )

    def test_legacy_scores_still_work_without_binary(self):
        scores = {dim: 9 for dim in pipeline.JUDGE_DIMENSIONS}
        self.assertTrue(pipeline._passed(self.item(), scores, binary=None))


if __name__ == "__main__":
    unittest.main()

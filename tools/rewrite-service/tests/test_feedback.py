#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反馈闭环改造的单测：两段式验收、诉求缺失、兜底交付、用户禁令重生成。

全部用假 providers，不联网、不碰 data/、不碰 8765。
运行：python -m unittest discover -s tools/rewrite-service/tests -v
"""

from __future__ import annotations

import json
import os
import sys
import unittest
from unittest import mock

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import pipeline  # noqa: E402
import providers  # noqa: E402

# 三段提示词的开头各自不同，假 providers 靠它认阶段
STAGE_MARKERS = (
    ("generate", "你是《单词突围》体系的领背讲师"),
    ("polish", "你是英文单词记忆卡的中文文案编辑"),
    ("judge", "你是《单词突围》体系的资深审稿人"),
)

DRAFT_LINES = [
    "① 先看 chronic 的读音像「克罗」",
    "② 再想他一拖就是三十年",
    "③ 合起来 ——> 慢性的",
]

SIX_9 = {
    "拆分成立": 9,
    "方法选对": 9,
    "谐音过关": 9,
    "逻辑连贯": 9,
    "文本充足": 9,
    "像人说话": 9,
}


def draft(method="contrast", split=("chron", "ic"), lines=None):
    return {
        "method": method,
        "split": list(split),
        "elements": [{"part": part, "method": method, "reasoning": "测试"} for part in split],
        "derivation": list(lines or DRAFT_LINES),
    }


def polished(lines=None):
    return {"derivation": list(lines or DRAFT_LINES)}


def judge_json(scores, issues=(), intent="change_method", same_method=False):
    return {
        "intent": intent,
        "same_method": same_method,
        "scores": dict(scores),
        "pass": True,
        "issues": list(issues),
    }


class FakeLLM:
    """按阶段逐个吐预置结果，并记下每次的 user 供断言。"""

    def __init__(self, generate_queue, polish_queue, judge_queue):
        self.queues = {
            "generate": list(generate_queue),
            "polish": list(polish_queue),
            "judge": list(judge_queue),
        }
        self.calls = []

    def _stage(self, system):
        for stage, marker in STAGE_MARKERS:
            if marker in system:
                return stage
        raise AssertionError("认不出这是哪一段提示词：%s" % system[:80])

    def complete(self, model, system, user, **kwargs):
        stage = self._stage(system)
        self.calls.append({"stage": stage, "user": user, "system": system, "kwargs": kwargs})
        queue = self.queues[stage]
        if not queue:
            raise AssertionError("%s 阶段没有预置结果了（第 %d 次调用）" % (stage, len(self.calls)))
        item = queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item if isinstance(item, str) else json.dumps(item, ensure_ascii=False)

    def users(self, stage):
        return [call["user"] for call in self.calls if call["stage"] == stage]


class FeedbackTest(unittest.TestCase):
    def prepare(self, *, instruction="", current_approach=None, generate=(), polish=(), judge=()):
        self.llm = FakeLLM(generate, polish, judge)
        self.item = {
            "word": "chronic",
            "phonetic": "/ˈkrɒnɪk/",
            "pos": "adj.",
            "meaning": "慢性的；长期的",
            "model": "ds-v41-flash",
            "instruction": instruction,
            "currentApproach": list(current_approach or []),
        }
        for patcher in (
            mock.patch.object(providers, "complete", side_effect=self.llm.complete),
            mock.patch.object(
                providers,
                "judge_target",
                return_value={"provider": "wb", "model": "fake-judge", "reasoning": "high"},
            ),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)
        return self

    # ------------------------------------------------------------ 两段式验收

    def test_two_stage_passes_correct_rewrite_with_one_seven(self):
        """诉求 9、谐音 7：第二段放行，不把正确改稿判死。"""
        self.prepare(
            instruction="换个方法背",
            generate=[draft()],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "谐音过关": 7, "诉求落实": 9})],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["attempts"], 1)
        self.assertFalse(out["unverified"])
        self.assertEqual(out["reason"], "")
        self.assertEqual(out["scores"]["诉求落实"], 9)

    def test_six_high_but_complaint_unmet_is_not_pass(self):
        """六维全 9、诉求 3、同一条思路：不能放行，兜底标 unverified。"""
        low = {**SIX_9, "诉求落实": 3}
        self.prepare(
            instruction="换个方法背",
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[
                judge_json(low, issues=["还是同一个方法"], same_method=True),
                judge_json(low, issues=["还是同一个方法"], same_method=True),
            ],
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["unverified"])
        self.assertEqual(out["scores"]["诉求落实"], 3)

    def test_same_method_change_method_fails_even_with_full_scores(self):
        """六维全 9、诉求 9，但 intent=change_method 且 same_method=true：硬闸必须拦下。"""
        full = {**SIX_9, "诉求落实": 9}
        self.prepare(
            instruction="换个方法背",
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[
                judge_json(full, intent="change_method", same_method=True),
                judge_json(full, intent="change_method", same_method=True),
            ],
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["unverified"])
        self.assertEqual(out["attempts"], 2)
        self.assertIn("同一条思路", out["reason"])

    def test_second_stage_rejects_below_floor(self):
        """诉求 9 但六维最低 5：第二段也不该放。"""
        low = {**SIX_9, "逻辑连贯": 5, "诉求落实": 9}
        self.prepare(
            instruction="换个方法背",
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[judge_json(low), judge_json(low)],
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["unverified"])

    def test_compliance_alias_is_mapped(self):
        """模型回旧名「解决诉求」也要认成「诉求落实」。"""
        self.prepare(
            instruction="换个方法背",
            generate=[draft()],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "解决诉求": 9})],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["attempts"], 1)
        self.assertEqual(out["scores"]["诉求落实"], 9)

    def test_negated_change_still_passes_if_judge_mislabels_intent(self):
        self.prepare(
            instruction="我不太想换方法，把第二步说清楚",
            current_approach=DRAFT_LINES,
            generate=[draft()],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "诉求落实": 9}, intent="change_method", same_method=True)],
        )
        self.assertFalse(pipeline.run(self.item)["unverified"])

    def test_reported_change_does_not_override_user_request_to_keep_method(self):
        cases = (
            ("别人说‘换个方法’，但我不想换方法", "other"),
            ("别人说换个方法，但我不想换方法", "change_method"),
            ("评委说：换个方法，但我不想换方法", "other"),
        )
        self.prepare(
            current_approach=DRAFT_LINES,
            generate=[draft() for _ in cases],
            polish=[polished() for _ in cases],
            judge=[
                judge_json({**SIX_9, "诉求落实": 9}, intent=intent, same_method=True)
                for _, intent in cases
            ],
        )
        for instruction, _ in cases:
            with self.subTest(instruction=instruction):
                out = pipeline.run({**self.item, "instruction": instruction})
                self.assertFalse(out["unverified"])

    def test_positive_change_in_later_clause_blocks_mislabeled_same_method(self):
        full = {**SIX_9, "诉求落实": 9}
        self.prepare(
            instruction="不要用词根了换个方法",
            current_approach=DRAFT_LINES,
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[judge_json(full, intent="other", same_method=True)] * 2,
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["quality_rejected"])
        self.assertEqual(len(self.llm.users("judge")), 2)

    def test_quoted_negation_does_not_override_request_to_change(self):
        full = {**SIX_9, "诉求落实": 9}
        self.prepare(
            instruction="请换个方法，别再出现「不用换方法」这句话",
            current_approach=DRAFT_LINES,
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[judge_json(full, intent="change_method", same_method=True)] * 2,
        )
        self.assertTrue(pipeline.run(self.item)["quality_rejected"])

    def test_quoted_affirmation_does_not_override_request_to_keep_method(self):
        self.prepare(
            instruction="不用换方法，把「换个方法」这句话删掉",
            current_approach=DRAFT_LINES,
            generate=[draft()],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "诉求落实": 9}, intent="other", same_method=True)],
        )
        self.assertFalse(pipeline.run(self.item)["unverified"])

    def test_quoted_commands_with_coordinated_request_or_english_punctuation(self):
        cases = (
            ("请「换个方法」并讲清楚第二步", "other", True),
            ("请「不用换方法」并讲清楚第二步", "change_method", False),
            ('Please "change the method".', "other", True),
        )
        votes = [
            judge_json({**SIX_9, "诉求落实": 9}, intent=intent, same_method=True)
            for _, intent, rejected in cases
            for _ in range(2 if rejected else 1)
        ]
        self.prepare(
            current_approach=DRAFT_LINES,
            generate=[draft() for _ in votes],
            polish=[polished() for _ in votes],
            judge=votes,
        )
        for instruction, _, rejected in cases:
            with self.subTest(instruction=instruction):
                out = pipeline.run({**self.item, "instruction": instruction})
                self.assertEqual(out["unverified"], rejected)

    def test_judge_returns_intent_and_same_method(self):
        self.prepare(
            instruction="换个方法背",
            judge=[judge_json({**SIX_9, "诉求落实": 9}, intent="change_method", same_method=True)],
        )
        scores, _issues, intent, same_method, history_repeat = pipeline.judge(
            self.item, {}, ["① 第一步", "② 第二步", "③ 收尾 ——> 慢性的"]
        )
        self.assertEqual(intent, "change_method")
        self.assertTrue(same_method)
        self.assertFalse(history_repeat)
        self.assertEqual(scores["诉求落实"], 9)

    def test_history_repeat_blocks_pass_even_without_new_instruction(self):
        item = {"word": "obscure", "meaning": "晦涩的"}
        scores = {dim: 9 for dim in pipeline.JUDGE_DIMENSIONS}
        self.assertFalse(
            pipeline._passed(item, scores, intent=None, same_method=False, history_repeat=True)
        )

    def test_judge_brief_uses_once_not_consecutive_wording(self):
        item = {
            "word": "obscure",
            "meaning": "晦涩的",
            "history": "用户原话：不要词根词缀",
        }
        brief = pipeline._judge_brief(item)
        self.assertNotIn("连续", brief)
        self.assertIn("明确否掉过一次", brief)

    def test_unknown_intent_is_rejudged_and_blocks_same_method(self):
        item = {
            "word": "obscure",
            "meaning": "晦涩的",
            "model": "fake",
            "instruction": "换个方法",
        }
        first = json.dumps({
            "intent": "不知道",
            "same_method": True,
            "scores": {**SIX_9, "诉求落实": 9},
            "pass": True,
            "issues": [],
        })
        patch = json.dumps({
            "intent": "change_method",
            "same_method": True,
            "history_repeat": False,
        })
        with mock.patch.object(providers, "judge_target", return_value="fake"), \
                mock.patch.object(providers, "complete", side_effect=[first, patch]):
            scores, _issues, intent, same_method, history_repeat = pipeline.judge(
                item, {}, ["① 第一步", "② 第二步", "③ 收尾 ——> 晦涩的"]
            )
        self.assertEqual(intent, "change_method")
        self.assertTrue(same_method)
        self.assertFalse(
            pipeline._passed(item, scores, intent, same_method, history_repeat)
        )

    def test_judge_missing_history_repeat_is_rejudged_once(self):
        item = {
            "word": "obscure",
            "meaning": "晦涩的",
            "model": "fake",
            "instruction": "换个方法，不要词根",
            "history": "用户原话：不要词根词缀",
        }
        first = json.dumps({
            "intent": "change_method",
            "same_method": False,
            "scores": {**SIX_9, "诉求落实": 9},
            "pass": True,
            "issues": [],
        })
        patch = json.dumps({
            "intent": "change_method",
            "same_method": True,
            "history_repeat": True,
        })
        with mock.patch.object(providers, "judge_target", return_value="fake"), \
                mock.patch.object(providers, "complete", side_effect=[first, patch]):
            _scores, _issues, _intent, same_method, history_repeat = pipeline.judge(
                item, {}, ["① 第一步", "② 第二步", "③ 收尾 ——> 晦涩的"]
            )
        self.assertTrue(history_repeat)

    def test_judge_missing_flag_twice_defers_instead_of_applying(self):
        item = {
            "word": "obscure",
            "meaning": "晦涩的",
            "model": "fake",
            "instruction": "换个方法",
            "history": "用户原话：不要词根词缀",
        }
        first = json.dumps({
            "intent": "change_method",
            "same_method": False,
            "scores": {**SIX_9, "诉求落实": 9},
            "pass": True,
            "issues": [],
        })
        with mock.patch.object(providers, "judge_target", return_value="fake"), \
                mock.patch.object(providers, "complete", side_effect=[first, "{}"]):
            with self.assertRaises(pipeline.PipelineError):
                pipeline.judge(item, {}, ["① 第一步", "② 第二步", "③ 收尾 ——> 晦涩的"])

    def test_judge_without_history_does_not_require_history_repeat(self):
        item = {"word": "chronic", "meaning": "慢性的", "model": "fake"}
        first = json.dumps({
            "scores": SIX_9,
            "pass": True,
            "issues": [],
        })
        with mock.patch.object(providers, "judge_target", return_value="fake"), \
                mock.patch.object(providers, "complete", return_value=first) as complete:
            _scores, _issues, _intent, _same, history_repeat = pipeline.judge(
                item, {}, ["① 第一步", "② 第二步", "③ 收尾 ——> 慢性的"]
            )
        self.assertFalse(history_repeat)
        self.assertEqual(complete.call_count, 1)

    # ------------------------------------------------------------ 诉求缺失

    def test_missing_compliance_with_instruction_counts_as_zero(self):
        self.prepare(
            instruction="换个方法背",
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[judge_json(SIX_9), judge_json(SIX_9)],
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["unverified"])
        self.assertEqual(out["scores"]["诉求落实"], 0)

    def test_no_instruction_does_not_require_compliance(self):
        """用户没提意见：六维全 9 就过，诉求落实不出现、也不误伤。"""
        self.prepare(
            instruction="",
            generate=[draft()],
            polish=[polished()],
            judge=[judge_json(SIX_9)],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["attempts"], 1)
        self.assertNotIn("诉求落实", out["scores"])
        self.assertNotIn("用户这一版指出的问题", self.llm.users("judge")[0])

    def test_no_instruction_keeps_old_standard(self):
        """用户没提意见：六维最低 7 不能走第二段放宽。"""
        low = {**SIX_9, "逻辑连贯": 7}
        self.prepare(
            instruction="",
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[judge_json(low), judge_json(low)],
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["unverified"])

    # ------------------------------------------------------------ 兜底交付

    def test_unverified_returns_best_history_not_blank(self):
        first = ["① 第一版开头", "② 第一版中段", "③ 第一版收尾 ——> 慢性的"]
        second = ["① 第二版开头", "② 第二版中段", "③ 第二版收尾 ——> 慢性的"]
        self.prepare(
            instruction="换个方法背",
            generate=[draft(), draft()],
            polish=[polished(first), polished(second)],
            judge=[
                # 第 1 轮：诉求 9、六维最低 5（第二段不过）——但诉求比第 2 轮高
                judge_json({**SIX_9, "逻辑连贯": 5, "诉求落实": 9}),
                # 第 2 轮：六维全 9、诉求 3（第一段不过）
                judge_json({**SIX_9, "诉求落实": 3}),
            ],
        )
        out = pipeline.run(self.item)
        self.assertTrue(out["unverified"])
        self.assertEqual(out["approach"], pipeline.normalize(first))
        self.assertEqual(out["attempts"], 2)
        self.assertIn("历史最佳", out["reason"])
        self.assertIn("第1轮", out["reason"])

    def test_one_bad_round_does_not_kill_good_result(self):
        self.prepare(
            instruction="",
            generate=["这不是 JSON", draft()],
            polish=[polished()],
            judge=[judge_json(SIX_9)],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["attempts"], 2)
        self.assertFalse(out["unverified"])

    def test_all_json_errors_raise(self):
        self.prepare(instruction="", generate=["不是 JSON", "也不是 JSON"])
        with self.assertRaises(pipeline.PipelineError):
            pipeline.run(self.item)

    # ------------------------------------------------------------ 用户禁令

    def test_banned_method_triggers_one_regeneration(self):
        bad = draft(method="root", split=("chron", "ic"))
        good = draft(method="contrast", split=("chro", "nic"))
        self.prepare(
            instruction="不要用词根词缀法，换个方法",
            generate=[bad, good],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "诉求落实": 9})],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["method"], "contrast")
        generate_users = self.llm.users("generate")
        self.assertEqual(len(generate_users), 2)
        self.assertIn("你违反了用户禁令", generate_users[1])
        self.assertIn("词根", generate_users[1])

    def test_banned_element_in_split_regenerates(self):
        bad = draft(method="contrast", split=("cathedr", "al"))
        good = draft(method="sound", split=("ca", "the", "dral"))
        self.prepare(
            instruction="不要用 cathedr 这个拆法",
            generate=[bad, good],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "诉求落实": 9})],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["method"], "sound")
        self.assertEqual(len(self.llm.users("generate")), 2)

    def test_banned_terms_ignores_discourse_noise(self):
        """原话里的「让我背」「最后提一嘴」是口气，不是禁令；只留真禁的词根。"""
        bans = pipeline.banned_terms(
            "我不想用词根词缀法 你换个方法让我背 最后再提一嘴词根词缀发就行"
        )
        self.assertEqual([ban["needle"] for ban in bans], ["词根词缀"])
        self.assertEqual(bans[0]["method"], "root")

    def test_normalize_accepts_note_after_final_arrow(self):
        """模型把「顺带一提」的旁注放在结论后面时，不该整轮作废。"""
        lines = [
            "① 先看 cathedral 的读音像「卡西德罗」",
            "② 再想大教堂里回声很长",
            "③ 合起来 ——> 大教堂",
            "注：词根 cathedr 是座位，这里只当附注",
        ]
        out = pipeline.normalize(lines)
        self.assertIsNotNone(out)
        self.assertEqual(len(out), 4)

    def test_final_line_note_is_not_a_violation(self):
        """用户自己说「最后再提一嘴词根词缀就行」，末行顺带一提不算违规。"""
        lines = [
            "① 先看 chronic 的读音像「克罗」",
            "② 再想他一拖就是三十年",
            "③ 合起来 ——> 慢性的（词根 chron 也这么来）",
        ]
        self.prepare(
            instruction="我不想用词根词缀法 你换个方法让我背 最后再提一嘴词根词缀发就行",
            generate=[draft(method="contrast", split=("chro", "nic"), lines=lines)],
            polish=[polished(lines)],
            judge=[judge_json({**SIX_9, "诉求落实": 9})],
        )
        out = pipeline.run(self.item)
        self.assertEqual(len(self.llm.users("generate")), 1)
        self.assertEqual(out["attempts"], 1)

    # ------------------------------------------------------------ 重试约束 & 评委输入

    def test_retry_repastes_user_words_as_hard_constraint(self):
        low = {**SIX_9, "诉求落实": 3}
        self.prepare(
            instruction="换个方法背 这个不好背",
            generate=[draft(), draft()],
            polish=[polished(), polished()],
            judge=[
                judge_json(low, issues=["换个方法"], same_method=True),
                judge_json({**SIX_9, "诉求落实": 9}),
            ],
        )
        out = pipeline.run(self.item)
        self.assertEqual(out["attempts"], 2)
        retry_user = self.llm.users("generate")[1]
        self.assertIn("换个方法背 这个不好背", retry_user)
        self.assertIn("硬约束", retry_user)
        self.assertIn("以用户原话为准", retry_user)
        self.assertIn("软建议", retry_user)

    def test_judge_sees_instruction_and_previous_version(self):
        prev = ["① 旧版把 cathedr 当座位", "② 加 al 后缀", "③ 合起来 ——> 大教堂"]
        self.prepare(
            instruction="换个方法背 这个不好背",
            current_approach=prev,
            generate=[draft()],
            polish=[polished()],
            judge=[judge_json({**SIX_9, "诉求落实": 9})],
        )
        pipeline.run(self.item)
        judge_user = self.llm.users("judge")[0]
        self.assertIn("换个方法背 这个不好背", judge_user)
        self.assertIn("上一版成品", judge_user)
        for line in prev:
            self.assertIn(line, judge_user)


    def test_polish_reintroducing_banned_method_blocks_verified(self):
        self.prepare(
            instruction="别用词根，换个方法",
            generate=[draft(), draft()],
            polish=[
                polished(["① 词根 chron 表示时间", "② 加后缀", "③ 合起来 ——> 慢性的"]),
                polished(["① 词根 chron 表示时间", "② 加后缀", "③ 合起来 ——> 慢性的"]),
            ],
            judge=[
                judge_json({**SIX_9, "诉求落实": 9}),
                judge_json({**SIX_9, "诉求落实": 9}),
            ],
        )
        result = pipeline.run(self.item)
        self.assertTrue(result["unverified"])
        self.assertIn("词根", result["reason"])

    def test_negated_mention_of_banned_root_is_not_a_violation(self):
        bans = [{"label": "词根", "needle": "词根", "method": "root"}]
        self.assertEqual(
            pipeline._text_hits(
                bans,
                ["① 这版不像词根那样硬拆", "② 用谐音记 x", "③ 合起来 ——> y"],
            ),
            [],
        )

    def test_plain_mention_of_banned_root_still_hits(self):
        bans = [{"label": "词根", "needle": "词根", "method": "root"}]
        self.assertEqual(
            pipeline._text_hits(bans, ["① 词根 de 表示向下", "② 加后缀", "③ 合起来 ——> y"]),
            ["词根"],
        )

    def test_negation_does_not_cover_second_occurrence(self):
        bans = [{"label": "词根", "needle": "词根", "method": "root"}]
        self.assertEqual(
            pipeline._text_hits(bans, ["① 不用词根，② 词根其实也能记", "③ 合起来 ——> y"]),
            ["词根"],
        )

    def test_judge_dimension_typo_maps_to_canonical(self):
        self.assertEqual(pipeline._canonical_dim("像人谐话"), "像人说话")
        self.assertEqual(pipeline._canonical_dim("拆分成力"), "拆分成立")
        self.assertEqual(pipeline._canonical_dim("诉求"), "诉求落实")
        self.assertEqual(pipeline._canonical_dim("方法选对"), "方法选对")

    def test_judge_typo_key_does_not_zero_the_real_dimension(self):
        scores = {key: 9 for key in pipeline.JUDGE_DIMENSIONS}
        scores.pop("像人说话")
        scores["像人谐话"] = 8
        mapped = {pipeline._canonical_dim(key): value for key, value in scores.items()}
        for dim in pipeline.JUDGE_DIMENSIONS:
            mapped.setdefault(dim, 0)
        self.assertEqual(mapped["像人说话"], 8)


if __name__ == "__main__":
    unittest.main()

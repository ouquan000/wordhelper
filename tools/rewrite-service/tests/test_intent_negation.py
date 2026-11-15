#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""intent 的否定语义：别把「不用换方法」当成「要换方法」。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pipeline


class IntentNegationTest(unittest.TestCase):
    def test_chinese_negated_change_is_other(self):
        # 只有「别换方法」= 没说要换，也没说改哪一步：不能反推成 fix_detail。
        self.assertEqual(pipeline._intent_field("不用换方法"), "other")
        self.assertEqual(pipeline._intent_field("不用换方法，把第二步说清楚"), "explain")
        self.assertEqual(pipeline._intent_field("不要换招"), "other")
        self.assertEqual(pipeline._intent_field("没必要换思路"), "other")
        # 同一句里有正向的 fix_detail 线索时，正向线索仍然生效。
        self.assertEqual(pipeline._intent("不用换方法，只改细节"), "fix_detail")

    def test_positive_change_still_wins(self):
        self.assertEqual(pipeline._intent_field("换个方法，不要再用词根"), "change_method")
        self.assertEqual(pipeline._intent_field("change method, please"), "change_method")

    def test_negated_change_with_explain(self):
        self.assertEqual(pipeline._intent_field("不用换方法，解释一下就行"), "explain")
        self.assertEqual(pipeline._intent("别换方法，看不懂"), "explain")

    def test_english_negation(self):
        self.assertEqual(pipeline._intent_field("don't change method, just fix it"), "fix_detail")
        # 光说「别换方法」没有正向诉求：不能反推成改细节。
        self.assertEqual(pipeline._intent_field("do not change the method"), "other")
        self.assertEqual(pipeline._intent_field("no need to change the method"), "other")
        self.assertEqual(pipeline._intent_field("change the method"), "change_method")

    def test_unknown_stays_unknown_for_strict_field(self):
        self.assertIsNone(pipeline._intent_field("随便看看"))
        self.assertIsNone(pipeline._intent_field(""))

    def test_common_negation_variants_are_not_change_method(self):
        for text in (
            "不想换方法",
            "我不太想换方法",
            "不想要换方法",
            "我不希望换方法",
            "不打算换方法",
            "没打算换方法",
            "不准备换方法",
            "不觉得需要换方法",
        ):
            self.assertEqual(pipeline._intent_field(text), "other", text)

    def test_negation_applies_to_the_actual_verb_and_occurrence(self):
        for text in (
            "特别想换个方法", "我特别想换个方法", "不得不换方法",
            "不要用词根了换个方法", "别用词根换个思路",
            "别换方法，但我还是想换方法", "换一个方法", "换个背法", "改一下方法",
            "I don't like it; CHANGE THE METHOD",
        ):
            with self.subTest(text=text):
                self.assertTrue(pipeline._explicit_change_request(text))
                self.assertEqual(pipeline._intent_field(text), "change_method")
        for text in (
            "你最好别再换方法", "I shouldn't change the method",
            "I don't think it's necessary to change the method", "DON’T CHANGE METHOD",
        ):
            with self.subTest(text=text):
                self.assertTrue(pipeline._negated_change_request(text))
                self.assertEqual(pipeline._intent_field(text), "other")
        self.assertIsNone(pipeline._intent_field("exchange examples"))
        self.assertFalse(pipeline._explicit_change_request("别人说换个方法"))
        self.assertTrue(pipeline._explicit_change_request("别人说换个方法，我也想换个方法"))

    def test_first_person_direction_survives_third_party_reporting(self):
        for text in (
            "别人说‘换个方法’，但我不想换方法",
            "别人说换个方法，但我不想换方法",
            "评委说：换个方法，但我不想换方法",
            "评委说: 'change the method'，但我不想换方法",
        ):
            with self.subTest(text=text):
                self.assertFalse(pipeline._explicit_change_request(text))
                self.assertTrue(pipeline._negated_change_request(text))
                self.assertEqual(pipeline._intent_field(text), "other")

    def test_latest_direct_instruction_wins_when_user_changes_their_mind(self):
        self.assertTrue(pipeline._explicit_change_request("我不想换方法，但能不能换个思路"))
        self.assertTrue(pipeline._negated_change_request("我本来想换方法，不过现在不想换方法"))

    def test_past_intent_does_not_override_current(self):
        # 「本来/原来……现在……」中，过去想法不覆盖当前诉求；交给评委判断（directive=None）。
        for text in (
            "我本来不想换方法，现在给我换条路吧",
            "我本来不想换方法，但现在试个别的记法吧",
            "我本来想换方法，现在先沿用原来的思路吧",
            "我原来想换个方法，但现在不用换了",
        ):
            with self.subTest(text=text):
                self.assertIsNone(pipeline._change_directive(text))

    def test_past_intent_current_change_wins(self):
        # 现在明确要换（必须换），过去「不想换」不覆盖。
        self.assertTrue(pipeline._explicit_change_request("我原来不想换方法，但现在必须换"))

    def test_double_negation_must_change_and_cannot_not_change(self):
        # 双重否定「不能不换」= 必须换；反问「能不能不换」= 保留，不能被误判。
        self.assertTrue(pipeline._explicit_change_request("不能不换方法"))
        self.assertTrue(pipeline._negated_change_request("能不能不换方法"))

    def test_change_words_cover_variants(self):
        # 「换种思路」「非换不可」「必须换」也是明确的换法信号。
        self.assertTrue(pipeline._explicit_change_request("换种思路吧"))
        self.assertTrue(pipeline._explicit_change_request("这个非换不可"))
        self.assertTrue(pipeline._explicit_change_request("这个方法必须换"))

    def test_quoted_text_does_not_override_instruction(self):
        for text in (
            "请换个方法，别再出现「不用换方法」这句话",
            "请换个方法，别再出现“先解释，不用换方法”这句话",
            "请换个方法，别再出现‘don’t change the method’这句话",
            "请换个方法，别再出现'don't change the method'这句话",
        ):
            with self.subTest(text=text):
                self.assertEqual(pipeline._intent_field(text), "change_method")
        self.assertEqual(pipeline._intent_field("不用换方法，把『换个方法』这句话删掉"), "other")

    def test_unframed_quote_is_undetermined_but_direct_request_can_use_quote(self):
        self.assertIsNone(pipeline._intent_field("「换个方法」"))
        self.assertIsNone(pipeline._intent_field("请把「换个方法」这句删掉"))
        self.assertEqual(
            pipeline._intent_field("本来想保留，不用换方法；不过我要求你：「换个方法」"),
            "change_method",
        )
        self.assertEqual(pipeline._intent_field("请「不用换方法」就行"), "other")

    def test_coordinated_quote_is_a_request_only_when_followed_by_an_action(self):
        self.assertTrue(pipeline._explicit_change_request("请「换个方法」并讲清楚第二步"))
        self.assertTrue(pipeline._negated_change_request("请「不用换方法」并讲清楚第二步"))
        self.assertEqual(pipeline._intent_field("请「不用换方法」并讲清楚第二步"), "explain")
        self.assertTrue(pipeline._explicit_change_request('Please "change the method".'))
        self.assertTrue(pipeline._explicit_change_request('Please "change the method" and explain.'))
        self.assertFalse(pipeline._explicit_change_request("请「换个方法」并列的说法都删掉"))
        self.assertFalse(pipeline._explicit_change_request('Please "change the method" and similar phrases.'))
        self.assertFalse(pipeline._explicit_change_request("评委请「换个方法」并讲清楚第二步"))

    def test_rhetorical_suggestion_is_not_negation(self):
        # 反问/建议语气不是「不用换」：同方法稿必须被硬闸挡住。
        for text in (
            "要不要换个方法",
            "要不要换方法试试",
            "是不是该换个方法",
            "何不换个方法",
            "为什么不换个方法",
            "要不换个方法",
            "怎么不换个方法",
        ):
            self.assertTrue(pipeline._explicit_change_request(text), text)
            self.assertFalse(pipeline._negated_change_request(text), text)
            self.assertFalse(
                pipeline._passed(
                    {"instruction": text, "word": "anchor"},
                    {"拆分成立": 8, "方法选对": 8, "谐音过关": 8,
                     "逻辑连贯": 8, "文本充足": 8, "像人说话": 8, "诉求落实": 8},
                    intent="other",
                    same_method=True,
                ),
                text,
            )

    def test_explicit_change_gate_uses_raw_instruction(self):
        item = {"instruction": "换个方法", "word": "anchor"}
        # 评委误判 other，同方法稿仍不能过。
        self.assertFalse(pipeline._passed(item, {"拆分成立": 9, "方法选对": 9, "谐音过关": 9,
                                                 "逻辑连贯": 9, "文本充足": 9, "像人说话": 9,
                                                 "诉求落实": 9}, intent="other", same_method=True))
        # 评委误判 change_method，但用户原话是否定：不能误伤同方法稿。
        item = {"instruction": "不用换方法", "word": "anchor"}
        self.assertTrue(pipeline._passed(item, {"拆分成立": 9, "方法选对": 9, "谐音过关": 9,
                                                "逻辑连贯": 9, "文本充足": 9, "像人说话": 9,
                                                "诉求落实": 9}, intent="change_method", same_method=True))


if __name__ == "__main__":
    unittest.main()

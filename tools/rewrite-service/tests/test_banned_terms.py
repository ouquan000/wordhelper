#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""banned_terms 反问极性：别把「就不能用 X 吗」这种请求使用当禁用。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pipeline


def _needles(bans):
    return sorted({(b["method"], b["needle"]) for b in bans})


class BannedTermsPolarityTest(unittest.TestCase):
    def test_rhetorical_request_use_is_not_a_ban(self):
        # 用户索要「含义联想」，不是禁它。
        for text in (
            "不是，你们怎么老是带我寄那个？词根词缀呀，你就不能用含义联想之类的其他方法吗？",
            "就不能用含义联想吗",
            "难道不能用词根吗",
            "你就不能用含义联想吗",
        ):
            with self.subTest(text=text):
                self.assertEqual(pipeline.banned_terms(text), [], text)

    def test_plain_and_explicit_bans_still_fire(self):
        for text in ("不能用词根", "不要再用词根", "能不能别用词根"):
            with self.subTest(text=text):
                bans = pipeline.banned_terms(text)
                self.assertIn(("root", "词根"), _needles(bans), text)

    def test_mixed_half_ban_half_request(self):
        # 禁词根、请求含义联想：只禁词根。
        bans = pipeline.banned_terms("不要用词根，能不能用含义联想")
        self.assertIn(("root", "词根"), _needles(bans))
        self.assertNotIn(("meaning", "含义联想"), _needles(bans))

    def test_current_scope_ban_vs_keep_method(self):
        # 「先别用 X」对本次仍是明确禁用；「先别改方法」是保留现状，不触发。
        self.assertIn(("root", "词根"), _needles(pipeline.banned_terms("先别用词根")))
        self.assertEqual(pipeline.banned_terms("先别改方法"), [])

    def test_restated_ban_stays_effective(self):
        bans = pipeline.banned_terms("我说过别用词根，这次也别用")
        self.assertIn(("root", "词根"), _needles(bans))

    def test_positive_request_is_not_a_ban(self):
        self.assertEqual(
            pipeline.banned_terms("这个词根记法挺好，以后就用它记吧"), []
        )

    def test_noun_ending_with_neng_is_not_released(self):
        # 「功能/可能 + 不能用 X」的「能」不是反问，仍是禁用。
        for text in ("功能不能用词根", "可能不能用词根", "这个功能不能用词根"):
            with self.subTest(text=text):
                self.assertIn(("root", "词根"), _needles(pipeline.banned_terms(text)), text)

    def test_zhilei_is_not_a_ban_word(self):
        # 「之类」是模糊后缀，不能当禁词。
        bans = pipeline.banned_terms("不要用词根之类")
        self.assertIn(("root", "词根"), _needles(bans))
        for b in bans:
            self.assertNotIn("之类", b["needle"], b)

    def test_draft_hits_follow_polarity(self):
        # 请求使用「含义联想」时，meaning 草稿不该被判违规。
        bans = pipeline.banned_terms("你就不能用含义联想之类的其他方法吗？")
        self.assertEqual(bans, [])
        draft = {
            "method": "meaning",
            "split": "ob、scur",
            "elements": ["ob", "scur"],
            "derivation": ["① ob 像灰布罩路灯", "② scur 是看不清", "③ 看不清 -> obscure"],
        }
        self.assertEqual(pipeline._draft_hits(bans, draft), [])
        # 明确禁用「含义联想」时，meaning 草稿要命中。
        bans = pipeline.banned_terms("不要用含义联想")
        self.assertEqual(pipeline._draft_hits(bans, draft), ["含义联想"])


if __name__ == "__main__":
    unittest.main()

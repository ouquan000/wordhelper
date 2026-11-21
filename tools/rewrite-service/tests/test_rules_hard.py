#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rules_hard 确定性硬拦截：黑名单、步数、箭头、用户禁令、干净文本。"""

import os
import sys
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import rules_hard  # noqa: E402


CLEAN = [
    "① cat 就是猫，he 是他。",
    "② 猫和他蹲在门口，一起抬头看尖顶。",
    "③ 先记 cat-he-dral，真读音 /kəˈθiːdrəl/ ——> 大教堂。",
]


def lines(*rows):
    return list(rows)


class RulesHardTest(unittest.TestCase):
    def test_clean_text_passes(self):
        result = rules_hard.check(CLEAN)
        self.assertTrue(result.ok)
        self.assertEqual(result.hits, [])
        self.assertEqual(result.feedback, "")
        # 兼容 dict 读法与属性读法
        self.assertTrue(result["ok"])
        self.assertEqual(result.to_dict()["hits"], [])

    def test_textbook_blacklist(self):
        for word in ("该词表示", "用于描述", "本词指", "意为", "核心意思是", "具体化为", "通过联想", "整体绑定"):
            body = lines(CLEAN[0], "② " + word + "一个画面。", CLEAN[2])
            result = rules_hard.check(body)
            self.assertFalse(result.ok, word)
            self.assertTrue(any(word in hit for hit in result.hits), (word, result.hits))
            self.assertIn(word, result.feedback)

    def test_choose_method_blacklist(self):
        body = lines(CLEAN[0], "② 因此选择谐音法来记。", CLEAN[2])
        result = rules_hard.check(body)
        self.assertFalse(result.ok)
        self.assertTrue(any("选择" in hit for hit in result.hits))

    def test_translation_blacklist(self):
        cases = (
            "② 进行拆分之后再看。",
            "② 使得画面更清楚。",
            "② 对于初学者而言很好记。",
            "② 通过声音来记住。",
            "② 在读音相似的情况下能记住。",
        )
        for row in cases:
            result = rules_hard.check(lines(CLEAN[0], row, CLEAN[2]))
            self.assertFalse(result.ok, row)

    def test_step_count_must_be_three_to_five(self):
        two = lines("① cat 是猫。", "② ——> 大教堂。")
        six = lines("① a", "② b", "③ c", "④ d", "⑤ e", "⑥ ——> 词义")
        for body in (two, six):
            result = rules_hard.check(body)
            self.assertFalse(result.ok)
            self.assertTrue(any("步数" in hit for hit in result.hits), result.hits)

    def test_last_line_needs_arrow(self):
        result = rules_hard.check(lines(CLEAN[0], CLEAN[1], "③ 最后是大教堂。"))
        self.assertFalse(result.ok)
        self.assertTrue(any("——>" in hit for hit in result.hits))

    def test_note_after_arrow_is_allowed(self):
        body = lines(CLEAN[0], CLEAN[1], "③ ——> 大教堂。", "注：真读音 /kəˈθiːdrəl/。")
        self.assertTrue(rules_hard.check(body).ok)

    def test_banned_terms_list(self):
        body = lines(CLEAN[0], "② 这里用谐音记。", CLEAN[2])
        result = rules_hard.check(body, banned_terms=["谐音"])
        self.assertFalse(result.ok)
        self.assertTrue(any("用户禁令：谐音" in hit for hit in result.hits))
        self.assertIn("谐音", result.feedback)

    def test_banned_terms_from_instruction(self):
        body = lines(CLEAN[0], "② 这里用谐音记。", CLEAN[2])
        result = rules_hard.check(body, instruction="不要用谐音")
        self.assertFalse(result.ok)
        self.assertTrue(any("用户禁令" in hit for hit in result.hits))

    def test_banned_terms_accept_dict(self):
        body = lines(CLEAN[0], "② 这里用谐音记。", CLEAN[2])
        result = rules_hard.check(body, banned_terms=[{"needle": "谐音", "label": "谐音"}])
        self.assertFalse(result.ok)

    def test_negated_ban_is_not_a_hit(self):
        body = lines(CLEAN[0], "② 不靠谐音，靠画面。", CLEAN[2])
        self.assertTrue(rules_hard.check(body, banned_terms=["谐音"]).ok)

    def test_empty_derivation(self):
        result = rules_hard.check([])
        self.assertFalse(result.ok)
        self.assertTrue(any("为空" in hit for hit in result.hits))


if __name__ == "__main__":
    unittest.main()

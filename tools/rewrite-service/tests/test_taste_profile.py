#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""动态审美画像：读写、条数/字数上限、lockedRules 保护、fake provider 蒸馏。"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import taste_profile  # noqa: E402


class FakeProvider:
    def __init__(self, rules):
        self.rules = rules
        self.calls = []

    def complete(self, model, system, user, **kwargs):
        self.calls.append({"model": model, "system": system, "user": user, "kwargs": kwargs})
        return json.dumps({"rules": self.rules}, ensure_ascii=False)


class TasteProfileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def _feedback(self, rows):
        with open(os.path.join(self.dir, "feedback.jsonl"), "w", encoding="utf-8") as fh:
            for index, row in enumerate(rows):
                payload = {"clientId": "c%d" % index, "word": row.get("word", "w%d" % index),
                           "rating": row.get("rating", "up"), "method": row.get("method", "meaning"),
                           "createdAt": row.get("createdAt", "2026-11-%02dT10:00:00+08:00" % (index + 1))}
                if row.get("note"):
                    payload["note"] = row["note"]
                fh.write(json.dumps(payload, ensure_ascii=False) + "\n")

    def test_missing_profile_is_zero_intrusion(self):
        profile = taste_profile.load(self.dir)
        self.assertEqual(profile["rules"], [])
        self.assertEqual(profile["lockedRules"], [])
        self.assertEqual(taste_profile.block(self.dir), "")

    def test_corrupt_profile_falls_back_to_empty(self):
        with open(taste_profile.profile_path(self.dir), "w", encoding="utf-8") as fh:
            fh.write("{not json")
        self.assertEqual(taste_profile.load(self.dir)["rules"], [])

    def test_sanitize_caps_count_and_chars(self):
        rows = taste_profile.sanitize_rules(["规则%d：%s" % (i, "很长" * 20) for i in range(12)])
        self.assertLessEqual(len(rows), taste_profile.MAX_RULES)
        self.assertLessEqual(sum(len(row) for row in rows), taste_profile.MAX_CHARS)

    def test_save_round_trip_keeps_locked(self):
        profile = taste_profile.save({
            "rules": ["锁定规则", "普通规则"],
            "lockedRules": ["锁定规则"],
            "updatedAt": "2026-11-26T00:00:00Z",
            "sourceFeedbackCount": 3,
        }, self.dir)
        again = taste_profile.load(self.dir)
        self.assertEqual(again["rules"], profile["rules"])
        self.assertEqual(again["lockedRules"], ["锁定规则"])
        self.assertEqual(again["sourceFeedbackCount"], 3)

    def test_set_rules_adds_locked_first(self):
        taste_profile.set_rules(self.dir, ["普通规则"], ["锁定规则"])
        profile = taste_profile.load(self.dir)
        self.assertEqual(profile["rules"][0], "锁定规则")
        self.assertIn("普通规则", profile["rules"])
        self.assertEqual(profile["lockedRules"], ["锁定规则"])

    def test_distill_uses_fake_provider_and_keeps_locked(self):
        self._feedback([{"word": "alpha"}, {"word": "beta", "rating": "down", "note": "太绕了"},
                        {"word": "gamma"}, {"word": "delta"}, {"word": "epsilon"}, {"word": "zeta"}])
        taste_profile.set_rules(self.dir, ["先亮词再拆块"], ["先亮词再拆块"])
        provider = FakeProvider(["规则%d" % i for i in range(6)])
        profile = taste_profile.distill(self.dir, provider=provider)
        self.assertEqual(len(provider.calls), 1)
        self.assertEqual(provider.calls[0]["model"], taste_profile.DEFAULT_MODEL)
        self.assertIn("太绕了", provider.calls[0]["user"])
        self.assertEqual(profile["rules"][0], "先亮词再拆块")
        self.assertLessEqual(len(profile["rules"]), taste_profile.MAX_RULES)
        self.assertGreaterEqual(len(profile["rules"]), taste_profile.MIN_RULES)
        self.assertEqual(profile["sourceFeedbackCount"], 6)
        self.assertTrue(profile["updatedAt"].endswith("Z"))
        self.assertLessEqual(sum(len(row) for row in profile["rules"]), taste_profile.MAX_CHARS)
        self.assertIn("先亮词再拆块", taste_profile.block(self.dir))

    def test_distill_without_feedback_skips_provider(self):
        class Boom:
            def complete(self, *args, **kwargs):
                raise AssertionError("没有反馈时不该调模型")

        profile = taste_profile.distill(self.dir, provider=Boom())
        self.assertEqual(profile["rules"], [])


if __name__ == "__main__":
    unittest.main()

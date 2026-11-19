#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""profile 六招 Beta 画像：冷启动先验、反馈/记忆更新、Thompson Top2、原子写。"""

import glob
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import profile  # noqa: E402


class ProfileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "profile.json")
        self.profile = profile.Profile(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def _row(self, method):
        return self.profile.snapshot()["methods"][method]

    def test_cold_start_priors(self):
        snap = self.profile.snapshot()
        expected = {
            "sound": (6.0, 1.0),
            "shape": (3.0, 2.0),
            "contrast": (3.0, 2.0),
            "root": (1.5, 4.0),
            "meaning": (1.5, 4.0),
            "polysemy": (1.5, 4.0),
        }
        for method, (alpha, beta) in expected.items():
            row = snap["methods"][method]
            self.assertEqual((row["alpha"], row["beta"]), (alpha, beta), method)

    def test_feedback_up_increments_alpha(self):
        before = self._row("sound")
        out = self.profile.update_feedback("sound", "up")
        self.assertTrue(out["applied"])
        after = self._row("sound")
        self.assertAlmostEqual(after["alpha"], before["alpha"] + 1.0)
        self.assertEqual(after["beta"], before["beta"])

    def test_feedback_down_increments_beta(self):
        before = self._row("shape")
        self.profile.update_feedback("shape", "down")
        after = self._row("shape")
        self.assertAlmostEqual(after["beta"], before["beta"] + 1.2)
        self.assertEqual(after["alpha"], before["alpha"])

    def test_feedback_event_id_is_idempotent(self):
        first = self.profile.update_feedback("root", "up", event_id="feedback:c1")
        second = self.profile.update_feedback("root", "up", event_id="feedback:c1")
        self.assertTrue(first["applied"])
        self.assertFalse(second["applied"])
        self.assertAlmostEqual(self._row("root")["alpha"], 2.5)

    def test_feedback_rejects_unknown(self):
        with self.assertRaises(ValueError):
            self.profile.update_feedback("不存在", "up")
        with self.assertRaises(ValueError):
            self.profile.update_feedback("sound", "meh")

    def test_memory_retained_increments_alpha(self):
        before = self._row("meaning")
        out = self.profile.update_memory_signal("meaning", "retained")
        self.assertTrue(out["applied"])
        self.assertAlmostEqual(self._row("meaning")["alpha"], before["alpha"] + 1.5)

    def test_memory_lapsed_increments_beta(self):
        before = self._row("polysemy")
        self.profile.update_memory_signal("polysemy", "lapsed")
        self.assertAlmostEqual(self._row("polysemy")["beta"], before["beta"] + 1.5)

    def test_memory_event_id_is_idempotent(self):
        first = self.profile.update_memory_signal("shape", "lapsed", event_id="memory:1")
        second = self.profile.update_memory_signal("shape", "lapsed", event_id="memory:1")
        self.assertTrue(first["applied"])
        self.assertFalse(second["applied"])
        self.assertAlmostEqual(self._row("shape")["beta"], 3.5)

    def test_memory_rejects_unknown_event(self):
        with self.assertRaises(ValueError):
            self.profile.update_memory_signal("sound", "maybe")

    def test_recommend_ranking_samples_each_method_once(self):
        # METHODS 顺序：sound/root/contrast/shape/polysemy/meaning
        draws = [0.1, 0.5, 0.3, 0.9, 0.2, 0.7]
        with mock.patch.object(profile.random, "betavariate", side_effect=draws) as sampler:
            ranking = self.profile.recommend_ranking()
        self.assertEqual(sampler.call_count, 6)
        self.assertEqual(len(ranking), 6)
        self.assertEqual(len(set(ranking)), 6)
        self.assertEqual(ranking, ["shape", "meaning", "root", "contrast", "polysemy", "sound"])

    def test_recommend_top_returns_two_distinct(self):
        top = self.profile.recommend_top(2)
        self.assertEqual(len(top), 2)
        self.assertEqual(len(set(top)), 2)
        self.assertTrue(all(x in profile.METHODS for x in top))

    def test_atomic_write_leaves_valid_json_and_no_temp(self):
        self.profile.update_feedback("contrast", "up")
        with open(self.path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertAlmostEqual(data["methods"]["contrast"]["alpha"], 4.0)
        self.assertEqual(glob.glob(os.path.join(self.tmp.name, ".profile-*")), [])

    def test_failed_replace_keeps_old_file(self):
        self.profile.update_feedback("sound", "up")
        with open(self.path, "r", encoding="utf-8") as fh:
            before = fh.read()
        with mock.patch.object(profile.os, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.profile.update_feedback("sound", "up")
        with open(self.path, "r", encoding="utf-8") as fh:
            after = fh.read()
        self.assertEqual(after, before)
        self.assertEqual(glob.glob(os.path.join(self.tmp.name, ".profile-*")), [])

    def test_reload_keeps_state(self):
        self.profile.update_feedback("root", "down", event_id="feedback:x")
        reloaded = profile.Profile(self.path)
        row = reloaded.snapshot()["methods"]["root"]
        self.assertAlmostEqual(row["beta"], 5.2)
        self.assertIn("feedback:x", reloaded.snapshot()["feedbackIds"])


if __name__ == "__main__":
    unittest.main()

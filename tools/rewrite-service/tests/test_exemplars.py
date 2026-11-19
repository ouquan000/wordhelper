#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exemplars 黄金正例/避雷反例池：种子、检索兜底、去重、空池不崩。"""

import glob
import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import exemplars  # noqa: E402


CLEAN = [
    "① cat 就是猫，he 是他。",
    "② 猫和他蹲在门口，一起抬头看尖顶。",
    "③ 先记 cat-he-dral，真读音 /kəˈθiːdrəl/ ——> 大教堂。",
]


def new_item(item_id="custom:1", method="root", word="alpha", kind="pos", derivation=None):
    return {
        "id": item_id,
        "kind": kind,
        "word": word,
        "method": method,
        "note": "测试",
        "derivation": list(derivation or CLEAN),
    }


class ExemplarsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "exemplars.jsonl")
        self.pool = exemplars.Exemplars(self.tmp.name, self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_seeds_are_present(self):
        snap = self.pool.snapshot()
        words = {row["word"] for row in snap["items"]}
        self.assertEqual(words, {"monopoly", "obscure", "cathedral"})
        self.assertEqual(snap["pos"], 3)
        self.assertEqual(snap["neg"], 0)
        self.assertTrue(os.path.exists(self.path))
        with open(self.path, "r", encoding="utf-8") as fh:
            self.assertEqual(len([x for x in fh if x.strip()]), 3)

    def test_retrieve_same_method_returns_two_pos(self):
        out = self.pool.retrieve("sound", k_pos=2, k_neg=1)
        self.assertEqual(len(out["pos"]), 2)
        self.assertTrue(all(x["method"] == "sound" for x in out["pos"]))
        self.assertFalse(out["fallback"])
        # 没有反例种子时 neg 为空，但结构必须完整，prompt 侧可静默跳过。
        self.assertEqual(out["neg"], [])

    def test_retrieve_falls_back_to_global_positive(self):
        out = self.pool.retrieve("contrast", k_pos=2, k_neg=1)
        self.assertEqual(len(out["pos"]), 2)
        self.assertTrue(out["fallback"])
        self.assertTrue(all(x["method"] == "sound" for x in out["pos"]))

    def test_retrieve_never_returns_empty_positive(self):
        # 即使方法名认不出，也要用全局正例兜底。
        out = self.pool.retrieve("不存在的方法", k_pos=2, k_neg=1)
        self.assertEqual(len(out["pos"]), 2)
        self.assertTrue(out["fallback"])

    def test_add_dedupes_by_id_and_signature(self):
        self.assertTrue(self.pool.add(new_item("custom:1")))
        self.assertFalse(self.pool.add(new_item("custom:1")))
        # 同内容不同 id 也要去重
        self.assertFalse(self.pool.add(new_item("custom:2")))
        self.assertEqual(len(self.pool.snapshot()["items"]), 4)

    def test_positive_must_pass_rules_hard(self):
        bad = new_item("custom:bad", derivation=["① 该词表示一种东西。", "② 意为某某。", "③ ——> 词义"])
        with self.assertRaises(ValueError):
            self.pool.add(bad)

    def test_ingest_feedback_is_idempotent(self):
        items = [
            {
                "clientId": "c9",
                "word": "beta",
                "method": "sound",
                "rating": "up",
                "approach": CLEAN,
                "receivedAt": "2026-01-01T00:00:00+08:00",
            },
            {
                "clientId": "c10",
                "word": "gamma",
                "method": "contrast",
                "rating": "down",
                "instruction": "太绕了，听不懂",
                "approach": CLEAN,
            },
        ]
        self.assertEqual(self.pool.ingest_feedback(items), 2)
        self.assertEqual(self.pool.ingest_feedback(items), 0)
        snap = self.pool.snapshot()
        self.assertEqual(snap["pos"], 4)
        self.assertEqual(snap["neg"], 1)
        neg = [x for x in snap["items"] if x["kind"] == "neg"][0]
        self.assertIn("太绕了", neg["note"])

    def test_negative_is_retrieved_for_method(self):
        self.pool.add(new_item("neg:1", method="contrast", word="adapt", kind="neg", derivation=CLEAN))
        out = self.pool.retrieve("contrast", k_pos=2, k_neg=1)
        self.assertEqual(len(out["neg"]), 1)
        self.assertEqual(out["neg"][0]["word"], "adapt")

    def test_atomic_write_no_temp_left(self):
        self.pool.add(new_item("custom:1"))
        self.assertEqual(glob.glob(os.path.join(self.tmp.name, ".exemplars-*")), [])
        with open(self.path, "r", encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    self.assertIsInstance(json.loads(line), dict)

    def test_new_instance_reloads_saved_rows(self):
        self.pool.add(new_item("custom:1"))
        again = exemplars.Exemplars(self.tmp.name, self.path)
        ids = {row["id"] for row in again.snapshot()["items"]}
        self.assertIn("custom:1", ids)
        self.assertEqual(len(ids), 4)


if __name__ == "__main__":
    unittest.main()

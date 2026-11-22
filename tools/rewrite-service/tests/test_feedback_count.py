#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.5.2 反馈 count 兼容：一条票可以代表「喊了几次」。

规则（三处必须一致）：
- 缺省 = 1，合法范围 1..50，bool 不算整数，非法整条拒掉、不影响同批其它条；
- 落盘行一定有 count，未知字段照旧原样透传；
- 画像权重 = 1 + min(3.0, log2(max(1, count)))：1→1、2→2、4→3、8→4（封顶），
  down 仍乘 1.2 的旧系数，所以 count=1 时与 1.4.6 逐位一致；
- clientId 幂等语义不变：同一 clientId 重推只算一次。
"""

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import profile  # noqa: E402
import service  # noqa: E402
import store as store_mod  # noqa: E402
from store import Store  # noqa: E402


def read_rows(data_dir):
    path = os.path.join(data_dir, "feedback.jsonl")
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def item(client_id="f1", rating="up", method="sound", count=None):
    row = {
        "clientId": client_id,
        "word": "cathedral",
        "bookId": "book-1",
        "wordId": 73,
        "method": method,
        "rating": rating,
        "approach": "① 谐音走通 ② 收在词义上",
        "approachRev": 2,
    }
    if count is not None:
        row["count"] = count
    return row


class FeedbackCountStoreTest(unittest.TestCase):
    """store.add_feedback：校验 count、落盘带 count、未知字段不丢。"""

    def test_missing_count_defaults_to_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback([item()])
            self.assertEqual(accepted, ["f1"])
            self.assertEqual(rejected, [])
            rows = read_rows(tmp)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["count"], 1)
            self.assertIsInstance(rows[0]["count"], int)

    def test_count_in_range_is_persisted(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            items = [item("f1", count=1), item("f2", count=2), item("f3", count=50)]
            accepted, rejected = store.add_feedback(items)
            self.assertEqual(accepted, ["f1", "f2", "f3"])
            self.assertEqual(rejected, [])
            rows = read_rows(tmp)
            self.assertEqual([row["count"] for row in rows], [1, 2, 50])

    def test_invalid_count_rejected(self):
        bad_values = [0, 51, -3, True, False, "3", 3.5, [], {}]
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback(
                [item("f%d" % index, count=value) for index, value in enumerate(bad_values)]
            )
            self.assertEqual(accepted, [])
            self.assertEqual(len(rejected), len(bad_values))
            for row in rejected:
                self.assertIn("count", row["reason"])
            self.assertEqual(read_rows(tmp), [])

    def test_bad_count_does_not_drop_neighbours(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback(
                [item("ok1", count=3), item("bad", count=0), item("ok2")]
            )
            self.assertEqual(accepted, ["ok1", "ok2"])
            self.assertEqual([row["clientId"] for row in rejected], ["bad"])
            rows = read_rows(tmp)
            self.assertEqual([(row["clientId"], row["count"]) for row in rows],
                             [("ok1", 3), ("ok2", 1)])

    def test_unknown_fields_and_idempotency_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.add_feedback([item("same", count=4)])
            accepted, rejected = store.add_feedback([item("same", count=4)])
            self.assertEqual(accepted, ["same"])
            self.assertEqual(rejected, [])
            rows = read_rows(tmp)
            self.assertEqual(len(rows), 1)  # clientId 幂等：不写第二行
            self.assertEqual(rows[0]["approachRev"], 2)
            self.assertEqual(rows[0]["count"], 4)

    def test_feedback_count_helper(self):
        self.assertEqual(store_mod.feedback_count(None), (1, ""))
        self.assertEqual(store_mod.feedback_count(1), (1, ""))
        self.assertEqual(store_mod.feedback_count(50), (50, ""))
        for value in (0, 51, True, "3", 1.5, []):
            count, reason = store_mod.feedback_count(value)
            self.assertEqual(count, 0)
            self.assertTrue(reason)


class FeedbackWeightTest(unittest.TestCase):
    """profile：权重曲线与 down 的 1.2 系数。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.prof = profile.Profile(os.path.join(self.tmp.name, "profile.json"))

    def tearDown(self):
        self.tmp.cleanup()

    def state(self, method="sound"):
        row = self.prof.snapshot()["methods"][method]
        return row["alpha"], row["beta"]

    def test_weight_curve_and_cap(self):
        self.assertEqual([profile.feedback_weight(n) for n in (1, 2, 4, 8, 16, 50)],
                         [1.0, 2.0, 3.0, 4.0, 4.0, 4.0])
        # 非法 / 越界输入回落 1，绝不放大
        self.assertEqual(profile.feedback_weight(0), 1.0)
        self.assertEqual(profile.feedback_weight(-5), 1.0)
        self.assertEqual(profile.feedback_weight(True), 1.0)
        self.assertEqual(profile.feedback_weight("8"), 1.0)

    def test_up_scales_alpha(self):
        before = self.state()[0]
        out = self.prof.update_feedback("sound", "up", event_id="a", count=4)
        self.assertTrue(out["applied"])
        self.assertEqual(out["count"], 4)
        self.assertEqual(out["weight"], 3.0)
        self.assertAlmostEqual(self.state()[0], before + 3.0)

    def test_down_scales_beta_with_legacy_factor(self):
        before = self.state()[1]
        self.prof.update_feedback("sound", "down", event_id="b", count=2)
        self.assertAlmostEqual(self.state()[1], before + 2.0 * 1.2)

    def test_legacy_call_without_count_unchanged(self):
        alpha, beta = self.state()
        self.prof.update_feedback("sound", "up", event_id="c")
        self.assertAlmostEqual(self.state()[0], alpha + 1.0)
        self.prof.update_feedback("sound", "down", event_id="d")
        self.assertAlmostEqual(self.state()[1], beta + 1.2)

    def test_module_level_update_feedback_takes_count(self):
        self.assertTrue(callable(profile.update_feedback))
        with tempfile.TemporaryDirectory() as tmp:
            old = os.environ.get("REWRITE_DATA_DIR")
            os.environ["REWRITE_DATA_DIR"] = tmp
            try:
                prof = profile.get_profile(os.path.join(tmp, "profile.json"))
                before = prof.snapshot()["methods"]["root"]["alpha"]
                profile.update_feedback("root", "up", event_id="m1", count=8)
                after = prof.snapshot()["methods"]["root"]["alpha"]
                self.assertAlmostEqual(after, before + 4.0)
            finally:
                if old is None:
                    os.environ.pop("REWRITE_DATA_DIR", None)
                else:
                    os.environ["REWRITE_DATA_DIR"] = old

    def test_duplicate_event_id_still_deduped(self):
        self.prof.update_feedback("sound", "up", event_id="dup", count=8)
        alpha = self.state()[0]
        out = self.prof.update_feedback("sound", "up", event_id="dup", count=8)
        self.assertFalse(out["applied"])
        self.assertEqual(out["reason"], "duplicate")
        self.assertAlmostEqual(self.state()[0], alpha)


class FeedbackCountApiTest(unittest.TestCase):
    """POST /rewrite/feedback：count 一路传到画像与 feedback.jsonl。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name).load()
        self.old_state = dict(service._state)
        self.old_data_dir = service.DATA_DIR
        self.old_log_path = service.LOG_PATH
        service.DATA_DIR = self.tmp.name
        service.LOG_PATH = os.path.join(self.tmp.name, "logs", "service.log")
        service._state.update({"store": self.store, "ready": True, "startedAt": "test"})

    def tearDown(self):
        self.store.close()
        service._state.clear()
        service._state.update(self.old_state)
        service.DATA_DIR = self.old_data_dir
        service.LOG_PATH = self.old_log_path
        self.tmp.cleanup()

    def post(self, items):
        status, _ctype, raw = service.respond(
            "POST", "/rewrite/feedback", json.dumps({"items": items}).encode("utf-8")
        )
        return status, json.loads(raw.decode("utf-8"))

    def alpha(self, method="sound"):
        path = os.path.join(self.tmp.name, "profile.json")
        return profile.get_profile(path).snapshot()["methods"][method]["alpha"]

    def test_default_count_moves_alpha_by_one(self):
        before = self.alpha()
        status, out = self.post([item("api-1")])
        self.assertEqual(status, 200)
        self.assertEqual(out["accepted"], ["api-1"])
        self.assertAlmostEqual(self.alpha(), before + 1.0)
        self.assertEqual(read_rows(self.tmp.name)[0]["count"], 1)

    def test_count_scales_alpha_and_is_stored(self):
        before = self.alpha()
        status, out = self.post([item("api-2", count=8)])
        self.assertEqual(status, 200)
        self.assertEqual(out["accepted"], ["api-2"])
        self.assertAlmostEqual(self.alpha(), before + 4.0)
        self.assertEqual(read_rows(self.tmp.name)[0]["count"], 8)

    def test_invalid_count_rejected_and_profile_untouched(self):
        before = self.alpha()
        status, out = self.post([item("api-3", count=0), item("api-4", count=True)])
        self.assertEqual(status, 200)
        self.assertEqual(out["accepted"], [])
        self.assertEqual(len(out["rejected"]), 2)
        self.assertAlmostEqual(self.alpha(), before)
        self.assertEqual(read_rows(self.tmp.name), [])

    def test_mixed_batch_keeps_valid_items(self):
        before = self.alpha()
        status, out = self.post([item("api-5", count=2), item("api-6", count=99)])
        self.assertEqual(status, 200)
        self.assertEqual(out["accepted"], ["api-5"])
        self.assertEqual(len(out["rejected"]), 1)
        self.assertAlmostEqual(self.alpha(), before + 2.0)

    def test_old_client_without_count_is_compatible(self):
        before = self.alpha()
        status, out = self.post([item("api-7")])
        self.assertEqual(status, 200)
        self.assertEqual(len(out["rejected"]), 0)
        self.assertAlmostEqual(self.alpha(), before + 1.0)


if __name__ == "__main__":
    unittest.main()

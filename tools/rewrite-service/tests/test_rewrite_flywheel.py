"""Offline contracts across targeting, completion, publishing and judging."""
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import Mock, patch

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE))
sys.path.insert(0, str(SERVICE.parent / "cardback-editor"))
import batch_pipeline
import card_publisher
import pipeline
import store
import worker
from worker_target import BOOK, learning_window


class FlywheelTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.db = store.Store(str(self.root)).load()
        self.addCleanup(self.db.close)
        self.snapshot = self.root / "snapshot.json"
        self.config = worker.normalize_config({"enabled": True, "sync_path": str(self.snapshot),
                                               "new_word_fallback_limit": 1, "max_per_day": 4,
                                               "include_review": True})
        self.runtime = {"runs": [], "done": {}, "washed": 0}
        self.words = ["alpha", "beta", "due", "late", "far", "other"]
        self.index = {w: {"word": w, "id": i + 1, "approach": ["seed"]} for i, w in enumerate(self.words)}
        instant = datetime.now().astimezone().replace(hour=3, minute=59, second=0, microsecond=0)
        self.now = instant.isoformat()
        end = int((instant + timedelta(days=1, minutes=1)).timestamp() * 1000)
        self.rows = {w: dict(w=w, b=BOOK, t="progress", nrt=n, fsrs_lapses=l)
                     for w, n, l in [("due", end-1, 0), ("late", 1, 2), ("far", end, 4),
                                      ("other", 1, 0)]}
        self.sync([])
        for binding in (patch.object(worker, "_word_index", {"loaded": True, "by_word": self.index}),
                        patch.object(worker, "_load_runtime", return_value=self.runtime),
                        patch.object(worker, "_save_runtime"), patch.object(worker, "_now", return_value=self.now)):
            binding.start()
            self.addCleanup(binding.stop)

    def sync(self, upcoming):
        self.snapshot.write_text(json.dumps({"rows": self.rows, "upcoming_new_word_ids": upcoming}),
                                 encoding="utf-8")

    def vote(self, word, rating, second):
        self.db.add_feedback([dict(clientId=f"{word}-{second}", word=word, rating=rating,
                                   createdAt=f"2026-01-01T12:00:{second:02d}+08:00")])

    def targets(self):
        return [b["word"] for b in worker.candidates(self.db, self.config, self.runtime)]

    def test_new_then_lapses_then_old_and_no_future(self):
        self.sync([2])
        self.vote("other", "down", 1)
        self.assertEqual(self.targets(), ["beta", "other", "late", "due"])
        self.sync([])
        self.assertEqual(self.targets(), ["alpha", "other", "late", "due"])

    def test_include_review_false_skips_all_review_words(self):
        self.config["include_review"] = False
        self.config["new_word_fallback_limit"] = 10
        self.sync([])
        rows = worker.candidates(self.db, self.config, self.runtime)
        self.assertEqual([b["word"] for b in rows], ["alpha", "beta"])
        self.assertTrue(all(b["new"] for b in rows))
        self.assertFalse(any(b["why"] == "review" for b in rows))

    def test_runtime_review_override_enables_review_words(self):
        self.config["include_review"] = False
        self.runtime["includeReview"] = True
        self.sync([])
        self.assertIn("due", self.targets())

    def test_new_word_daily_cap_sleeps_after_only_new_words(self):
        self.config["include_review"] = False
        self.config["new_word_fallback_limit"] = 10
        self.config["max_new_per_day"] = 1
        self.sync([])
        self.assertEqual([b["word"] for b in worker.candidates(
            self.db, self.config, self.runtime)], ["alpha"])
        run = Mock(return_value={"ok": True, "entry": {"seq": 1}})
        first = worker.run_once(self.db, run, self.config)
        self.assertEqual(first["reason"], "ok")
        self.assertEqual([w["word"] for w in first["washed"]], ["alpha"])
        second = worker.run_once(self.db, run, self.config)
        self.assertEqual(second["reason"], "new_word_budget")
        self.assertEqual(run.call_count, 1)

    def test_upcoming_word_ids_still_outrank_alphabetical_new_words(self):
        self.config["include_review"] = False
        self.config["new_word_fallback_limit"] = 10
        self.sync(["beta"])
        rows = worker.candidates(self.db, self.config, self.runtime)
        self.assertEqual(rows[0]["word"], "beta")
        self.assertEqual(rows[0]["why"], "upcoming")

    def test_preview_ignores_today_new_word_budget(self):
        self.config["include_review"] = False
        day, _ = learning_window(self.now)
        self.runtime["daily"] = {"day": day, "attempts": 4, "fallback": 0, "new": 25}
        with patch.object(worker, "load_config", return_value=self.config), \
                patch.dict(worker._state, {"store": self.db}, clear=False):
            rows = worker.preview(limit=2)
        self.assertEqual([row["word"] for row in rows], ["alpha", "beta"])
        self.assertTrue(all(row["new"] for row in rows))

    def test_daily_reservation_survives_failed_run_and_pool_sleeps(self):
        self.config["max_per_day"] = 2
        run = Mock(return_value={"ok": False, "reason": "quality_rejected", "rejected": True})
        worker.run_once(self.db, run, self.config)
        self.assertNotIn("beta", self.targets())
        worker.run_once(self.db, run, self.config)
        self.assertEqual(worker.run_once(self.db, run, self.config)["reason"], "daily_budget")
        self.assertEqual(run.call_count, 2)
        self.runtime.clear()
        self.runtime.update(runs=[], done={})
        self.config["new_word_fallback_limit"] = 0
        self.rows = {"far": self.rows["far"]}
        self.sync([])
        self.assertEqual(worker.run_once(self.db, run, self.config)["reason"], "no_candidates")
        self.assertEqual(run.call_count, 2)

    def test_dole_as_component_is_rejected_but_the_target_word_is_allowed(self):
        lines = ["① dole 出现在这一行。", "② ——> 分发。"]
        self.assertFalse(pipeline.rules_hard.check(lines, elements=["dole"], target_word="other").ok)
        self.assertTrue(pipeline.rules_hard.check(lines, elements=["dole"], target_word="dole").ok)

    def test_learning_day_rolls_over_at_four(self):
        instant = datetime.fromisoformat(self.now)
        before, cutoff = learning_window(self.now)
        after, next_cutoff = learning_window((instant + timedelta(minutes=1)).isoformat())
        self.assertNotEqual(before, after)
        self.assertEqual(datetime.fromtimestamp(cutoff / 1000).hour, 4)
        self.assertGreater(next_cutoff, cutoff)

    def test_like_and_lock_protect_selection_and_inflight_write(self):
        self.sync(["alpha", "beta"])
        self.db.set_lock(2, 7, word="beta", book=BOOK)
        self.vote("alpha", "up", 2)
        self.vote("alpha", "down", 1)  # older event uploaded later
        self.assertNotIn("alpha", self.targets())
        self.assertNotIn("beta", self.targets())
        self.assertIsNone(self.db.record_worker_result("alpha", ["draft"], word_id=1))
        self.vote("alpha", "down", 3)
        self.assertIn("alpha", self.targets())
        entry = self.db.record_worker_result("alpha", ["approved"], word_id=1, book=BOOK)
        self.assertEqual(entry["engine"], "rewrite-v2")
        self.assertNotIn("alpha", self.targets())
        self.db.add_feedback([dict(word="alpha", clientId="later", rating="down",
                                   createdAt="2090-01-01T00:00:00Z")])
        self.assertIn("alpha", self.targets())

    def test_rejected_receipt_leaves_previous_approved_revision_intact(self):
        for cid in ("first", "second"):
            self.db.add_marks([dict(clientId=cid, word="alpha", model="ds-v41-flash")])
            item = self.db.claim()
            if cid == "first":
                approved = self.db.finish_ok(item, ["approved"])
            else:
                for _ in range(5):
                    with patch.object(store, "_retry_at", return_value="2000-01-01"):
                        self.db.finish_quality_rejected(item, ["rejected"], {"reason": "logic"})
                    if self.db.items[-1]["state"] != "failed":
                        item = self.db.claim()
        receipt, = self.db.results_page(approved["seq"], 10)["results"]
        self.assertEqual((receipt["status"], receipt["approach"], receipt["rev"]), ("failed", [], 1))
        self.assertEqual(store._current(self.db.results["alpha"])["approach"], ["approved"])

    def test_publisher_preserves_protected_body_and_requires_new_approval(self):
        seed = self.root / "seed.json"
        seed.write_text(json.dumps({"words": list(self.index.values())}), encoding="utf-8")
        out = self.root / "published"
        results = self.root / "results"
        def draft(word, second, source="", unverified=False):
            data = dict(word=word, status="ok", approach=[word + str(second)], method="root",
                        source=source, unverified=unverified, book=BOOK, rev=second,
                        updatedAt=f"2026-01-01T12:00:{second:02d}+08:00")
            (results / (word + ".json")).write_text(json.dumps({"entries": [data]}), encoding="utf-8")
        with patch.object(card_publisher, "SEED_PATH", seed), patch.object(card_publisher, "RESULTS_DIR", results):
            draft("alpha", 1)
            card_publisher.publish(out)
            self.vote("alpha", "up", 2)
            self.vote("due", "down", 2)
            self.db.set_lock(2, 7, word="beta")
            draft("alpha", 3, "worker")
            draft("beta", 3)
            draft("due", 1)
            draft("other", 3, unverified=True)
            def published():
                card_publisher.publish(out)
                return {w["word"]: w["approach"] for w in json.loads(
                    (out / card_publisher.CARDS_FILENAME).read_bytes())["words"]}
            cards = published()
            self.assertEqual(cards["alpha"], ["alpha1"])
            for w in ("beta", "due", "other"):
                self.assertEqual(cards[w], ["seed"])
            draft("alpha", 4)
            draft("due", 4, "worker")
            cards = published()
            self.assertEqual(cards["alpha"], ["alpha4"])
            self.assertEqual(cards["due"], ["due4"])

    def test_judge_single_and_batch_receive_bounded_method_examples(self):
        samples = {k: [dict(method="shape", derivation=[k + "x" * 900])] for k in ("pos", "neg")}
        judge = dict(intent="other", same_method=False, history_repeat=False,
                     **{key: False for key in pipeline.BINARY_FIELDS})
        item = dict(word="alpha", model="ds-v41-flash", history="历史反馈")
        draft = {"method": "shape"}
        with patch.object(pipeline.exemplars, "retrieve", return_value=samples) as retrieve, patch.object(
            pipeline, "style_block", return_value="黑榜注入"
        ), patch.object(pipeline.providers, "complete", return_value=json.dumps(judge)) as fake:
            pipeline.judge(item, draft, ["正文"])
            batch = batch_pipeline._judge_user([item], {"alpha": ["正文"]}, {"alpha": draft})
            for body in (fake.call_args.args[2], batch):
                for marker in ("历史反馈", "黑榜注入", "点赞正例", "点踩反例", "pos", "neg"):
                    self.assertIn(marker, body)
                self.assertNotIn("x" * 181, body)
            retrieve.assert_called_with("shape", k_pos=1, k_neg=1)


if __name__ == "__main__":
    unittest.main()

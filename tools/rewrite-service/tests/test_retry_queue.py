#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.4.7.2 持续重写/停止/代次：不跑模型，只测落盘状态机。"""

import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import service
from _isolation import guard_network, isolate  # noqa: E402

isolate(service)
guard_network()
import store as store_mod
from store import Store


MARK = {
    "clientId": "w1-100",
    "word": "alpha",
    "model": "ds-v41-flash",
    "instruction": "",
}


class RetryQueueTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name).load()
        self.store.add_marks([dict(MARK)])
        self.item = self.store.claim(timeout=1)
        self.assertIsNotNone(self.item)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_result_stream_distinguishes_same_seq_after_data_reset(self):
        old = self.store.finish_ok(self.item, ["old"], {})
        first = self.store.results_page(0, 10)
        self.assertEqual(old["seq"], 1)
        self.assertEqual(first["highWater"], 1)
        self.assertEqual(Store(self.tmp.name).load().results_page(1, 10)["streamId"], first["streamId"])
        with tempfile.TemporaryDirectory() as replacement_dir:
            replacement = Store(replacement_dir).load()
            replacement.add_marks([dict(MARK, clientId="w1-200")])
            item = replacement.claim(timeout=1)
            replacement.finish_ok(item, ["new"], {})
            collided = replacement.results_page(1, 10)
            self.assertEqual(collided["nextSince"], 1)
            self.assertEqual(collided["highWater"], 1)
            self.assertEqual(collided["results"], [])
            self.assertNotEqual(collided["streamId"], first["streamId"])
            self.assertEqual(replacement.results_page(0, 10)["results"][0]["approach"], ["new"])

    def test_recover_high_water_from_saved_results_when_state_missing(self):
        self.store.finish_ok(self.item, ["old"], {})
        os.remove(self.store.state_path)
        recovered = Store(self.tmp.name).load()
        self.assertEqual(recovered.results_page(50, 10)["nextSince"], 1)
        self.assertEqual(recovered.results_page(0, 10)["results"][0]["seq"], 1)
        recovered.add_marks([dict(MARK, clientId="w1-200")])
        item = recovered.claim(timeout=1)
        self.assertEqual(recovered.finish_ok(item, ["new"], {})["seq"], 2)

    def test_same_word_in_distinct_books_keeps_both_tasks_and_history(self):
        with tempfile.TemporaryDirectory() as data:
            store = Store(data).load()
            left = dict(MARK, bookId=2, book="left", currentApproach=["left old"])
            right = dict(MARK, clientId="w1-200", bookId=3, book="right", currentApproach=["right old"])
            store.add_marks([left])
            active = store.claim(timeout=1)
            store.add_marks([right])
            a = store.finish_ok(active, ["left new"], {})
            b = store.finish_ok(store.claim(timeout=1), ["right new"], {})
            self.assertEqual(a["prev"], ["left old"])
            self.assertEqual(b["prev"], ["right old"])
            self.assertEqual([e["clientId"] for e in store.results_page(0, 10)["results"]],
                             [left["clientId"], right["clientId"]])

    def test_delayed_old_mark_is_acked_without_replacing_new_mark_even_after_restart(self):
        with tempfile.TemporaryDirectory() as data:
            store = Store(data).load()
            old = dict(MARK, bookId=2, book="same", instruction="old")
            new = dict(old, clientId="w1-new", instruction="new")
            for mark in (old, new, old):
                self.assertEqual(store.add_marks([mark]), ([mark["clientId"]], []))
            self.assertEqual([item["clientId"] for item in store.items], [new["clientId"]])
            store.close()

            resumed = Store(data).load()
            self.assertEqual(resumed.add_marks([old]), ([old["clientId"]], []))
            self.assertEqual(resumed.claim(timeout=1)["instruction"], "new")
            resumed.close()

    def test_restart_retires_superseded_running_but_resumes_live_tasks(self):
        old = self.item
        new = dict(MARK, clientId="w1-new", instruction="current")
        self.store.add_marks([new])
        unrelated = dict(MARK, clientId="w2-running", word="beta")
        self.store.add_marks([unrelated])
        # Give the unrelated running task a higher priority: it must still resume.
        self.store._find(unrelated["clientId"])["priority"] = 110
        self.assertEqual(self.store.claim()["clientId"], unrelated["clientId"])
        self.store.close()

        resumed = Store(self.tmp.name).load()
        self.addCleanup(resumed.close)
        retired = resumed._find(old["clientId"])
        self.assertEqual((retired["state"], retired["attempts"]), ("done", 1))
        self.assertEqual(resumed.add_marks([MARK]), ([MARK["clientId"]], []))
        live = resumed.claim()
        self.assertEqual(live["clientId"], unrelated["clientId"])
        self.assertEqual(live["attempts"], 2)
        claimed = resumed.claim()
        self.assertEqual(claimed["clientId"], new["clientId"])
        self.assertEqual(resumed.results_page(0, 10)["results"], [])
        self.assertEqual(resumed.finish_ok(claimed, ["current"], {})["clientId"], new["clientId"])

    def test_claim_retires_legacy_pending_and_waiting_rows_before_pick(self):
        with tempfile.TemporaryDirectory() as data:
            store = Store(data).load()
            old_pending = dict(MARK, clientId="old-pending")
            old_retry = dict(MARK, clientId="old-retry", word="beta")
            new = dict(MARK, clientId="new", word="gamma")
            store.add_marks([old_pending, old_retry, new])
            store._find(old_pending["clientId"]).update(superseded=True, priority=110)
            store._find(old_retry["clientId"]).update(
                superseded=True, priority=110, state="retry_wait", nextRunAt=""
            )
            self.assertEqual(store.claim()["clientId"], new["clientId"])
            self.assertEqual(store.status()["pending"], 0)
            self.assertEqual(store.status()["retryWait"], 0)

    def test_pruning_retired_rows_keeps_receipt_and_retry_cannot_revive(self):
        self.store.add_marks([dict(MARK, clientId="w1-new")])
        self.assertIsNone(self.store.finish_retry(self.item, "late timeout"))
        self.assertFalse(self.store.retry("alpha", self.item["clientId"]))
        self.store.close()
        with patch.object(store_mod, "KEEP_FINISHED", 0):
            resumed = Store(self.tmp.name).load()
            self.addCleanup(resumed.close)
            claimed = resumed.claim()
            self.assertEqual(claimed["clientId"], "w1-new")
            resumed.finish_ok(claimed, ["current"], {})
            self.assertIsNone(resumed._find(self.item["clientId"]))
            self.assertEqual(resumed.add_marks([MARK]), ([MARK["clientId"]], []))
            self.assertEqual(resumed.status()["pending"], 0)
            self.assertEqual([r["clientId"] for r in resumed.results_page(0, 10)["results"]], ["w1-new"])

    def test_status_can_disambiguate_same_word_in_two_same_named_books(self):
        with tempfile.TemporaryDirectory() as data:
            store = Store(data).load()
            one = dict(MARK, bookId=2, book="same")
            two = dict(one, clientId="w1-other-book", bookId=3)
            store.add_marks([one, two])
            with (
                patch.object(service, "startup"),
                patch.dict(service._state, {"store": store}),
                patch.object(service.wb_gate, "status", return_value={}),
            ):
                status, _, payload = service.respond("GET", "/rewrite/status")
            self.assertEqual(status, 200)
            self.assertEqual(
                [(row["word"], row["clientId"]) for row in json.loads(payload)["items"]],
                [(one["word"], one["clientId"]), (two["word"], two["clientId"])],
            )

    def test_quality_rejected_waits_and_keeps_best(self):
        out = self.store.finish_quality_rejected(
            self.item,
            ["a", "b", "——>", "c"],
            {"reason": "拆分不成立", "scores": {"拆分成立": 5}},
        )
        self.assertEqual(out["state"], "retry_wait")
        self.assertEqual(out["failures"], 1)
        self.assertTrue(self.store.items[0]["nextRunAt"])
        self.assertEqual(self.store.items[0]["state"], "retry_wait")
        self.assertEqual(self.store.items[0]["bestApproach"][0], "a")
        self.assertEqual(self.store.results, {})
        # 到点前不放行，到点后回到 pending，再 claim 拿到同一代次。
        self.store.items[0]["nextRunAt"] = "2000-01-01T00:00:00+08:00"
        self.store._promote_retries_locked()
        self.assertEqual(self.store.items[0]["state"], "pending")
        again = self.store.claim(timeout=1)
        self.assertEqual(again["clientId"], "w1-100")
        self.assertEqual(again["generation"], 0)
        self.assertEqual(again["attempts"], 2)
        entry = self.store.finish_ok(again, ["x", "y", "——>", "z"], {"method": "sound"})
        self.assertIsNotNone(entry)
        self.assertEqual(self.store.items[0]["state"], "done")
        self.assertEqual(self.store.items[0]["nextRunAt"], "")
        self.assertFalse(self.store.items[0]["qualityRejected"])
        self.assertEqual(len(self.store.results["alpha"]["entries"]), 1)

    def test_retry_error_also_waits_without_result(self):
        out = self.store.finish_retry(self.item, "timeout")
        self.assertEqual(out["state"], "retry_wait")
        self.assertEqual(self.store.items[0]["failures"], 1)
        self.assertEqual(self.store.results, {})

    def test_release_claim_returns_running_to_retry_wait_and_keeps_attempts(self):
        out = self.store.release_claim(self.item, "工人异常：boom")
        self.assertEqual(out["state"], "retry_wait")
        live = self.store.items[0]
        self.assertEqual(live["state"], "retry_wait")
        self.assertEqual(live["attempts"], 1)
        self.assertEqual(live["stage"], "")
        self.assertEqual(live["error"], "工人异常：boom")
        self.assertEqual(live["nextRunAt"], out["nextRunAt"])
        self.assertTrue(live["nextRunAt"])
        # 重复释放不会再次改状态，避免把更晚的退避时间往后推。
        self.assertIsNone(self.store.release_claim(self.item, "again"))
        # 到点回 pending，再 claim 拿到同一代次，attempts 只由 claim 累加。
        live["nextRunAt"] = "2000-01-01T00:00:00+08:00"
        self.store._promote_retries_locked()
        again = self.store.claim(timeout=1)
        self.assertEqual(again["clientId"], self.item["clientId"])
        self.assertEqual(again["generation"], self.item["generation"])
        self.assertEqual(again["attempts"], 2)

    def test_release_claim_does_not_overwrite_finished_item(self):
        self.assertIsNotNone(self.store.finish_ok(self.item, ["ok"], {}))
        self.assertIsNone(self.store.release_claim(self.item, "late"))
        self.assertEqual(self.store.items[0]["state"], "done")

    def test_release_claim_ignores_stopped_generation(self):
        self.store.stop(word="alpha")
        self.assertIsNone(self.store.release_claim(self.item, "late"))
        self.assertEqual(self.store.items[0]["state"], "stopped")

    def test_release_claim_ignores_unknown_item(self):
        self.assertIsNone(self.store.release_claim({"clientId": "missing"}, "late"))
        self.assertEqual(self.store.items[0]["state"], "running")

    def test_stop_discards_late_result(self):
        stopped = self.store.stop(word="alpha")
        self.assertEqual(stopped, 1)
        self.assertEqual(self.store.items[0]["state"], "stopped")
        self.assertEqual(self.store.items[0]["generation"], 1)
        late = self.store.finish_ok(self.item, ["late"], {"method": "sound"})
        self.assertIsNone(late)
        self.assertEqual(self.store.results, {})

    def test_stop_retry_wait_and_status_counts(self):
        self.store.finish_quality_rejected(self.item, ["a"], {"reason": "bad"})
        self.assertEqual(self.store.stop(word="alpha"), 1)
        status = self.store.status()
        self.assertEqual(status["stopped"], 1)
        self.assertEqual(status["retryWait"], 0)
        self.assertTrue(all(row["state"] != "retry_wait" for row in status["items"]))

    def test_new_mark_replaces_stopped_item(self):
        self.store.stop(word="alpha")
        self.store.add_marks([dict(MARK, clientId="w1-101")])
        self.assertEqual(len(self.store.items), 1)
        self.assertEqual(self.store.items[0]["clientId"], "w1-101")
        self.assertEqual(self.store.items[0]["state"], "pending")

    def test_repeated_quality_failures_grow_backoff_and_survive_restart(self):
        first = self.store.finish_quality_rejected(self.item, ["v1"], {"reason": "bad"})
        self.assertEqual(first["failures"], 1)
        self.store.items[0]["nextRunAt"] = "2000-01-01T00:00:00+08:00"
        self.store._promote_retries_locked()
        second_item = self.store.claim(timeout=1)
        second = self.store.finish_quality_rejected(second_item, ["v2"], {"reason": "bad again"})
        self.assertEqual(second["failures"], 2)
        # 第二次退避必须比第一次长：30s → 120s。
        self.assertGreater(second["nextRunAt"], first["nextRunAt"])
        again = Store(self.tmp.name).load()
        live = again.items[0]
        self.assertEqual(live["state"], "retry_wait")
        self.assertEqual(live["failures"], 2)
        self.assertEqual(live["bestApproach"], ["v2"])


class WorkerReleaseTest(unittest.TestCase):
    """工人整轮异常：本轮已 claim 的 running 任务必须退回队列。"""

    class FakeStore:
        def __init__(self, claims):
            self.claims = list(claims)
            self.released = []

        def claim(self, timeout=60):
            if not self.claims:
                return None
            value = self.claims.pop(0)
            if isinstance(value, Exception):
                raise value
            return value

        def release_claim(self, item, reason):
            self.released.append((item["clientId"], reason))
            return {"state": "retry_wait", "nextRunAt": "2000-01-01T00:00:00+08:00"}

    def test_worker_releases_item_when_work_raises(self):
        item = {"clientId": "c1", "word": "alpha"}
        store = self.FakeStore([item, None])
        with (
            patch.object(service, "BATCH_ENABLED", False),
            patch.object(service, "log"),
            patch.dict(service._state, {"store": store}),
            patch.object(service, "_work", side_effect=RuntimeError("boom")),
            patch.object(service.time, "sleep") as sleep,
        ):
            service._worker(0)
        self.assertEqual([row[0] for row in store.released], ["c1"])
        self.assertIn("boom", store.released[0][1])
        self.assertEqual(sleep.call_count, 1)

    def test_worker_releases_partial_batch_when_claim_raises(self):
        first = {"clientId": "c1", "word": "alpha"}
        # 第一次 claim 成功，第二次（凑批）抛异常：已拿到的 c1 仍要释放。
        store = self.FakeStore([first, RuntimeError("claim boom"), None])
        with (
            patch.object(service, "BATCH_ENABLED", True),
            patch.object(service, "BATCH_SIZE", 3),
            patch.object(service, "log"),
            patch.dict(service._state, {"store": store}),
            patch.object(service.time, "sleep") as sleep,
        ):
            service._worker(1)
        self.assertEqual([row[0] for row in store.released], ["c1"])
        self.assertIn("claim boom", store.released[0][1])
        self.assertEqual(sleep.call_count, 1)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.5.3 回归：闲时洗词 worker（默认关闭、只洗该洗的词、绝不抢手动槽）。

这层测试定死 worker 的安全边界：
1. 默认关闭 —— 没有 config/worker.json 时 enabled=False，一轮都不跑；
2. 只洗「差评够多、差评多于好评、没锁定、最近没洗过」的词，meaning/polysemy 优先；
3. 手动队列 pending + running > 0 时一律不启动；
4. 一小时配额硬上限；
5. 结构上不碰 queue —— run_once 结束后 pending/running 还是 0；
6. 单条洗词失败不会把 worker 线程搞死。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import providers  # noqa: E402
import service  # noqa: E402
import worker  # noqa: E402
from store import Store  # noqa: E402

SOME_MODEL = next(iter(providers.MODELS))


def _fb(client_id, word, rating, count=1, method="meaning", word_id=None, book_id=1, **extra):
    row = {
        "clientId": client_id,
        "wordId": word_id if word_id is not None else abs(hash(word)) % 10000,
        "bookId": book_id,
        "word": word,
        "rating": rating,
        "count": count,
        "method": method,
        "model": SOME_MODEL,
        "instruction": "",
        "createdAt": "2026-11-20T10:00:00+08:00",
        "receivedAt": "2026-11-20T10:00:00+08:00",
    }
    row.update(extra)
    return row


def _write_feedback(tmp, rows):
    worker.store_mod._write_json(os.path.join(tmp, "tools", "cardback-editor", "sync.json"), {"rows": {
        row["word"]: {"w": row["word"], "t": "progress", "b": "六级记忆卡", "nrt": 1} for row in rows}})
    with open(os.path.join(tmp, "feedback.jsonl"), "w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


class WorkerHarness(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        names = ["enough", "single", "mixed", "lockedword", "meaningword", "polyword",
                 "soundword", "fresh", "candidate"]
        index = {"loaded": True, "by_word": {w: {"word": w} for w in names}}
        for binding in (patch.object(worker, "_word_index", index),
                        patch.object(worker, "REPO_ROOT", self.dir)):
            binding.start()
            self.addCleanup(binding.stop)
        self.snapshot = os.path.join(self.dir, "tools", "cardback-editor", "sync.json")
        worker.store_mod._write_json(self.snapshot, {"rows": {}})
        worker.store_mod._write_json(worker.config_path(), {"new_word_fallback_limit": 0})
        self._saved = (service.DATA_DIR, service.LOG_PATH, dict(service._state))
        os.makedirs(os.path.join(self.dir, "logs"), exist_ok=True)
        service.DATA_DIR = self.dir
        service.LOG_PATH = os.path.join(self.dir, "logs", "service.log")
        worker.configure(self.dir)
        self.store = Store(self.dir).load()
        service._state.update({"store": self.store, "ready": True, "startedAt": "test"})

    def tearDown(self):
        worker._state["store"] = None
        data_dir, log_path, old_state = self._saved
        service.DATA_DIR = data_dir
        service.LOG_PATH = log_path
        service._state.clear()
        service._state.update(old_state)
        self._tmp.cleanup()


class WorkerConfigTest(WorkerHarness):

    def test_defaults_when_no_config_file(self):
        config = worker.normalize_config({})
        self.assertFalse(config["enabled"])
        self.assertEqual(config["interval_sec"], 300)
        self.assertEqual(config["max_per_hour"], 15)
        self.assertTrue(config["only_when_manual_idle"])
        self.assertEqual(config["min_downvotes"], 2)
        self.assertEqual(config["target_methods"], ["meaning", "polysemy"])

    def test_bad_config_is_clamped_not_trusted(self):
        config = worker.normalize_config({
            "enabled": True,
            "interval_sec": 1,
            "max_per_hour": 999,
            "min_downvotes": 99,
            "per_run": 50,
            "target_methods": ["meaning", "不存在的招式", "meaning"],
        })
        self.assertEqual(config["interval_sec"], 30)
        self.assertEqual(config["max_per_hour"], 60)
        self.assertEqual(config["min_downvotes"], 20)
        self.assertEqual(config["per_run"], 5)
        self.assertEqual(config["target_methods"], ["meaning"])

    def test_config_file_is_read_but_never_written(self):
        path = os.path.join(self.dir, "worker.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"enabled": True, "min_downvotes": 3}, fh)
        before = open(path, encoding="utf-8").read()
        config = worker.load_config(path)
        self.assertTrue(config["configFound"])
        self.assertTrue(config["enabled"])
        self.assertEqual(config["min_downvotes"], 3)
        self.assertEqual(open(path, encoding="utf-8").read(), before)


class WorkerCandidateTest(WorkerHarness):

    def test_thresholds_and_locks(self):
        _write_feedback(self.dir, [
            _fb("a1", "enough", "down", word_id=11),
            _fb("a2", "enough", "down", word_id=11),
            _fb("b1", "single", "down", word_id=12),
            _fb("c1", "mixed", "down", word_id=13),
            _fb("c2", "mixed", "up", word_id=13),
            _fb("d1", "lockedword", "down", word_id=14),
            _fb("d2", "lockedword", "down", word_id=14),
        ])
        self.store.set_lock(14, 1, word="lockedword")
        words = [b["word"] for b in worker.candidates(self.store)]
        self.assertEqual(words, ["enough", "single"])

    def test_equal_due_targets_preserve_seed_order(self):
        _write_feedback(self.dir, [
            _fb("e1", "meaningword", "down", method="meaning", word_id=21),
            _fb("e2", "meaningword", "down", method="meaning", word_id=21),
            _fb("f1", "soundword", "down", count=5, method="sound", word_id=22),
            _fb("g1", "polyword", "down", method="polysemy", word_id=23),
            _fb("g2", "polyword", "down", method="polysemy", word_id=23),
        ])
        words = [b["word"] for b in worker.candidates(self.store)]
        self.assertEqual(words, ["meaningword", "polyword", "soundword"])

    def test_recently_washed_words_are_skipped(self):
        _write_feedback(self.dir, [
            _fb("h1", "fresh", "down", word_id=31),
            _fb("h2", "fresh", "down", word_id=31),
        ])
        key = worker.store_mod.lock_key(31, 1)
        worker.store_mod._write_json(os.path.join(self.dir, "worker_state.json"), {
            "enabled": True,
            "runs": [],
            "done": {key: {"day": (worker.datetime.now() - worker.timedelta(hours=4)).date().isoformat(), "at": worker._now(), "ok": True, "failures": 0}},
        })
        worker.configure("")  # 强制下次重读运行态
        worker.configure(self.dir)
        self.assertEqual(worker.candidates(self.store), [])


class WorkerRunOnceTest(WorkerHarness):

    def test_disabled_by_default(self):
        _write_feedback(self.dir, [_fb("i1", "candidate", "down", word_id=41),
                                  _fb("i2", "candidate", "down", word_id=41)])
        result = worker.run_once(self.store, config=worker.normalize_config({}))
        self.assertFalse(result["ok"])
        self.assertEqual(result["reason"], "disabled")
        self.assertEqual(result["washed"], [])

    def test_manual_queue_busy_blocks_the_worker(self):
        _write_feedback(self.dir, [_fb("j1", "candidate", "down", word_id=51),
                                  _fb("j2", "candidate", "down", word_id=51)])
        accepted, rejected = self.store.add_marks([
            {"clientId": "manual-1", "word": "candidate", "model": SOME_MODEL}
        ])
        self.assertEqual(rejected, [])
        self.assertGreaterEqual(self.store.status()["pending"], 1)
        config = worker.normalize_config({"enabled": True})
        result = worker.run_once(self.store, config=config)
        self.assertEqual(result["reason"], "manual_queue_busy")
        self.assertEqual(result["washed"], [])

    def test_hourly_budget_is_enforced(self):
        _write_feedback(self.dir, [_fb("k1", "candidate", "down", word_id=61),
                                  _fb("k2", "candidate", "down", word_id=61)])
        worker.store_mod._write_json(os.path.join(self.dir, "worker_state.json"), {
            "enabled": True,
            "runs": [worker._now() for _ in range(6)],
            "done": {},
        })
        worker.configure("")
        worker.configure(self.dir)
        config = worker.normalize_config({"enabled": True, "max_per_hour": 6})
        result = worker.run_once(self.store, config=config)
        self.assertEqual(result["reason"], "hourly_budget")

    def test_run_once_with_injected_runner_never_touches_the_queue(self):
        _write_feedback(self.dir, [_fb("l1", "candidate", "down", word_id=71),
                                  _fb("l2", "candidate", "down", word_id=71)])
        config = worker.normalize_config({"enabled": True, "per_run": 1, "new_word_fallback_limit": 0})
        calls = []

        def runner(bucket, store):
            calls.append(bucket["word"])
            entry = store.record_worker_result(
                bucket["word"], ["洗过的新思路"], meta={"method": bucket["method"]},
                word_id=bucket["word_id"], book_id=bucket["book_id"], book=bucket["book"],
            )
            return {"ok": True, "entry": entry}

        result = worker.run_once(self.store, runner=runner, config=config)
        self.assertTrue(result["ok"])
        self.assertEqual(result["reason"], "ok")
        self.assertEqual(calls, ["candidate"])
        self.assertEqual(len(result["washed"]), 1)
        entry = self.store.results["candidate"]["entries"][-1]
        self.assertEqual(entry["source"], "worker")
        status = self.store.status()
        self.assertEqual(status["pending"], 0)
        self.assertEqual(status["running"], 0)
        self.assertEqual(status["done"], 0)  # 洗词不往队列里塞 done
        runtime = worker._load_runtime()
        self.assertEqual(runtime["washed"], 1)
        self.assertIn("ok", runtime["lastRun"]["reason"])

    def test_runner_failure_is_swallowed_and_counted(self):
        _write_feedback(self.dir, [_fb("m1", "candidate", "down", word_id=81),
                                  _fb("m2", "candidate", "down", word_id=81)])
        config = worker.normalize_config({"enabled": True, "per_run": 1, "new_word_fallback_limit": 0})

        def boom(bucket, store):
            raise RuntimeError("模型抽风")

        result = worker.run_once(self.store, runner=boom, config=config)
        self.assertTrue(result["ok"])
        self.assertEqual(result["reason"], "nothing_washed")
        self.assertEqual(result["washed"], [])
        runtime = worker._load_runtime()
        self.assertIn("模型抽风", runtime["lastError"])
        self.assertEqual(list(runtime["done"].values())[0]["failures"], 1)

    def test_no_candidates_is_not_an_error(self):
        config = worker.normalize_config({"enabled": True, "new_word_fallback_limit": 0})
        result = worker.run_once(self.store, config=config)
        self.assertTrue(result["ok"])
        self.assertEqual(result["reason"], "no_candidates")
        self.assertEqual(result["candidates"], 0)


class WorkerEndpointTest(WorkerHarness):

    def _call(self, method, path, payload=None):
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        status, _ctype, raw = service.respond(method, path, body)
        return status, json.loads(raw.decode("utf-8"))

    def test_status_endpoint(self):
        status, payload = self._call("GET", "/rewrite/worker/status")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["version"], service.SERVICE_VERSION)
        self.assertFalse(payload["enabled"])
        self.assertEqual(payload["priority"], worker.OFFLINE_PRIORITY)
        self.assertEqual(payload["intervalSec"], 300)
        self.assertEqual(payload["maxPerHour"], 15)
        self.assertEqual(payload["minDownvotes"], 2)
        self.assertTrue(payload["onlyWhenManualIdle"])
        self.assertEqual(payload["targetMethods"], ["meaning", "polysemy"])
        self.assertTrue(payload["configPath"].endswith(os.path.join("config", "worker.json")))

    def test_toggle_endpoint(self):
        status, payload = self._call("POST", "/rewrite/worker/toggle", {"enabled": True})
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertTrue(payload["enabled"])
        self.assertTrue(payload["status"]["enabled"])
        # 落在 data/worker_state.json，重启也记得
        raw = json.load(open(os.path.join(self.dir, "worker_state.json"), encoding="utf-8"))
        self.assertIs(raw["enabled"], True)
        status, payload = self._call("POST", "/rewrite/worker/toggle", {"enabled": False})
        self.assertEqual(status, 200)
        self.assertFalse(payload["enabled"])

    def test_toggle_requires_flag(self):
        status, payload = self._call("POST", "/rewrite/worker/toggle", {})
        self.assertEqual(status, 400)
        self.assertIn("enabled", payload["error"])


if __name__ == "__main__":
    unittest.main()

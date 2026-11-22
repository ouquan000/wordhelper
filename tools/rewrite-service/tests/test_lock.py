#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.5.3 回归：手改助记锁定（locks.json / is_locked / 结果流过滤）。

锁定的意义只有一句：用户手动改过的词，自动流程一个字都不许动。
所以这里锁住三件事：
1. 锁只对「词 + 书」这一对成立，换本书不串味；
2. 锁一旦成立，洗词 worker 的成品直接被拒收（record_worker_result 返回 None）；
3. 结果流里 source=worker 的条目会被锁挡掉，而用户自己手写的条目永远不会被挡。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import service  # noqa: E402
import store as store_mod  # noqa: E402
from store import Store  # noqa: E402


class LockStoreTest(unittest.TestCase):

    def test_set_lock_persists_with_note(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            row = store.set_lock(73, 1, note="我自己改的", word="cathedral", book="GRE")
            self.assertTrue(row["locked"])
            self.assertEqual(row["note"], "我自己改的")
            path = os.path.join(tmp, "locks.json")
            self.assertTrue(os.path.exists(path))
            with open(path, encoding="utf-8") as fh:
                raw = json.load(fh)
            self.assertEqual(raw["version"], 1)
            self.assertEqual(raw["locks"]["73|1"]["locked"], True)
            self.assertEqual(raw["locks"]["73|1"]["word"], "cathedral")
            self.assertEqual(store.locks_updated_at(), raw["updatedAt"])

    def test_lock_is_scoped_per_book(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.set_lock(73, 1)
            self.assertTrue(store.is_locked(73, 1))
            self.assertTrue(store.is_locked("73", "1"))
            self.assertFalse(store.is_locked(73, 2))
            self.assertFalse(store.is_locked(74, 1))

    def test_unlock_keeps_row_and_note(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.set_lock(73, 1, note="留个说明")
            store.set_lock(73, 1, locked=False)
            self.assertFalse(store.is_locked(73, 1))
            self.assertEqual(len(store.lock_records()), 1)
            self.assertEqual(store.lock_records()[0]["note"], "留个说明")
            self.assertFalse(store.lock_records()[0]["locked"])

    def test_locks_filter_by_book_id_or_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.set_lock(73, 1, word="cathedral", book="GRE")
            store.set_lock(74, 2, word="chronic", book="TOEFL")
            self.assertEqual(len(store.locks()), 2)
            self.assertEqual(len(store.lock_records(book="GRE")), 1)
            self.assertEqual(len(store.lock_records(book="1")), 1)  # 书名/书号都能筛
            self.assertEqual(len(store.lock_records(book="TOEFL")), 1)


class LockWorkerResultTest(unittest.TestCase):

    def test_locked_word_refuses_worker_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.set_lock(73, 1)
            entry = store.record_worker_result(
                "cathedral", ["思路 A"], meta={"method": "meaning"}, word_id=73, book_id=1
            )
            self.assertIsNone(entry)
            self.assertNotIn("cathedral", store.results)

    def test_unlocked_word_accepts_worker_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            entry = store.record_worker_result(
                "cathedral", ["思路 A"], meta={"method": "meaning"}, word_id=73, book_id=1
            )
            self.assertIsNotNone(entry)
            self.assertEqual(entry["source"], "worker")
            self.assertEqual(entry["status"], "ok")
            self.assertEqual(entry["bookId"], 1)
            self.assertTrue(os.path.exists(os.path.join(tmp, "results", "cathedral.json")))
            # 队列一律不碰
            self.assertEqual(store.status()["pending"], 0)
            self.assertEqual(store.status()["running"], 0)

    def test_worker_result_disappears_after_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.record_worker_result(
                "cathedral", ["思路 A"], meta={"method": "meaning"}, word_id=73, book_id=1
            )
            self.assertEqual([r["word"] for r in store.results_page(0, 10)["results"]], ["cathedral"])
            store.set_lock(73, 1)
            page = store.results_page(0, 10)
            self.assertEqual(page["results"], [])
            self.assertEqual(page["nextSince"], 0)

    def test_manual_result_is_never_hidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            store.set_lock(73, 1)
            # 用户手动重写的条目（source 为空 = 老版本/手工）
            store.results["cathedral"] = {
                "word": "cathedral",
                "entries": [{
                    "seq": store._next_seq(),
                    "word": "cathedral",
                    "wordId": "73",
                    "bookId": 1,
                    "book": "",
                    "rev": 1,
                    "approach": ["手改的稿子"],
                    "method": "meaning",
                    "prev": [],
                    "updatedAt": "2026-11-20T10:00:00+08:00",
                    "status": "ok",
                }],
            }
            words = [r["word"] for r in store.results_page(0, 10)["results"]]
            self.assertEqual(words, ["cathedral"])


class LockEndpointTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self._saved = (service.DATA_DIR, service.LOG_PATH, dict(service._state))
        service.DATA_DIR = self.dir
        service.LOG_PATH = os.path.join(self.dir, "logs", "service.log")
        os.makedirs(os.path.join(self.dir, "logs"), exist_ok=True)
        self.store = Store(self.dir).load()
        service._state.update({"store": self.store, "ready": True, "startedAt": "test"})

    def tearDown(self):
        data_dir, log_path, old_state = self._saved
        service.DATA_DIR = data_dir
        service.LOG_PATH = log_path
        service._state.clear()
        service._state.update(old_state)
        self._tmp.cleanup()

    def _call(self, method, path, payload=None):
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        status, _ctype, raw = service.respond(method, path, body)
        return status, json.loads(raw.decode("utf-8"))

    def test_lock_single(self):
        status, payload = self._call("POST", "/rewrite/lock", {
            "wordId": 73, "bookId": 1, "word": "cathedral", "note": "手改过",
        })
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertTrue(payload["locked"])
        self.assertEqual(payload["lockedCount"], 1)
        self.assertEqual(len(payload["locks"]), 1)
        self.assertEqual(payload["locks"][0]["note"], "手改过")
        self.assertTrue(self.store.is_locked(73, 1))

    def test_lock_batch_and_unlock(self):
        status, payload = self._call("POST", "/rewrite/lock", {"items": [
            {"wordId": 73, "bookId": 1},
            {"wordId": 74, "bookId": 1},
            {"wordId": 75, "bookId": 1, "locked": False},
        ]})
        self.assertEqual(status, 200)
        self.assertEqual(len(payload["locks"]), 3)
        self.assertEqual(payload["lockedCount"], 2)
        self.assertFalse(payload["locked"])
        self.assertTrue(self.store.is_locked(73, 1))
        self.assertFalse(self.store.is_locked(75, 1))

    def test_lock_requires_ids(self):
        status, payload = self._call("POST", "/rewrite/lock", {"bookId": 1})
        self.assertEqual(status, 400)
        self.assertIn("wordId", payload["error"])
        status, payload = self._call("POST", "/rewrite/lock", {"wordId": 73})
        self.assertEqual(status, 400)
        self.assertIn("bookId", payload["error"])

    def test_locks_listing(self):
        self._call("POST", "/rewrite/lock", {"items": [
            {"wordId": 73, "bookId": 1, "word": "cathedral"},
            {"wordId": 74, "bookId": 1, "locked": False},
        ]})
        status, payload = self._call("GET", "/rewrite/locks")
        self.assertEqual(status, 200)
        self.assertEqual(payload["count"], 2)
        self.assertEqual(payload["lockedCount"], 1)
        self.assertTrue(payload["updatedAt"])
        status, payload = self._call("GET", "/rewrite/locks?book=1")
        self.assertEqual(status, 200)
        self.assertEqual(payload["count"], 2)


if __name__ == "__main__":
    unittest.main()

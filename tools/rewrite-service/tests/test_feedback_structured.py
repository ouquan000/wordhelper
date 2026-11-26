#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.5.3 回归：点踩结构化（reasons / comment）。

四条底线，一条都不能松：
1. 新字段原样进 feedback.jsonl —— 只做规范化（去空、去重、截断），不改语义；
2. 理由/评论写得再离谱（不是列表、混了数字、超长）也绝不导致整条评价被拒，
   评价本身比理由值钱；
3. 老客户端不传这两个字段时行为与 1.5.2 完全一致（[] / ""），
   accepted / rejected 计数不变；
4. profile.update_feedback 只吃具名参数，行里多出字段不会让它出错。
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

import profile  # noqa: E402
import service  # noqa: E402
from _isolation import guard_network, isolate  # noqa: E402

isolate(service)
guard_network()
import store as store_mod  # noqa: E402
from store import Store  # noqa: E402


def _read_rows(tmp):
    path = os.path.join(tmp, "feedback.jsonl")
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def _row(client_id, **extra):
    item = {
        "clientId": client_id,
        "wordId": 73,
        "bookId": 1,
        "word": "cathedral",
        "rating": "down",
        "approach": "旧思路",
        "method": "meaning",
        "model": "ds-v41-flash",
        "instruction": "",
        "createdAt": "2026-11-20T11:00:00+08:00",
    }
    item.update(extra)
    return item


class FeedbackReasonHelperTest(unittest.TestCase):
    """纯函数层：规范化只做「干净化」，不做业务判断。"""

    def test_reasons_drop_empty_and_duplicates(self):
        got = store_mod.feedback_reasons([" 编得太假 ", "", "  ", "记不住", "编得太假", None, 3])
        self.assertEqual(got, ["编得太假", "记不住"])

    def test_reasons_reject_non_list(self):
        self.assertEqual(store_mod.feedback_reasons("不是列表"), [])
        self.assertEqual(store_mod.feedback_reasons(None), [])
        self.assertEqual(store_mod.feedback_reasons({"a": 1}), [])

    def test_reasons_truncate_each_and_cap_total(self):
        long_text = "长" * 200
        got = store_mod.feedback_reasons([long_text] + ["理由%d" % i for i in range(30)])
        self.assertEqual(len(got), store_mod.FEEDBACK_REASONS_MAX)
        self.assertEqual(len(got[0]), store_mod.FEEDBACK_REASON_LEN_MAX)

    def test_comment_is_string_or_empty(self):
        self.assertEqual(store_mod.feedback_comment("  记不住  "), "记不住")
        self.assertEqual(store_mod.feedback_comment(123), "")
        self.assertEqual(store_mod.feedback_comment(None), "")
        self.assertEqual(len(store_mod.feedback_comment("字" * 5000)), store_mod.FEEDBACK_COMMENT_LEN_MAX)


class FeedbackStructuredStoreTest(unittest.TestCase):

    def test_reasons_and_comment_land_in_jsonl(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback([
                _row(
                    "f73-1-down-r4",
                    count=2,
                    reasons=[" 编得太假", "", "记不住", "编得太假", 42],
                    comment="  这个词的 C 找不到  ",
                )
            ])
            self.assertEqual(accepted, ["f73-1-down-r4"])
            self.assertEqual(rejected, [])
            rows = _read_rows(tmp)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["reasons"], ["编得太假", "记不住"])
            self.assertEqual(rows[0]["comment"], "这个词的 C 找不到")
            # 原有字段一个都不能丢
            self.assertEqual(rows[0]["rating"], "down")
            self.assertEqual(rows[0]["count"], 2)
            self.assertEqual(rows[0]["clientId"], "f73-1-down-r4")

    def test_old_client_without_new_fields_is_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback([_row("f73-1-down-r5")])
            self.assertEqual(accepted, ["f73-1-down-r5"])
            self.assertEqual(rejected, [])
            row = _read_rows(tmp)[0]
            self.assertEqual(row["reasons"], [])
            self.assertEqual(row["comment"], "")
            self.assertEqual(row["count"], 1)

    def test_broken_new_fields_never_reject_the_vote(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback([
                _row("f73-1-down-r6", reasons="不是列表", comment=98765)
            ])
            self.assertEqual(accepted, ["f73-1-down-r6"])
            self.assertEqual(rejected, [])
            row = _read_rows(tmp)[0]
            self.assertEqual(row["reasons"], [])
            self.assertEqual(row["comment"], "")

    def test_invalid_count_still_rejected_with_new_fields(self):
        """count 的老校验不许因为新字段而失效。"""
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(tmp)
            accepted, rejected = store.add_feedback([
                _row("f73-1-down-r7", count=999, reasons=["记不住"], comment="x")
            ])
            self.assertEqual(accepted, [])
            self.assertEqual(len(rejected), 1)
            path = os.path.join(tmp, "feedback.jsonl")
            self.assertEqual(_read_rows(tmp) if os.path.exists(path) else [], [])


class FeedbackProfileCompatTest(unittest.TestCase):

    def test_update_feedback_ignores_extra_row_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            current = profile.get_profile(os.path.join(tmp, "profile.json"))
            row = _row("f73-1-down-r8", reasons=["记不住"], comment="C 找不到")
            count, error = store_mod.feedback_count(row.get("count"))
            self.assertEqual(error, "")
            before = current.snapshot()
            result = current.update_feedback(
                profile.normalize_method(row["method"]),
                row["rating"],
                event_id="feedback:" + row["clientId"],
                count=count,
            )
            self.assertTrue(result["applied"])
            after = current.snapshot()
            self.assertNotEqual(before, after)
            # 重复 event_id 不再加分（老逻辑不变）
            again = current.update_feedback(
                profile.normalize_method(row["method"]),
                row["rating"],
                event_id="feedback:" + row["clientId"],
                count=count,
            )
            self.assertFalse(again["applied"])


class FeedbackStructuredEndpointTest(unittest.TestCase):
    """走 service.respond，确认接口层不会把新字段拦掉。"""

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

    def _post(self, path, payload):
        status, _ctype, body = service.respond("POST", path, json.dumps(payload).encode("utf-8"))
        return status, json.loads(body.decode("utf-8"))

    def test_endpoint_accepts_reasons_and_comment(self):
        status, payload = self._post("/rewrite/feedback", {"items": [
            _row("f73-1-down-r9", count=3, reasons=["编得太假", "记不住"], comment="  换个角度  ")
        ]})
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["accepted"], ["f73-1-down-r9"])
        self.assertEqual(payload["rejected"], [])
        row = _read_rows(self.dir)[0]
        self.assertEqual(row["reasons"], ["编得太假", "记不住"])
        self.assertEqual(row["comment"], "换个角度")
        self.assertEqual(row["count"], 3)

    def test_endpoint_accepts_old_payload(self):
        status, payload = self._post("/rewrite/feedback", {"items": [_row("f73-1-down-r10")]})
        self.assertEqual(status, 200)
        self.assertEqual(payload["accepted"], ["f73-1-down-r10"])
        row = _read_rows(self.dir)[0]
        self.assertEqual(row["reasons"], [])
        self.assertEqual(row["comment"], "")


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""未复核交付（unverified）契约自测（1.4.7.1 / S1，1.4.7.2 起改为持续重写）

约定：
    pipeline.run() 两轮都不过时返回 {"quality_rejected": True, "unverified": True, ...}，
    service._work 不再走 finish_ok（结果不能当合格稿发给用户），而是
    finish_quality_rejected：保留最佳候选、按退避进 retry_wait，等下一次重写。
    results/<word>.json 不写这条未过稿；队列 JSON 保留 bestApproach/lastReason/nextRunAt。
    旧记录没有这些字段 → 一律按 false / "" 输出，保持兼容。

用法：python test_unverified.py
（不联网、不调模型：直接调 store 与 monkeypatch service.pipeline.run）
"""

from __future__ import annotations

import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import service  # noqa: E402
import store as store_mod  # noqa: E402

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def make_store(tmp):
    service.DATA_DIR = tmp
    service.LOG_PATH = os.path.join(tmp, "logs", "service.log")
    st = store_mod.Store(tmp).load()
    service._state["store"] = st
    return st


def enqueue_and_claim(st, client_id, word):
    accepted, rejected = st.add_marks([{"clientId": client_id, "word": word, "model": "step5"}])
    assert accepted == [client_id], (accepted, rejected)
    item = st.claim(1)
    assert item is not None and item["clientId"] == client_id, item
    return item


def test_store_finish_ok(tmp):
    print("\n[1] Store.finish_ok 落 unverified / reason")
    st = make_store(tmp)
    item = enqueue_and_claim(st, "c-unverified", "abandon")
    entry = st.finish_ok(
        item, ["拆词", "词根"], {
            "method": "联想",
            "scores": {"meaning": 4},
            "attempts": 2,
            "unverified": True,
            "reason": "评分模型超时，未复核",
        }
    )
    check("finish_ok 仍返回 ok entry", entry is not None and entry.get("status") == "ok",
          repr(entry and entry.get("status")))
    check("entry.unverified=True", entry.get("unverified") is True, repr(entry.get("unverified")))
    check("entry.reason 原文保留", entry.get("reason") == "评分模型超时，未复核",
          repr(entry.get("reason")))

    public = st.results_page(0, 10)["results"][0]
    check("results 对外 JSON 带 unverified", public.get("unverified") is True, repr(public.get("unverified")))
    check("results 对外 JSON 带 reason", public.get("reason") == "评分模型超时，未复核",
          repr(public.get("reason")))

    status = st.status()
    row = next((r for r in status["items"] if r["word"] == "abandon"), None)
    check("status.items 带 unverified", row is not None and row.get("unverified") is True, repr(row))
    check("status.items 带 reason", row is not None and row.get("reason") == "评分模型超时，未复核",
          repr(row and row.get("reason")))
    recent = next((r for r in status["recent"] if r.get("word") == "abandon"), None)
    check("status.recent 带 unverified", recent is not None and recent.get("unverified") is True,
          repr(recent))

    records = st.records_page()
    rec = next((r for r in records["records"] if r["word"] == "abandon"), None)
    check("records 带 unverified", rec is not None and rec.get("unverified") is True, repr(rec))
    check("records 带 reason", rec is not None and rec.get("reason") == "评分模型超时，未复核",
          repr(rec and rec.get("reason")))

    # 落盘后再载入，标记不能丢
    again = store_mod.Store(tmp).load()
    again_public = again.results_page(0, 10)["results"][0]
    check("重新载入后 results 仍带标记",
          again_public.get("unverified") is True
          and again_public.get("reason") == "评分模型超时，未复核",
          repr(again_public))

    # 旧记录缺字段 → false / ""
    st2 = make_store(os.path.join(tmp, "legacy"))
    item2 = enqueue_and_claim(st2, "c-legacy", "increase")
    entry2 = st2.finish_ok(item2, ["旧思路"], {"method": "词根"})
    pub2 = st2.results_page(0, 10)["results"][0]
    check("缺省 entry.unverified=False", entry2.get("unverified") is False, repr(entry2.get("unverified")))
    check("缺省 entry.reason=''", entry2.get("reason") == "", repr(entry2.get("reason")))
    check("缺省对外 unverified=False", pub2.get("unverified") is False, repr(pub2.get("unverified")))
    check("缺省对外 reason=''", pub2.get("reason") == "", repr(pub2.get("reason")))
    rec2 = st2.records_page()["records"][0]
    check("缺省 records 也兼容", rec2.get("unverified") is False and rec2.get("reason") == "", repr(rec2))

    # 手动塞一条 1.4.7 之前的老 entry（完全没有字段），序列化仍要安全
    legacy_entry = {
        "seq": 999, "word": "ancient", "rev": 1, "approach": ["老结果"], "method": "",
        "prev": [], "updatedAt": "2026-01-01 00:00:00", "status": "ok", "model": "step5",
        "clientId": "c-ancient",
    }
    st2.results["ancient"] = {"word": "ancient", "entries": [legacy_entry]}
    st2._save_word("ancient")
    old_pub = next(p for p in st2.results_page(0, 100)["results"] if p["word"] == "ancient")
    check("旧版 entry 序列化缺省 false/''",
          old_pub.get("unverified") is False and old_pub.get("reason") == "", repr(old_pub))


def test_service_work_plumbing(tmp):
    print("\n[2] service._work → finish_ok（monkeypatch pipeline.run）")
    st = make_store(tmp)
    item = enqueue_and_claim(st, "c-work", "banana")

    calls = []

    def fake_run(item, log=None, on_stage=None):
        calls.append(item["clientId"])
        if on_stage:
            on_stage("judge")
        return {
            "approach": ["联想 A", "词根 B"],
            "method": "联想",
            "scores": {"meaning": 3},
            "attempts": 1,
            "timings": {"total": 0.01},
            "unverified": True,
            "quality_rejected": True,
            "reason": "抽查未过",
        }

    original = service.pipeline.run
    service.pipeline.run = fake_run
    try:
        service._work(item)
    finally:
        service.pipeline.run = original

    entry = st._entry_for("c-work")
    check("_work 调用了 pipeline.run", calls == ["c-work"], repr(calls))
    check("质量未过不写 results", entry is None, repr(entry))
    live = st._find("c-work")
    check("队列记录 state=retry_wait", live.get("state") == "retry_wait", repr(live.get("state")))
    check("队列记录 failures=1", live.get("failures") == 1, repr(live.get("failures")))
    check("队列记录保留最佳候选", live.get("bestApproach") == ["联想 A", "词根 B"],
          repr(live.get("bestApproach")))
    check("队列记录带 nextRunAt", bool(live.get("nextRunAt")), repr(live.get("nextRunAt")))
    check("队列记录带 lastReason", live.get("lastReason") == "抽查未过", repr(live.get("lastReason")))

    with open(service.LOG_PATH, "r", encoding="utf-8") as fh:
        log_text = fh.read()
    check("日志写明质量未过", "质量未过" in log_text and "抽查未过" in log_text, log_text[-300:])

    # 正常（已复核）路径：unverified 缺省为 false
    item2 = enqueue_and_claim(st, "c-normal", "cherry")
    calls.clear()

    def fake_ok(item, log=None, on_stage=None):
        calls.append(item["clientId"])
        return {"approach": ["正常"], "method": "词根", "scores": {}, "attempts": 1,
                "timings": {"total": 0.01}}

    service.pipeline.run = fake_ok
    try:
        service._work(item2)
    finally:
        service.pipeline.run = original
    entry2 = st._entry_for("c-normal")
    check("正常结果 entry.unverified=False", entry2 and entry2.get("unverified") is False,
          repr(entry2 and entry2.get("unverified")))
    check("正常结果 reason=''", entry2 and entry2.get("reason") == "",
          repr(entry2 and entry2.get("reason")))


def test_current_approach_not_truncated(tmp):
    print("\\n[3] 上一版思路保留到 5 步（改稿 / 评委要完整）")
    st = make_store(os.path.join(tmp, "approach"))
    steps = ["第一步", "第二步", "第三步", "第四步", "第五步", "第六步"]
    accepted, rejected = st.add_marks([{
        "clientId": "c-approach", "word": "alpha", "model": "step5",
        "currentApproach": steps,
    }])
    check("mark 入队", accepted == ["c-approach"], repr((accepted, rejected)))
    item = st.claim(1)
    got = (item or {}).get("currentApproach")
    check("前 5 步全部保留（旧的 [:3] 会丢第 4/5 步）", got == steps[:5], repr(got))
    check("第 6 步按上限丢弃", "第六步" not in (got or []), repr(got))


def main():
    print("== unverified 契约自测 ==")
    with tempfile.TemporaryDirectory(prefix="rewrite-unverified-") as tmp:
        test_store_finish_ok(tmp)
        test_service_work_plumbing(os.path.join(tmp, "work"))
        test_current_approach_not_truncated(tmp)
    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())

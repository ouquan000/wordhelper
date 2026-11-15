#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1.4.7.2 W5：恢复代次、幂等重放、按书容量。

用法：python test_sync_epoch.py
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import sync_store  # noqa: E402

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def mkop(word, lrt, op_id, book="六级"):
    return {
        "op_id": op_id,
        "t": "progress",
        "w": word,
        "b": book,
        "st": 1,
        "reps": 1,
        "ivl": 2,
        "nrt": 1794283200000,
        "ef": 2.6,
        "rc": 1,
        "lrt": lrt,
    }


def quiet_push(store, ops, device="d1"):
    with contextlib.redirect_stdout(io.StringIO()):
        return store.push(device, 0, ops)


def test_epoch_default_and_pull(tmp):
    path = os.path.join(tmp, "epoch.json")
    store = sync_store.SyncStore(path)
    check("新库 epoch=1", store.stats().get("epoch") == 1, repr(store.stats()))
    result = quiet_push(store, [mkop("abandon", 1000, "op-1")])
    check("push 回执带 epoch=1", result.get("epoch") == 1, repr(result))
    page = store.pull(0)
    check("pull 带 epoch=1", page.get("epoch") == 1, repr(page))
    again = sync_store.SyncStore(path)
    check("重新载入 epoch 保持 1", again.stats().get("epoch") == 1, repr(again.stats()))


def test_corrupt_increments_epoch(tmp):
    path = os.path.join(tmp, "bad.json")
    store = sync_store.SyncStore(path)
    quiet_push(store, [mkop("abandon", 1000, "op-1")])
    with open(path, "w", encoding="utf-8") as fh:
        fh.write('{"rev": 42, "rows": {"a": {"rev": 99}, ')
    recovered = sync_store.SyncStore(path)
    stats = recovered.stats()
    check("坏文件恢复 epoch=2", stats.get("epoch") == 2, repr(stats))
    check("坏文件恢复 corrupt_count=1", stats.get("corrupt_count") == 1, repr(stats))
    check("坏文件恢复 rev 沿用 99", stats.get("rev") == 99, repr(stats))
    page = recovered.pull(0)
    check("恢复后 pull 也报 epoch=2", page.get("epoch") == 2, repr(page))


def test_same_op_replay(tmp):
    path = os.path.join(tmp, "replay.json")
    store = sync_store.SyncStore(path)
    op = mkop("abandon", 1000, "op-1")
    first = quiet_push(store, [op])
    check("首次 push rev=1", first.get("server_rev") == 1, repr(first))
    second = quiet_push(store, [op])
    check("同 op 重放仍 ack", second.get("acked") == ["op-1"], repr(second))
    check("同 op 重放不涨 rev", second.get("server_rev") == 1, repr(second))
    check("同 op 重放进 replayed", second.get("replayed") == ["op-1"], repr(second))
    check("同 op 重放不算冲突", second.get("conflicts") == [], repr(second))
    check("replayed_total 累计 1", store.stats().get("replayed_total") == 1, repr(store.stats()))


def test_per_book_trim(tmp):
    old_book = sync_store.MAX_ROWS_PER_BOOK
    old_global = sync_store.MAX_ROWS
    sync_store.MAX_ROWS_PER_BOOK = 2
    sync_store.MAX_ROWS = 99
    try:
        path = os.path.join(tmp, "book-trim.json")
        store = sync_store.SyncStore(path)
        ops = [
            mkop("a-1", 1000, "a1", book="A"),
            mkop("a-2", 1001, "a2", book="A"),
            mkop("a-3", 1002, "a3", book="A"),
            mkop("b-1", 1000, "b1", book="B"),
            mkop("b-2", 1001, "b2", book="B"),
        ]
        result = quiet_push(store, ops)
        check("按书超额只告警：trimmed=0", result.get("trimmed") == 0, repr(result))
        check("A 书超线计数 1", result.get("over_limit", {}).get("A") == 1, repr(result))
        stats = store.stats()
        check("总行数保留 5", stats.get("rows") == 5, repr(stats))
        check("A 书三条都还在", [r["w"] for r in store.pull(0)["rows"] if r["b"] == "A"] == ["a-1", "a-2", "a-3"],
              repr([r["w"] for r in store.pull(0)["rows"] if r["b"] == "A"]))
        check("B 书两条都在", [r["w"] for r in store.pull(0)["rows"] if r["b"] == "B"] == ["b-1", "b-2"],
              repr([r["w"] for r in store.pull(0)["rows"] if r["b"] == "B"]))
        check("stats 带按书超线计数", stats.get("rows_over_limit_by_book", {}).get("A") == 1, repr(stats))
    finally:
        sync_store.MAX_ROWS_PER_BOOK = old_book
        sync_store.MAX_ROWS = old_global


def main():
    print("== sync_store 1.4.7.2 W5 自测 ==")
    with tempfile.TemporaryDirectory(prefix="sync-epoch-test-") as tmp:
        test_epoch_default_and_pull(tmp)
        test_corrupt_increments_epoch(tmp)
        test_same_op_replay(tmp)
        test_per_book_trim(tmp)
    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())

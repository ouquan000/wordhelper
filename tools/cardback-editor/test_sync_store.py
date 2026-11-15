#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sync_store 加固自测（1.4.7.1 / S1）。

覆盖：
  1. 正常 push/pull、原子写（不留 .tmp）；
  2. 冲突按 lrt 判、旧 op 仍 ack，但 conflicts 里要看得见，日志里也要有；
  3. 坏文件：备份成 .corrupt-<时间>（不覆盖旧备份）、rev 沿用备份里的最大值；
  4. rows 上限：只告警不删除，超线计数可见，持久化后数据仍在。

用法：python test_sync_store.py
"""

from __future__ import annotations

import contextlib
import glob
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


def mkop(word, lrt, op_id, book="六级", **extra):
    op = {
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
    op.update(extra)
    return op


def quiet_push(store, device, ops):
    """push 会往 stdout 写冲突/清理日志；测试里接住，顺便断言日志内容。"""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        result = store.push(device, 0, ops)
    return result, buf.getvalue()


def test_normal(tmp):
    path = os.path.join(tmp, "normal.json")
    store = sync_store.SyncStore(path)
    result, _ = quiet_push(store, "test-device", [mkop("abandon", 1000, "op-1")])
    check("正常 push：acked 一条", result.get("acked") == ["op-1"], repr(result))
    check("正常 push：rev 从 0 到 1", result.get("server_rev") == 1, repr(result))
    check("正常 push：无冲突", result.get("conflicts") == [], repr(result.get("conflicts")))
    check("正常 push：无清理", result.get("trimmed") == 0, repr(result.get("trimmed")))
    check("落盘后没有 .tmp 残留", not os.path.exists(path + ".tmp"), path + ".tmp")

    with open(path, "r", encoding="utf-8") as fh:
        saved = json.load(fh)
    check("落盘 JSON 可解析且 rev=1", saved.get("rev") == 1, repr(saved.get("rev")))
    check("落盘 JSON 有一行", len(saved.get("rows") or {}) == 1, repr(len(saved.get("rows") or {})))

    page = store.pull(0)
    check("pull(0) 拿到 1 行", len(page["rows"]) == 1, repr(page))
    check("pull(0) server_rev=1", page["server_rev"] == 1, repr(page["server_rev"]))
    check("pull(0) more=False", page["more"] is False, repr(page["more"]))
    check("pull(1) 没有增量", store.pull(1)["rows"] == [], "")
    check("pull 空页时 next=server_rev", store.pull(0)["next_since_rev"] == 1, "")

    # 重新载入：数据还在
    again = sync_store.SyncStore(path)
    check("重新载入 rev 保持", again.pull(0)["server_rev"] == 1, "")
    check("重新载入 rows 保持", len(again.pull(0)["rows"]) == 1, "")


def test_conflicts(tmp):
    path = os.path.join(tmp, "conflict.json")
    store = sync_store.SyncStore(path)
    quiet_push(store, "d1", [mkop("increase", 2000, "op-new")])

    result, out = quiet_push(store, "d1", [mkop("increase", 1000, "op-old")])
    conflicts = result.get("conflicts") or []
    check("旧 lrt 冲突被返回", len(conflicts) == 1, repr(conflicts))
    check("冲突原因 stale", conflicts and conflicts[0].get("reason") == "stale", repr(conflicts))
    check("冲突带 word/old_lrt/new_lrt",
          conflicts and conflicts[0].get("word") == "increase"
          and conflicts[0].get("old_lrt") == 2000 and conflicts[0].get("new_lrt") == 1000,
          repr(conflicts))
    check("旧 op 仍 acked（手机端能从队列移除）", result.get("acked") == ["op-old"], repr(result.get("acked")))
    check("日志里有冲突记录", "冲突丢弃" in out and "increase" in out, out.strip())
    check("库里的行仍是新 lrt", store.pull(0)["rows"][0]["lrt"] == 2000, "")

    # 同 lrt：op_id 小的输
    result, out = quiet_push(store, "d1", [mkop("increase", 2000, "op-aaa")])
    conflicts = result.get("conflicts") or []
    check("同 lrt 冲突被返回", len(conflicts) == 1 and conflicts[0].get("reason") == "same_lrt",
          repr(conflicts))
    check("同 lrt 的旧 op 也 ack", result.get("acked") == ["op-aaa"], repr(result.get("acked")))
    check("同 lrt 日志有记录", "same_lrt" in out, out.strip())

    check("conflicts_total 累计 2", store.stats().get("conflicts_total") == 2,
          repr(store.stats().get("conflicts_total")))
    check("last_conflict 有值", isinstance(store.stats().get("last_conflict"), dict), "")


def test_corrupt_recovery(tmp):
    path = os.path.join(tmp, "corrupt.json")
    # 先造一份「曾经正常」的文件，确保 rev 不是 0
    seed = sync_store.SyncStore(path)
    quiet_push(seed, "d1", [mkop("word-%d" % i, 1000 + i, "op-%d" % i) for i in range(7)])
    check("种子 rev=7", seed.stats()["rev"] == 7, repr(seed.stats()["rev"]))

    corrupt_text = '{"rev": 42, "rows": {"a": {"rev": 99}, '  # 截断，不是合法 JSON
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(corrupt_text)

    recovered = sync_store.SyncStore(path)
    stats = recovered.stats()
    check("坏文件被识别", stats.get("corrupt_count") == 1, repr(stats))
    check("corrupt 时间有值", bool(stats.get("last_corrupt_at")), repr(stats.get("last_corrupt_at")))
    backups = glob.glob(path + ".corrupt-*")
    check("备份文件只有一个", len(backups) == 1, repr(backups))
    if backups:
        with open(backups[0], "r", encoding="utf-8") as fh:
            check("备份保留原始坏内容", fh.read() == corrupt_text, backups[0])
    check("rev 沿用备份里的最大 rev（99）", stats.get("rev") == 99, repr(stats.get("rev")))
    check("重建后 rows 为空", stats.get("rows") == 0, repr(stats.get("rows")))
    check("重建后 pull 不会倒挂", recovered.pull(0)["server_rev"] == 99, "")

    # 再写一条：rev 必须从 100 继续，而不是回到 1
    result, _ = quiet_push(recovered, "d1", [mkop("after", 3000, "op-after")])
    check("重建后新 op 从 100 开始", result.get("server_rev") == 100, repr(result.get("server_rev")))

    # 第二次坏：不能覆盖第一次的备份
    with open(path, "w", encoding="utf-8") as fh:
        fh.write('{"rev": 7, "rows": ' )
    second = sync_store.SyncStore(path)
    backups2 = glob.glob(path + ".corrupt-*")
    check("第二次坏文件又有一份备份（共 2 份）", len(backups2) == 2, repr(backups2))
    check("两次备份名不同", len(set(backups2)) == 2, repr(backups2))
    check("第二次 corrupt_count 从 1 起算（新实例）", second.stats().get("corrupt_count") == 1,
          repr(second.stats().get("corrupt_count")))

    # 合法 JSON 但形状不对，也算坏文件
    with open(path, "w", encoding="utf-8") as fh:
        json.dump({"rev": 5, "rows": []}, fh)
    third = sync_store.SyncStore(path)
    check("rows 不是对象也按坏文件处理", third.stats().get("corrupt_count") == 1, repr(third.stats()))
    check("形状不对也不能把 rev 拉回 5", third.stats().get("rev") == 100, repr(third.stats().get("rev")))
    check("第三次坏文件备份累计 3 份", len(glob.glob(path + ".corrupt-*")) == 3, "")


def test_row_limit(tmp):
    old_limit = sync_store.MAX_ROWS
    sync_store.MAX_ROWS = 3
    try:
        path = os.path.join(tmp, "limit.json")
        store = sync_store.SyncStore(path)
        ops = [mkop("w-%d" % i, 1000 + i, "op-%d" % i) for i in range(8)]
        result, out = quiet_push(store, "d1", ops)
        check("超上限不删数据：trimmed=0", result.get("trimmed") == 0, repr(result.get("trimmed")))
        check("rows 保留全部 8 行", store.stats()["rows"] == 8, repr(store.stats()["rows"]))
        check("告警日志有记录", "容量告警线" in out and "不自动删除" in out, out.strip())
        remaining = [row["w"] for row in store.pull(0)["rows"]]
        check("8 行全部还在", remaining == ["w-%d" % i for i in range(8)], repr(remaining))
        check("rows_trimmed 仍为 0", store.stats().get("rows_trimmed") == 0,
              repr(store.stats().get("rows_trimmed")))
        check("超线计数为 5", store.stats().get("rows_over_limit") == 5,
              repr(store.stats().get("rows_over_limit")))

        again = sync_store.SyncStore(path)
        check("重新载入后仍是 8 行", again.stats()["rows"] == 8, repr(again.stats()["rows"]))
        check("重新载入 rev 不回退", again.stats()["rev"] == 8, repr(again.stats()["rev"]))
    finally:
        sync_store.MAX_ROWS = old_limit


def test_key_contract(tmp):
    print("\n[0] _key 契约：真 NUL 分隔（兼容线上历史 key）")
    book, word = "六级", "abandon"
    key = sync_store._key(book, word)
    check("_key 用真 NUL 分隔", key == book + "\u0000" + word, repr(key))
    check("比字面量 \\u0000 版本短 5 个字符", len(book + "\\u0000" + word) - len(key) == 5,
          "%d vs %d" % (len(book + "\\u0000" + word), len(key)))

    path = os.path.join(tmp, "key.json")
    legacy_key = book + "\u0000" + word
    legacy_row = dict(mkop(word, 1000, "legacy"), rev=3)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump({"rev": 3, "rows": {legacy_key: legacy_row}}, fh, ensure_ascii=False)

    store = sync_store.SyncStore(path)
    check("_sanitize 不改写旧 key", list(store._data["rows"].keys()) == [legacy_key],
          repr(list(store._data["rows"].keys())))
    result, logs = quiet_push(store, "dev", [mkop(word, 2000, "new")])
    check("push 旧词命中同一 key（不新增第二条）", store.stats()["rows"] == 1,
          repr(store.stats()["rows"]))
    check("旧行被更新（rev 3 → 4）", result.get("server_rev") == 4, repr(result))
    check("旧行被更新（lrt 变为 2000）",
          store._data["rows"][legacy_key].get("lrt") == 2000,
          repr(store._data["rows"][legacy_key].get("lrt")))


def main():
    print("== sync_store 加固自测 ==")
    with tempfile.TemporaryDirectory(prefix="sync-store-test-") as tmp:
        test_key_contract(tmp)
        test_normal(tmp)
        test_conflicts(tmp)
        test_corrupt_recovery(tmp)
        test_row_limit(tmp)

    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())

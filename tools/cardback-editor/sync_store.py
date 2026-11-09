#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""学习记录同步的服务端存储（1.4.4）

抄的是墨墨那套最小骨架：本地一条队列把「结果态」发上来，服务端只做两件事——
记住每个词最新的一条，并给每次写入发一个递增号码；手机下次带上号码来取增量。

不做的事（明确划掉）：云端算排程、意图级 op、墓碑、整包覆盖。
冲突按「最后复习时间」判，晚的那条赢，不弹窗让用户选。

落的文件：tools/rewrite-service/data/sync.json
{
  "rev": 123,
  "rows": {"六级记忆卡\\\\u0000abandon": {...op..., "rev": 101}}
}
"""

from __future__ import annotations

import json
import os
import re
import threading
import time

# 一次拉取最多返回多少行；手机端会带着新的 since_rev 再来。
PULL_LIMIT = 500

# 服务端最多保留多少行。超过时按 rev 从旧到新丢掉，避免 sync.json 无限长。
# 20000 行 ≈ 单用户几十年也到不了，正常使用永远碰不到；只是兜底。
MAX_ROWS = 20000

# op 里必须存在的字段；缺了就当脏数据丢掉，别让它污染库。
REQUIRED = ("op_id", "t", "w", "b", "st", "reps", "ivl", "nrt", "ef", "rc", "lrt")

# 从坏文件原文里尽量捞回最大的 rev，避免重建后 rev 归 0，
# 让手机端拿着旧 since_rev 永远拉不到新数据。
_REV_RE = re.compile(r'"rev"\s*:\s*(\d+)')


def _key(book: str, word: str) -> str:
    # 这里必须是「真 NUL 字符」做分隔符（json.dump 会写成 \u0000）。
    # 历史数据（线上 sync.json）全部是真 NUL，绝不能写成字面量 \\u0000，
    # 否则旧行查不到：冲突判定失效、同一个词会出现两条 key、pull 下发重复。
    return f"{book}\u0000{word}"


def _as_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


class SyncStore:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        # 可观测性：坏文件恢复 / 行数清理 / 冲突丢弃都记在这里，stats() 和对端可见。
        self.corrupt_count = 0
        self.last_corrupt_at = ""
        self.last_corrupt_backup = ""
        self.rows_trimmed = 0
        self.conflicts_total = 0
        self.last_conflict = None
        self._data = self._load()

    # ------------------------------------------------------------ 落盘

    def _load(self) -> dict:
        if not os.path.exists(self.path):
            return {"rev": 0, "rows": {}}
        raw = ""
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                raw = f.read()
            data = json.loads(raw)
            if not isinstance(data, dict) or not isinstance(data.get("rows"), dict):
                raise ValueError("顶层不是 {rev, rows}")
        except Exception as exc:
            return self._recover_corrupt(raw, exc)
        return self._sanitize(data)

    def _rev_floor(self, raw: str) -> int:
        """坏文件里能读到的最大 rev（全局 rev 或行 rev 都算）。"""
        floor = 0
        for match in _REV_RE.finditer(raw or ""):
            floor = max(floor, _as_int(match.group(1), 0))
        return max(0, floor)

    def _recover_corrupt(self, raw: str, exc: Exception) -> dict:
        """坏文件先备份再重建；备份名带时间戳，绝不覆盖上一份坏文件。"""
        stamp = time.strftime("%Y%m%d-%H%M%S")
        backup = f"{self.path}.corrupt-{stamp}"
        suffix = 2
        while os.path.exists(backup):
            backup = f"{self.path}.corrupt-{stamp}-{suffix}"
            suffix += 1
        moved = False
        try:
            os.replace(self.path, backup)
            moved = True
        except OSError as move_exc:
            print(
                f"[sync] 警告：坏文件备份失败 {self.path} → {backup}：{move_exc}",
                flush=True,
            )
        floor = self._rev_floor(raw)
        self.corrupt_count += 1
        self.last_corrupt_at = time.strftime("%Y-%m-%d %H:%M:%S")
        self.last_corrupt_backup = backup if moved else ""
        print(
            f"[sync] 警告：{self.path} 无法解析（{type(exc).__name__}: {exc}）；"
            f"已备份到 {backup if moved else '（备份失败）'}，"
            f"沿用 rev={floor} 重建，避免客户端 since_rev 倒挂。",
            flush=True,
        )
        return {"rev": floor, "rows": {}}

    def _sanitize(self, data: dict) -> dict:
        """把读进来的数据收拾成能安全用的形状；坏行丢掉，rev 不回退。"""
        rows = data.get("rows")
        if not isinstance(rows, dict):
            rows = {}
        clean = {}
        max_row_rev = 0
        for key, row in rows.items():
            if not isinstance(row, dict):
                continue
            rev = _as_int(row.get("rev"), -1)
            if rev < 0:
                continue
            row["rev"] = rev
            max_row_rev = max(max_row_rev, rev)
            clean[key] = row
        data["rows"] = clean
        data["rev"] = max(_as_int(data.get("rev"), 0), max_row_rev, 0)
        self._trim_rows(data)
        return data

    def _save(self) -> None:
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._data, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, self.path)

    def _trim_rows(self, data: dict | None = None) -> int:
        """超过 MAX_ROWS 时按 rev 从旧到新清理，返回本次清理行数。"""
        data = data if data is not None else self._data
        rows = data["rows"]
        overflow = len(rows) - MAX_ROWS
        if overflow <= 0:
            return 0
        oldest = sorted(rows.items(), key=lambda kv: _as_int(kv[1].get("rev"), 0))[:overflow]
        for key, _row in oldest:
            rows.pop(key, None)
        self.rows_trimmed += overflow
        print(
            f"[sync] 提示：rows 超过上限 {MAX_ROWS}，按 rev 清理最旧 {overflow} 行"
            f"（累计 {self.rows_trimmed}）。",
            flush=True,
        )
        return overflow

    # ------------------------------------------------------------ 接口

    def push(self, device_id: str, base_rev: int, ops: list) -> dict:
        """收下一批 op。返回 {server_rev, acked, rejected, conflicts, trimmed}。

        过期（lrt 更小）的 op 也算 acked：手机端已经把它发出来了，留在队列里只会反复重发。
        被丢掉的旧 op 会进 conflicts（同时在日志里留一行），不静默吞掉。
        """
        acked: list[str] = []
        rejected: list[dict] = []
        conflicts: list[dict] = []
        now = int(time.time() * 1000)
        with self._lock:
            for op in ops or []:
                if not isinstance(op, dict):
                    continue
                op_id = str(op.get("op_id") or "")
                missing = [k for k in REQUIRED if op.get(k) is None]
                if not op_id or missing:
                    rejected.append({"op_id": op_id, "reason": f"缺字段 {','.join(missing)}"})
                    continue
                key = _key(str(op["b"]), str(op["w"]))
                old = self._data["rows"].get(key)
                if old is not None:
                    old_lrt = _as_int(old.get("lrt"), 0)
                    new_lrt = _as_int(op["lrt"], 0)
                    if old_lrt > new_lrt:
                        self._record_conflict(
                            conflicts, op_id, str(op["w"]), old, "stale", old_lrt, new_lrt
                        )
                        acked.append(op_id)
                        continue
                    if old_lrt == new_lrt and str(old.get("op_id", "")) >= op_id:
                        self._record_conflict(
                            conflicts, op_id, str(op["w"]), old, "same_lrt", old_lrt, new_lrt
                        )
                        acked.append(op_id)
                        continue
                self._data["rev"] += 1
                row = {k: op.get(k) for k in REQUIRED}
                row["device"] = str(device_id or "")[:64]
                row["rev"] = self._data["rev"]
                row["at"] = now
                self._data["rows"][key] = row
                acked.append(op_id)
            trimmed = self._trim_rows()
            self._save()
            return {
                "server_rev": self._data["rev"],
                "acked": acked,
                "rejected": rejected,
                "conflicts": conflicts,
                "trimmed": trimmed,
            }

    def _record_conflict(
        self,
        conflicts: list,
        op_id: str,
        word: str,
        old: dict,
        reason: str,
        old_lrt: int,
        new_lrt: int,
    ) -> None:
        kept_op_id = str(old.get("op_id", ""))
        item = {
            "op_id": op_id,
            "word": word,
            "kept_op_id": kept_op_id,
            "reason": reason,
            "old_lrt": old_lrt,
            "new_lrt": new_lrt,
        }
        conflicts.append(item)
        self.conflicts_total += 1
        self.last_conflict = item
        print(
            f"[sync] 冲突丢弃 op_id={op_id} word={word} "
            f"old_lrt={old_lrt} new_lrt={new_lrt} reason={reason} "
            f"（保留 op_id={kept_op_id}）",
            flush=True,
        )

    def pull(self, since_rev: int, limit: int = PULL_LIMIT) -> dict:
        """取 since_rev 之后写过的行，按 rev 升序，一次最多 limit 条。

        next_since_rev 是下一页该从哪继续：还有更多时只推进到本页最后一行，
        全部拉完才推到全局 rev。手机端用它推游标，一次超过 limit 条也不会漏。
        """
        with self._lock:
            rows = [r for r in self._data["rows"].values() if _as_int(r.get("rev"), 0) > since_rev]
            rows.sort(key=lambda r: _as_int(r.get("rev"), 0))
            page = rows[:limit]
            more = len(rows) > len(page)
            if page:
                next_rev = _as_int(page[-1].get("rev"), 0)
            else:
                next_rev = _as_int(self._data["rev"], 0)
            if not more:
                next_rev = _as_int(self._data["rev"], 0)
            return {
                "server_rev": _as_int(self._data["rev"], 0),
                "next_since_rev": next_rev,
                "rows": page,
                "more": more,
            }

    def stats(self) -> dict:
        with self._lock:
            return {
                "rev": _as_int(self._data["rev"], 0),
                "rows": len(self._data["rows"]),
                "max_rows": MAX_ROWS,
                "rows_trimmed": self.rows_trimmed,
                "corrupt_count": self.corrupt_count,
                "last_corrupt_at": self.last_corrupt_at,
                "last_corrupt_backup": self.last_corrupt_backup,
                "conflicts_total": self.conflicts_total,
                "last_conflict": self.last_conflict,
            }

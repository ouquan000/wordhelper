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

# 容量阈值只用于告警，不删任何有效学习状态（1.4.7.2 W5 复审裁决）。
# 以前超过阈值会按 rev 清最旧，这会在用户不知情时删掉可撤销/可恢复的进度，
# 属于未授权数据损失；现在只统计并打印告警，由用户/运维决定是否迁移。
MAX_ROWS = 20000

# 单本书的容量告警线（6000 ≈ 一本 5200 词的书全部背完还有余量）。
MAX_ROWS_PER_BOOK = 6000

# op 里必须存在的字段；缺了就当脏数据丢掉，别让它污染库。
REQUIRED = ("op_id", "t", "w", "b", "st", "reps", "ivl", "nrt", "ef", "rc", "lrt")

# 1.4.9-P1：FSRS 四字段是「向后兼容的可选字段」。
# 老客户端（1.4.9-P0 及以前）根本不发这四项，缺省时完全走旧逻辑；
# 一旦带了，就必须是干净的数字类型，否则整条 op 拒收，避免脏类型写进 sync.json
# 再被 pull 下发给手机端（Gson 反序列化会直接抛异常，整页同步失败）。
OPTIONAL_FSRS = ("fsrs_stability", "fsrs_difficulty", "fsrs_lapses", "fsrs_state")

# 除 ef（允许小数）以外的契约字段都必须是整数。
_INT_FIELDS = ("st", "reps", "ivl", "nrt", "rc", "lrt")


def _is_number(value) -> bool:
    """JSON 数字判定：bool 是 int 的子类，true/false 不能混进来当 1/0。"""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _type_error(op: dict) -> str:
    """检查基础字段类型，返回第一条错误描述；类型正常返回空串。

    只做「脏类型」防守，不裁决业务范围（范围由客户端和 FSRS 自己保证）。
    """
    if not isinstance(op.get("op_id"), str) or not op.get("op_id", "").strip():
        return "op_id 不是有效字符串"
    for key in ("t", "w", "b"):
        if not isinstance(op.get(key), str):
            return f"{key} 不是字符串"
    for key in _INT_FIELDS:
        value = op.get(key)
        if not isinstance(value, int) or isinstance(value, bool):
            return f"{key} 不是整数"
    if not _is_number(op.get("ef")):
        return "ef 不是数字"
    if op.get("fsrs_stability") is not None and not _is_number(op.get("fsrs_stability")):
        return "fsrs_stability 不是数字"
    if op.get("fsrs_difficulty") is not None and not _is_number(op.get("fsrs_difficulty")):
        return "fsrs_difficulty 不是数字"
    for key in ("fsrs_lapses", "fsrs_state"):
        value = op.get(key)
        if value is not None and (not isinstance(value, int) or isinstance(value, bool)):
            return f"{key} 不是整数"
    return ""

# 从坏文件原文里尽量捞回最大的 rev，避免重建后 rev 归 0，
# 让手机端拿着旧 since_rev 永远拉不到新数据。
_REV_RE = re.compile(r'"rev"\s*:\s*(\d+)')
_EPOCH_RE = re.compile(r'"epoch"\s*:\s*(\d+)')


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
        self.rows_over_limit = 0
        self.rows_over_limit_by_book: dict[str, int] = {}
        self.conflicts_total = 0
        self.last_conflict = None
        # 1.4.7.2 W5：恢复代次。正常卸载/重装/换机都保持同一个 epoch；只有服务端
        # 从坏文件重建时才递增，客户端据此把游标归零但保留本地待发队列。
        self.epoch = self._read_epoch_file()
        # rev 也要有持久下限：坏文件正文可能被截断成空/低 rev，不能让它把全局 rev 拉回去。
        self.rev_floor = self._read_rev_file()
        self.replayed_total = 0
        self.trimmed_by_book: dict[str, int] = {}
        self._data = self._load()
        # 1.4.7.2 W5 评分 P2-09：旧版 sync.json 没有 .rev sidecar 时，先补写一次，
        # 否则「升级后首次损坏、且损坏前没发生过写入」会把 rev 拉回旧值。
        self._write_rev_file(_as_int(self._data.get("rev"), 0))

    # ------------------------------------------------------------ 落盘

    def _load(self) -> dict:
        if not os.path.exists(self.path):
            return {"rev": max(0, self.rev_floor), "rows": {}, "epoch": self.epoch}
        raw = ""
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                raw = f.read()
            data = json.loads(raw)
            if not isinstance(data, dict) or not isinstance(data.get("rows"), dict):
                raise ValueError("顶层不是 {rev, rows}")
        except Exception as exc:
            recovered = self._recover_corrupt(raw, exc)
            # 坏文件恢复必须立刻落盘：只在内存里修好、重启又读到原坏文件，
            # epoch 会退回 1、rev 会退回 0，客户端游标就失去保护。
            self._write(recovered)
            return recovered
        return self._sanitize(data)

    def _epoch_path(self) -> str:
        return self.path + ".epoch"

    def _read_epoch_file(self) -> int:
        try:
            with open(self._epoch_path(), "r", encoding="utf-8") as f:
                return max(1, _as_int(f.read().strip(), 1))
        except OSError:
            return 1

    def _write_epoch_file(self, epoch: int) -> None:
        tmp = self._epoch_path() + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(str(max(1, epoch)))
            os.replace(tmp, self._epoch_path())
        except OSError:
            pass

    def _rev_path(self) -> str:
        return self.path + ".rev"

    def _read_rev_file(self) -> int:
        try:
            with open(self._rev_path(), "r", encoding="utf-8") as f:
                return max(0, _as_int(f.read().strip(), 0))
        except OSError:
            return 0

    def _write_rev_file(self, rev: int) -> None:
        tmp = self._rev_path() + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(str(max(0, rev)))
            os.replace(tmp, self._rev_path())
        except OSError:
            pass

    def _rev_floor(self, raw: str) -> int:
        """坏文件里能读到的最大 rev（全局 rev 或行 rev 都算）。"""
        floor = 0
        for match in _REV_RE.finditer(raw or ""):
            floor = max(floor, _as_int(match.group(1), 0))
        return max(0, floor)

    def _epoch_floor(self, raw: str) -> int:
        """坏文件里能读到的 epoch；恢复时必须比它更大，不能倒回默认 1。"""
        floor = 1
        for match in _EPOCH_RE.finditer(raw or ""):
            floor = max(floor, _as_int(match.group(1), 1))
        return max(1, floor)

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
        floor = max(self.rev_floor, self._rev_floor(raw))
        self.corrupt_count += 1
        self.last_corrupt_at = time.strftime("%Y-%m-%d %H:%M:%S")
        self.last_corrupt_backup = backup if moved else ""
        # 坏文件重建是一次真正的「换库」：epoch+1，让客户端把游标归零重拉，
        # 但不要动客户端尚未发出去的本地队列。坏文件里能读到的 epoch 是下限，
        # 否则恢复后会从 2 重来，客户端可能把更早的 epoch 当成同代。
        prior = max(self.epoch, self._epoch_floor(raw), self._read_epoch_file())
        self.epoch = max(2, prior + 1)
        self._write_epoch_file(self.epoch)
        print(
            f"[sync] 警告：{self.path} 无法解析（{type(exc).__name__}: {exc}）；"
            f"已备份到 {backup if moved else '（备份失败）'}，"
            f"沿用 rev={floor}、epoch={self.epoch} 重建，避免客户端 since_rev 倒挂。",
            flush=True,
        )
        return {"rev": floor, "rows": {}, "epoch": self.epoch}

    def _sanitize(self, data: dict) -> dict:
        """把读进来的数据收拾成能安全用的形状；坏行丢掉，rev 不回退。"""
        rows = data.get("rows")
        if not isinstance(rows, dict):
            rows = {}
        clean = {}
        max_row_rev = 0
        seen = data.get("seen")
        if not isinstance(seen, dict):
            seen = {}
        clean_seen = {}
        for op_id, rev in seen.items():
            if isinstance(op_id, str) and op_id:
                clean_seen[op_id] = _as_int(rev, 0)
        # 只保留最近 50000 个已应用 op_id，防止无限增长。
        if len(clean_seen) > 50000:
            clean_seen = dict(list(clean_seen.items())[-50000:])
        data["seen"] = clean_seen
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
        data["rev"] = max(_as_int(data.get("rev"), 0), max_row_rev, self.rev_floor, 0)
        self.rev_floor = max(self.rev_floor, data["rev"])
        # 老文件没有 epoch：补 1，保持向后兼容；以后只有坏文件重建才会递增。
        data["epoch"] = max(1, _as_int(data.get("epoch"), self.epoch), self._read_epoch_file())
        self.epoch = max(self.epoch, data["epoch"])
        self._trim_rows(data)
        return data

    def _write(self, data: dict) -> None:
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, self.path)
        epoch = max(1, _as_int(data.get("epoch"), self.epoch))
        self._write_epoch_file(epoch)
        rev = max(0, _as_int(data.get("rev"), 0))
        self.rev_floor = max(self.rev_floor, rev)
        self._write_rev_file(self.rev_floor)

    def _save(self) -> None:
        self._write(self._data)

    def _trim_rows(self, data: dict | None = None) -> int:
        """只告警，不删任何行；返回 0（保留旧接口给调用方/测试）。

        1.4.7.2 W5 复审裁决：按书 6000 / 全局 20000 直接删最新有效学习状态
        是未授权的数据损失。容量阈值只用于可诊断告警；真正的归档/迁移必须由
        用户显式触发，并且先备份。这里统计「超线多少」写进 stats()，让状态页
        和运维能看见，但绝不动 rows。
        """
        data = data if data is not None else self._data
        rows = data["rows"]
        over_by_book: dict[str, int] = {}
        by_book: dict[str, int] = {}
        for row in rows.values():
            if not isinstance(row, dict):
                continue
            book = str(row.get("b") or "")
            by_book[book] = by_book.get(book, 0) + 1
        for book, count in by_book.items():
            overflow = count - MAX_ROWS_PER_BOOK
            if overflow > 0:
                over_by_book[book] = overflow
        global_over = max(0, len(rows) - MAX_ROWS)
        self.rows_over_limit = global_over
        self.rows_over_limit_by_book = over_by_book
        if over_by_book or global_over:
            print(
                f"[sync] 警告：rows 超过容量告警线（单书 {MAX_ROWS_PER_BOOK} / 全局 {MAX_ROWS}），"
                f"当前 {len(rows)} 行；超线按书：{over_by_book}，全局超线 {global_over}。"
                f"为保护学习进度不自动删除，请先备份再显式迁移。",
                flush=True,
            )
        return 0

    # ------------------------------------------------------------ 接口

    def push(self, device_id: str, base_rev: int, ops: list, upcoming=None) -> dict:
        """Merge progress and atomically replace a supplied device preview."""
        acked: list[str] = []
        rejected: list[dict] = []
        conflicts: list[dict] = []
        replayed: list[str] = []
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
                type_error = _type_error(op)
                if type_error:
                    rejected.append({"op_id": op_id, "reason": f"字段类型不对：{type_error}"})
                    continue
                key = _key(str(op["b"]), str(op["w"]))
                seen = self._data.setdefault("seen", {})
                if op_id in seen:
                    # 这条 op 以前成功落过，只是后来被同词更新的 op 覆盖了；
                    # 客户端断线重发时仍应幂等 ack，不能记成 stale 冲突。
                    replayed.append(op_id)
                    self.replayed_total += 1
                    acked.append(op_id)
                    continue
                old = self._data["rows"].get(key)
                if old is not None:
                    # 同一条 op 重发（客户端没收到回执/断线重试）：幂等 ack，不涨 rev、
                    # 不算冲突。以前会落进 same_lrt 冲突，把正常重放记成真实冲突。
                    if str(old.get("op_id", "")) == op_id:
                        replayed.append(op_id)
                        self.replayed_total += 1
                        acked.append(op_id)
                        continue
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
                # 可选字段：只有客户端真的带了才落盘，老行/老客户端保持原样。
                # 注意：循环变量绝不能叫 key —— 上面的行键 key 还要用来写入 rows。
                for field in OPTIONAL_FSRS:
                    if op.get(field) is not None:
                        row[field] = op.get(field)
                row["device"] = str(device_id or "")[:64]
                row["rev"] = self._data["rev"]
                row["at"] = now
                self._data["rows"][key] = row
                seen[op_id] = self._data["rev"]
                if len(seen) > 50000:
                    self._data["seen"] = dict(list(seen.items())[-50000:])
                acked.append(op_id)
            if upcoming is not None:
                self._data.update(upcoming)
            trimmed = self._trim_rows()
            self._save()
            return {
                "server_rev": self._data["rev"],
                "epoch": self.epoch,
                "acked": acked,
                "rejected": rejected,
                "conflicts": conflicts,
                "trimmed": trimmed,
                "trimmed_by_book": dict(self.trimmed_by_book),
                "over_limit": dict(self.rows_over_limit_by_book),
                "replayed": replayed,
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
                "epoch": self.epoch,
                "next_since_rev": next_rev,
                "rows": page,
                "more": more,
            }

    def stats(self) -> dict:
        with self._lock:
            return {
                "rev": _as_int(self._data["rev"], 0),
                "epoch": self.epoch,
                "rows": len(self._data["rows"]),
                "max_rows": MAX_ROWS,
                "max_rows_per_book": MAX_ROWS_PER_BOOK,
                "rows_trimmed": self.rows_trimmed,
                "trimmed_by_book": dict(self.trimmed_by_book),
                "rows_over_limit": self.rows_over_limit,
                "rows_over_limit_by_book": dict(self.rows_over_limit_by_book),
                "trim_mode": "warn-only",
                "replayed_total": self.replayed_total,
                "corrupt_count": self.corrupt_count,
                "last_corrupt_at": self.last_corrupt_at,
                "last_corrupt_backup": self.last_corrupt_backup,
                "conflicts_total": self.conflicts_total,
                "last_conflict": self.last_conflict,
            }

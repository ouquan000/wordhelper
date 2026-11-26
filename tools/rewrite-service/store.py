#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""落盘层：队列 queue.json / 序号 state.json / 每词结果 results/<word>.json。

单进程单写者：内存里是权威副本，每次变更后原子写回磁盘，重启读回来接着跑。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import threading
import time
import uuid
from datetime import datetime, timedelta

import providers

from store_queue import QueueOperations
from store_feedback import FeedbackOperations
from store_completion import CompletionOperations
from store_results import ResultOperations

KEEP_FINISHED = 500  # 队列只留最近这么多条已完成的，防止一年后扫盘变慢
KEEP_OK = 5  # 每词最多 5 版（spec §4.4）
KEEP_FAIL = 3
MEMORY_COOLDOWN_SECONDS = 30 * 60  # lapsed 同词同方法 30 分钟冷却
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")

# 未到质量上限或传输失败时按表退避。
RETRY_DELAYS = (30, 120, 300, 900, 1800, 3600)
# 工人整轮异常时把 running 退回队列的短退避：不是这条词本身的失败，
# 只是当前线程没跑完，稍等就让别的工人接手，别按失败次数长退避。
RELEASE_DELAY_SECONDS = 5


def _priority_value(value, default=100):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _retry_at(failures):
    """第 N 次失败后多久再试；超出表长就停在最后一档。"""
    index = max(0, min(int(failures or 1) - 1, len(RETRY_DELAYS) - 1))
    return (datetime.now().astimezone() + timedelta(seconds=RETRY_DELAYS[index])).isoformat(
        timespec="seconds"
    )


def _seconds_from_now(seconds):
    return (datetime.now().astimezone() + timedelta(seconds=seconds)).isoformat(
        timespec="seconds"
    )


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def _write_json(path, data):
    """先写 .tmp 再替换：手机随时可能来拉，不能读到半份。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # 多工人同时收尾时，Windows 的 os.replace 偶尔会撞上扫描器/读句柄；
    # 用线程唯一的临时名，并短暂重试，避免整条工人线程被一次瞬时占用打死。
    tmp = "%s.%d.%d.tmp" % (path, os.getpid(), threading.get_ident())
    try:
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
        last = None
        for attempt in range(10):
            try:
                os.replace(tmp, path)
                return
            except PermissionError as exc:
                last = exc
                time.sleep(0.02 * (attempt + 1))
        raise last
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def result_name(word):
    """词可能带撇号/空格/汉字，落成文件名前得洗一遍；洗过就补哈希防撞。"""
    safe = SAFE_NAME.sub("_", word)
    if safe == word and safe:
        return safe + ".json"
    digest = hashlib.sha1(word.encode("utf-8")).hexdigest()[:6]
    return "%s-%s.json" % (safe or "w", digest)


def _current(record):
    """这个词最近一版成功的结果（没有就空）。"""
    for entry in reversed(record["entries"]):
        if entry.get("status") == "ok" and not entry.get("unverified"):
            return entry
    return None


def book_scope(row):
    """New marks carry a book ID; old marks can only be scoped by book name."""
    try:
        book_id = max(0, int(row.get("bookId") or 0))
    except (TypeError, ValueError, OverflowError):
        book_id = 0
    return book_id, str(row.get("book") or "").strip()[:60]


def same_book_scope(left, right):
    left_id, left_name = book_scope(left)
    right_id, right_name = book_scope(right)
    if left_id or right_id:
        # A name by itself cannot identify a modern book, even if only one
        # matching ID has been observed so far. Keep legacy rows as-is.
        return left_id == right_id and (not left_name or not right_name or left_name == right_name)
    return left_name == right_name


def _current_in_book(record, item):
    return next((e for e in reversed(record["entries"])
                 if e.get("status") == "ok" and not e.get("unverified") and same_book_scope(e, item)), None)


def _trim(entries):
    scoped = {}
    for entry in entries:
        scoped.setdefault(book_scope(entry), []).append(entry)
    kept = []
    for group in scoped.values():
        kept.extend([e for e in group if e.get("status") == "ok"][-KEEP_OK:])
        kept.extend([e for e in group if e.get("status") != "ok"][-KEEP_FAIL:])
    return sorted(kept, key=lambda e: e["seq"])


def _public(entry):
    out = {
        key: entry.get(key)
        for key in ("seq", "word", "rev", "approach", "method", "prev", "updatedAt", "status", "clientId")
    }
    out["model"] = entry.get("model", "")
    # 1.4.7.1：流水线自报「没复核」（unverified=true）时，结果照发但必须带标记。
    # 旧结果没有这两个字段 → 默认 false / 空串，App 端按已复核处理，兼容。
    out["unverified"] = bool(entry.get("unverified"))
    out["reason"] = str(entry.get("reason") or "")
    out["reused"] = bool(entry.get("reused"))
    # 1.5.3：结果来源。手工重写是 ""（老结果也当作手工），闲时洗词是 "worker"。
    # App 端可以据此决定要不要自动覆盖卡背。
    out["source"] = str(entry.get("source") or "")
    if entry.get("reuseOf") is not None:
        out["reuseOf"] = entry.get("reuseOf")
    if entry.get("unverified") or entry.get("status") == "failed":
        out.update(status="failed", approach=[], error=entry.get("error") or out["reason"] or "质量未过审")
    return out


# 一条标记的真实阶段：入队 → 生成 → 润色 → 评分 → 完成/失败
STAGES = ("queued", "generate", "polish", "judge", "done", "failed")
_STATE_STAGE = {"pending": "queued", "running": "generate", "done": "done", "failed": "failed"}


def stage_of(item):
    """旧数据没有 stage：按 state 推断，缺字段绝不能把面板/接口弄崩。"""
    stage = str(item.get("stage") or "")
    if stage in STAGES:
        return stage
    return _STATE_STAGE.get(item.get("state") or "pending", "queued")


FEEDBACK_COUNT_MIN = 1
FEEDBACK_COUNT_MAX = 50


def feedback_count(value):
    """把反馈里的可选 `count` 规范化：缺省 1，必须是 1..50 的整数。

    返回 (count, reason)：reason 非空表示这条的 count 非法，调用方拒掉这一条即可。
    bool 不算整数（`True`/`False` 是 JSON 里的 true/false，不是 1/0）；字符串也不收，
    免得手机端把 "3" 和 3 混着发出来两边记成不同口径。
    """
    if value is None:
        return FEEDBACK_COUNT_MIN, ""
    if isinstance(value, bool) or not isinstance(value, int):
        return 0, "count 只能是 1..50 的整数"
    if value < FEEDBACK_COUNT_MIN or value > FEEDBACK_COUNT_MAX:
        return 0, "count 只能是 1..50 的整数"
    return value, ""


# 1.5.3：点踩结构化。手机端可以带「为什么踩」（reasons）和一句自由补充（comment），
# 两个都不发就是老客户端，行为与 1.5.2 完全一致。这些字段只做规范化，绝不因为
# 它们不合法就丢掉这一条反馈 —— 评价本身（rating/count）比理由值钱。
FEEDBACK_REASONS_MAX = 12
FEEDBACK_REASON_LEN_MAX = 64
FEEDBACK_COMMENT_LEN_MAX = 1000


def feedback_reasons(value):
    """把可选 `reasons` 规范成 list[str]：非列表按空处理，去空白、去空项、去重、截断。"""
    if not isinstance(value, list):
        return []
    out = []
    for item in value:
        if not isinstance(item, str):
            continue
        text = item.strip()[:FEEDBACK_REASON_LEN_MAX]
        if not text or text in out:
            continue
        out.append(text)
        if len(out) >= FEEDBACK_REASONS_MAX:
            break
    return out


def feedback_comment(value):
    """把可选 `comment` 规范成 str：非字符串按空处理，去首尾空白后限长。"""
    if not isinstance(value, str):
        return ""
    return value.strip()[:FEEDBACK_COMMENT_LEN_MAX]


# 1.5.3：手改助记锁定。锁定的键必须同时含「词」和「书」——同一个 wordId 在
# 不同书里是不同的卡，按词单独锁会误锁到别的书上去。
LOCK_KEY_SEP = "|"


def _lock_part(value):
    """锁键的一段：整数按十进制，字符串去空白；bool/None 一律空串（JSON 的 true 不是 ID）。"""
    if value is None or isinstance(value, bool):
        return ""
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()[:64]


def lock_key(word_id, book_id):
    return LOCK_KEY_SEP.join((_lock_part(word_id), _lock_part(book_id)))


class Store(QueueOperations, FeedbackOperations, CompletionOperations, ResultOperations):
    _api = sys.modules[__name__]

    def __init__(self, data_dir):
        self.data_dir = data_dir
        self.queue_path = os.path.join(data_dir, "queue.json")
        self.state_path = os.path.join(data_dir, "state.json")
        self.results_dir = os.path.join(data_dir, "results")
        # 1.4.6：卡背 👍/👎 只追加、不改队列；按 clientId 幂等去重。
        self.feedback_path = os.path.join(data_dir, "feedback.jsonl")
        # v8：单词复习的 retained/lapsed 信号，落盘做幂等与冷却。
        self.memory_signals_path = os.path.join(data_dir, "memory_signals.json")
        # 1.5.3：手改助记锁定表。锁了的词不许被闲时洗词/批量重写覆盖。
        self.locks_path = os.path.join(data_dir, "locks.json")
        self._locks = None
        self._memory_signals = {}
        self._memory_cooldowns = {}
        self._feedback_ids = None
        self.items = []
        self.accepted_mark_ids = set()
        self.results = {}
        self.seq = 0
        self.stream_id = uuid.uuid4().hex
        self._closing = False
        self._lock = threading.RLock()
        self._cond = threading.Condition(self._lock)

    # ---------------------------------------------------------- 生命周期

    def load(self):
        os.makedirs(self.results_dir, exist_ok=True)
        queue = _read_json(self.queue_path)
        if isinstance(queue, dict) and isinstance(queue.get("items"), list):
            self.items = [it for it in queue["items"] if isinstance(it, dict) and it.get("clientId")]
        saved_ids = queue.get("acceptedMarkIds") if isinstance(queue, dict) else None
        if isinstance(saved_ids, list):
            self.accepted_mark_ids.update(key for key in saved_ids if isinstance(key, str) and key)
        self.accepted_mark_ids.update(it["clientId"] for it in self.items)
        state = _read_json(self.state_path)
        stored_seq = state.get("seq") if isinstance(state, dict) else None
        stored_stream = state.get("streamId") if isinstance(state, dict) else None
        if type(stored_seq) is int and stored_seq >= 0:
            self.seq = stored_seq
            if isinstance(stored_stream, str) and re.fullmatch(r"[0-9a-f]{32}", stored_stream):
                self.stream_id = stored_stream
        signals = _read_json(self.memory_signals_path)
        if isinstance(signals, dict):
            saved_signals = signals.get("signals")
            if isinstance(saved_signals, dict):
                self._memory_signals = {
                    str(key): value for key, value in saved_signals.items()
                    if isinstance(value, dict)
                }
            saved_cooldowns = signals.get("cooldowns")
            if isinstance(saved_cooldowns, dict):
                self._memory_cooldowns = {
                    str(key): value for key, value in saved_cooldowns.items()
                    if isinstance(value, str) and value
                }
        # 只有未被替换的旧 running 任务才接续；过期任务不再占工位。
        self._retire_superseded(("pending", "running", "retry_wait"))
        for item in self.items:
            if item.get("state") == "running":
                item["state"] = "pending"
                item["note"] = "重启接续"
        for name in sorted(os.listdir(self.results_dir)):
            if not name.endswith(".json"):
                continue
            record = _read_json(os.path.join(self.results_dir, name))
            if isinstance(record, dict) and record.get("word"):
                self.results[record["word"]] = record
                for entry in record.get("entries") or ():
                    if not isinstance(entry, dict):
                        continue
                    seq = entry.get("seq")
                    if type(seq) is int:
                        self.seq = max(self.seq, seq)
                    client_id = entry.get("clientId")
                    if isinstance(client_id, str) and client_id:
                        self.accepted_mark_ids.add(client_id)
        for item in self.items:
            seq = item.get("resultSeq")
            if type(seq) is int:
                self.seq = max(self.seq, seq)
        if stored_stream != self.stream_id or stored_seq != self.seq:
            self._save_state()
        if self.items or self.accepted_mark_ids:
            self._save_queue()
        return self

    def close(self):
        with self._cond:
            self._closing = True
            self._cond.notify_all()

    @property
    def closed(self):
        with self._lock:
            return self._closing

    def _find(self, client_id):
        for item in self.items:
            if item["clientId"] == client_id:
                return item
        return None

    def _entry_for(self, client_id):
        """这条标记产出的结果（同一 clientId 正常情况下只有一条）。"""
        hits = [
            entry
            for record in self.results.values()
            for entry in record["entries"]
            if entry.get("clientId") == client_id
        ]
        if not hits:
            return None
        return max(hits, key=lambda e: e.get("seq") or 0)

    def _next_seq(self):
        self.seq += 1
        self._save_state()
        return self.seq

    def _save_state(self):
        _write_json(self.state_path, {
            "seq": self.seq, "streamId": self.stream_id, "updatedAt": _now(),
        })

    def _count_today(self, status):
        today = datetime.now().astimezone().date()
        total = 0
        for record in self.results.values():
            for entry in record["entries"]:
                if entry.get("status") != status:
                    continue
                if str(entry.get("updatedAt", ""))[:10] == today.isoformat():
                    total += 1
        return total

    def _save_queue(self):
        # Write queue + receipt IDs in one atomic replace. Coalescing or
        # pruning a queue item must never make that request eligible again.
        _write_json(self.queue_path, {
            "updatedAt": _now(),
            "items": self.items,
            "acceptedMarkIds": sorted(self.accepted_mark_ids),
        })

    def _save_word(self, word):
        record = self.results.get(word)
        if record is not None:
            _write_json(os.path.join(self.results_dir, result_name(word)), record)

    def _prune(self):
        keep, dropped = [], 0
        # 倒着扫：先保住最新完成的 KEEP_FINISHED 条，再把更早的丢掉
        for item in reversed(self.items):
            if item.get("state") in ("pending", "running", "retry_wait"):
                keep.append(item)
            elif dropped < KEEP_FINISHED:
                keep.append(item)
                dropped += 1
        keep.reverse()
        if len(keep) != len(self.items):
            self.items = keep

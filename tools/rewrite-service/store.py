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
import threading
import time
import uuid
from datetime import datetime, timedelta

import providers

KEEP_FINISHED = 500  # 队列只留最近这么多条已完成的，防止一年后扫盘变慢
KEEP_OK = 5  # 每词最多 5 版（spec §4.4）
KEEP_FAIL = 3
MEMORY_COOLDOWN_SECONDS = 30 * 60  # lapsed 同词同方法 30 分钟冷却
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")

# 质量不合格/传输失败后的退避：不设总次数，间隔逐级拉长，避免把网关打穿。
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
        if entry.get("status") == "ok":
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
                 if e.get("status") == "ok" and same_book_scope(e, item)), None)


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
    if entry.get("reuseOf") is not None:
        out["reuseOf"] = entry.get("reuseOf")
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


class Store:
    def __init__(self, data_dir):
        self.data_dir = data_dir
        self.queue_path = os.path.join(data_dir, "queue.json")
        self.state_path = os.path.join(data_dir, "state.json")
        self.results_dir = os.path.join(data_dir, "results")
        # 1.4.6：卡背 👍/👎 只追加、不改队列；按 clientId 幂等去重。
        self.feedback_path = os.path.join(data_dir, "feedback.jsonl")
        # v8：单词复习的 retained/lapsed 信号，落盘做幂等与冷却。
        self.memory_signals_path = os.path.join(data_dir, "memory_signals.json")
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

    # ---------------------------------------------------------- 队列

    def add_marks(self, marks):
        accepted, rejected = [], []
        with self._cond:
            for raw in marks:
                if not isinstance(raw, dict):
                    rejected.append({"clientId": "", "reason": "标记不是对象"})
                    continue
                client_id = str(raw.get("clientId") or "").strip()
                word = str(raw.get("word") or "").strip()
                model = str(raw.get("model") or "").strip()
                if not client_id:
                    rejected.append({"clientId": "", "reason": "缺 clientId"})
                elif not word:
                    rejected.append({"clientId": client_id, "reason": "缺 word"})
                elif model not in providers.MODELS:
                    rejected.append({"clientId": client_id, "reason": "unknown model"})
                elif client_id in self.accepted_mark_ids:
                    accepted.append(client_id)
                else:
                    self._enqueue(raw, client_id, word, model)
                    self.accepted_mark_ids.add(client_id)
                    accepted.append(client_id)
            self._save_queue()
            self._cond.notify_all()
        return accepted, rejected

    # ------------------------------------------------------ 卡背反馈（1.4.6）

    def add_feedback(self, items):
        """收手机端卡背的 👍/👎：幂等追加到 data/feedback.jsonl。

        反馈是旁路数据，只追加、不碰队列。手机断网会把同一批重推，
        所以按 clientId 去重：重复的算「已接受」，但不会再写一行。
        返回 (accepted, rejected)，形状和 add_marks 保持一致。
        """
        accepted, rejected = [], []
        with self._lock:
            self._load_feedback_ids()
            os.makedirs(self.data_dir, exist_ok=True)
            with open(self.feedback_path, "a", encoding="utf-8") as fh:
                for raw in items:
                    if not isinstance(raw, dict):
                        rejected.append({"clientId": "", "reason": "反馈不是对象"})
                        continue
                    client_id = str(raw.get("clientId") or "").strip()
                    rating = str(raw.get("rating") or "").strip().lower()
                    if not client_id:
                        rejected.append({"clientId": "", "reason": "缺 clientId"})
                        continue
                    if rating not in ("up", "down"):
                        rejected.append({"clientId": client_id, "reason": "rating 只能是 up/down"})
                        continue
                    if client_id in self._feedback_ids:
                        accepted.append(client_id)  # 幂等：之前收过了
                        continue
                    record = dict(raw)
                    record["rating"] = rating
                    record["receivedAt"] = _now()
                    # print 自带换行：不手写转义，避免不同编辑器把换行写歪
                    print(json.dumps(record, ensure_ascii=False), file=fh)
                    fh.flush()
                    self._feedback_ids.add(client_id)
                    accepted.append(client_id)
        return accepted, rejected

    # ------------------------------------------------------ 复习记忆信号（v8）

    def record_memory_signal(self, payload):
        """记录一条 retained/lapsed 复习信号。

        幂等键：clientId + word + bookId + method + event；同一事件只生效一次。
        lapsed 额外加 30 分钟冷却（同词同方法），避免手机端补传把一次遗忘记成多次。
        返回 (applied, reason)。
        """
        client_id = str(payload.get("clientId") or "").strip()
        word = str(payload.get("word") or "").strip()
        book_id = str(payload.get("bookId") or "0").strip()
        method = str(payload.get("method") or "").strip()
        event = str(payload.get("event") or "").strip().lower()
        key = "|".join((client_id, word, book_id, method, event))
        with self._lock:
            if key in self._memory_signals:
                return False, "重复信号，已生效过"
            if event == "lapsed":
                cooldown_key = word + "|" + method
                last = self._memory_cooldowns.get(cooldown_key)
                if last:
                    try:
                        last_at = datetime.fromisoformat(last)
                        if last_at.tzinfo is None:
                            last_at = last_at.replace(tzinfo=datetime.now().astimezone().tzinfo)
                        elapsed = (datetime.now().astimezone() - last_at).total_seconds()
                        if elapsed < MEMORY_COOLDOWN_SECONDS:
                            return False, "同词同方法 30 分钟冷却中"
                    except (TypeError, ValueError):
                        pass
            record = {
                "clientId": client_id,
                "word": word,
                "bookId": book_id,
                "method": method,
                "event": event,
                "stability": payload.get("stability"),
                "difficulty": payload.get("difficulty"),
                "lapses": payload.get("lapses"),
                "receivedAt": _now(),
            }
            self._memory_signals[key] = record
            if event == "lapsed":
                self._memory_cooldowns[word + "|" + method] = record["receivedAt"]
            self._save_memory_signals()
        return True, ""

    def _save_memory_signals(self):
        _write_json(self.memory_signals_path, {
            "signals": self._memory_signals,
            "cooldowns": self._memory_cooldowns,
        })

    def _load_feedback_ids(self):
        """第一次收到反馈时扫一遍文件，把已有 clientId 读进内存。"""
        if self._feedback_ids is not None:
            return
        ids = set()
        try:
            with open(self.feedback_path, "r", encoding="utf-8-sig") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        row = json.loads(line)
                    except ValueError:
                        continue
                    if not isinstance(row, dict):
                        continue
                    client_id = str(row.get("clientId") or "").strip()
                    if client_id:
                        ids.add(client_id)
        except OSError:
            pass
        self._feedback_ids = ids

    def _enqueue(self, raw, client_id, word, model):
        book_id, book = book_scope(raw)
        for item in self.items:
            if item["word"] != word or not same_book_scope(item, raw):
                continue
            if item.get("state") in ("pending", "retry_wait", "stopped"):
                # 同词未处理/等重试的标记：新的覆盖旧的（spec §3.1）
                self.items.remove(item)
                break
            if item.get("state") == "running":
                # 已经在跑了没法撤回，跑完直接丢结果，让新的这条说了算
                item["superseded"] = True
        self.items.append(
            {
                "clientId": client_id,
                "word": word,
                "phonetic": str(raw.get("phonetic") or ""),
                "pos": str(raw.get("pos") or ""),
                "meaning": str(raw.get("meaning") or ""),
                "instruction": str(raw.get("instruction") or ""),
                "bookId": book_id,
                "book": book,
                # 改稿/评委要看到完整上一版（推导 3–5 步）；App 端上报的是按行拆分的全文，
                # 旧的 [:3] 会把第 4、5 步截掉，评委只看到前 3 步。（1.4.7.1）
                "currentApproach": [str(x) for x in (raw.get("currentApproach") or [])][:5],
                "model": model,
                "markedAt": str(raw.get("markedAt") or "") or _now(),
                "state": "pending",
                "stage": "queued",
                "attempts": 0,
                "error": "",
                "createdAt": _now(),
                "updatedAt": _now(),
                # 1.4.7.2：持续重写/停止/晚到结果都靠这三个字段
                "generation": 0,
                "failures": 0,
                "nextRunAt": "",
                "lastReason": "",
                "bestApproach": [],
                "bestScores": {},
                "qualityRejected": False,
                # G01：前台用户重写默认 100；离线批处理可显式传 10。
                "priority": max(0, min(100, _priority_value(raw.get("priority")))),
            }
        )

    def _retire_superseded(self, states):
        """收尾无工人持有的过期任务，不生成结果或移除接收回执。"""
        changed = False
        for item in self.items:
            if item.get("superseded") and item.get("state") in states:
                item.update(state="done", stage="done", error="", nextRunAt="",
                            note="被同词新标记覆盖，取消后续运行", updatedAt=_now())
                changed = True
        if changed:
            self._prune()
        return changed

    def _promote_retries_locked(self):
        """到点的 retry_wait 放回 pending；没写 nextRunAt 的旧数据立刻可跑。"""
        changed = self._retire_superseded(("pending", "retry_wait"))
        now = _now()
        for item in self.items:
            if item.get("state") != "retry_wait":
                continue
            due = str(item.get("nextRunAt") or "")
            if not due or due <= now:
                item["state"] = "pending"
                item["stage"] = "queued"
                item["nextRunAt"] = ""
                item["updatedAt"] = now
                changed = True
        if changed:
            self._save_queue()

    def claim(self, timeout=60):
        """取一条到点的活；没有就等，等不到继续等，关门才返回 None。

        公平性：先按 nextRunAt（重试到点时间）/ createdAt 排序，再取第一条；
        单个词失败不会把整个队列堵死（spec §2.2）。
        """
        with self._cond:
            while not self._closing:
                self._promote_retries_locked()
                ready = [item for item in self.items if item.get("state") == "pending"]
                if ready:
                    item = min(
                        ready,
                        key=lambda x: (
                            -_priority_value(x.get("priority"), 100),
                            str(x.get("nextRunAt") or ""),
                            str(x.get("createdAt") or ""),
                        ),
                    )
                    item["state"] = "running"
                    item["stage"] = "generate"
                    item["attempts"] = int(item.get("attempts") or 0) + 1
                    item["startedAt"] = _now()
                    item["updatedAt"] = item["startedAt"]
                    self._save_queue()
                    return dict(item)
                self._cond.wait(timeout)
            return None

    def set_stage(self, client_id, stage):
        """阶段推进：只认固定取值，找不到这条记录就安静跳过。"""
        if stage not in STAGES:
            return False
        with self._cond:
            live = self._find(client_id)
            if live is None:
                return False
            live["stage"] = stage
            live["updatedAt"] = _now()
            self._save_queue()
            self._cond.notify_all()
            return True

    # ---------------------------------------------------------- 收尾

    def _same_generation(self, item, live):
        """晚到的结果/停止后回来的结果：代次不一致就不许写。"""
        return int(item.get("generation") or 0) == int(live.get("generation") or 0)

    def finish_ok(self, item, approach, meta=None):
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None:
                return None
            if not self._same_generation(item, live):
                return None
            if live.get("superseded"):
                live.update(state="done", stage="done", note="被同词新标记覆盖，结果丢弃", updatedAt=_now())
                self._save_queue()
                self._cond.notify_all()
                return None
            record = self.results.setdefault(live["word"], {"word": live["word"], "entries": []})
            prev = _current(record)
            own_prev = _current_in_book(record, live)
            unverified = bool(meta.get("unverified"))
            reason = str(meta.get("reason") or "")[:400]
            entry = {
                "seq": self._next_seq(),
                "word": live["word"],
                "bookId": live.get("bookId", 0),
                "book": live.get("book", ""),
                "rev": ((prev or {}).get("rev") or 0) + 1,
                "approach": list(approach),
                "method": str(meta.get("method") or ""),  # 六招之一，手机端据此显示「联想 · 词根」标签
                "prev": list(live.get("currentApproach") or (own_prev or {}).get("approach") or []),
                "updatedAt": _now(),
                "status": "ok",
                "model": live["model"],
                "clientId": live["clientId"],
                "scores": meta.get("scores") or {},
                "attempts": meta.get("attempts", 1),
                "unverified": unverified,
                "reason": reason,
                # 历史复用指纹：只有提示词/用户原话/模型完全一致的新结果才允许复用。
                "promptHash": str(meta.get("promptHash") or ""),
                "instructionHash": str(meta.get("instructionHash") or ""),
                "reused": bool(meta.get("reused")),
                "reuseOf": meta.get("reuseOf"),
            }
            record["entries"] = _trim(record["entries"] + [entry])
            self._save_word(live["word"])
            live.update(
                state="done",
                stage="done",
                error="",
                updatedAt=entry["updatedAt"],
                resultSeq=entry["seq"],
                unverified=unverified,
                reason=reason,
                nextRunAt="",
                qualityRejected=False,
                failures=0,
                lastReason="",
                bestApproach=[],
                bestScores={},
            )
            self._prune()
            self._save_queue()
            self._cond.notify_all()
            return entry

    def finish_fail(self, item, error):
        with self._cond:
            live = self._find(item["clientId"])
            if live is None:
                return None
            if not self._same_generation(item, live):
                return None
            # 与 finish_ok 对称：被同词新标记覆盖的旧任务，失败也不许写结果，
            # 否则面板/手机会收到一条本不该存在的 failed。
            if live.get("superseded"):
                live.update(state="failed", stage="failed", note="被同词新标记覆盖，失败记录丢弃", updatedAt=_now())
                self._save_queue()
                self._cond.notify_all()
                return None
            record = self.results.setdefault(live["word"], {"word": live["word"], "entries": []})
            current = _current(record)
            own_current = _current_in_book(record, live)
            entry = {
                "seq": self._next_seq(),
                "word": live["word"],
                "bookId": live.get("bookId", 0),
                "book": live.get("book", ""),
                "rev": (current or {}).get("rev") or 0,
                "approach": [],
                "method": "",
                "prev": list(live.get("currentApproach") or (own_current or {}).get("approach") or []),
                "updatedAt": _now(),
                "status": "failed",
                "model": live["model"],
                "clientId": live["clientId"],
                "error": error[:400],
            }
            record["entries"] = _trim(record["entries"] + [entry])
            self._save_word(live["word"])
            live.update(state="failed", stage="failed", error=error[:400], updatedAt=entry["updatedAt"],
                        unverified=False, reason="")
            self._prune()
            self._save_queue()
            self._cond.notify_all()
            return entry

    def finish_quality_rejected(self, item, approach, meta=None):
        """质量没过：不交付、不写 results，只留最佳候选并排下一次。

        这是 1.4.7.2 的核心语义变化：以前 unverified 也算 done，用户会永远卡在
        「两轮都不过」的稿子上；现在失败进 retry_wait，按指数退避一直重试，直到
        某次通过或用户停止。
        """
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None or not self._same_generation(item, live):
                return None
            if live.get("superseded"):
                live.update(state="done", stage="done", note="被同词新标记覆盖，质量结果丢弃", updatedAt=_now())
                self._save_queue()
                self._cond.notify_all()
                return None
            failures = int(live.get("failures") or 0) + 1
            reason = str(meta.get("reason") or "质量未过")[:400]
            if approach:
                live["bestApproach"] = list(approach)
                live["bestScores"] = meta.get("scores") or {}
            next_at = _retry_at(failures)
            live.update(
                state="retry_wait",
                stage="judge",
                failures=failures,
                lastReason=reason,
                error=reason,
                nextRunAt=next_at,
                qualityRejected=True,
                updatedAt=_now(),
            )
            self._save_queue()
            self._cond.notify_all()
            return {"state": "retry_wait", "failures": failures, "nextRunAt": next_at}

    def finish_retry(self, item, error, meta=None):
        """传输/格式/限流等可恢复错误：也走退避，不把词永久钉成 failed。"""
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None or not self._same_generation(item, live):
                return None
            if live.get("superseded"):
                live.update(state="failed", stage="failed", note="被同词新标记覆盖，失败记录丢弃", updatedAt=_now())
                self._save_queue()
                self._cond.notify_all()
                return None
            failures = int(live.get("failures") or 0) + 1
            reason = str(error or "可恢复错误")[:400]
            next_at = _retry_at(failures)
            live.update(
                state="retry_wait",
                stage="generate",
                failures=failures,
                lastReason=reason,
                error=reason,
                nextRunAt=next_at,
                qualityRejected=False,
                updatedAt=_now(),
            )
            self._save_queue()
            self._cond.notify_all()
            return {"state": "retry_wait", "failures": failures, "nextRunAt": next_at}

    def release_claim(self, item, reason="工人异常"):
        """工人本轮异常：把还处于 running 的认领退回重试，杜绝僵尸 running。

        只在 generation 未变且记录仍是 running 时生效：本轮已经 finish_ok /
        finish_retry / 被 stop 的任务不能被一次迟到的释放覆盖。attempts 保留，
        面板上仍能看到真实尝试次数；nextRunAt 用短退避，让别的工人很快接手。
        """
        with self._cond:
            live = self._find((item or {}).get("clientId"))
            if live is None or not self._same_generation(item, live):
                return None
            if live.get("state") != "running":
                return None
            reason = str(reason or "工人异常")[:400]
            next_at = _seconds_from_now(RELEASE_DELAY_SECONDS)
            live.update(
                state="retry_wait",
                stage="",
                error=reason,
                lastReason=reason,
                nextRunAt=next_at,
                updatedAt=_now(),
            )
            self._save_queue()
            self._cond.notify_all()
            return {"state": "retry_wait", "nextRunAt": next_at}

    def stop(self, word="", client_id=""):
        """停止：待运行、等重试、运行中的都标 stopped；运行中的靠 generation+1 丢晚到结果。"""
        with self._cond:
            hit = 0
            for item in self.items:
                if item.get("state") not in ("pending", "running", "retry_wait"):
                    continue
                if client_id and item["clientId"] != client_id:
                    continue
                if word and item["word"] != word:
                    continue
                item["generation"] = int(item.get("generation") or 0) + 1
                item.update(
                    state="stopped",
                    stage="failed",
                    error="用户停止",
                    lastReason="用户停止",
                    nextRunAt="",
                    qualityRejected=False,
                    failures=0,
                    bestApproach=[],
                    bestScores={},
                    updatedAt=_now(),
                )
                hit += 1
            if hit:
                self._save_queue()
                self._cond.notify_all()
            return hit

    def retry(self, word, client_id=""):
        """把这个词的失败项重新排进队列（保留原指令、模型与已尝试次数）。

        同词可能有多条失败标记：给了 clientId 就精确命中那一条，否则退回按词取最近一条。
        """
        with self._cond:
            hit = None
            for item in reversed(self.items):
                if item.get("superseded") or item.get("state") not in ("failed", "retry_wait", "stopped"):
                    continue
                if client_id:
                    if item["clientId"] == client_id:
                        hit = item
                        break
                    continue
                if item["word"] == word:
                    hit = item
                    break
            if hit is None:
                return False
            hit.update(
                state="pending",
                stage="queued",
                error="",
                nextRunAt="",
                failures=0,
                bestApproach=[],
                bestScores={},
                qualityRejected=False,
                updatedAt=_now(),
            )
            hit.pop("unverified", None)
            hit.pop("reason", None)
            self._save_queue()
            self._cond.notify_all()
            return True

    # ---------------------------------------------------------- 读取

    def status(self):
        with self._lock:
            counts = {"pending": 0, "running": 0, "retry_wait": 0, "done": 0, "failed": 0, "stopped": 0}
            active, finished = [], []
            for item in self.items:
                state = item.get("state", "pending")
                counts[state] = counts.get(state, 0) + 1
                row = {
                    "clientId": item["clientId"],
                    "word": item["word"],
                    "model": item["model"],
                    "state": state,
                    "stage": stage_of(item),
                    "error": item.get("error") or item.get("note") or "",
                    "updatedAt": item.get("updatedAt", ""),
                    "unverified": bool(item.get("unverified")),
                    "reason": str(item.get("reason") or ""),
                    "failures": int(item.get("failures") or 0),
                    "nextRunAt": item.get("nextRunAt") or "",
                }
                (active if state in ("pending", "running", "retry_wait") else finished).append(row)
            finished.sort(key=lambda r: r["updatedAt"], reverse=True)
            recent = sorted(
                (e for rec in self.results.values() for e in rec["entries"]),
                key=lambda e: e["seq"],
                reverse=True,
            )[:8]
            return {
                "pending": counts.get("pending", 0),
                "running": counts.get("running", 0),
                "retryWait": counts.get("retry_wait", 0),
                "stopped": counts.get("stopped", 0),
                "done": counts.get("done", 0),
                "failed": counts.get("failed", 0),
                "items": (active + finished)[:30],
                "todayDone": self._count_today("ok"),
                "todayFailed": self._count_today("failed"),
                "seq": self.seq,
                "recent": [_public(e) for e in recent],
            }

    def results_page(self, since, limit):
        with self._lock:
            entries = [e for rec in self.results.values() for e in rec["entries"] if e["seq"] > since]
            entries.sort(key=lambda e: e["seq"])
            page = entries[:limit]
            return {
                "results": [_public(e) for e in page],
                "nextSince": page[-1]["seq"] if page else min(since, self.seq),
                "more": len(entries) > limit,
                "streamId": self.stream_id,
                "highWater": self.seq,
            }

    def word_history(self, word):
        with self._lock:
            record = self.results.get(word) or {"entries": []}
            return [_public(e) for e in record["entries"]]

    def find_reusable_result(self, item, prompt_hash, instruction_hash):
        """同一词、同一本书、同一模型、同一提示词、同一用户原话的最近成品。

        只复用 status=ok 且非 unverified 的 entry；任何一项指纹不同都当没命中，
        宁可不复用，也不能把旧提示词/旧指令的稿子当成新结果交出去。
        """
        if not prompt_hash or not instruction_hash:
            return None
        word = str((item or {}).get("word") or "")
        if not word:
            return None
        want_book_id, want_book = book_scope(item)
        want_model = str((item or {}).get("model") or "")
        with self._lock:
            record = self.results.get(word) or {"entries": []}
            for entry in reversed(record.get("entries") or []):
                if entry.get("status") != "ok" or entry.get("unverified"):
                    continue
                if str(entry.get("model") or "") != want_model:
                    continue
                if str(entry.get("promptHash") or "") != prompt_hash:
                    continue
                if str(entry.get("instructionHash") or "") != instruction_hash:
                    continue
                entry_book_id, entry_book = book_scope(entry)
                # 精确书范围：知道 ID 就按 ID（同时知道书名时两者都要等），
                # 只剩书名按书名，两者都空只认 (0, "")。不能用「ID 或书名
                # 任一相等」——bookId=0 / book="" 的空值会跨书误命中。
                if want_book_id and want_book:
                    if entry_book_id != want_book_id or entry_book != want_book:
                        continue
                elif want_book_id:
                    if entry_book_id != want_book_id:
                        continue
                elif want_book:
                    if entry_book != want_book:
                        continue
                elif entry_book_id != 0 or entry_book:
                    continue
                found = dict(entry)
                found["approach"] = list(entry.get("approach") or [])
                return found
        return None

    def records_page(self, limit=50, offset=0, book=""):
        """重写记录页：每条标记一行，按最近更新倒序，附上成品分步思路与上一版。

        book 传书名只看那本；传 `__none__` 只看没标书目的（1.4.4 之前的老记录）。
        """
        with self._lock:
            rows = []
            for item in self.items:
                entry = self._entry_for(item["clientId"])
                ok = entry is not None and entry.get("status") == "ok"
                rows.append(
                    {
                        "word": item["word"],
                        "instruction": item.get("instruction") or "",
                        "book": item.get("book") or "",
                        "model": item["model"],
                        "stage": stage_of(item),
                        "state": item.get("state", "pending"),
                        "status": entry.get("status") if entry else item.get("state", "pending"),
                        "error": item.get("error") or item.get("note") or "",
                        "createdAt": item.get("createdAt", ""),
                        "updatedAt": item.get("updatedAt", ""),
                        "clientId": item["clientId"],
                        "seq": (entry or {}).get("seq"),
                        "rev": (entry or {}).get("rev"),
                        "attempts": int(
                            (entry or {}).get("attempts") or item.get("attempts") or 0
                        ),
                        "method": (entry or {}).get("method") or "",
                        "approach": list(entry["approach"]) if ok else [],
                        "prev": list((entry or {}).get("prev") or item.get("currentApproach") or []),
                        "unverified": bool((entry or item).get("unverified")),
                        "reason": str((entry or item).get("reason") or ""),
                    }
                )
            # 书目聚合按「全部记录」算，不看当前筛哪本 —— 否则选完一本，下拉里就只剩它自己
            counts = {}
            for row in rows:
                counts[row["book"]] = counts.get(row["book"], 0) + 1
            books = [
                {"name": name, "count": count}
                for name, count in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
            ]
            book = (book or "").strip()
            if book:
                wanted = "" if book == "__none__" else book
                rows = [row for row in rows if row["book"] == wanted]
            rows.sort(key=lambda r: (r["updatedAt"], r["clientId"]), reverse=True)
            page = rows[offset : offset + limit]
            return {
                "records": page,
                "total": len(rows),
                "limit": limit,
                "offset": offset,
                "more": offset + limit < len(rows),
                "books": books,
                "book": book,
            }

    # ---------------------------------------------------------- 内部

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

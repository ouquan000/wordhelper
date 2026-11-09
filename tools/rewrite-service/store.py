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
from datetime import datetime

import providers

KEEP_FINISHED = 500  # 队列只留最近这么多条已完成的，防止一年后扫盘变慢
KEEP_OK = 5  # 每词最多 5 版（spec §4.4）
KEEP_FAIL = 3
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")


def _now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def _write_json(path, data):
    """先写 .tmp 再替换：手机随时可能来拉，不能读到半份。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    os.replace(tmp, path)


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


def _trim(entries):
    ok = [e for e in entries if e.get("status") == "ok"][-KEEP_OK:]
    fail = [e for e in entries if e.get("status") != "ok"][-KEEP_FAIL:]
    return sorted(ok + fail, key=lambda e: e["seq"])


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
        self._feedback_ids = None
        self.items = []
        self.results = {}
        self.seq = 0
        self._closing = False
        self._lock = threading.RLock()
        self._cond = threading.Condition(self._lock)

    # ---------------------------------------------------------- 生命周期

    def load(self):
        os.makedirs(self.results_dir, exist_ok=True)
        queue = _read_json(self.queue_path)
        if isinstance(queue, dict) and isinstance(queue.get("items"), list):
            self.items = [it for it in queue["items"] if isinstance(it, dict) and it.get("clientId")]
        state = _read_json(self.state_path)
        if isinstance(state, dict) and isinstance(state.get("seq"), int):
            self.seq = state["seq"]
        # 上次没跑完的（进程被杀）一律退回 pending，别让它们永远卡在 running
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
        if self.items:
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
            seen = {it["clientId"] for it in self.items}
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
                elif client_id in seen:
                    accepted.append(client_id)  # 幂等：同一个 clientId 只入队一次
                else:
                    self._enqueue(raw, client_id, word, model)
                    seen.add(client_id)
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
        for item in self.items:
            if item["word"] != word:
                continue
            if item.get("state") == "pending":
                # 同词未处理的标记：新的覆盖旧的（spec §3.1）
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
                # 书目在手机上，这边只转存名字：记录页靠它筛选（1.4.4）
                "book": str(raw.get("book") or "").strip()[:60],
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
            }
        )

    def claim(self, timeout=60):
        """取一条待处理的活；没有就等，等不到继续等，关门才返回 None。"""
        with self._cond:
            while not self._closing:
                for item in self.items:
                    if item.get("state") == "pending":
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

    def finish_ok(self, item, approach, meta=None):
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None:
                return None
            if live.get("superseded"):
                live.update(state="done", stage="done", note="被同词新标记覆盖，结果丢弃", updatedAt=_now())
                self._save_queue()
                self._cond.notify_all()
                return None
            record = self.results.setdefault(live["word"], {"word": live["word"], "entries": []})
            prev = _current(record)
            unverified = bool(meta.get("unverified"))
            reason = str(meta.get("reason") or "")[:400]
            entry = {
                "seq": self._next_seq(),
                "word": live["word"],
                "rev": ((prev or {}).get("rev") or 0) + 1,
                "approach": list(approach),
                "method": str(meta.get("method") or ""),  # 六招之一，手机端据此显示「联想 · 词根」标签
                "prev": list((prev or {}).get("approach") or live.get("currentApproach") or []),
                "updatedAt": _now(),
                "status": "ok",
                "model": live["model"],
                "clientId": live["clientId"],
                "scores": meta.get("scores") or {},
                "attempts": meta.get("attempts", 1),
                "unverified": unverified,
                "reason": reason,
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
            # 与 finish_ok 对称：被同词新标记覆盖的旧任务，失败也不许写结果，
            # 否则面板/手机会收到一条本不该存在的 failed。
            if live.get("superseded"):
                live.update(state="failed", stage="failed", note="被同词新标记覆盖，失败记录丢弃", updatedAt=_now())
                self._save_queue()
                self._cond.notify_all()
                return None
            record = self.results.setdefault(live["word"], {"word": live["word"], "entries": []})
            current = _current(record)
            entry = {
                "seq": self._next_seq(),
                "word": live["word"],
                "rev": (current or {}).get("rev") or 0,
                "approach": [],
                "method": "",
                "prev": list((current or {}).get("approach") or live.get("currentApproach") or []),
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

    def retry(self, word, client_id=""):
        """把这个词的失败项重新排进队列（保留原指令、模型与已尝试次数）。

        同词可能有多条失败标记：给了 clientId 就精确命中那一条，否则退回按词取最近一条。
        """
        with self._cond:
            hit = None
            for item in reversed(self.items):
                if item.get("state") != "failed":
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
            hit.update(state="pending", stage="queued", error="", updatedAt=_now())
            hit.pop("superseded", None)
            hit.pop("unverified", None)
            hit.pop("reason", None)
            self._save_queue()
            self._cond.notify_all()
            return True

    # ---------------------------------------------------------- 读取

    def status(self):
        with self._lock:
            counts = {"pending": 0, "running": 0, "done": 0, "failed": 0}
            active, finished = [], []
            for item in self.items:
                state = item.get("state", "pending")
                counts[state] = counts.get(state, 0) + 1
                row = {
                    "word": item["word"],
                    "model": item["model"],
                    "state": state,
                    "stage": stage_of(item),
                    "error": item.get("error") or item.get("note") or "",
                    "updatedAt": item.get("updatedAt", ""),
                    "unverified": bool(item.get("unverified")),
                    "reason": str(item.get("reason") or ""),
                }
                (active if state in ("pending", "running") else finished).append(row)
            finished.sort(key=lambda r: r["updatedAt"], reverse=True)
            recent = sorted(
                (e for rec in self.results.values() for e in rec["entries"]),
                key=lambda e: e["seq"],
                reverse=True,
            )[:8]
            return {
                "pending": counts.get("pending", 0),
                "running": counts.get("running", 0),
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
                "nextSince": page[-1]["seq"] if page else since,
                "more": len(entries) > limit,
            }

    def word_history(self, word):
        with self._lock:
            record = self.results.get(word) or {"entries": []}
            return [_public(e) for e in record["entries"]]

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
                        "attempts": int(item.get("attempts") or 0),
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
        _write_json(self.state_path, {"seq": self.seq, "updatedAt": _now()})
        return self.seq

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
        _write_json(self.queue_path, {"updatedAt": _now(), "items": self.items})

    def _save_word(self, word):
        record = self.results.get(word)
        if record is not None:
            _write_json(os.path.join(self.results_dir, result_name(word)), record)

    def _prune(self):
        keep, dropped = [], 0
        # 倒着扫：先保住最新完成的 KEEP_FINISHED 条，再把更早的丢掉
        for item in reversed(self.items):
            if item.get("state") in ("pending", "running"):
                keep.append(item)
            elif dropped < KEEP_FINISHED:
                keep.append(item)
                dropped += 1
        keep.reverse()
        if len(keep) != len(self.items):
            self.items = keep

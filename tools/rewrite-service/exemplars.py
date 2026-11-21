#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""黄金正例 / 避雷反例池：给生成与润色提供少样本手感锚点。

数据来源：
- 正池：feedback.jsonl 里 rating=up 且通过 rules_hard 的真实卡片；
- 负池：feedback.jsonl 里 rating=down 且带用户吐槽原话的卡片，
        以及 queue.json 里质量被拒/失败且带原因的真实反例；
- 预置 3 条种子（monopoly / obscure / cathedral），保证冷启动就有正例可检索。

落盘 data/exemplars.jsonl，一行一条，原子写、按 id 与内容双重去重。
retrieve(method, k_pos=2, k_neg=1) 返回 {"pos": [...], "neg": [...]}：
同方法优先，不够就用全局正例/反例兜底；种子常驻，所以不会返回空导致 prompt 崩。
"""

from __future__ import annotations

import copy
import json
import os
import re
import tempfile
import threading
from datetime import datetime

import rules_hard
from profile import normalize_method

HERE = os.path.dirname(os.path.abspath(__file__))


def default_data_dir():
    return os.environ.get("REWRITE_DATA_DIR") or os.path.join(HERE, "data")


def _now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


# ------------------------------------------------------------------ 预置种子

SEEDS = (
    {
        "id": "seed:monopoly",
        "kind": "pos",
        "word": "monopoly",
        "method": "sound",
        "note": "摸拿不离：读音一跳，直接长出一个「摸到手里不放」的画面",
        "source": "seed",
        "derivation": [
            "① monopoly 一口气念下来像「摸拿不离」：摸到、拿到、不离开，尾音 li 正好收住。",
            "② 一个人把整条街的买卖摸到手、拿住不放，别人连摆摊的缝都插不进来。",
            "③ 买家只能找他 ——> 垄断；专营。",
        ],
    },
    {
        "id": "seed:obscure",
        "kind": "pos",
        "word": "obscure",
        "method": "sound",
        "note": "俺不救：声音糊在嗓子里，正好是「看不清、说不明」",
        "source": "seed",
        "derivation": [
            "① obscure 读快了像「俺不救」：声音含在嗓子眼里，听的人一头雾水。",
            "② 一句「俺不救」糊在耳边，话被遮住，谁也听不明白。",
            "③ 看不清、听不懂、被遮住 ——> 晦涩的；使模糊。",
        ],
    },
    {
        "id": "seed:cathedral",
        "kind": "pos",
        "word": "cathedral",
        "method": "shape",
        "note": "cat + he + dral 三块；拼写拆法与真读音分开提醒",
        "source": "seed",
        "derivation": [
            "① cathedral 拆成 cat + he + dral：cat 是猫，he 是他，dral 念「卓」。",
            "② 猫和他蹲在大教堂门口，dral 一响，两个人一起抬头看尖顶。",
            "③ 先记拼写 cat-he-dral，真读音是 /kəˈθiːdrəl/，别按字母硬念 ——> 大教堂。",
        ],
    },
)


def _lines(value):
    """list / 带换行的 str / 带 ①②③ 的单串 → 干净的行列表。"""
    if value is None:
        return []
    if isinstance(value, str):
        raw = value.replace("\r", "\n").split("\n")
        if len(raw) == 1 and raw[0].strip():
            raw = [x for x in re.split(r"(?=[①②③④⑤])", raw[0]) if x.strip()]
    else:
        raw = []
        for item in value:
            text = str(item or "")
            raw.extend(text.splitlines())
    out = []
    for text in raw:
        text = str(text or "").strip()
        if text:
            out.append(text)
    return out[:5]


def _compact(item, kind):
    return {
        "kind": kind,
        "word": str(item.get("word") or ""),
        "method": normalize_method(item.get("method")),
        "note": str(item.get("note") or "")[:160],
        "derivation": list(item.get("derivation") or []),
    }


def _feedback_positive(row):
    """rating=up 的真实卡片；只收 3–5 步且过得了硬拦截的。"""
    method = normalize_method(row.get("method"))
    if not method:
        return None
    derivation = _lines(
        row.get("approach") or row.get("derivation") or row.get("currentApproach")
    )
    if not (rules_hard.MIN_STEPS <= len(derivation) <= rules_hard.MAX_STEPS):
        return None
    if not rules_hard.check(derivation).ok:
        return None
    client_id = str(row.get("clientId") or "").strip()
    word = str(row.get("word") or "").strip()
    if not client_id or not word:
        return None
    return {
        "id": "feedback:%s" % client_id,
        "kind": "pos",
        "word": word,
        "method": method,
        "note": str(row.get("instruction") or "用户点赞的版本")[:160],
        "source": "feedback",
        "derivation": derivation,
        "createdAt": str(row.get("receivedAt") or row.get("createdAt") or ""),
    }


def _feedback_negative(row):
    """rating=down 且带用户吐槽原话的反例；没有原话就不收。"""
    method = normalize_method(row.get("method"))
    client_id = str(row.get("clientId") or "").strip()
    word = str(row.get("word") or "").strip()
    complaint = str(
        row.get("instruction") or row.get("comment") or row.get("reason") or ""
    ).strip()
    if not method or not client_id or not word or not complaint:
        return None
    derivation = _lines(
        row.get("approach") or row.get("derivation") or row.get("currentApproach")
    )
    if not derivation:
        return None
    return {
        "id": "feedback:%s" % client_id,
        "kind": "neg",
        "word": word,
        "method": method,
        "note": complaint[:160],
        "source": "feedback",
        "derivation": derivation,
        "createdAt": str(row.get("receivedAt") or row.get("createdAt") or ""),
    }


def _queue_negative(row):
    """queue.json 里质量被拒/失败、且留了原因和上一版的反例。"""
    if not isinstance(row, dict):
        return None
    client_id = str(row.get("clientId") or "").strip()
    word = str(row.get("word") or "").strip()
    method = normalize_method(row.get("method"))
    complaint = str(row.get("lastReason") or row.get("error") or row.get("note") or "").strip()
    derivation = _lines(row.get("currentApproach") or row.get("bestApproach"))
    state = str(row.get("state") or "")
    rejected = bool(row.get("qualityRejected")) or state in ("failed", "retry_wait")
    if not client_id or not word or not method or not complaint or not derivation or not rejected:
        return None
    return {
        "id": "queue:%s" % client_id,
        "kind": "neg",
        "word": word,
        "method": method,
        "note": complaint[:160],
        "source": "queue",
        "derivation": derivation,
        "createdAt": str(row.get("updatedAt") or row.get("createdAt") or ""),
    }


def _read_jsonl(path):
    rows = []
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict):
                    rows.append(row)
    except OSError:
        pass
    return rows


def _atomic_write_jsonl(path, rows):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".exemplars-", suffix=".tmp", dir=os.path.dirname(path) or ".")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def _signature(item):
    return (
        str(item.get("kind") or ""),
        normalize_method(item.get("method")),
        str(item.get("word") or "").strip().lower(),
        tuple(str(x).strip() for x in (item.get("derivation") or [])),
    )


class Exemplars:
    def __init__(self, data_dir=None, path=None):
        self.data_dir = data_dir or default_data_dir()
        self.path = path or os.path.join(self.data_dir, "exemplars.jsonl")
        self._lock = threading.RLock()
        self._items = []
        self.load()

    # ---------------------------------------------------------- 读写

    def load(self):
        with self._lock:
            rows = []
            seen_ids = set()
            for row in list(SEEDS) + _read_jsonl(self.path):
                item = dict(row)
                item_id = str(item.get("id") or "").strip()
                if not item_id or item_id in seen_ids:
                    continue
                if item.get("kind") not in ("pos", "neg"):
                    continue
                item["method"] = normalize_method(item.get("method"))
                if not item["method"]:
                    continue
                item["derivation"] = _lines(item.get("derivation"))
                if not item["derivation"]:
                    continue
                seen_ids.add(item_id)
                rows.append(item)
            self._items = rows
            changed = self.sync_sources(save=False)
            if changed or not os.path.exists(self.path):
                self._save_locked()
            return self

    def ensure(self):
        """确保磁盘上有一份种子（服务启动时调用；缺文件就写）。"""
        with self._lock:
            if not os.path.exists(self.path):
                self._save_locked()
            return self

    def _save_locked(self):
        _atomic_write_jsonl(self.path, self._items)

    def save(self):
        with self._lock:
            self._save_locked()
            return self

    def snapshot(self):
        with self._lock:
            items = copy.deepcopy(self._items)
        return {
            "path": self.path,
            "pos": sum(1 for x in items if x["kind"] == "pos"),
            "neg": sum(1 for x in items if x["kind"] == "neg"),
            "items": items,
        }

    # ---------------------------------------------------------- 入库

    def add(self, item):
        """加一条；正例必须过 rules_hard。返回 True 表示真的新增。"""
        with self._lock:
            candidate = dict(item or {})
            kind = str(candidate.get("kind") or "").strip()
            if kind not in ("pos", "neg"):
                raise ValueError("kind 只能是 pos/neg")
            candidate["kind"] = kind
            candidate["method"] = normalize_method(candidate.get("method"))
            candidate["derivation"] = _lines(candidate.get("derivation"))
            candidate["word"] = str(candidate.get("word") or "").strip()
            candidate["id"] = str(candidate.get("id") or "").strip()
            if not candidate["id"] or not candidate["method"] or not candidate["word"]:
                raise ValueError("缺少 id / method / word")
            if not candidate["derivation"]:
                raise ValueError("缺少 derivation")
            if kind == "pos" and not rules_hard.check(candidate["derivation"]).ok:
                raise ValueError("正例未通过 rules_hard")
            if any(x.get("id") == candidate["id"] for x in self._items):
                return False
            if any(_signature(x) == _signature(candidate) for x in self._items):
                return False
            self._items.append(candidate)
            self._save_locked()
            return True

    def ingest_feedback(self, items):
        """把 /rewrite/feedback 的原始 items 直接喂进来；按 id 幂等去重。"""
        added = 0
        with self._lock:
            for row in items or ():
                if not isinstance(row, dict):
                    continue
                rating = str(row.get("rating") or "").strip().lower()
                candidate = _feedback_positive(row) if rating == "up" else (
                    _feedback_negative(row) if rating == "down" else None
                )
                if not candidate:
                    continue
                if any(x.get("id") == candidate["id"] for x in self._items):
                    continue
                if any(_signature(x) == _signature(candidate) for x in self._items):
                    continue
                self._items.append(candidate)
                added += 1
            if added:
                self._save_locked()
        return added

    def sync_sources(self, save=True):
        """从 feedback.jsonl + queue.json 补捞真实样本；返回是否有新增。"""
        with self._lock:
            candidates = []
            for row in _read_jsonl(os.path.join(self.data_dir, "feedback.jsonl")):
                rating = str(row.get("rating") or "").strip().lower()
                candidate = _feedback_positive(row) if rating == "up" else (
                    _feedback_negative(row) if rating == "down" else None
                )
                if candidate:
                    candidates.append(candidate)
            queue = None
            try:
                with open(os.path.join(self.data_dir, "queue.json"), "r", encoding="utf-8-sig") as fh:
                    queue = json.load(fh)
            except (OSError, ValueError):
                queue = None
            if isinstance(queue, dict):
                for row in queue.get("items") or ():
                    candidate = _queue_negative(row)
                    if candidate:
                        candidates.append(candidate)
            added = 0
            for candidate in candidates:
                if any(x.get("id") == candidate["id"] for x in self._items):
                    continue
                if any(_signature(x) == _signature(candidate) for x in self._items):
                    continue
                self._items.append(candidate)
                added += 1
            if added and save:
                self._save_locked()
            return bool(added)

    # ---------------------------------------------------------- 检索

    def retrieve(self, method="", k_pos=2, k_neg=1):
        """同方法优先，全局兜底；返回 {"pos": ..., "neg": ..., "fallback": bool}。"""
        method = normalize_method(method)
        with self._lock:
            items = list(self._items)
        pos_pool = [x for x in items if x.get("kind") == "pos"]
        neg_pool = [x for x in items if x.get("kind") == "neg"]

        same_pos = [x for x in pos_pool if x.get("method") == method] if method else []
        pos = list(same_pos[: max(0, int(k_pos))])
        fallback = len(pos) < max(0, int(k_pos))
        if fallback:
            for item in pos_pool:
                if len(pos) >= max(0, int(k_pos)):
                    break
                if item not in pos:
                    pos.append(item)

        same_neg = [x for x in neg_pool if x.get("method") == method] if method else []
        neg = list(same_neg[: max(0, int(k_neg))])
        if len(neg) < max(0, int(k_neg)):
            for item in neg_pool:
                if len(neg) >= max(0, int(k_neg)):
                    break
                if item not in neg:
                    neg.append(item)

        return {
            "pos": [_compact(x, "pos") for x in pos],
            "neg": [_compact(x, "neg") for x in neg],
            "method": method,
            "fallback": fallback,
        }


# ------------------------------------------------------------------ 模块级默认实例

_instances = {}
_instances_lock = threading.Lock()


def get_exemplars(data_dir=None):
    data_dir = os.path.abspath(data_dir or default_data_dir())
    with _instances_lock:
        instance = _instances.get(data_dir)
        if instance is None:
            instance = Exemplars(data_dir)
            _instances[data_dir] = instance
        return instance


def retrieve(method="", k_pos=2, k_neg=1):
    return get_exemplars().retrieve(method, k_pos=k_pos, k_neg=k_neg)


def ingest_feedback(items):
    return get_exemplars().ingest_feedback(items)


def snapshot():
    return get_exemplars().snapshot()

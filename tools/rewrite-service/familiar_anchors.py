#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真熟词锚点（WAE-20261126-002 任务 A）。

从卡背同步文件里读出用户「已经背到滚瓜烂熟」的词：生成端可以自然借力，
评委端拿到一份动态白名单（这些词出现不算 has_unknown_part），硬规则也放行。

三条硬约束：
1. 只认同一本书（默认「六级记忆卡」）的行，别的书再熟也不拿来当锚点；
2. 稳定度优先看 fsrs_stability，老数据没这个字段才退回 reps / lapses；
3. 最新一条有效反馈是 down 的词一律不算「熟」，避免把刚被否掉的词塞回去。

整个模块只读：不写 sync.json、不改 config，任何缺失/损坏都静默降级为空。
"""

from __future__ import annotations

import json
import os
import re
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DEFAULT_BOOK = "六级记忆卡"
STABLE_MIN = 10.0
FALLBACK_REPS = 3
MAX_ANCHORS = 5
STEM_MIN = 4
MAX_EDITS = 2
CACHE_TTL = 300.0

# 纯语法尾巴不算「有效词干」，否则 create / celebrate 会因为 -ate 被硬凑成一对。
STOP_STEMS = frozenset((
    "tion", "sion", "ment", "ness", "able", "ible", "ical", "ious",
    "eous", "ance", "ence", "ated", "ates", "ting", "sive", "tive",
))

_LOCK = threading.Lock()
_CACHE = {"key": None, "at": 0.0, "index": {}}
_VOTE_CACHE = {"key": None, "at": 0.0, "words": frozenset()}


def config_path():
    override = str(os.environ.get("REWRITE_WORKER_CONFIG") or "").strip()
    return override or os.path.join(HERE, "config", "worker.json")


def data_dir():
    override = str(os.environ.get("REWRITE_DATA_DIR") or "").strip()
    return override or os.path.join(HERE, "data")


def sync_path():
    """只读 config/worker.json 里的 sync_path；相对路径按仓库根解析。"""
    try:
        with open(config_path(), "r", encoding="utf-8-sig") as fh:
            config = json.load(fh)
        raw = str((config or {}).get("sync_path") or "").strip()
    except (OSError, ValueError, TypeError):
        return ""
    if not raw:
        return ""
    return raw if os.path.isabs(raw) else os.path.join(ROOT, raw)


def _number(value, default=None):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number


def _book_of(key, row):
    book = str(row.get("b") or "").strip()
    if book:
        return book
    if isinstance(key, str) and "\u0000" in key:
        return key.split("\u0000", 1)[0].strip()
    return ""


def _read_rows(path):
    """返回 [(key, row)]；文件缺失/损坏/结构不对都当空，绝不抛给调用方。"""
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            payload = json.load(fh)
    except (OSError, ValueError):
        return []
    if not isinstance(payload, dict):
        return []
    rows = payload.get("rows")
    if isinstance(rows, dict):
        pairs = list(rows.items())
    elif isinstance(rows, list):
        pairs = [(None, row) for row in rows]
    else:
        return []
    return [(key, row) for key, row in pairs if isinstance(row, dict)]


def is_familiar(row):
    """真熟词判定：lapses 必须为 0；有稳定度就看稳定度，没有才退回 reps。"""
    if not isinstance(row, dict):
        return False
    lapses = _number(row.get("fsrs_lapses"), 0) or 0
    if lapses > 0:
        return False
    stability = _number(row.get("fsrs_stability"))
    if stability is not None and stability > 0:
        return stability >= STABLE_MIN
    return (_number(row.get("reps"), 0) or 0) >= FALLBACK_REPS


def _build_index(path):
    index = {}
    for key, row in _read_rows(path):
        if _book_of(key, row) != DEFAULT_BOOK:
            continue
        word = str(row.get("w") or "").strip()
        if not word or not is_familiar(row):
            continue
        name = word.casefold()
        index.setdefault(name, {
            "word": word,
            "key": name,
            "stability": _number(row.get("fsrs_stability"), 0.0) or 0.0,
            "reps": int(_number(row.get("reps"), 0) or 0),
        })
    return index


def index(refresh=False):
    """按 (路径, mtime) 缓存；文件没动就不重复解析。"""
    path = sync_path()
    if not path:
        return {}
    try:
        stat = os.stat(path)
        key = (path, stat.st_mtime_ns, stat.st_size)
    except OSError:
        return {}
    now = time.monotonic()
    with _LOCK:
        if (not refresh and _CACHE["key"] == key
                and now - _CACHE["at"] < CACHE_TTL):
            return dict(_CACHE["index"])
    built = _build_index(path)
    with _LOCK:
        _CACHE.update({"key": key, "at": now, "index": built})
    return dict(built)


def clear_cache():
    with _LOCK:
        _CACHE.update({"key": None, "at": 0.0, "index": {}})
        _VOTE_CACHE.update({"key": None, "at": 0.0, "words": frozenset()})


def _down_words():
    """最新有效反馈为 down 的词（casefold）。读不到就当没有，不影响熟词筛选。"""
    directory = data_dir()
    path = os.path.join(directory, "feedback.jsonl")
    try:
        stat = os.stat(path)
        key = (path, stat.st_mtime_ns, stat.st_size)
    except OSError:
        return frozenset()
    now = time.monotonic()
    with _LOCK:
        if _VOTE_CACHE["key"] == key and now - _VOTE_CACHE["at"] < CACHE_TTL:
            return _VOTE_CACHE["words"]
    words = set()
    try:
        import rewrite_protection

        votes = rewrite_protection.latest_votes(rewrite_protection.feedback_rows(directory))
        words = {word for word, (rank, _row) in votes.items() if not rank[1]}
    except Exception:  # noqa: BLE001 - 反馈坏了不能影响锚点
        words = set()
    result = frozenset(words)
    with _LOCK:
        _VOTE_CACHE.update({"key": key, "at": now, "words": result})
    return result


def _levenshtein(left, right, limit=MAX_EDITS):
    if left == right:
        return 0
    if abs(len(left) - len(right)) > limit:
        return limit + 1
    previous = list(range(len(right) + 1))
    for i, char_a in enumerate(left, 1):
        current = [i]
        for j, char_b in enumerate(right, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1,
                               previous[j - 1] + (char_a != char_b)))
        if min(current) > limit:
            return limit + 1
        previous = current
    return previous[-1]


def shared_stem(left, right):
    """共享的有效词干：同前缀 / 同后缀 / 一前一后，至少 4 个字母。"""
    for size in range(min(len(left), len(right)), STEM_MIN - 1, -1):
        for first, second in ((left, right), (right, left)):
            piece = first[:size]
            if piece in STOP_STEMS:
                continue
            if second.startswith(piece) or second.endswith(piece):
                return piece
    return ""


def _clean(word):
    return re.sub(r"[^0-9a-z]", "", str(word or "").strip().casefold())


def candidates(word, limit=MAX_ANCHORS, refresh=False):
    """目标词 W 的 0–5 个锚点：形近（编辑距离 ≤2）优先，其次共享词干。"""
    target = _clean(word)
    if len(target) < 3:
        return []
    rows = index(refresh)
    if not rows:
        return []
    blocked = _down_words()
    scored = []
    for key, meta in rows.items():
        if key == target or key in blocked:
            continue
        distance = _levenshtein(target, key)
        stem = shared_stem(target, key)
        if distance > MAX_EDITS and len(stem) < STEM_MIN:
            continue
        if distance > MAX_EDITS and len(key) < STEM_MIN:
            continue
        rank = (0 if distance <= MAX_EDITS else 1, distance, -len(stem),
                -meta["stability"], len(key), key)
        scored.append((rank, {
            "word": meta["word"],
            "key": key,
            "stability": meta["stability"],
            "distance": distance,
            "stem": stem,
        }))
    scored.sort(key=lambda pair: pair[0])
    return [item for _rank, item in scored[:limit]]


def prompt_block(word, items=None):
    """生成端提示：可借力，但不许硬用。没命中返回空串（零侵入）。"""
    rows = candidates(word) if items is None else items
    if not rows:
        return ""
    lines = ["【用户已背熟的可用锚点（可自然借力，无须硬用）】"]
    for item in rows:
        why = "形近" if item["distance"] <= MAX_EDITS else "同词干 " + item["stem"]
        lines.append("- %s（%s，稳定度 %.1f）" % (item["word"], why, item["stability"]))
    lines.append("只有真的顺口才借一个；借不上就按原思路写，别为用锚点硬拗句子。")
    return "\n".join(lines)


def judge_block(word):
    """评委端提示：动态真熟词白名单，出现这些词不算生僻零件。"""
    rows = candidates(word)
    if not rows:
        return ""
    lines = ["【动态真熟词白名单（用户已经背熟）】"]
    lines.extend("- " + item["word"] for item in rows)
    lines.append("这些词可以作锚点/对比词，也可以出现在拆解里；出现它们不构成 has_unknown_part。")
    return "\n".join(lines)


def whitelist(word):
    """给 rules_hard 用：该词命中时允许出现的熟词集合（casefold）。异常一律空集。"""
    try:
        return {item["key"] for item in candidates(word)}
    except Exception:  # noqa: BLE001 - 硬规则不能因为锚点坏了而误杀
        return set()

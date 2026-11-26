#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""活水正反例（WAE-20261126-002 任务 B①）。

exemplars.retrieve() 只负责「找得到」，这里负责「排得对、踢得掉」：
- 正例按时间衰减加权，近 7 天的新赞额外加权，旧赞不会永远压着新赞；
- 每个候选都要过一遍最新反馈仲裁：曾点赞、但更晚一次点踩推翻了它的
  （隔夜打脸），立刻从正例里踢出去，并降级成反例警示。

放在单独模块里，是为了不动 exemplars.py 的 479 行物理行数。
"""

from __future__ import annotations

import os
import time

import rewrite_protection

HERE = os.path.dirname(os.path.abspath(__file__))

RECENT_DAYS = 7.0
RECENT_BOOST = 1.6
HALF_LIFE_DAYS = 21.0
MAX_NEG = 2


def _stamp(item):
    for key in ("at", "createdAt", "updatedAt", "receivedAt"):
        value = rewrite_protection.timestamp(item.get(key))
        if value:
            return value
    return 0.0


def weight(item, now=None):
    """时间衰减权重：21 天半衰期；7 天内的新赞再乘 1.6。"""
    moment = _stamp(item if isinstance(item, dict) else {})
    if not moment:
        return 0.0
    now = time.time() if now is None else float(now)
    age_days = max(0.0, (now - moment) / 86400.0)
    score = 0.5 ** (age_days / HALF_LIFE_DAYS)
    return score * (RECENT_BOOST if age_days <= RECENT_DAYS else 1.0)


def _data_dir(directory=None):
    return directory or str(os.environ.get("REWRITE_DATA_DIR") or "").strip() or os.path.join(HERE, "data")


def _votes(directory):
    try:
        return rewrite_protection.latest_votes(
            rewrite_protection.feedback_rows(_data_dir(directory)))
    except Exception:  # noqa: BLE001 - 反馈坏了就按原顺序放行
        return {}


def _as_negative(item):
    row = dict(item)
    row["kind"] = "neg"
    note = str(item.get("note") or "").strip()
    warning = "曾被点赞，但更晚一次点踩推翻了（隔夜打脸）：这条手感别再复刻"
    row["note"] = warning + ("；" + note if note else "")
    row["flipped"] = True
    return row


def refine(data, data_dir=None, now=None):
    """返回新的 retrieve 结果：pos 重排 + 打脸项转 neg；不改原对象。"""
    if not isinstance(data, dict):
        return data
    votes = _votes(data_dir)
    moment = time.time() if now is None else float(now)
    kept, flipped = [], []
    for item in (data.get("pos") or []):
        if not isinstance(item, dict):
            continue
        word = str(item.get("word") or "").strip().casefold()
        vote = votes.get(word)
        if vote and not vote[0][1]:
            flipped.append(_as_negative(item))
            continue
        kept.append(item)
    fresh = []
    for item in (data.get("neg") or []):
        if not isinstance(item, dict):
            continue
        word = str(item.get("word") or "").strip().casefold()
        vote = votes.get(word)
        if vote and vote[0][1]:
            continue  # 用户后来改口点赞，这条不该再当雷区
        fresh.append(item)
    kept.sort(key=lambda item: -weight(item, moment))
    result = dict(data)
    result["pos"] = kept
    result["neg"] = (flipped + fresh)[:MAX_NEG]
    return result

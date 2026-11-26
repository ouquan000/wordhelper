#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""动态审美画像（WAE-20261126-002 任务 B②）。

把近期点赞/点踩蒸馏成 5–8 条可执行规则，存 `user_taste_profile.json`，
自动并入生成与评委提示；文件缺失时零侵入跳过，不影响任何老流程。

- rules：总注入不超过 300 字，最多 8 条；
- lockedRules：用户在界面上锁定的规则，蒸馏时原样保留、永远排在最前；
- updatedAt / sourceFeedbackCount：这次画像基于哪批反馈、什么时候更新。

蒸馏默认走 WB 8788 的低价模型 ds-v41-flash；单测一律传 fake provider，绝不真调模型。
"""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone

import rewrite_protection

HERE = os.path.dirname(os.path.abspath(__file__))
PROFILE_NAME = "user_taste_profile.json"
DEFAULT_MODEL = "ds-v41-flash"
MIN_RULES = 5
MAX_RULES = 8
MAX_CHARS = 300
MAX_RULE_CHARS = 60
DISTILL_LIMIT = 40

SYSTEM_PROMPT = (
    "你在给一位背单词 App 的用户做「审美蒸馏」。读近期的点赞/点踩记录，总结 5–8 条"
    "能直接指导写作的规则：每条一句话、不超过 40 字、只谈可执行的手法（开场、拆块、"
    "语气、长度、举例方式），不要点名具体单词，不要写空话。只输出 JSON："
    '{"rules": ["规则一", "规则二"]}'
)


def data_dir(directory=None):
    return directory or str(os.environ.get("REWRITE_DATA_DIR") or "").strip() or os.path.join(HERE, "data")


def profile_path(directory=None):
    return os.path.join(data_dir(directory), PROFILE_NAME)


def _clean(value):
    text = str(value or "").strip()
    text = text.lstrip("-*·0123456789.、 　").strip()
    return " ".join(text.split())


def _locked_list(values):
    out = []
    for raw in values or []:
        text = _clean(raw)
        if text and text not in out:
            out.append(text)
    return out


def sanitize_rules(values, locked=()):
    """去重、压到 8 条 / 300 字；locked 永远先放且不因长度被丢。"""
    locked_rows = _locked_list(locked)
    rows = list(locked_rows)
    for raw in values or []:
        text = _clean(raw)
        if not text or text in rows:
            continue
        if len(text) > MAX_RULE_CHARS:
            text = text[:MAX_RULE_CHARS].rstrip("，,。;； ")
        rows.append(text)
    kept, used = [], 0
    for text in rows:
        if text not in locked_rows and len(kept) >= MAX_RULES:
            break
        if text not in locked_rows and used + len(text) > MAX_CHARS:
            continue
        kept.append(text)
        used += len(text)
    return kept


def _empty():
    return {"rules": [], "lockedRules": [], "updatedAt": "", "sourceFeedbackCount": 0}


def load(directory=None):
    """读画像；缺失/损坏/结构不对都返回空画像，绝不抛异常。"""
    try:
        with open(profile_path(directory), "r", encoding="utf-8-sig") as fh:
            payload = json.load(fh)
    except (OSError, ValueError):
        return _empty()
    if not isinstance(payload, dict):
        return _empty()
    locked = _locked_list(payload.get("lockedRules"))
    rules = sanitize_rules(payload.get("rules"), locked)
    try:
        count = int(payload.get("sourceFeedbackCount") or 0)
    except (TypeError, ValueError):
        count = 0
    return {
        "rules": rules,
        "lockedRules": [row for row in locked if row in rules],
        "updatedAt": str(payload.get("updatedAt") or ""),
        "sourceFeedbackCount": count,
    }


def save(profile, directory=None):
    """原子写：先写 .tmp 再 replace，半截文件不会被读到。"""
    path = profile_path(directory)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    locked = _locked_list((profile or {}).get("lockedRules"))
    payload = {
        "rules": sanitize_rules((profile or {}).get("rules"), locked),
        "lockedRules": locked,
        "updatedAt": str((profile or {}).get("updatedAt") or ""),
        "sourceFeedbackCount": int((profile or {}).get("sourceFeedbackCount") or 0),
    }
    payload["lockedRules"] = [row for row in locked if row in payload["rules"]]
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)
    return payload


def block(directory=None):
    """注入用文本；没有规则返回空串（老流程完全不受影响）。"""
    profile = load(directory)
    rows = profile["rules"]
    if not rows:
        return ""
    lines = ["【用户审美画像（近期反馈蒸馏；与用户原话冲突时以原话为准）】"]
    lines.extend("- " + row for row in rows)
    return "\n".join(lines)


def set_rules(directory=None, rules=None, locked_rules=None):
    """界面编辑入口：rules / lockedRules 都可以单独改，locked 自动并入 rules。"""
    current = load(directory)
    locked = current["lockedRules"] if locked_rules is None else _locked_list(locked_rules)
    values = current["rules"] if rules is None else rules
    merged = sanitize_rules(list(locked) + list(values or []), locked)
    profile = {
        "rules": merged,
        "lockedRules": [row for row in locked if row in merged],
        "updatedAt": _now(),
        "sourceFeedbackCount": current["sourceFeedbackCount"],
    }
    return save(profile, directory)


def _now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _recent_feedback(directory=None, limit=DISTILL_LIMIT):
    """返回 (最近 limit 条有效反馈, 有效反馈总数)。"""
    try:
        rows = rewrite_protection.feedback_rows(data_dir(directory))
    except Exception:  # noqa: BLE001 - 反馈坏了就当没有，别让画像更新崩掉
        return [], 0
    valid = [row for row in rows if isinstance(row, dict)
             and str(row.get("word") or "").strip()
             and row.get("rating") in ("up", "down")]
    valid.sort(key=lambda row: (rewrite_protection.timestamp(row.get("createdAt"))
                                or rewrite_protection.timestamp(row.get("receivedAt"))), reverse=True)
    return valid[:limit], len(valid)


def _note(row):
    for key in ("note", "comment", "reason", "instruction"):
        text = str(row.get(key) or "").strip()
        if text:
            return " ".join(text.split())[:120]
    return ""


def _prompt(rows, profile):
    lines = []
    for index, row in enumerate(rows, 1):
        note = _note(row)
        lines.append("%d. [%s] %s | 方法 %s%s" % (
            index, row.get("rating"), row.get("word"),
            str(row.get("method") or "未标"), (" | 原话：" + note) if note else ""))
    current = profile.get("rules") or []
    locked = profile.get("lockedRules") or []
    tail = []
    if current:
        tail.append("现有规则（可改写，别重复）：\n" + "\n".join("- " + row for row in current))
    if locked:
        tail.append("锁定规则（必须原样保留，不要改写）：\n" + "\n".join("- " + row for row in locked))
    return "近期反馈（新→旧）：\n" + "\n".join(lines) + ("\n\n" + "\n\n".join(tail) if tail else "")


def _completer(provider):
    if provider is None:
        import providers

        return providers.complete
    if hasattr(provider, "complete"):
        return provider.complete
    if callable(provider):
        return provider
    raise TypeError("provider 必须可调用或带 complete()")


def _extract_rules(reply):
    raw = str(reply or "").strip()
    if raw.startswith("```"):
        raw = raw.strip("`").strip()
        if raw[:4].lower() == "json":
            raw = raw[4:].strip()
    start = raw.find("{")
    if start < 0:
        raise ValueError("蒸馏结果不是 JSON 对象")
    payload, _end = json.JSONDecoder().raw_decode(raw[start:])
    rules = payload.get("rules") if isinstance(payload, dict) else None
    if not isinstance(rules, list):
        raise ValueError("蒸馏结果缺 rules 数组")
    return [row for row in (_clean(value) for value in rules) if row]


def distill(directory=None, provider=None, model=DEFAULT_MODEL, limit=DISTILL_LIMIT):
    """近期反馈 → 5–8 条规则；lockedRules 原样保留。返回落盘后的画像。"""
    current = load(directory)
    rows, total = _recent_feedback(directory, limit)
    if not rows:
        return current
    reply = _completer(provider)(
        model, SYSTEM_PROMPT, _prompt(rows, current),
        max_tokens=1200, temperature=0.2, timeout=180,
    )
    locked = current["lockedRules"]
    merged = sanitize_rules(list(locked) + _extract_rules(reply), locked)
    profile = {
        "rules": merged,
        "lockedRules": [row for row in locked if row in merged],
        "updatedAt": _now(),
        "sourceFeedbackCount": total,
    }
    return save(profile, directory)

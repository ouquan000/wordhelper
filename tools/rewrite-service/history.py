#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""同词历史派生：把 feedback.jsonl 与原结果记录压成给生成/润色看的短摘要。

原则（spec §2.2 / §3 W1）：
- 点踩和用户原话是硬约束，被否掉的路线默认不许再当主线；
- 点赞是正样本，具体版本/文本要保护，不能因为重写弄丢；
- 只给有依据的摘要，不脑补用户没说过的话；
- 历史可能来自旧版本：approachRev 正文缺省 0 时，从 clientId 的 -rN 后缀还原。
"""

from __future__ import annotations

import json
import os
import re

from store import result_name, same_book_scope

MAX_ROWS = 12
MAX_APPROACH = 160
MAX_TEXT = 1400
REV_SUFFIX = re.compile(r"-r(\d+)$")


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


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def revision_of(row):
    """优先正文 approachRev；老数据正文是 0，但 clientId 末尾的 -rN 仍有效。"""
    try:
        value = int(row.get("approachRev") or 0)
    except (TypeError, ValueError):
        value = 0
    if value > 0:
        return value
    match = REV_SUFFIX.search(str(row.get("clientId") or ""))
    return int(match.group(1)) if match else 0


def _squash(text, limit=MAX_APPROACH):
    text = " ".join(str(text or "").split())
    return text[:limit] + ("…" if len(text) > limit else "")


def _reasons_of(row):
    """feedback 行的 reasons 收成 list：兼容 list 与 JSON 字符串；缺失返回 []。"""
    raw = row.get("reasons")
    if raw is None:
        return []
    if isinstance(raw, list):
        items = raw
    elif isinstance(raw, str):
        text = raw.strip()
        if not text:
            return []
        try:
            decoded = json.loads(text)
        except ValueError:
            decoded = None
        items = decoded if isinstance(decoded, list) else [raw]
    else:
        return []
    return [str(x).strip() for x in items if str(x).strip()]


def _complaint_text(row):
    """差评 complaint：reasons 逐条 + comment + instruction 合成；三者都空才回退。

    「太烂了」触发硬约束：整套推翻、换一种记忆方法重来。硬约束先预留长度再加，
    宁可把前面的原话截短，也保证这条硬约束完整进入最终 Prompt。
    """
    parts = _reasons_of(row)
    comment = str(row.get("comment") or "").strip()
    if comment:
        parts.append(comment)
    instruction = str(row.get("instruction") or "").strip()
    if instruction:
        parts.append(instruction)
    base = "；".join(parts)
    if not base:
        return "只点了踩，没写原因"
    if any("太烂了" in item for item in _reasons_of(row)):
        constraint = "。整套推翻，严禁沿用原拆法，必须切换另一种记忆方法重来"
        # 预留约束的完整长度：先给 base 留 80-|constraint|-1 个字符（-1 给可能的省略号），
        # 保证 outer _squash(..., 80) 不会再截掉这条硬约束的任何一个字。
        return _squash(base, 80 - len(constraint) - 1) + constraint
    return base


def _method_label(row):
    method = str(row.get("method") or "").strip()
    return {"sound": "谐音", "meaning": "含义", "shape": "字形",
            "root": "词根", "contrast": "对比", "polysemy": "熟义"}.get(method, method)


def _feedback_rows(data_dir, word, scope):
    rows = [r for r in _read_jsonl(os.path.join(data_dir, "feedback.jsonl"))
            if str(r.get("word") or "").strip() == word
            and (scope is None or same_book_scope(r, scope))]
    rows.sort(key=lambda r: (str(r.get("createdAt") or ""), str(r.get("receivedAt") or "")))
    return rows[-MAX_ROWS:]


def _queue_instructions(data_dir, word, scope):
    """用户每次点重写时写的原话，真正的「他到底嫌什么」在这里。

    历史 feedback.jsonl 的 instruction 多为空（1.4.7.1 之前 App 没传），
    只读 feedback 会把「连续被否掉」写成「只点了踩，没写原因」，评委自然
    看不出该换路线。queue.json 的 instruction 是当时请求的原始输入，必须带上。
    """
    data = _read_json(os.path.join(data_dir, "queue.json")) or {}
    items = data.get("items") if isinstance(data.get("items"), list) else []
    rows = []
    for item in items:
        if not isinstance(item, dict):
            continue
        if str(item.get("word") or "").strip() != word:
            continue
        if scope is not None and not same_book_scope(item, scope):
            continue
        text = " ".join(str(item.get("instruction") or "").split())
        if not text:
            continue
        rows.append((str(item.get("createdAt") or ""), text))
    rows.sort(key=lambda row: row[0])
    return rows[-8:]


def build(data_dir, word, instruction="", *, scope=None):
    """返回给提示词用的历史摘要；没有历史时返回空串。"""
    word = str(word or "").strip()
    if not word:
        return ""
    lines = []
    rows = _feedback_rows(data_dir, word, scope)
    downs = [r for r in rows if str(r.get("rating") or "") == "down"]
    ups = [r for r in rows if str(r.get("rating") or "") == "up"]

    quotes = _queue_instructions(data_dir, word, scope)
    if quotes:
        lines.append("【用户原话（按时间，来自他每次要求重写的输入）】")
        for when, text in quotes:
            stamp = when[:16].replace("T", " ") if when else ""
            lines.append("- %s：%s" % (stamp, _squash(text, 140)))

    if downs:
        lines.append("【这个词以前被否掉的路线（默认避开；用户这次明确要求才可重提）】")
        for row in downs[-6:]:
            rev = revision_of(row)
            complaint = _squash(_complaint_text(row), 80)
            approach = _squash(row.get("approach") or "", 120)
            tag = "第%d版" % rev if rev else "旧版"
            method = _method_label(row)
            piece = "- %s%s 被点踩：原话「%s」" % (tag, ("·" + method) if method else "", complaint)
            if approach:
                piece += "；那版思路：%s" % approach
            lines.append(piece)

    if ups:
        lines.append("【被点赞的版本（正样本，保护原版本/原文本，不要改掉它的有效部分）】")
        for row in ups[-4:]:
            rev = revision_of(row)
            tag = "第%d版" % rev if rev else "旧版"
            method = _method_label(row)
            approach = _squash(row.get("approach") or "", 120)
            piece = "- %s%s 被点赞" % (tag, ("·" + method) if method else "")
            if approach:
                piece += "：%s" % approach
            lines.append(piece)

    record = _read_json(os.path.join(data_dir, "results", result_name(word)))
    entries = (record or {}).get("entries") or []
    bad = [e for e in entries if isinstance(e, dict)
           and (scope is None or same_book_scope(e, scope))
           and (e.get("status") != "ok" or e.get("unverified"))]
    if bad:
        lines.append("【自动重写失败/未复核记录（这些路线的具体问题）】")
        for entry in bad[-4:]:
            rev = entry.get("rev")
            tag = "第%s版" % rev if rev not in (None, "") else "旧版"
            reason = _squash(entry.get("reason") or entry.get("error") or "没写原因", 120)
            method = _method_label(entry)
            lines.append("- %s%s：%s" % (tag, ("·" + method) if method else "", reason))

    text = "\n".join(lines)
    if len(text) > MAX_TEXT:
        text = text[:MAX_TEXT] + "…"
    return text

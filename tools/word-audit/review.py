#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W6-A 审查清单生成器：只读 scan.json，不调用模型。

从扫描结果中筛出 P0/P1 全量索引，并按类别、正文行数分层抽样，生成人工/模型
可直接阅读的 Markdown 清单，同时写一份机器可读的 review.json。

用法：
    python tools/word-audit/review.py \
        --scan _recon/1.4.7.2/W6/scan.json \
        --out _recon/1.4.7.2/W6/review-list.md
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any, Callable, Iterable

SEVERITY_RANK = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}
REVIEW_HINTS = {
    "empty_word": "确认是否漏词；空词不能进入任何生成队列。",
    "duplicate_word": "确认哪一条是主版本，另一条是否应删除或合并。",
    "missing_meaning": "补中文释义后再判断记忆思路是否对应。",
    "missing_approach": "补记忆思路；不能只留释义。",
    "missing_pos": "确认该行是否漏了 n./v./adj. 等词性；同义项是否需要拆行。",
    "pos_mixed": "确认是否应拆成多行、每行一个词性；不要只加空格。",
    "line_count": "少于 2 行或多于 5 行，无法构成步骤链；确认是否截断/合并。",
    "short_approach": "2 行旧稿，确认信息是否完整、是否值得按 3–5 步重写。",
    "arrow_missing": "末行是否真正落到中文词义；补「——>」还是重写末步。",
    "arrow_penultimate": "确认末行是旁注而非结论；若是结论，应把箭头移到末行。",
    "internal_term": "删掉「第N招/这一招/走X法/评分/评委/候选稿/拆法」等流程用语，改成读者能懂的画面。",
    "part_impure": "删掉「元素：/part=/编者注」等编辑标记，正文只留连贯叙述。",
    "root_claim_suspect": "核对词根/词缀是否真是该词的构成；片段不在词中时不要硬拆。",
    "root_annotation_review": "核对词根标注的真实性与必要性；用户不喜欢词根时要尊重历史约束。",
    "history_conflict": "同一正文同时被点赞和点踩：先人工判断哪条反馈代表当前意图，再决定保护谁。",
    "history_down_current": "当前正文被点踩：优先修订，但先读 feedback 正文与原话，确认踩点。",
    "history_protection_review": "点赞在旧版本：确认旧稿哪些地方必须保留，不能把点赞点一起重写掉。",
    "english_clue_missing": "整卡没有英文锚点；确认是否应补拼写/读音线索，还是纯语义讲解就够。",
    "long_line": "单行过长，朗读/手机阅读会断气；确认是否拆行或删修饰。",
    "long_approach": "总长过长，确认是否有重复解释或可删的正确废话。",
    "long_word": "超长词，确认拆分/读音线索是否足够。",
    "numbering_mismatch": "首行有①②编号但后续缺号/跳号；确认是漏标还是把子句误当一步。",
    "arrow_not_last": "箭头出现在中间行；确认是否把结论提前了。",
    "empty_line": "删空行。",
    "duplicate_line": "删除重复行或改写成递进。",
    "missing_phonetic": "补音标后再判断谐音线索。",
    "missing_collocations": "补搭配或确认该词不需要搭配。",
    "word_shape": "词形异常，确认是否混入标点/中文/空格。",
    "meaning_empty_line": "删释义空行。",
    "meaning_long": "释义行过长，确认是否拆行。",
    "placeholder": "占位文本未完成，不能进入审查通过。",
}


def load_scan(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict) or "words" not in data:
        raise ValueError("不是 scan.py 生成的扫描结果：缺少 words")
    return data


def record_categories(record: dict[str, Any]) -> list[dict[str, Any]]:
    issues = record.get("issues") or []
    return [i for i in issues if isinstance(i, dict)]


def category_records(records: Iterable[dict[str, Any]], category: str) -> list[dict[str, Any]]:
    return [r for r in records if any(i.get("category") == category for i in record_categories(r))]


def evenly_spaced(items: list[dict[str, Any]], k: int) -> list[dict[str, Any]]:
    """按已排序列表等距取 k 个，包含首尾；不依赖随机数。"""
    if k <= 0 or not items:
        return []
    if len(items) <= k:
        return list(items)
    if k == 1:
        return [items[0]]
    indexes = sorted({round(i * (len(items) - 1) / (k - 1)) for i in range(k)})
    return [items[i] for i in indexes]


def stratified_sample(records: list[dict[str, Any]], k: int,
                      stratum: Callable[[dict[str, Any]], str]) -> list[dict[str, Any]]:
    """按 stratum 分层，按各层数量比例分配样本；每层内部等距取样。"""
    if k <= 0 or not records:
        return []
    groups: dict[str, list[dict[str, Any]]] = {}
    for record in records:
        groups.setdefault(stratum(record), []).append(record)
    for key in groups:
        groups[key].sort(key=lambda r: (str(r.get("word", "")).lower(), int(r.get("lineNo") or 0)))
    ordered_keys = sorted(groups, key=lambda x: (len(groups[x]), x), reverse=True)
    if len(ordered_keys) > k:
        # 层数比样本还多：先覆盖尽量多的层，再按总量补。
        chosen_keys = ordered_keys[:k]
        return [groups[key][0] for key in sorted(chosen_keys)]
    allocation = {key: 1 for key in ordered_keys}
    remaining = k - len(ordered_keys)
    total = sum(len(v) for v in groups.values()) or 1
    if remaining > 0:
        quotas = {key: remaining * len(groups[key]) / total for key in ordered_keys}
        for key in ordered_keys:
            allocation[key] += int(math.floor(quotas[key]))
        assigned = sum(allocation.values())
        # 余数按小数部分从大到小补，保持确定性。
        remainders = sorted(ordered_keys,
                            key=lambda key: (quotas[key] - math.floor(quotas[key]), key),
                            reverse=True)
        for key in remainders:
            if assigned >= k:
                break
            allocation[key] += 1
            assigned += 1
    out: list[dict[str, Any]] = []
    for key in ordered_keys:
        out.extend(evenly_spaced(groups[key], allocation[key]))
    # 按字母序输出，便于人工按词查。
    out.sort(key=lambda r: (str(r.get("word", "")).lower(), int(r.get("lineNo") or 0)))
    return out


def letter_band(word: str) -> str:
    first = (word or "?")[0].upper()
    if "A" <= first <= "C":
        return "A-C"
    if "D" <= first <= "F":
        return "D-F"
    if "G" <= first <= "L":
        return "G-L"
    if "M" <= first <= "R":
        return "M-R"
    if "S" <= first <= "Z":
        return "S-Z"
    return "其他"


def position_band(line_no: Any, total_words: int) -> str:
    """词表位置分位；词包没有独立词频字段，用位置作确定性代理。"""
    try:
        line = max(1, int(line_no or 1))
    except (TypeError, ValueError):
        line = 1
    total = max(1, int(total_words or 1))
    quartile = min(3, max(0, int((line - 1) * 4 / total)))
    return f"Q{quartile + 1}"


def feedback_band(record: dict[str, Any]) -> str:
    history = record.get("history") or {}
    records = int(history.get("records") or 0)
    if not records:
        return "无历史反馈"
    return f"有历史反馈({history.get('up', 0)}up/{history.get('down', 0)}down)"


def ok_sample_reason(record: dict[str, Any], total_words: int) -> str:
    word = str(record.get("word") or "")
    return (f"词书 {record.get('book') or '未知词书'}；字母段 {letter_band(word)}；"
            f"词表位置 {position_band(record.get('lineNo'), total_words)}"
            f"（词频代理，词包无独立词频字段）；{feedback_band(record)}；status=ok")


def select_ok_samples(records: list[dict[str, Any]], total_words: int, k: int = 40) -> list[dict[str, Any]]:
    """从 status=ok 的词中做确定性分层抽样，供人工/模型语义审查。"""
    ok_records = [r for r in records if r.get("status") == "ok"]
    if not ok_records or k <= 0:
        return []
    ok_records.sort(key=lambda r: (str(r.get("word", "")).lower(), int(r.get("lineNo") or 0)))
    must = [r for r in ok_records if int((r.get("history") or {}).get("records") or 0) > 0]
    must = must[:k]
    must_keys = {(r.get("word"), r.get("lineNo")) for r in must}
    remaining = [r for r in ok_records if (r.get("word"), r.get("lineNo")) not in must_keys]
    sampled = stratified_sample(
        remaining, max(0, k - len(must)),
        stratum=lambda r: (f"{str(r.get('book') or '未知词书')}|{letter_band(str(r.get('word') or ''))}|"
                           f"{position_band(r.get('lineNo'), total_words)}|{feedback_band(r)}"),
    )
    out = must + [r for r in sampled
                  if (r.get("word"), r.get("lineNo")) not in must_keys]
    if len(out) < k:
        used = {(r.get("word"), r.get("lineNo")) for r in out}
        fill = [r for r in ok_records if (r.get("word"), r.get("lineNo")) not in used]
        out.extend(evenly_spaced(fill, k - len(out)))
    out.sort(key=lambda r: (str(r.get("word", "")).lower(), int(r.get("lineNo") or 0)))
    return out[:k]


def fmt_bool(value: Any) -> str:
    return "是" if value else "否"


def render_ok_sample(record: dict[str, Any], index: int, total_words: int) -> list[str]:
    lines: list[str] = []
    word = record.get("word") or "(空词)"
    lines.append(f"#### {index}. {word}（第 {record.get('lineNo')} 条，OK）")
    lines.append("")
    lines.append(f"- 抽样原因：{ok_sample_reason(record, total_words)}")
    lines.append(f"- 释义：{str(record.get('_meaning') or '').replace(chr(10), ' / ')}")
    lines.append(f"- 记忆思路（{len(record.get('_approach') or [])} 行）：")
    for i, line in enumerate(record.get("_approach") or [], start=1):
        lines.append(f"  {i}. {line}")
    protection = record.get("protection") or {}
    lines.append(f"- 文本指纹：`{protection.get('currentTextSha256', '')}`")
    lines.append(f"- 历史反馈：{(record.get('history') or {}).get('records', 0)} 条")
    lines.append("")
    return lines


def compact_words(records: list[dict[str, Any]], limit: int = 100,
                  json_path: str = "review-list.json") -> str:
    words = []
    seen = set()
    for record in records:
        word = str(record.get("word") or "(空词)")
        key = (word, record.get("lineNo"))
        if key in seen:
            continue
        seen.add(key)
        words.append(word)
    if len(words) <= limit:
        return "、".join(words)
    return "、".join(words[:limit]) + (
        f" …（共 {len(words)} 词，Markdown 仅列前 {limit} 词；完整列表见 JSON：`{json_path}`）"
    )


def render_record(record: dict[str, Any], index: int) -> list[str]:
    lines: list[str] = []
    word = record.get("word") or "(空词)"
    issues = record.get("issues") or []
    lines.append(f"#### {index}. {word}（第 {record.get('lineNo')} 条，"
                 f"{record.get('severity')}，status={record.get('status')}）")
    lines.append("")
    lines.append(f"- 释义：{str(record.get('_meaning') or '').replace(chr(10), ' / ')}")
    lines.append(f"- 记忆思路（{len(record.get('_approach') or [])} 行）：")
    for i, line in enumerate(record.get("_approach") or [], start=1):
        lines.append(f"  {i}. {line}")
    if issues:
        lines.append("- 命中问题：")
        for issue in issues:
            lines.append(f"  - `{issue.get('category')}`（{issue.get('severity')}）：{issue.get('evidence')}")
    protection = record.get("protection") or {}
    history = record.get("history") or {}
    lines.append(f"- 历史反馈：{history.get('records', 0)} 条（up {history.get('up', 0)} / down {history.get('down', 0)}）；"
                 f"当前正文 up 精确 {protection.get('upExact', 0)}、down 精确 {protection.get('downExact', 0)}；"
                 f"受保护={fmt_bool(record.get('protected'))}；冲突={fmt_bool(protection.get('conflict'))}")
    lines.append(f"- 文本指纹：`{protection.get('currentTextSha256', '')}`")
    hints = []
    for issue in issues:
        hint = REVIEW_HINTS.get(str(issue.get("category")))
        if hint and hint not in hints:
            hints.append(hint)
    if hints:
        lines.append("- 审查提示：" + "；".join(hints))
    lines.append("")
    return lines


def build_review(scan: dict[str, Any], per_category: int = 8,
                 p0_limit: int = 50, ok_samples_limit: int = 40) -> tuple[str, dict[str, Any]]:
    records = [dict(r) for r in scan.get("words") or []]
    # source 里保存了完整原文；老版本 scan.json 没有 source 时退回 lineChecks.preview。
    for record in records:
        source = record.get("source") or {}
        record["_approach"] = [str(line) for line in (source.get("approach") or [])]
        record["_meaning"] = str(source.get("meaning") or "")
        if not record["_approach"]:
            previews = []
            for check in record.get("lineChecks") or []:
                preview = str(check.get("preview") or "")
                if preview.endswith("…"):
                    preview = preview[:-1]
                previews.append(preview)
            record["_approach"] = previews
    categories = scan.get("summary", {}).get("categories") or {}
    total_words = int(scan.get("summary", {}).get("totalWords") or len(records))
    all_p0 = [r for r in records if r.get("severity") == "P0"]
    all_p1 = [r for r in records if r.get("severity") == "P1"]
    all_p2 = [r for r in records if r.get("severity") == "P2"]
    ok_samples = select_ok_samples(records, total_words, ok_samples_limit)

    selection: dict[str, list[dict[str, Any]]] = {}
    for category in categories:
        matching = category_records(records, category)
        if not matching:
            continue
        selection[category] = stratified_sample(
            matching, per_category,
            stratum=lambda r: f"{int((r.get('metrics') or {}).get('approachLines') or 0)}行",
        )

    review_obj: dict[str, Any] = {
        "meta": {
            "source": scan.get("meta"),
            "summary": scan.get("summary"),
            "perCategory": per_category,
        },
        "p0": [{"word": r.get("word"), "lineNo": r.get("lineNo"), "issues": r.get("issues")}
               for r in all_p0[:p0_limit]],
        "p1": [{"word": r.get("word"), "lineNo": r.get("lineNo"),
                "severity": r.get("severity"), "status": r.get("status"),
                "issues": r.get("issues")} for r in all_p1],
        "p2": [{"word": r.get("word"), "lineNo": r.get("lineNo"),
                "severity": r.get("severity"), "status": r.get("status"),
                "issues": r.get("issues"),
                "source": {"meaning": r.get("_meaning", ""), "approach": r.get("_approach", [])}}
               for r in all_p2],
        "okSamples": [{
            "word": r.get("word"),
            "book": r.get("book") or scan.get("summary", {}).get("book") or "",
            "lineNo": r.get("lineNo"),
            "reason": ok_sample_reason(r, total_words),
            "stratum": {
                "book": r.get("book") or scan.get("summary", {}).get("book") or "",
                "letter": letter_band(str(r.get("word") or "")),
                "position": position_band(r.get("lineNo"), total_words),
                "feedback": feedback_band(r),
            },
            "meaning": r.get("_meaning", ""),
            "approach": r.get("_approach", []),
            "source": r.get("source") or {"meaning": r.get("_meaning", ""), "approach": r.get("_approach", [])},
            "history": r.get("history"),
            "textFingerprint": (r.get("protection") or {}).get("currentTextSha256", ""),
        } for r in ok_samples],
        "sampled": {
            cat: [{"word": r.get("word"), "lineNo": r.get("lineNo"),
                   "issues": r.get("issues"), "protection": r.get("protection"),
                   "history": r.get("history")} for r in rows]
            for cat, rows in selection.items()
        },
    }

    lines: list[str] = []
    s = scan.get("summary", {})
    lines.append("# 1.4.7.2 W6-A 审查清单")
    lines.append("")
    lines.append(f"- 来源：`{scan.get('meta', {}).get('cardsPath', '')}`")
    lines.append(f"- 总词数：{s.get('totalWords', 0)}；P0 {s.get('issueWords', {}).get('P0', 0)}、"
                 f"P1 {s.get('issueWords', {}).get('P1', 0)}、P2 {s.get('issueWords', {}).get('P2', 0)}、"
                 f"OK {s.get('okWords', 0)}")
    lines.append(f"- 每类抽样：{per_category} 词（按正文行数分层，层内等距；确定性，无随机）")
    lines.append(f"- OK/正常词样本：{len(ok_samples)} 词（按字母段/词表位置分位/历史反馈分层；仅供审查，未做语义判定）")
    lines.append("- 本清单不调用模型、不改词包；只给人工/模型审查用。")
    lines.append("")

    lines.append("## 1. P0（全量）")
    lines.append("")
    if not all_p0:
        lines.append("无。空词、重复词、释义缺失、记忆思路缺失均为 0。")
    else:
        lines.append(f"共 {len(all_p0)} 词；清单上限 {p0_limit}。")
        lines.append("")
        for record in all_p0[:p0_limit]:
            lines.extend(render_record(record, all_p0.index(record) + 1))
    lines.append("")

    lines.append("## 2. P1 审查索引与分层样本")
    lines.append("")
    for category, info in categories.items():
        if info.get("severity") != "P1":
            continue
        matching = category_records(records, category)
        if not matching:
            continue
        lines.append(f"### `{category}` — {info.get('label')}（{info.get('words', 0)} 词）")
        lines.append("")
        lines.append(f"- 判定口径：{info.get('desc', '')}")
        lines.append(f"- 词名索引（Markdown 截断；完整列表见 JSON）：{compact_words(matching, 120)}")
        lines.append("")
        sample = selection.get(category, [])
        if sample:
            lines.append(f"**分层样本（{len(sample)} 词）**")
            lines.append("")
            for idx, record in enumerate(sample, start=1):
                lines.extend(render_record(record, idx))
    lines.append("")

    lines.append("## 3. P2 语义/格式审查样本")
    lines.append("")
    p2_categories = [c for c, info in categories.items() if info.get("severity") == "P2"]
    if not p2_categories:
        lines.append("无。")
    for category in p2_categories:
        info = categories[category]
        matching = category_records(records, category)
        if not matching:
            continue
        sample = selection.get(category, [])
        lines.append(f"### `{category}` — {info.get('label')}（{info.get('words', 0)} 词）")
        lines.append("")
        lines.append(f"- 判定口径：{info.get('desc', '')}")
        lines.append(f"- 词名索引（Markdown 截断；完整列表见 JSON）：{compact_words(matching, 80)}")
        lines.append("")
        for idx, record in enumerate(sample, start=1):
            lines.extend(render_record(record, idx))
    lines.append("")

    lines.append("## 4. OK/正常词代表性语义审查样本（仅抽样，未做语义判定）")
    lines.append("")
    lines.append(f"从 `status=ok` 的 {sum(1 for r in records if r.get('status') == 'ok')} 词中抽取 "
                 f"{len(ok_samples)} 词；分层维度为词书、字母段、词表位置分位（词频代理）、有无历史反馈。"
                 "这些词没有任何确定性规则命中，但仍需人工/后续模型判断语义是否成立。")
    lines.append("")
    if not ok_samples:
        lines.append("无 `status=ok` 样本。")
    else:
        for idx, record in enumerate(ok_samples, start=1):
            lines.extend(render_ok_sample(record, idx, total_words))
    lines.append("")

    lines.append("## 5. 点赞保护与人工复核（全量）")
    lines.append("")
    protected = [r for r in records if r.get("protected")]
    conflicts = [r for r in records if (r.get("protection") or {}).get("conflict")]
    up_other = [r for r in records if (r.get("protection") or {}).get("upOtherVersion")]
    down_current = [r for r in records if (r.get("protection") or {}).get("downExact")]
    lines.append(f"- 当前正文受点赞保护：{len(protected)} 词")
    for record in protected:
        lines.append(f"  - {record.get('word')}（第 {record.get('lineNo')} 条，status={record.get('status')}，"
                     f"upClients={(record.get('protection') or {}).get('matchedUpClients')}）")
    lines.append(f"- 同词 up/down 冲突：{len(conflicts)} 词")
    for record in conflicts:
        p = record.get("protection") or {}
        lines.append(f"  - {record.get('word')}：up={p.get('matchedUpClients')}，down={p.get('matchedDownClients')}")
    lines.append(f"- 点赞在旧版本、当前正文不同：{len(up_other)} 词")
    for record in up_other:
        p = record.get("protection") or {}
        lines.append(f"  - {record.get('word')}（upOtherVersion={p.get('upOtherVersion')}，"
                     f"upOtherClients={p.get('upOtherVersionClients', [])}）")
        for detail in p.get("matchedFeedback", []):
            if detail.get("rating") == "up" and detail.get("textMatch") == "other_version":
                lines.append(f"    - client={detail.get('clientId')}，createdAt={detail.get('createdAt')}，"
                             f"approachRev={detail.get('approachRev')}，旧正文={detail.get('textSnippet', '')}")
    down_other = [r for r in records if (r.get("protection") or {}).get("downOtherVersion")]
    lines.append(f"- 点踩在旧版本、当前正文不同：{len(down_other)} 词")
    for record in down_other:
        p = record.get("protection") or {}
        lines.append(f"  - {record.get('word')}（downOtherVersion={p.get('downOtherVersion')}，"
                     f"downOtherClients={p.get('downOtherVersionClients', [])}）")
        for detail in p.get("matchedFeedback", []):
            if detail.get("rating") == "down" and detail.get("textMatch") == "other_version":
                lines.append(f"    - client={detail.get('clientId')}，createdAt={detail.get('createdAt')}，"
                             f"approachRev={detail.get('approachRev')}，旧正文={detail.get('textSnippet', '')}")
    lines.append(f"- 当前正文被点踩：{len(down_current)} 词")
    for record in down_current:
        lines.append(f"  - {record.get('word')}（downExact={record.get('protection', {}).get('downExact')}）")
    down_category_words = int((categories.get("history_down_current") or {}).get("words") or 0)
    conflict_down = max(0, len(down_current) - down_category_words)
    lines.append(f"- 口径说明：`downExact` {len(down_current)} 词 = `history_down_current` {down_category_words} 词 "
                 f"+ 同文 up/down 冲突 {conflict_down} 词（冲突词归入 `history_conflict`，不重复计入 down 队列）。")
    lines.append("")

    lines.append("## 6. 审查顺序建议")
    lines.append("")
    lines.append("1. 先处理 P0（当前为 0）与 `history_conflict` / `history_down_current`：用户已经明确表态的优先。")
    lines.append("2. 再处理 `internal_term`、`part_impure`、`root_claim_suspect`：这些是明确不该出现在正文里的内容。")
    lines.append("3. 然后处理 `missing_pos` / `pos_mixed` / `arrow_missing`：机械格式可批量修，但要逐词确认末行仍落到词义。")
    lines.append("4. `short_approach`、`english_clue_missing`、`long_line` 属于代表性语义审查，按抽样结果判断是否重写。")
    lines.append("5. 点赞保护词只能改非受保护部分；跨版本 up 必须先人工确认保护对象。")
    lines.append("")
    return "\n".join(lines), review_obj


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="W6-A 审查清单生成器（不调用模型）")
    parser.add_argument("--scan", required=True, help="scan.json 路径")
    parser.add_argument("--out", required=True, help="输出 Markdown 路径")
    parser.add_argument("--json", default=None, help="可选：输出机器可读 review.json")
    parser.add_argument("--per-category", type=int, default=8, help="每类抽样数，默认 8")
    parser.add_argument("--p0-limit", type=int, default=50, help="P0 清单上限，默认 50")
    parser.add_argument("--ok-samples", type=int, default=40, help="OK/正常词分层样本数，默认 40（建议 30-50）")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    scan_path = Path(args.scan)
    if not scan_path.exists():
        print(f"扫描结果不存在：{scan_path}", file=sys.stderr)
        return 2
    try:
        scan = load_scan(scan_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"读取扫描结果失败：{exc}", file=sys.stderr)
        return 1
    md, review_obj = build_review(scan, per_category=args.per_category, p0_limit=args.p0_limit,
                                  ok_samples_limit=args.ok_samples)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(md, encoding="utf-8")
    json_path = Path(args.json) if args.json else out_path.with_suffix(".json")
    json_path.write_text(json.dumps(review_obj, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"review md: {out_path}")
    print(f"review json: {json_path}")
    print(f"sampled categories: {len(review_obj.get('sampled', {}))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

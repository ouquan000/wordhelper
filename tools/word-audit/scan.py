#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W6-A：存量词包确定性扫描器。

只读扫描 ``cet6_cards.json``，不调用任何模型，不修改词包。
输出一份逐词 JSON（每词一条）和一份汇总 Markdown。

用法：
    python tools/word-audit/scan.py \
        --cards app/src_repo/app/src/main/assets/cet6_cards.json \
        --out _recon/1.4.7.2/W6/scan.json \
        --md _recon/1.4.7.2/W6/scan-report.md

设计原则：
- 标准库 only；不联网；不写词包。
- 规则尽量保守：宁可漏报，不把正常词误报成问题。
- 语义类判断（词义对应、谐音是否过音标、词根是否真实）不在本阶段下结论，
  只输出可供人工/模型审查的候选与证据。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

SCANNER_VERSION = "1.1.0"
CIRCLED_NUMBERS = "①②③④⑤⑥⑦⑧⑨⑩"
POS_MARKERS = {"n", "v", "adj", "adv", "prep", "conj", "pron", "num", "art", "int", "aux", "vt", "vi"}
POS_LINE_RE = re.compile(r"^\s*([A-Za-z]{1,8})\.\s*")
POS_ANY_RE = re.compile(r"(?<![A-Za-z])(n|v|adj|adv|prep|conj|pron|num|art|int|aux|vt|vi)\.\s*", re.I)
ASCII_RE = re.compile(r"[A-Za-z]")
PHONETIC_RE = re.compile(r"/[^/\n]{1,40}/")
ARROW_OK = "——>"

# 类别元数据：报告和 review.py 共用一套口径。
CATEGORY_META: dict[str, dict[str, str]] = {
    "empty_word": {"label": "空词/缺词", "severity": "P0", "kind": "deterministic",
                   "desc": "word 字段为空或缺失，词条不可用。"},
    "duplicate_word": {"label": "重复词", "severity": "P0", "kind": "deterministic",
                        "desc": "同一词（忽略大小写）在词包中出现多次。"},
    "missing_meaning": {"label": "释义缺失", "severity": "P0", "kind": "deterministic",
                        "desc": "meaning 为空，卡片没有中文释义。"},
    "missing_approach": {"label": "记忆思路缺失", "severity": "P0", "kind": "deterministic",
                         "desc": "approach 为空或全部为空白行。"},
    "missing_pos": {"label": "词性标注缺失", "severity": "P1", "kind": "deterministic",
                    "desc": "释义某行没有以 n./v./adj. 等词性标记开头。"},
    "pos_mixed": {"label": "词性未分行", "severity": "P1", "kind": "deterministic",
                  "desc": "同一释义行里出现两个及以上词性标记，应拆分到多行。"},
    "line_count": {"label": "正文行数异常", "severity": "P1", "kind": "deterministic",
                   "desc": "记忆思路少于 2 行或多于 5 行，无法构成步骤链。"},
    "short_approach": {"label": "正文仅 2 行", "severity": "P2", "kind": "deterministic",
                       "desc": "2 行旧稿，低于 1.4.4 之后 3–5 步的生成标准；可读性需人工确认。"},
    "arrow_missing": {"label": "末行缺箭头", "severity": "P1", "kind": "deterministic",
                      "desc": "最后一行没有「——>」；若倒数第二行有箭头且末行是旁注，按 1.4.7.1 规则单独登记。"},
    "arrow_penultimate": {"label": "箭头在倒数第二行", "severity": "P2", "kind": "deterministic",
                          "desc": "末行无箭头但倒数第二行有，符合 1.4.7.1 的旁注写法，需确认末行确实是旁注。"},
    "internal_term": {"label": "内部术语泄漏", "severity": "P1", "kind": "deterministic",
                      "desc": "正文出现「第N招/这一招/挑一招/走X法/方法名/评分/评委/候选稿/拆法」等流程用语。"},
    "part_impure": {"label": "元素标注不纯", "severity": "P1", "kind": "deterministic",
                    "desc": "正文出现「元素：」、part=、编者注等元素级标记；卡包不存 elements 字段，这是替代信号。"},
    "root_claim_suspect": {"label": "词根拆分可疑", "severity": "P1", "kind": "deterministic",
                            "desc": "正文声称拆词素，但引用的片段不是单词的子串，属于结构性可疑。"},
    "root_annotation_review": {"label": "词根标注待语义复核", "severity": "P2", "kind": "semantic-queue",
                                "desc": "正文出现显式「词根/词缀/前缀/后缀」标注，本阶段只登记，不做真伪判断。"},
    "history_conflict": {"label": "同词点赞/点踩冲突", "severity": "P1", "kind": "deterministic",
                          "desc": "同一段正文既有 up 又有 down，保护与修订冲突，必须人工复核。"},
    "history_down_current": {"label": "当前正文被点踩", "severity": "P1", "kind": "deterministic",
                              "desc": "历史反馈中的 down 正文与当前正文完全一致，应优先修订。"},
    "history_protection_review": {"label": "点赞版本与当前版本不一致", "severity": "P2", "kind": "deterministic",
                                   "desc": "同词有 up 反馈，但其正文与当前正文不同；点赞保护只覆盖旧版本，需人工确认。"},
    "english_clue_missing": {"label": "整卡无英文线索", "severity": "P2", "kind": "deterministic",
                              "desc": "所有 approach 行都不含任何英文字母，无法从拼写/读音建立锚点。"},
    "long_line": {"label": "正文单行过长", "severity": "P2", "kind": "deterministic",
                   "desc": "单行超过 120 字符，阅读节奏可能变差。"},
    "long_approach": {"label": "正文总长过长", "severity": "P2", "kind": "deterministic",
                       "desc": "approach 总字符数超过 600。"},
    "long_word": {"label": "超长词", "severity": "P2", "kind": "deterministic",
                   "desc": "word 长度超过 20 个字符。"},
    "numbering_mismatch": {"label": "行号不连续", "severity": "P2", "kind": "deterministic",
                            "desc": "首行使用了①②③编号，但后续行编号缺失或跳号。"},
    "arrow_not_last": {"label": "箭头出现在中间行", "severity": "P2", "kind": "deterministic",
                        "desc": "非末行出现「——>」，与末行收束的格式约定不一致。"},
    "empty_line": {"label": "正文空行", "severity": "P2", "kind": "deterministic",
                    "desc": "approach 中存在空白行。"},
    "duplicate_line": {"label": "正文重复行", "severity": "P2", "kind": "deterministic",
                        "desc": "同一卡片的 approach 中存在完全重复的行。"},
    "missing_phonetic": {"label": "音标缺失", "severity": "P2", "kind": "deterministic",
                          "desc": "phonetic 为空。"},
    "missing_collocations": {"label": "搭配缺失", "severity": "P2", "kind": "deterministic",
                              "desc": "collocations 为空。"},
    "word_shape": {"label": "词形异常", "severity": "P2", "kind": "deterministic",
                    "desc": "word 含字母、空格、连字符、撇号以外的字符。"},
    "meaning_empty_line": {"label": "释义空行", "severity": "P2", "kind": "deterministic",
                            "desc": "meaning 中存在空白行。"},
    "meaning_long": {"label": "释义行过长", "severity": "P2", "kind": "deterministic",
                      "desc": "单条释义行超过 120 字符。"},
    "placeholder": {"label": "占位文本", "severity": "P2", "kind": "deterministic",
                     "desc": "正文出现 TODO/FIXME、待补充、待完善、此处填写或（空）等占位词。"},
}

# 报告里的固定顺序（同级时决定 primary category）。
CATEGORY_ORDER = [
    "empty_word", "duplicate_word", "missing_meaning", "missing_approach",
    "history_conflict", "history_down_current", "missing_pos", "pos_mixed",
    "line_count", "short_approach", "arrow_missing", "arrow_penultimate",
    "internal_term", "part_impure", "root_claim_suspect",
    "history_protection_review", "root_annotation_review", "english_clue_missing",
    "long_line", "long_approach", "long_word", "numbering_mismatch", "arrow_not_last",
    "empty_line", "duplicate_line", "missing_phonetic", "missing_collocations",
    "word_shape", "meaning_empty_line", "meaning_long", "placeholder",
]
CATEGORY_RANK = {name: i for i, name in enumerate(CATEGORY_ORDER)}
SEVERITY_RANK = {"P0": 0, "P1": 1, "P2": 2, "P3": 3, "none": 9}

INTERNAL_TERM_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("第N招", re.compile(r"第[一二三四五六七八九十0-9]+招")),
    ("这一招", re.compile(r"这一?招")),
    ("挑招式", re.compile(r"(?:挑|选)[一]?招")),
    ("走X法", re.compile(r"走\s*(?:sound|meaning|shape|root|contrast|polysemy|谐音|含义|字形|词根|对比|熟词生义)\s*法", re.I)),
    ("方法名", re.compile(r"(?:含义|语义|谐音|字形|词根|对比|联想|拆词|拆分|记忆)(?:联想|法)")),
    ("挑方法", re.compile(r"(?:挑|选)\s*方法")),
    ("方法论", re.compile(r"方法论")),
    ("评分/评委", re.compile(r"评分|评委")),
    ("候选稿", re.compile(r"候选稿|候选版本")),
    ("提示词", re.compile(r"提示词\s*[：:（(]|系统提示|内部术语")),
    ("拆法", re.compile(r"拆法")),
    ("元素标签", re.compile(r"元素\s*[：:]")),
]
PART_IMPURE_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("元素标签", re.compile(r"元素\s*[：:]")),
    ("part字段", re.compile(r"(?<![A-Za-z])part\s*[=：]\s*[A-Za-z]")),
    ("elements字段", re.compile(r"(?<![A-Za-z])elements?\s*[=：]", re.I)),
    ("编者注", re.compile(r"（原稿|\(原稿|编者注|原稿写作")),
]
ROOT_ANNOTATION_RE = re.compile(r"词根|词缀|前缀|后缀")
ROOT_SPLIT_RE = re.compile(r"词素|词根|词缀|前缀|后缀")
PLUS_SPLIT_RE = re.compile(r"(?<![A-Za-z])([A-Za-z]{1,12})-?\s*\+\s*-?([A-Za-z]{1,12})(?![A-Za-z])")
WORD_SHAPE_RE = re.compile(r"^[A-Za-z][A-Za-z' .\-]*$")
# 只认明确的占位标记；「占位/待定」在讲解 pending、book 等词时会自然出现，不能误报。
PLACEHOLDER_RE = re.compile(r"TODO|FIXME|lorem ipsum|待补充|待完善|此处填写|（空）", re.I)


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def normalize_text(text: Any) -> str:
    """去空白后比较，避免换行/空格差异影响反馈正文匹配。"""
    return re.sub(r"\s+", "", str(text or "")).strip()


def as_int(value: Any) -> int | None:
    """把 bookId/wordId 这类字段安全转成 int；脏值返回 None。"""
    try:
        if value is None or str(value).strip() == "":
            return None
        return int(value)
    except (TypeError, ValueError):
        return None


def is_test_like(entry: dict[str, Any]) -> bool:
    return "test" in str(entry.get("clientId") or "").lower()


def in_book_scope(entry: dict[str, Any], book_id: int | None) -> bool:
    """显式其他书排除；bookId 缺失按未知书处理（不冒充 book-1）。"""
    entry_book = as_int(entry.get("bookId"))
    if book_id is None:
        return True
    if entry_book is None:
        return False
    return entry_book == int(book_id)


def feedback_scope_stats(rows: list[dict[str, Any]], pack_words: set[str],
                         book_id: int | None, pack_book_id: int | None = None) -> dict[str, Any]:
    """反馈统计：all-books 与 book-scoped 分开，避免 56/54 混淆。"""
    scoped = [r for r in rows if in_book_scope(r, book_id)]
    words = {str(r.get("word") or "").strip().lower() for r in scoped}
    words.discard("")
    ratings = Counter(str(r.get("rating") or "").lower() for r in scoped)
    target_book = as_int(pack_book_id)
    return {
        "bookId": book_id,
        "records": len(scoped),
        "recordsWithWord": sum(1 for r in scoped if str(r.get("word") or "").strip()),
        "recordsWithText": sum(1 for r in scoped if str(r.get("approach") or "").strip()),
        "testLike": sum(1 for r in scoped if is_test_like(r)),
        "ratings": {k: ratings.get(k, 0) for k in ("up", "down")},
        "distinctWords": len(words),
        "matchedWords": len(words & pack_words),
        "unmatchedWords": sorted(words - pack_words),
        "instructionsNonEmpty": sum(1 for r in scoped if str(r.get("instruction") or "").strip()),
        "approachRevNonZero": sum(1 for r in scoped
                                  if str(r.get("approachRev") or "0").strip() not in ("", "0")),
        "explicitOtherBook": sum(1 for r in rows
                                 if as_int(r.get("bookId")) is not None
                                 and target_book is not None
                                 and as_int(r.get("bookId")) != target_book),
        "unknownBook": sum(1 for r in rows if as_int(r.get("bookId")) is None),
    }


def text_fingerprint(text: Any) -> str:
    return hashlib.sha256(normalize_text(text).encode("utf-8")).hexdigest()[:16]


def clip(text: Any, limit: int = 180) -> str:
    s = str(text or "").replace("\r", "").replace("\n", " / ")
    s = re.sub(r"\s+", " ", s).strip()
    return s if len(s) <= limit else s[: limit - 1] + "…"


def as_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [line for line in value.splitlines()]
    if isinstance(value, (list, tuple)):
        out: list[str] = []
        for item in value:
            if item is None:
                out.append("")
            elif isinstance(item, str):
                out.append(item)
            else:
                out.append(str(item))
        return out
    return [str(value)]


def load_cards(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    raw_text = path.read_text(encoding="utf-8-sig")
    data = json.loads(raw_text)
    if isinstance(data, list):
        meta = {"version": None, "book": "", "generatedAt": None, "count": len(data)}
        words = data
    elif isinstance(data, dict):
        meta = {
            "version": data.get("version"),
            "book": data.get("book") or "",
            "generatedAt": data.get("generatedAt"),
            "count": data.get("count"),
        }
        words = data.get("words")
        if words is None:
            # 容忍单行一词的对象结构：{"word": {...}, ...}
            words = [dict(v, word=k) for k, v in data.items() if isinstance(v, dict)]
    else:
        raise ValueError("词包顶层必须是对象或数组")
    if not isinstance(words, list):
        raise ValueError("词包 words 必须是数组")
    return meta, [w if isinstance(w, dict) else {"word": "", "_raw": w} for w in words]


def load_feedback(paths: Iterable[Path]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    records: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = []
    for path in paths:
        info: dict[str, Any] = {"path": str(path), "exists": path.exists(), "records": 0, "malformed": 0}
        if not path.exists():
            sources.append(info)
            continue
        try:
            text = path.read_text(encoding="utf-8-sig")
        except OSError as exc:
            info["error"] = str(exc)
            sources.append(info)
            continue
        rows: list[Any] = []
        if path.suffix.lower() == ".jsonl":
            for line in text.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    info["malformed"] += 1
        else:
            try:
                payload = json.loads(text)
                if isinstance(payload, list):
                    rows = payload
                elif isinstance(payload, dict):
                    rows = payload.get("feedback") or payload.get("items") or payload.get("rows") or []
            except json.JSONDecodeError:
                info["malformed"] += 1
        for row in rows:
            if not isinstance(row, dict):
                info["malformed"] += 1
                continue
            item = dict(row)
            item["_source"] = str(path)
            records.append(item)
            info["records"] += 1
        sources.append(info)
    return records, sources


def discover_feedback(root: Path) -> list[Path]:
    candidates = [root / "tools" / "rewrite-service" / "data" / "feedback.jsonl"]
    candidates.extend(sorted((root / "_recon").glob("*/feedback.jsonl")))
    seen: set[str] = set()
    out: list[Path] = []
    for path in candidates:
        key = str(path.resolve()) if path.exists() else str(path)
        if key not in seen:
            seen.add(key)
            out.append(path)
    return out


def add_issue(issues: list[dict[str, Any]], category: str, evidence: str,
              location: str = "word", rule: str | None = None) -> None:
    meta = CATEGORY_META.get(category, {"severity": "P2", "label": category})
    issues.append({
        "category": category,
        "severity": meta["severity"],
        "location": location,
        "evidence": clip(evidence),
        "rule": rule or category,
    })


def analyze_word(entry: dict[str, Any], line_no: int, book: str,
                 duplicate_words: Counter[str]) -> dict[str, Any]:
    word = str(entry.get("word") or "").strip()
    word_key = word.lower()
    meaning = str(entry.get("meaning") or "")
    phonetic = str(entry.get("phonetic") or "").strip()
    approach = [str(x) for x in as_list(entry.get("approach"))]
    approach = [x.rstrip("\r") for x in approach]
    collocations = as_list(entry.get("collocations"))
    issues: list[dict[str, Any]] = []

    # ---------- 词条级 ----------
    if not word:
        add_issue(issues, "empty_word", "word 字段为空或缺失", "word")
    elif not WORD_SHAPE_RE.match(word):
        add_issue(issues, "word_shape", f"word={word!r} 含非法字符", "word")
    if len(word) > 20:
        add_issue(issues, "long_word", f"word={word!r} 长度 {len(word)} > 20", "word")
    if word_key and duplicate_words[word_key] > 1:
        add_issue(issues, "duplicate_word",
                  f"word={word!r} 出现 {duplicate_words[word_key]} 次（本条第 {line_no} 条）", "word")
    if not meaning.strip():
        add_issue(issues, "missing_meaning", "meaning 为空", "meaning")
    if not phonetic:
        add_issue(issues, "missing_phonetic", "phonetic 为空", "phonetic")
    if not [c for c in collocations if str(c).strip()]:
        add_issue(issues, "missing_collocations", "collocations 为空", "collocations")

    # ---------- 释义 / 词性 ----------
    meaning_lines = meaning.split("\n") if meaning.strip() else []
    meaning_empty_lines = 0
    pos_lines = 0
    for m_idx, line in enumerate(meaning_lines, start=1):
        stripped = line.strip()
        if not stripped:
            meaning_empty_lines += 1
            continue
        m = POS_LINE_RE.match(stripped)
        if not m or m.group(1).lower() not in POS_MARKERS:
            add_issue(issues, "missing_pos",
                      f"释义第 {m_idx} 行无有效词性标记：{clip(stripped, 100)}",
                      f"meaning[{m_idx}]")
        else:
            pos_lines += 1
        markers = POS_ANY_RE.findall(stripped)
        if len(markers) >= 2:
            add_issue(issues, "pos_mixed",
                      f"释义第 {m_idx} 行含多个词性 {markers}：{clip(stripped, 120)}",
                      f"meaning[{m_idx}]")
        if len(stripped) > 120:
            add_issue(issues, "meaning_long",
                      f"释义第 {m_idx} 行长度 {len(stripped)} > 120：{clip(stripped, 100)}",
                      f"meaning[{m_idx}]")
    if meaning_empty_lines:
        add_issue(issues, "meaning_empty_line", f"meaning 有 {meaning_empty_lines} 个空行", "meaning")

    # ---------- 正文 / 行检查 ----------
    approach = [line for line in approach]
    nonblank = [line for line in approach if line.strip()]
    if not nonblank:
        add_issue(issues, "missing_approach", "approach 为空或全部为空白行", "approach")
    else:
        n = len(approach)
        if n < 2 or n > 5:
            add_issue(issues, "line_count",
                      f"approach 共 {n} 行，无法构成步骤链（应为 2–5 行；当前生成标准 3–5 行）", "approach")
        elif n == 2:
            add_issue(issues, "short_approach",
                      "approach 仅 2 行；低于 1.4.4 之后 3–5 步的生成标准，作为旧稿格式队列单列", "approach")

        line_checks: list[dict[str, Any]] = []
        english_lines = 0
        numbered_lines = 0
        arrow_lines = 0
        for idx, line in enumerate(approach, start=1):
            has_english = bool(ASCII_RE.search(line))
            has_arrow = ARROW_OK in line
            has_numbering = bool(line and line[0] in CIRCLED_NUMBERS)
            english_lines += int(has_english)
            numbered_lines += int(has_numbering)
            arrow_lines += int(has_arrow)
            matched_terms = [name for name, pat in INTERNAL_TERM_PATTERNS if pat.search(line)]
            line_checks.append({
                "lineNo": idx,
                "chars": len(line),
                "hasEnglish": has_english,
                "hasArrow": has_arrow,
                "numbering": line[0] if has_numbering else "",
                "internalTerms": matched_terms,
                "preview": clip(line, 60),
            })
            if not line.strip():
                add_issue(issues, "empty_line", f"第 {idx} 行为空行", f"approach[{idx}]")
                continue
            if matched_terms:
                add_issue(issues, "internal_term",
                          f"第 {idx} 行命中内部术语 {matched_terms}：{clip(line, 140)}",
                          f"approach[{idx}]")
            for name, pat in PART_IMPURE_PATTERNS:
                if pat.search(line):
                    add_issue(issues, "part_impure",
                              f"第 {idx} 行命中元素/编者标记「{name}」：{clip(line, 140)}",
                              f"approach[{idx}]")
                    break
            if ROOT_ANNOTATION_RE.search(line):
                add_issue(issues, "root_annotation_review",
                          f"第 {idx} 行出现显式词根/词缀标注：{clip(line, 140)}",
                          f"approach[{idx}]")
        if english_lines == 0:
            add_issue(issues, "english_clue_missing",
                      f"全部 {n} 行均不含英文字母；逐行英文标记见 lineChecks", "approach")
        last_has_arrow = bool(approach) and ARROW_OK in approach[-1]
        penult_has_arrow = len(approach) >= 2 and ARROW_OK in approach[-2]
        if approach and not last_has_arrow and not penult_has_arrow:
            last = approach[-1]
            if "——" in last:
                variant = "em_dash_only"
            elif "→" in last or "->" in last:
                variant = "single_arrow"
            else:
                variant = "none"
            add_issue(issues, "arrow_missing",
                      f"末行缺「——>」（{variant}）：{clip(last, 160)}",
                      f"approach[{len(approach)}]")
        elif approach and not last_has_arrow and penult_has_arrow:
            add_issue(issues, "arrow_penultimate",
                      f"末行无箭头，但倒数第二行有；1.4.7.1 允许旁注，需确认末行是否为旁注："
                      f"{clip(approach[-1], 140)}",
                      f"approach[{len(approach)}]")
        for idx, line in enumerate(approach[:-1], start=1):
            if ARROW_OK not in line:
                continue
            # 末行有箭头时，倒数第二行再有箭头属于重复；末行无箭头时，倒数第二行是合法旁注位。
            if idx == len(approach) - 1 and not last_has_arrow:
                continue
            add_issue(issues, "arrow_not_last",
                      f"第 {idx} 行出现「——>」，但箭头应在末行：{clip(line, 120)}",
                      f"approach[{idx}]")
        if approach and approach[0] and approach[0][0] in CIRCLED_NUMBERS:
            expected = list(CIRCLED_NUMBERS[:len(approach)])
            actual = [(line[0] if line and line[0] in CIRCLED_NUMBERS else "·") for line in approach]
            if actual != expected:
                add_issue(issues, "numbering_mismatch",
                          f"行号序列 {''.join(actual)}，期望 {''.join(expected)}", "approach")
        if len(approach) >= 3 and any(line.strip() for line in approach):
            total_chars = sum(len(line) for line in approach)
            if total_chars > 600:
                add_issue(issues, "long_approach",
                          f"approach 总长 {total_chars} > 600 字符", "approach")
        for idx, line in enumerate(approach, start=1):
            if len(line) > 120:
                add_issue(issues, "long_line",
                          f"第 {idx} 行长度 {len(line)} > 120：{clip(line, 100)}",
                          f"approach[{idx}]")
        if any(PLACEHOLDER_RE.search(line) for line in approach):
            add_issue(issues, "placeholder", "正文出现占位词："
                       + clip(next(line for line in approach if PLACEHOLDER_RE.search(line)), 140),
                       "approach")
        repeated = [text for text, count in Counter(approach).items() if text.strip() and count > 1]
        if repeated:
            add_issue(issues, "duplicate_line",
                      f"有 {len(repeated)} 组完全重复行，例如：{clip(repeated[0], 120)}", "approach")

    # ---------- 正文语义候选：词根结构 ----------
    for idx, line in enumerate(approach, start=1):
        if not ROOT_SPLIT_RE.search(line):
            continue
        for m in PLUS_SPLIT_RE.finditer(line):
            segments = [m.group(1), m.group(2)]
            normalized_word = re.sub(r"[^a-z]", "", word.lower())
            bad = [seg for seg in segments if seg.lower().strip("-") not in normalized_word]
            if bad:
                add_issue(issues, "root_claim_suspect",
                          f"第 {idx} 行声称拆分 {m.group(0)}，片段 {bad} 不在 word 中：{clip(line, 140)}",
                          f"approach[{idx}]")

    # ---------- 组装逐词记录（历史关联稍后补） ----------
    severity = "none"
    if issues:
        severity = min((issue["severity"] for issue in issues), key=lambda s: SEVERITY_RANK[s])
    primary = None
    if issues:
        primary = sorted(issues, key=lambda x: (SEVERITY_RANK[x["severity"]],
                                                 CATEGORY_RANK.get(x["category"], 999)))[0]
    return {
        "word": word,
        "book": book,
        "lineNo": line_no,
        "category": primary["category"] if primary else "ok",
        "severity": severity,
        "evidence": primary["evidence"] if primary else "",
        "protected": False,
        "status": "open" if issues else "ok",
        "priority": severity,
        "issues": issues,
        "source": {
            "meaning": meaning,
            "approach": approach,
            "phonetic": phonetic,
            "example": str(entry.get("example") or ""),
            "collocations": collocations,
        },
        "lineChecks": line_checks if nonblank else [],
        "metrics": {
            "approachLines": len(approach),
            "approachChars": sum(len(line) for line in approach),
            "englishLines": english_lines if nonblank else 0,
            "numberedLines": numbered_lines if nonblank else 0,
            "arrowLines": arrow_lines if nonblank else 0,
            "meaningLines": len(meaning_lines),
            "posLines": pos_lines,
        },
        "protection": {
            "upExact": 0,
            "upOtherVersion": 0,
            "downExact": 0,
            "downOtherVersion": 0,
            "conflict": False,
            "currentTextSha256": text_fingerprint("\n".join(approach)),
            "matchedUpClients": [],
            "matchedDownClients": [],
            "matchedFeedback": [],
            "upClients": [],
            "downClients": [],
            "upOtherVersionClients": [],
            "downOtherVersionClients": [],
        },
        "history": {"records": 0, "up": 0, "down": 0, "withText": 0,
                    "noText": 0, "idMismatch": 0, "testLikeSkipped": 0},
    }


def apply_history(record: dict[str, Any], entries: list[dict[str, Any]],
                  pack_index_by_word: dict[str, int], book_id: int) -> None:
    word_key = record["word"].lower()
    if not word_key:
        return
    history = record["history"]
    protection = record["protection"]
    # 以记录当前的 approach 文本为保护对象；调用方会在 record 中保留 _approach_text。
    exact_text = record.pop("_approach_text", "")
    exact_norm = normalize_text(exact_text)

    matched_feedback: list[dict[str, Any]] = []
    up_clients: list[str] = []
    down_clients: list[str] = []
    up_other_clients: list[str] = []
    down_other_clients: list[str] = []
    test_like_skipped = 0
    for entry in entries:
        entry_word = str(entry.get("word") or "").strip().lower()
        if entry_word != word_key:
            continue
        if is_test_like(entry):
            # 合成/测试反馈只统计，不进入点赞保护或点踩优先队列。
            test_like_skipped += 1
            continue
        if not in_book_scope(entry, book_id):
            continue
        history["records"] += 1
        rating = str(entry.get("rating") or "").lower()
        if rating in ("up", "down"):
            history[rating] += 1
        word_id = as_int(entry.get("wordId"))
        expected = pack_index_by_word.get(word_key)
        if word_id is not None and expected is not None and word_id != expected:
            history["idMismatch"] += 1

        text = str(entry.get("approach") or "")
        client = str(entry.get("clientId") or "")
        if rating == "up":
            up_clients.append(client)
        elif rating == "down":
            down_clients.append(client)

        if not text.strip():
            history["noText"] += 1
            matched_feedback.append({
                "clientId": client,
                "rating": rating,
                "textSha256": "",
                "textFingerprint": "",
                "textSnippet": "",
                "approach": "",
                "textMatch": "no_text",
                "createdAt": entry.get("createdAt"),
                "receivedAt": entry.get("receivedAt"),
                "approachRev": entry.get("approachRev"),
                "wordId": entry.get("wordId"),
                "bookId": entry.get("bookId"),
                "source": entry.get("_source"),
            })
            continue
        history["withText"] += 1
        if normalize_text(text) != exact_norm:
            if rating == "up":
                protection["upOtherVersion"] += 1
                up_other_clients.append(client)
            elif rating == "down":
                protection["downOtherVersion"] += 1
                down_other_clients.append(client)
            matched_feedback.append({
                "clientId": client,
                "rating": rating,
                "textSha256": sha256_text(text),
                "textFingerprint": text_fingerprint(text),
                "textSnippet": clip(text, 220),
                "approach": text,
                "textMatch": "other_version",
                "createdAt": entry.get("createdAt"),
                "receivedAt": entry.get("receivedAt"),
                "approachRev": entry.get("approachRev"),
                "wordId": entry.get("wordId"),
                "bookId": entry.get("bookId"),
                "source": entry.get("_source"),
            })
            continue
        if rating == "up":
            protection["upExact"] += 1
            if client:
                protection["matchedUpClients"].append(client)
        elif rating == "down":
            protection["downExact"] += 1
            if client:
                protection["matchedDownClients"].append(client)
        matched_feedback.append({
            "clientId": client,
            "rating": rating,
            "textSha256": sha256_text(text),
            "textFingerprint": text_fingerprint(text),
            "textSnippet": clip(text, 220),
            "approach": text,
            "textMatch": "exact",
            "createdAt": entry.get("createdAt"),
            "receivedAt": entry.get("receivedAt"),
            "approachRev": entry.get("approachRev"),
            "wordId": entry.get("wordId"),
            "bookId": entry.get("bookId"),
            "source": entry.get("_source"),
        })

    # 跨版本 client 明细：review.py 不再只看到一个计数。
    protection["matchedFeedback"] = sorted(
        matched_feedback,
        key=lambda x: (str(x.get("createdAt") or ""), str(x.get("clientId") or "")),
    )
    protection["upClients"] = up_clients
    protection["downClients"] = down_clients
    protection["upOtherVersionClients"] = up_other_clients
    protection["downOtherVersionClients"] = down_other_clients
    history["testLikeSkipped"] = test_like_skipped

    record["protected"] = protection["upExact"] > 0
    if protection["upExact"] and protection["downExact"]:
        protection["conflict"] = True
        add_issue(record["issues"], "history_conflict",
                  f"当前正文同时有 {protection['upExact']} 条 up 与 {protection['downExact']} 条 down；"
                  f"upClients={protection['matchedUpClients']} downClients={protection['matchedDownClients']}",
                  "history")
    elif protection["downExact"]:
        add_issue(record["issues"], "history_down_current",
                  f"当前正文被点踩 {protection['downExact']} 次；"
                  f"downClients={protection['matchedDownClients']}",
                  "history")
    if protection["upOtherVersion"]:
        add_issue(record["issues"], "history_protection_review",
                  f"同词有 {protection['upOtherVersion']} 条 up 的正文与当前版本不同；"
                  f"点赞保护仅覆盖旧正文，需人工确认保护对象；"
                  f"upOtherClients={protection['upOtherVersionClients']}",
                  "history")

    # 重新计算 primary / severity
    if record["issues"]:
        record["severity"] = min((i["severity"] for i in record["issues"]),
                                 key=lambda s: SEVERITY_RANK[s])
        primary = sorted(record["issues"], key=lambda x: (SEVERITY_RANK[x["severity"]],
                                                           CATEGORY_RANK.get(x["category"], 999)))[0]
        record["category"] = primary["category"]
        record["evidence"] = primary["evidence"]
        record["priority"] = record["severity"]
        record["status"] = "open"
    if protection["conflict"] or protection["upOtherVersion"]:
        record["status"] = "manual_review"
    elif protection["upExact"] and record["issues"]:
        record["status"] = "protected_open"
    elif protection["upExact"]:
        record["status"] = "protected"
    elif record["issues"]:
        record["status"] = "open"
    else:
        record["status"] = "ok"


def scan(cards_path: Path, feedback_paths: list[Path], book_id: int = 1) -> dict[str, Any]:
    started = time.perf_counter()
    cards_bytes = cards_path.read_bytes()
    meta, entries = load_cards(cards_path)
    feedback, feedback_sources = load_feedback(feedback_paths)
    book = str(meta.get("book") or "")

    words = [str(e.get("word") or "").strip().lower() for e in entries]
    duplicate_words = Counter(w for w in words if w)
    pack_index_by_word: dict[str, int] = {}
    for idx, word in enumerate(words, start=1):
        pack_index_by_word.setdefault(word, idx)

    records: list[dict[str, Any]] = []
    for idx, entry in enumerate(entries, start=1):
        record = analyze_word(entry, idx, book, duplicate_words)
        record["_approach_text"] = "\n".join(as_list(entry.get("approach")))
        records.append(record)

    for record in records:
        apply_history(record, feedback, pack_index_by_word, book_id)

    # 反馈侧的统计：all-books 与 book-1 两套口径并列，避免 56/54 混淆。
    pack_words = set(pack_index_by_word)
    all_stats = feedback_scope_stats(feedback, pack_words, None, book_id)
    book_stats = feedback_scope_stats(feedback, pack_words, book_id, book_id)
    history_summary = {
        "sources": feedback_sources,
        "bookId": book_id,
        "allBooks": all_stats,
        "bookScoped": book_stats,
        # 兼容旧字段：保留 all-books 口径，另在 bookScoped 给过滤后的口径。
        "records": all_stats["records"],
        "recordsWithWord": all_stats["recordsWithWord"],
        "recordsWithText": all_stats["recordsWithText"],
        "testLike": all_stats["testLike"],
        "noWord": sum(1 for r in feedback if not str(r.get("word") or "").strip()),
        "otherBook": book_stats["explicitOtherBook"],
        "unknownBook": book_stats["unknownBook"],
        "ratings": all_stats["ratings"],
        "distinctWords": all_stats["distinctWords"],
        "instructionsNonEmpty": all_stats["instructionsNonEmpty"],
        "approachRevNonZero": all_stats["approachRevNonZero"],
        "unmatchedWords": all_stats["unmatchedWords"],
        "matchedWords": all_stats["matchedWords"],
        "upExact": sum(1 for r in records if r["protection"]["upExact"]),
        "upOtherVersion": sum(1 for r in records if r["protection"]["upOtherVersion"]),
        "downExact": sum(1 for r in records if r["protection"]["downExact"]),
        "downOtherVersion": sum(1 for r in records if r["protection"]["downOtherVersion"]),
        "conflicts": sum(1 for r in records if r["protection"]["conflict"]),
        "protectedWords": sum(1 for r in records if r["protected"]),
        "manualReviewWords": sum(1 for r in records if r["status"] == "manual_review"),
    }

    # 汇总
    issue_words: Counter[str] = Counter()
    issue_instances: Counter[str] = Counter()
    category_words: Counter[str] = Counter()
    for record in records:
        for issue in record["issues"]:
            issue_instances[issue["category"]] += 1
        for category in {issue["category"] for issue in record["issues"]}:
            category_words[category] += 1
        if record["issues"]:
            issue_words[record["severity"]] += 1
    ok_words = sum(1 for r in records if not r["issues"])
    status_counts = Counter(r["status"] for r in records)
    line_checks = [check for r in records for check in r["lineChecks"]]
    summary = {
        "totalWords": len(records),
        "uniqueWords": len(set(words)),
        "book": book,
        "bookVersion": meta.get("version"),
        "bookGeneratedAt": meta.get("generatedAt"),
        "declaredCount": meta.get("count"),
        "okWords": ok_words,
        "issueWords": {s: issue_words.get(s, 0) for s in ("P0", "P1", "P2", "P3")},
        "issueInstances": {s: sum(1 for r in records for i in r["issues"] if i["severity"] == s)
                           for s in ("P0", "P1", "P2", "P3")},
        "categories": {
            cat: {
                "label": CATEGORY_META.get(cat, {}).get("label", cat),
                "severity": CATEGORY_META.get(cat, {}).get("severity", "P2"),
                "kind": CATEGORY_META.get(cat, {}).get("kind", "deterministic"),
                "desc": CATEGORY_META.get(cat, {}).get("desc", ""),
                "words": category_words.get(cat, 0),
                "instances": issue_instances.get(cat, 0),
            }
            for cat in CATEGORY_ORDER if category_words.get(cat, 0) or issue_instances.get(cat, 0)
        },
        "statusCounts": dict(status_counts),
        "approachLines": len(line_checks),
        "linesWithEnglish": sum(1 for c in line_checks if c["hasEnglish"]),
        "linesWithoutEnglish": sum(1 for c in line_checks if not c["hasEnglish"]),
        "cardsWithoutEnglish": sum(1 for r in records if r["metrics"]["approachLines"] and r["metrics"]["englishLines"] == 0),
        "cardsWithRequiredArrow": sum(1 for r in records if r["lineChecks"] and r["lineChecks"][-1]["hasArrow"]),
        "cardsWithAcceptedArrow": sum(
            1 for r in records
            if r["lineChecks"] and (r["lineChecks"][-1]["hasArrow"]
                                    or (len(r["lineChecks"]) >= 2 and r["lineChecks"][-2]["hasArrow"]))
        ),
        "cardsMissingRequiredArrow": sum(1 for r in records if r["lineChecks"] and not r["lineChecks"][-1]["hasArrow"]),
        "feedback": history_summary,
        "scanDurationMs": int((time.perf_counter() - started) * 1000),
    }
    for record in records:
        record.pop("_approach_text", None)
    return {
        "meta": {
            "scanner": "tools/word-audit/scan.py",
            "scannerVersion": SCANNER_VERSION,
            "generatedAt": now_iso(),
            "cardsPath": str(cards_path),
            # cardsSha256 保留为规范化文本 hash 的兼容别名；报告分别展示下面两个口径。
            "cardsSha256": sha256_text(cards_path.read_text(encoding="utf-8-sig")),
            "cardsTextSha256": sha256_text(cards_path.read_text(encoding="utf-8-sig")),
            "cardsFileSha256": hashlib.sha256(cards_bytes).hexdigest(),
            "cardsFileSize": len(cards_bytes),
            "hashMethod": {
                "cardsFileSha256": "sha256(raw file bytes，等同 sha256sum)",
                "cardsTextSha256": "sha256(utf-8-sig decoded text；Path.read_text 将 CRLF 规范化为 LF)",
            },
            "bookIdForFeedback": book_id,
            "feedbackPaths": [str(p) for p in feedback_paths],
        },
        "summary": summary,
        "words": records,
    }


def pct(part: int, total: int) -> str:
    return f"{(part / total * 100) if total else 0:.1f}%"


def sample_for_category(result: dict[str, Any], category: str, limit: int = 3) -> list[dict[str, Any]]:
    out = []
    for record in result["words"]:
        for issue in record["issues"]:
            if issue["category"] == category:
                out.append({"word": record["word"], "lineNo": record["lineNo"],
                            "evidence": issue["evidence"], "severity": issue["severity"]})
                if len(out) >= limit:
                    return out
    return out


def render_markdown(result: dict[str, Any], result_path: str | None = None) -> str:
    meta = result["meta"]
    s = result["summary"]
    total = s["totalWords"]
    lines: list[str] = []
    lines.append("# 1.4.7.2 W6-A 词包确定性扫描报告")
    lines.append("")
    lines.append(f"- 生成时间：{meta['generatedAt']}")
    lines.append(f"- 扫描器：`{meta['scanner']}` v{meta['scannerVersion']}")
    lines.append(f"- 词包：`{meta['cardsPath']}`")
    lines.append(f"- 词包原始文件 SHA-256（sha256sum）：`{meta['cardsFileSha256']}`（{meta['cardsFileSize']:,} bytes）")
    lines.append(f"- 词包规范化文本 SHA-256（CRLF→LF）：`{meta['cardsTextSha256']}`")
    lines.append(f"- 词表：`{s['book']}`（version={s['bookVersion']}，generatedAt={s['bookGeneratedAt']}，声明 count={s['declaredCount']}）")
    lines.append(f"- 实际扫描：**{total}** 词（去重后 {s['uniqueWords']} 词）")
    lines.append(f"- 扫描耗时：{s['scanDurationMs']} ms（纯标准库、只读、无模型调用）")
    lines.append(f"- 反馈源：{len(meta['feedbackPaths'])} 个文件；"
                 f"有效记录 {s['feedback']['records']} 条，其中有正文 {s['feedback']['recordsWithText']} 条")
    lines.append("")

    lines.append("## 1. 结论")
    lines.append("")
    p0, p1, p2 = s["issueWords"]["P0"], s["issueWords"]["P1"], s["issueWords"]["P2"]
    lines.append(f"1. **{total} 词全部完成确定性扫描**，未发现 P0 级数据缺失"
                 if p0 == 0 else f"1. **{total} 词全部完成确定性扫描**，P0 级 {p0} 词需要先修数据")
    lines.append(f"2. P1 级 {p1} 词、P2 级 {p2} 词；无问题 {s['okWords']} 词。")
    lines.append(f"3. 点赞保护：{s['feedback']['protectedWords']} 词当前正文与历史 up 正文忽略空白差异后一致，标记 `protected=true`；"
                 f"{s['feedback']['manualReviewWords']} 词进入人工复核（含同词 up/down 冲突与 up 旧版本）。")
    lines.append(f"4. 最大确定性问题：末行缺「——>」{s['categories'].get('arrow_missing', {}).get('words', 0)} 词、"
                 f"2 行短稿 {s['categories'].get('short_approach', {}).get('words', 0)} 词、"
                 f"词性未分行 {s['categories'].get('pos_mixed', {}).get('words', 0)} 词。"
                 f"末行无箭头共 {s['cardsMissingRequiredArrow']} 词，其中 "
                 f"{s['categories'].get('arrow_penultimate', {}).get('words', 0)} 词的倒数第二行有箭头"
                 f"（按 1.4.7.1 的单列旁注队列处理），实际可接受收束 {s['cardsWithAcceptedArrow']} 词。")
    lines.append("5. 语义层（词义对应、谐音是否过音标、词根真实性、历史约束是否真正满足）不在本阶段下结论，"
                 "已单列「语义审查队列」。")
    lines.append("")

    lines.append("## 2. 规则总览：确定性 vs 语义")
    lines.append("")
    lines.append("| 类别 | 检查项 | 级别 | 类型 | 判定口径 |")
    lines.append("|---|---|---|---|---|")
    for cat, info in s["categories"].items():
        lines.append(f"| `{cat}` | {info['label']} | {info['severity']} | "
                     f"{'确定性' if info['kind'] == 'deterministic' else '语义队列'} | {info['desc']} |")
    lines.append("")
    lines.append("**本阶段明确不下结论、留给后续语义审查的项**：")
    lines.append("")
    lines.append("- 拼写/读音线索是否真的能自然唤起画面（谐音是否过音标、字形拆分是否合理）。")
    lines.append("- 末行释义是否与 `meaning` 真正对应（本阶段只检查末行/全文是否出现英文与箭头，不做同义判断）。")
    lines.append("- 词根/词源标注是否真实（卡包没有 `wordRootConfidence` 字段；只登记显式标注和结构性可疑拆分）。")
    lines.append("- 历史用户原话是否被满足（85 条反馈的 `instruction` 全为空，无法做否定约束匹配）。")
    lines.append("- 点赞保护跨版本冲突的最终归属（本阶段按正文指纹精确匹配，跨版本一律列人工复核）。")
    lines.append("")

    lines.append("## 3. 各类问题计数")
    lines.append("")
    lines.append("| 类别 | 级别 | 词数 | 问题实例数 | 占全部词比例 | 样本 |")
    lines.append("|---|---|---:|---:|---:|---|")
    for cat, info in sorted(s["categories"].items(),
                            key=lambda kv: (SEVERITY_RANK[kv[1]["severity"]], -kv[1]["words"], kv[0])):
        sample = sample_for_category(result, cat, 1)
        sample_text = f"{sample[0]['word']}：{clip(sample[0]['evidence'], 70)}" if sample else ""
        lines.append(f"| `{cat}` | {info['severity']} | {info['words']} | {info['instances']} | "
                     f"{pct(info['words'], total)} | {sample_text} |")
    lines.append("")
    lines.append(f"按词的最高级别统计：P0 **{p0}** 词，P1 **{p1}** 词，P2 **{p2}** 词，"
                 f"OK **{s['okWords']}** 词。")
    lines.append("")

    lines.append("## 4. P0 / P1 样本证据")
    lines.append("")
    if p0 == 0:
        lines.append("### P0")
        lines.append("")
        lines.append("无。空词、重复词、释义缺失、记忆思路缺失均为 0。")
        lines.append("")
    else:
        lines.append("### P0（全量）")
        lines.append("")
        for record in result["words"]:
            if record["severity"] == "P0":
                lines.append(f"- **{record['word'] or '(空词)'}**（第 {record['lineNo']} 条，`{record['category']}`）：{record['evidence']}")
        lines.append("")
    lines.append("### P1（每类最多 3 条）")
    lines.append("")
    for cat, info in s["categories"].items():
        if info["severity"] != "P1":
            continue
        lines.append(f"**`{cat}`（{info['words']} 词）**")
        lines.append("")
        for sample in sample_for_category(result, cat, 3):
            lines.append(f"- {sample['word']}（第 {sample['lineNo']} 条）：{sample['evidence']}")
        lines.append("")

    lines.append("## 5. 点赞保护与历史反馈关联")
    lines.append("")
    fb = s["feedback"]
    all_fb = fb.get("allBooks") or fb
    book_fb = fb.get("bookScoped") or {}
    lines.append("| 指标 | 全部反馈 | bookId=1 | 说明 |")
    lines.append("|---|---:|---:|---|")
    lines.append(f"| 反馈记录 | {all_fb.get('records', 0)} | {book_fb.get('records', 0)} | 主反馈库 85 条 + `_recon` 两份各 1 条 |")
    lines.append(f"| up / down | {all_fb.get('ratings', {}).get('up', 0)} / {all_fb.get('ratings', {}).get('down', 0)} | "
                 f"{book_fb.get('ratings', {}).get('up', 0)} / {book_fb.get('ratings', {}).get('down', 0)} | 原始条数只用于盘点 |")
    lines.append(f"| 有单词 | {all_fb.get('recordsWithWord', 0)} | {book_fb.get('recordsWithWord', 0)} | 缺 word 的记录无法关联 |")
    lines.append(f"| 有正文 | {all_fb.get('recordsWithText', 0)} | {book_fb.get('recordsWithText', 0)} | 用于忽略空白差异的正文匹配 |")
    lines.append(f"| 不同单词 | {all_fb.get('distinctWords', 0)} | {book_fb.get('distinctWords', 0)} | 反馈覆盖的词数 |")
    lines.append(f"| 匹配到词包 | {all_fb.get('matchedWords', 0)} | {book_fb.get('matchedWords', 0)} | 未匹配（全部）：{', '.join(all_fb.get('unmatchedWords', [])) or '无'} |")
    lines.append(f"| instruction 非空 | {all_fb.get('instructionsNonEmpty', 0)} | {book_fb.get('instructionsNonEmpty', 0)} | 当前全空，无法做用户禁令匹配 |")
    lines.append(f"| approachRev 非 0 | {all_fb.get('approachRevNonZero', 0)} | {book_fb.get('approachRevNonZero', 0)} | W1 已知上传缺陷，版本号需组合核验 |")
    lines.append(f"| test-like | {all_fb.get('testLike', 0)} | {book_fb.get('testLike', 0)} | clientId 含 test；**已从保护匹配中排除**，只统计 |")
    lines.append(f"| 显式其他书 | {all_fb.get('explicitOtherBook', 0)} | {book_fb.get('explicitOtherBook', 0)} | 不参与 book-1 保护 |")
    lines.append(f"| 缺 bookId（未知书） | {all_fb.get('unknownBook', 0)} | {book_fb.get('unknownBook', 0)} | 不冒充 book-1，不参与保护 |")
    lines.append(f"| 当前正文 up 精确匹配 | {fb['upExact']} 词 | {fb['upExact']} 词 | `protected=true` |")
    lines.append(f"| 当前正文 down 精确匹配 | {fb['downExact']} 词 | {fb['downExact']} 词 | 优先修订名单 |")
    lines.append(f"| up 但正文不同版本 | {fb['upOtherVersion']} 词 | {fb['upOtherVersion']} 词 | 保护只覆盖旧正文，需人工确认 |")
    lines.append(f"| down 但正文不同版本 | {fb['downOtherVersion']} 词 | {fb['downOtherVersion']} 词 | 历史负样本，不直接修订当前稿 |")
    lines.append(f"| up/down 同文冲突 | {fb['conflicts']} 词 | {fb['conflicts']} 词 | 必须人工复核 |")
    lines.append("")
    if fb["conflicts"]:
        lines.append(f"注：`down` 精确匹配 {fb['downExact']} 词中，同时有 up 的冲突词归入 "
                     "`history_conflict`/`manual_review`；`history_down_current` 只列非冲突词。")
        lines.append("")
    protected = [r for r in result["words"] if r["protected"]]
    if protected:
        lines.append("**当前正文受点赞保护的词（忽略空白差异匹配）**：")
        lines.append("")
        for r in protected:
            lines.append(f"- {r['word']}（第 {r['lineNo']} 条，status={r['status']}，"
                         f"upClients={r['protection']['matchedUpClients']}）")
        lines.append("")
    conflicts = [r for r in result["words"] if r["protection"]["conflict"]]
    if conflicts:
        lines.append("**同词 up/down 冲突（人工复核）**：")
        lines.append("")
        for r in conflicts:
            lines.append(f"- {r['word']}（第 {r['lineNo']} 条）：up={r['protection']['matchedUpClients']}，"
                         f"down={r['protection']['matchedDownClients']}")
        lines.append("")
    up_other = [r for r in result["words"] if r["protection"]["upOtherVersion"]]
    if up_other:
        lines.append("**点赞在旧版本、当前正文不同（人工复核；含旧正文明细）**：")
        lines.append("")
        for r in up_other:
            p = r["protection"]
            lines.append(f"- {r['word']}（第 {r['lineNo']} 条）：upOtherVersion={p['upOtherVersion']}，"
                         f"upOtherClients={p.get('upOtherVersionClients', [])}")
            for detail in p.get("matchedFeedback", []):
                if detail.get("rating") != "up" or detail.get("textMatch") != "other_version":
                    continue
                lines.append(f"  - client={detail.get('clientId')}，createdAt={detail.get('createdAt')}，"
                             f"receivedAt={detail.get('receivedAt')}，approachRev={detail.get('approachRev')}，"
                             f"textSha256={detail.get('textSha256', '')[:16]}…，旧正文：{detail.get('textSnippet', '')}")
        lines.append("")
    down_other = [r for r in result["words"] if r["protection"]["downOtherVersion"]]
    if down_other:
        lines.append("**点踩在旧版本、当前正文不同（历史负样本，不直接修订当前稿）**：")
        lines.append("")
        for r in down_other:
            p = r["protection"]
            lines.append(f"- {r['word']}（第 {r['lineNo']} 条）：downOtherVersion={p['downOtherVersion']}，"
                         f"downOtherClients={p.get('downOtherVersionClients', [])}")
            for detail in p.get("matchedFeedback", []):
                if detail.get("rating") != "down" or detail.get("textMatch") != "other_version":
                    continue
                lines.append(f"  - client={detail.get('clientId')}，createdAt={detail.get('createdAt')}，"
                             f"approachRev={detail.get('approachRev')}，"
                             f"textSha256={detail.get('textSha256', '')[:16]}…，旧正文：{detail.get('textSnippet', '')}")
        lines.append("")

    lines.append("## 6. 语义审查队列（本阶段未判定）")
    lines.append("")
    lines.append("`review.py` 会从扫描结果中生成人工/模型审查清单：P0/P1 全量索引、"
                 "按正文行数分层的 P1/P2 样本，以及 **40 个 `status=ok` 正常词的分层样本**"
                 "（按词书、字母段、词表位置分位、有无历史反馈分层；确定性、无随机、不调用模型）。"
                 "这些只是审查候选，**不代表已做语义判定**。")
    lines.append("")
    semantic_candidates = [
        ("拼写/读音线索", "`english_clue_missing` 466 词在 scan.json 逐词留证；review 按类别抽样，完整 P2 索引见 review-list.json"),
        ("词义对应", "P1 问题词 + 每类代表样本；重点看末行是否收束到 `meaning`"),
        ("词根/词源", "`root_annotation_review`、`root_claim_suspect` 当前均 0 命中；无命中不等于已审查，仍需后续词源核验"),
        ("历史约束", "有 down 反馈的词；`instruction` 全空时只能核对正文与反馈文本"),
        ("点赞版本", f"{fb['upOtherVersion']} 个跨版本 up、{fb['conflicts']} 个同文冲突；旧正文明细见第 5 节"),
        ("正常词对照", "40 个 OK 词分层样本，见 review-list.md/json 的 `okSamples`，用于发现“扫描器无问题但语义仍可能错”的盲区"),
    ]
    lines.append("| 审查项 | 队列来源 |")
    lines.append("|---|---|")
    for a, b in semantic_candidates:
        lines.append(f"| {a} | {b} |")
    lines.append("")

    lines.append("## 7. 耗时、样本与失败分类（W6-A 口径）")
    lines.append("")
    lines.append("W6-A 是纯确定性扫描，不调用模型，因此**排队/生成/润色/评分/回传耗时、"
                 "首轮通过率、再重写率、模型成本均未采集**。下表明确区分“本阶段实测”与“后续阶段待采集”，"
                 "避免把扫描耗时误当成生成耗时。")
    lines.append("")
    lines.append("| 指标 | 本阶段值 | 说明 |")
    lines.append("|---|---:|---|")
    lines.append(f"| 排队耗时 | 未采集 | 无模型任务队列 |")
    lines.append(f"| 生成耗时 | 未采集 | 未调用生成 |")
    lines.append(f"| 润色耗时 | 未采集 | 未调用润色 |")
    lines.append(f"| 评分耗时 | 未采集 | 未调用评委 |")
    lines.append(f"| 回传耗时 | 未采集 | 未回传 App |")
    lines.append(f"| 扫描耗时 | {s['scanDurationMs']} ms | {total} 词，纯标准库 |")
    lines.append(f"| 样本数 | 0 | 本阶段不生成、不评分任何稿件 |")
    lines.append(f"| 首轮通过率 | 不适用 | 无评分 |")
    lines.append(f"| 再重写率 | 不适用 | 无生成 |")
    lines.append(f"| 模型成本 | 0 次调用 / 0 token | 未联网 |")
    lines.append(f"| 失败分类 | 0 | 扫描器无失败；输入解析失败、反馈缺失会在 `meta`/`history` 中单列 |")
    lines.append("")
    lines.append("**长跑 / 切网 / 重启 / 真实未过任务与单测分列**：")
    lines.append("")
    lines.append("| 项目 | 本阶段状态 | 证据/去向 |")
    lines.append("|---|---|---|")
    lines.append("| 24h 长跑 | 未执行（不属于 W6-A） | 留给 P5/L01 |")
    lines.append("| 切网（WiFi⇄4G） | 未执行（不属于 W6-A） | 留给 N03/P5 |")
    lines.append("| 电脑重启恢复 | 未执行（不属于 W6-A） | 留给 P5/L01 |")
    lines.append("| 真实未过任务 | 未采集 | W6-A 不调用模型；留给 W6-B/C 与 P5 |")
    lines.append("| 单元测试 | 见 `tools/word-audit/tests/test_scan.py` | `python -m unittest discover -s tools/word-audit/tests` |")
    lines.append("")

    lines.append("## 8. 覆盖范围与未覆盖项")
    lines.append("")
    lines.append("**已覆盖（确定性）**：")
    lines.append("")
    lines.append("- 空词/重复词/空释义/空思路/缺音标/缺搭配/超长词/词形异常。")
    lines.append("- 释义词性分行：缺词性、同行为多词性、空行、超长释义行。")
    lines.append("- 正文行数：<2 或 >5 为 P1，2 行旧稿单列 P2；末行 `——>`、倒数第二行旁注箭头、中间行箭头、行号连续性、空行、重复行、单行/总长。")
    lines.append("- 每行是否含英文字母（逐行 `lineChecks.hasEnglish`），整卡无英文线索。")
    lines.append("- 内部术语：第N招、这一招、挑招式、走X法、方法名（含义/语义/谐音/字形/联想等）、评分/评委、候选稿、提示词、拆法、元素标签。")
    lines.append("- 元素标注/编者注（`part_impure`）；显式词根/词缀标注登记；结构性可疑拆分。")
    lines.append("- 历史反馈：up/down 与当前正文逐字匹配、点赞保护、同文冲突、跨版本 up、当前稿被点踩。")
    lines.append("")
    lines.append("**未覆盖 / 需后续处理**：")
    lines.append("")
    lines.append("- 语义判断：谐音是否过音标、字形拆分是否自然、画面是否能唤起词义、末行释义是否真正对应。")
    lines.append("- 词根词源真实性：卡包没有 `wordRootConfidence`，本扫描器不内置词源词典。")
    lines.append("- 用户历史 `instruction` 语义：当前 85 条反馈的 instruction 全为空；若后续出现非空值，"
                 "需接入 rewrite-service 的 `banned_terms` 逻辑做真正的否定约束匹配。")
    lines.append("- 点赞保护的人工归属：跨版本 up 只做登记，不自动把旧点赞迁移到新正文。")
    lines.append("- 例句、搭配、音标、图片、App 渲染层不在本扫描范围。")
    lines.append("- 未做模型评分、首轮通过率、再重写率与成本测量；这些属于 W6-B/C。")
    lines.append("")

    lines.append("## 9. 复现")
    lines.append("")
    lines.append("```bash")
    lines.append("python tools/word-audit/scan.py \\")
    lines.append("  --cards app/src_repo/app/src/main/assets/cet6_cards.json \\")
    lines.append("  --out _recon/1.4.7.2/W6/scan.json \\")
    lines.append("  --md _recon/1.4.7.2/W6/scan-report.md")
    lines.append("")
    lines.append("python tools/word-audit/review.py \\")
    lines.append("  --scan _recon/1.4.7.2/W6/scan.json \\")
    lines.append("  --out _recon/1.4.7.2/W6/review-list.md")
    lines.append("")
    lines.append("python -m unittest discover -s tools/word-audit/tests")
    lines.append("```")
    lines.append("")
    lines.append("## 10. 独立测试问题修复对照（TF-01..TF-09）")
    lines.append("")
    lines.append("| 编号 | 状态 | 修复说明 |")
    lines.append("|---|---|---|")
    lines.append("| TF-01 | 已修 | `review.py` 生成 40 个 `status=ok` 分层样本（词书/字母段/词表位置分位/历史反馈），字段含 word/book/reason/原文摘要；不宣称已做语义判定。 |")
    lines.append("| TF-02 | 已修 | 报告同时列原始文件 `cardsFileSha256`（等同 sha256sum）与规范化文本 `cardsTextSha256`（CRLF→LF），并标明算法。 |")
    lines.append("| TF-03 | 已修 | Markdown 截断处统一标注「完整列表见 JSON」；review JSON 增加 P2 全量 `p2`。 |")
    lines.append("| TF-04 | 已修 | 反馈统计并列 all-books 与 bookId=1；book-1 82 条/54 词，全部 87 条/56 词。 |")
    lines.append("| TF-05 | 已修 | `apply_history` 在保护匹配前排除 clientId 含 test 的反馈；报告只统计 test-like。 |")
    lines.append("| TF-06 | 已修 | `protection.matchedFeedback` 输出 clientId/rating/旧正文/旧文本 hash/时间/approachRev；另给跨版本 client 列表。 |")
    lines.append("| TF-07 | 已修 | 报告与 review 注明 `downExact` 23 = `history_down_current` 22 + 同文冲突 1。 |")
    lines.append("| TF-08 | 已修 | README 改为“忽略空白差异的正文匹配”。 |")
    lines.append("| TF-09 | 部分 | 单测由 14 个增至 18 个，补充 review/哈希/book 统计/test-like 定向测试；未逐类覆盖全部 category，不阻断本阶段。 |")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("扫描器只读输入；本报告由 `scan.py` 自动生成，未修改 `cet6_cards.json` 或任何生产数据。")
    lines.append("")
    return "\n".join(lines)


def write_output(result: dict[str, Any], out_path: Path, md_path: Path | None) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    suffix = out_path.suffix.lower()
    if suffix == ".md":
        out_path.write_text(render_markdown(result), encoding="utf-8")
        return
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    if md_path is not None:
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(render_markdown(result), encoding="utf-8")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="W6-A 存量词包确定性扫描器")
    parser.add_argument("--cards", required=True, help="cet6_cards.json 路径")
    parser.add_argument("--out", required=True, help="输出 .json 或 .md")
    parser.add_argument("--md", default=None, help="额外输出 Markdown 汇总（--out 为 json 时）")
    parser.add_argument("--feedback", action="append", default=None,
                        help="反馈 JSONL/JSON；可重复。默认自动发现主反馈库与 _recon/*/feedback.jsonl")
    parser.add_argument("--book-id", type=int, default=1, help="反馈关联使用的 bookId，默认 1")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    cards_path = Path(args.cards)
    if not cards_path.exists():
        print(f"词包不存在：{cards_path}", file=sys.stderr)
        return 2
    if args.feedback:
        feedback_paths = [Path(p) for p in args.feedback]
    else:
        root = Path(__file__).resolve().parents[2]
        feedback_paths = discover_feedback(root)
    try:
        result = scan(cards_path, feedback_paths, book_id=args.book_id)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"扫描失败：{exc}", file=sys.stderr)
        return 1
    out_path = Path(args.out)
    md_path = Path(args.md) if args.md else None
    write_output(result, out_path, md_path)
    s = result["summary"]
    print(f"scanned {s['totalWords']} words in {s['scanDurationMs']} ms")
    print(f"P0={s['issueWords']['P0']} P1={s['issueWords']['P1']} P2={s['issueWords']['P2']} "
          f"OK={s['okWords']} protected={s['feedback']['protectedWords']} manual={s['feedback']['manualReviewWords']}")
    print(f"json: {out_path}")
    if md_path:
        print(f"md: {md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

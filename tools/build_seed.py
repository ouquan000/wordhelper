#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 by-word 逐词 JSON 汇总成 App 内置种子文件 cet6_cards.json。

用法：
    python tools/build_seed.py

输入：D:/deepseek harness/workcodex/.tmp/export/by-word/*.json（可用 --src 改）
输出：content/seed/cet6_cards.json
      app/src_repo/app/src/main/assets/cet6_cards.json（同一个文件，给 App 打包用）
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_SRC = Path(r"D:\deepseek harness\workcodex\.tmp\export\by-word")
REPO = Path(__file__).resolve().parent.parent
OUT_REPO = REPO / "content" / "seed" / "cet6_cards.json"
OUT_ASSETS = REPO / "app" / "src_repo" / "app" / "src" / "main" / "assets" / "cet6_cards.json"

BOOK_NAME = "六级记忆卡"
SEED_VERSION = 1

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'’ .\-]*$")
MULTI_SPACE = re.compile(r"[ \u3000]{2,}")

# 这些不是可背的词条，来源数据里是分词噪声
BLACKLIST = {"and", "by", "for", "in", "on", "that", "they", "to", "you", "as", "of"}


def as_text_list(value) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        out = []
        for item in value:
            if isinstance(item, str):
                text = item.strip()
                if text:
                    out.append(text)
            elif item:
                out.append(str(item).strip())
        return [t for t in out if t]
    return []


def clean_meaning(meaning: str) -> str:
    """把 "v. 增加；提高  n. 增长" 这种一行多词性拆成多行，卡片上更好读。"""
    text = (meaning or "").strip()
    if not text:
        return ""
    parts = [p.strip() for p in MULTI_SPACE.split(text) if p.strip()]
    if len(parts) <= 1:
        return text
    # 只有每一段都长得像「词性. 释义」才拆，避免把正常释义切碎
    if all(re.match(r"^[a-z]{1,5}\.", p) for p in parts):
        return "\n".join(parts)
    return text


def build_word(raw: dict) -> dict | None:
    word = (raw.get("word") or "").strip()
    if not word or not WORD_RE.match(word) or len(word) < 3:
        return None
    if word.lower() in BLACKLIST:
        return None
    meaning = clean_meaning(raw.get("meaning") or raw.get("definition") or "")
    if not meaning:
        return None
    phonetic = (raw.get("phonetic") or "").strip()
    if not phonetic:
        return None

    approach = as_text_list(raw.get("derivation"))
    if not approach:
        approach = as_text_list(raw.get("hint"))
    if not approach:
        approach = as_text_list(raw.get("scene"))
    collocations = as_text_list(raw.get("collocations"))

    example = (raw.get("example") or "").strip()
    example_zh = (raw.get("exampleZh") or "").strip()
    example_text = f"{example}\n{example_zh}" if example and example_zh else (example or example_zh)

    if not approach and not collocations:
        return None

    return {
        "word": word,
        "phonetic": phonetic,
        "meaning": meaning,
        "example": example_text,
        "approach": approach,
        "collocations": collocations,
        "_rank": int(raw.get("sourceRank") or 10**6),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(DEFAULT_SRC))
    args = ap.parse_args()

    src = Path(args.src)
    files = sorted(src.glob("*.json"))
    if not files:
        print(f"没有找到源文件：{src}", file=sys.stderr)
        return 1

    words: dict[str, dict] = {}
    dropped = {"no_word": 0, "no_meaning": 0, "no_phonetic": 0, "no_approach": 0, "blacklist": 0}

    for path in files:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            dropped["no_word"] += 1
            continue
        if not isinstance(raw, dict):
            dropped["no_word"] += 1
            continue
        name = (raw.get("word") or "").strip()
        if name.lower() in BLACKLIST:
            dropped["blacklist"] += 1
            continue
        entry = build_word(raw)
        if entry is None:
            key = "no_word" if not name else ("no_phonetic" if not raw.get("phonetic") else "no_meaning")
            dropped[key] += 1
            continue
        words.setdefault(entry["word"].lower(), entry)

    ordered = sorted(words.values(), key=lambda e: (e["_rank"], e["word"].lower()))
    for entry in ordered:
        entry.pop("_rank", None)

    payload = {
        "version": SEED_VERSION,
        "book": BOOK_NAME,
        "generatedAt": __import__("datetime").datetime.now().strftime("%Y-%m-%d %H:%M"),
        "count": len(ordered),
        "words": ordered,
    }

    OUT_REPO.parent.mkdir(parents=True, exist_ok=True)
    OUT_ASSETS.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    OUT_REPO.write_text(text, encoding="utf-8")
    OUT_ASSETS.write_text(text, encoding="utf-8")

    no_approach = sum(1 for w in ordered if not w["approach"])
    no_colloc = sum(1 for w in ordered if not w["collocations"])
    no_example = sum(1 for w in ordered if not w["example"])
    approaches = [len(w["approach"]) for w in ordered if w["approach"]]
    print(f"源文件 {len(files)} 个 → 词条 {len(ordered)} 个")
    print(f"丢弃统计 {dropped}")
    print(f"缺记忆思路 {no_approach} / 缺搭配 {no_colloc} / 缺例句 {no_example}")
    if approaches:
        print(f"记忆思路行数 min/avg/max = {min(approaches)}/{sum(approaches)/len(approaches):.1f}/{max(approaches)}")
    print(f"输出 {OUT_REPO}（{OUT_REPO.stat().st_size/1024/1024:.2f} MB）")
    print(f"拷贝 {OUT_ASSETS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

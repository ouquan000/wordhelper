# -*- coding: utf-8 -*-
"""扫描词库 meaning，排查「行内错位词性标签」候选。
检测规则：一行 meaning 内出现 >=2 种不同词性前缀（前缀切换），
其中切换点之后紧跟无前缀义项段的，最可能是「换行被误写成同行分号」的错位。
输出候选供人工审查，不改任何数据。
"""
import json
import re
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

CARDS = r"D:\deepseek harness\work_anki_enligish\app\src_repo\app\src\main\assets\cet6_cards.json"

POS_TAGS = ["vt.", "vi.", "v.", "n.", "adj.", "adv.",
            "prep.", "conj.", "pron.", "num.", "art.", "int.", "aux."]
POS_PREFIX = re.compile(
    "^(" + "|".join(re.escape(t) for t in POS_TAGS) + ")[ \\t]*",
    re.IGNORECASE,
)
SEG = re.compile("[；;]")


def parse_line(line: str):
    """模拟 MeaningTextFormatter 块内继承，返回 [(pos, text)]。"""
    out = []
    cur = None
    for raw in SEG.split(line):
        seg = raw.strip()
        if not seg:
            continue
        m = POS_PREFIX.match(seg)
        if m:
            cur = m.group(1).lower()
            text = seg[m.end():].strip()
        else:
            text = seg
        if not text:
            continue
        out.append((cur, text))
    return out


def main():
    with open(CARDS, encoding="utf-8") as f:
        cards = json.load(f)["words"]

    candidates = []  # (word, line, prefix_sequence_str, has_bare_after_switch)
    multi_lines = 0
    for card in cards:
        meaning = card.get("meaning") or ""
        for line in meaning.split("\n"):
            parsed = parse_line(line)
            prefixes = [p for p, _ in parsed if p]
            distinct = []
            for p in prefixes:
                if p not in distinct:
                    distinct.append(p)
            if len(distinct) >= 2:
                # 切换点之后是否有无前缀段（继承段）
                bare_after_switch = False
                seen_switch = False
                for i, (p, t) in enumerate(parsed):
                    if p is not None and i > 0 and prefixes[i] != prefixes[i - 1]:
                        seen_switch = True
                    if seen_switch and p is None:
                        bare_after_switch = True
                candidates.append((card.get("word"), line, "→".join(prefixes), bare_after_switch))
                if len(distinct) >= 2:
                    multi_lines += 1

    print("总词条数:", len(cards))
    print("meaning 行内出现>=2种词性前缀的行数:", len(candidates))
    print("其中切换点后紧跟无前缀段(高嫌疑错位):", sum(1 for c in candidates if c[3]))
    print("=" * 80)
    for word, line, seq, bare in candidates:
        flag = "★错位嫌疑" if bare else "  混排"
        print(f"[{flag}] {word} | 前缀序列:{seq}")
        print(f"    {line}")

    # 汇总所有「高嫌疑」词条供后续定向修正
    suspicious = sorted({c[0] for c in candidates if c[3]})
    print("=" * 80)
    print("高嫌疑词条(去重):", len(suspicious))
    print(", ".join(suspicious))


if __name__ == "__main__":
    main()

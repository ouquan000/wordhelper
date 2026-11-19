#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""0ms 确定性硬拦截：在调用 LLM Judge 之前机械扫描最终 derivation。

为什么要有这一层：
- 评委是概率模型，黑名单/步数/箭头这种「一眼就能查」的问题不该花 Judge Token；
- 更不该让「该词表示……」「通过联想……」这类教材腔靠人情分蒙混过关；
- 命中后把具体违规词拼进 feedback，下一轮生成能精准改，不浪费一次完整评审。

对外只暴露一个稳定接口：

    result = check(derivation, instruction="", banned_terms=None)
    result.ok        # bool
    result.hits      # list[str]
    result.feedback  # str，可直接塞回生成侧

banned_terms 既接受 pipeline.banned_terms() 的 [{"needle": ...}] 结构，
也接受 ["词根", ...] 这种纯字符串列表。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# ------------------------------------------------------------------ 词表

# 报幕 / 教材腔：出现即拦截。正则里用 [^\n] 限制在单行内，避免跨行误吞。
TEXTBOOK_PATTERNS = (
    ("该词表示", re.compile(r"该词表示")),
    ("用于描述", re.compile(r"用于描述")),
    ("本词指", re.compile(r"本词指")),
    ("意为", re.compile(r"意为")),
    ("核心意思是", re.compile(r"核心意思是")),
    ("选择…法", re.compile(r"选择[^\n。；;]{0,12}法")),
    ("具体化为", re.compile(r"具体化为")),
    ("通过联想", re.compile(r"通过联想")),
    ("整体绑定", re.compile(r"整体绑定")),
)

# 翻译腔：出现即拦截。
TRANSLATION_PATTERNS = (
    ("进行…", re.compile(r"进行[^\n]{1,6}")),
    ("使得", re.compile(r"使得")),
    ("对于…而言", re.compile(r"对于[^\n]{1,10}而言")),
    ("通过…来", re.compile(r"通过[^\n]{1,8}来")),
    ("在…的情况下", re.compile(r"在[^\n]{1,10}的情况下")),
)

# 没有显式 banned_terms 时，从用户原话里兜底抽「不要 X」的招名。
METHOD_WORDS = (
    "词根词缀", "词根", "词缀", "谐音联想", "谐音", "字形联想", "字形",
    "对比辨析", "对比", "熟词生义", "生义", "含义联想",
)
BAN_TRIGGER = re.compile(
    r"(?:不要|别再|别用|不需要|禁止|不能用|不许|不可以|不想用|(?<![分特个区性类])别)"
    r"([^，,。；;！!？?\n]{1,30})"
)
NEGATION_MARKERS = ("不", "别", "没", "勿", "非", "无需", "无须", "不用", "不要", "禁止", "避免")
NEGATION_STOPS = "，。；：！？、\n\r\t—…"

# 末行之后允许挂一句旁注（normalize 也允许这种写法）。
NOTE_PREFIX = re.compile(r"^\s*(?:注|附|顺带|ps|p\.s\.)[:：]?", re.IGNORECASE)

ARROW = "——>"
MIN_STEPS, MAX_STEPS = 3, 5
_NUM_PREFIX = re.compile(r"^\s*(?:[①-⑳]|\d{1,2}\s*[.、,，:：)\]])\s*")


@dataclass
class HardResult:
    """结构化结果；同时支持 result["ok"] / result.ok 两种读法。"""

    ok: bool
    hits: list[str] = field(default_factory=list)
    feedback: str = ""

    def __getitem__(self, key):
        if key in ("ok", "hits", "feedback"):
            return getattr(self, key)
        raise KeyError(key)

    def get(self, key, default=None):
        return getattr(self, key, default)

    def to_dict(self):
        return {"ok": self.ok, "hits": list(self.hits), "feedback": self.feedback}


def _clean_lines(derivation):
    """把 list/str 统一成非空行列表，顺手剥掉 ①②③ 与 1. 这类序号。"""
    if derivation is None:
        return []
    if isinstance(derivation, str):
        raw = derivation.splitlines()
    else:
        raw = []
        for item in derivation:
            text = str(item or "")
            raw.extend(text.splitlines())
    lines = []
    for text in raw:
        text = _NUM_PREFIX.sub("", str(text).strip()).strip()
        if text:
            lines.append(text)
    return lines


def _negated(text, start):
    """看命中词前 6 个字符是不是否定语境（「不像词根那样拆」不算违规）。"""
    window = text[max(0, start - 6):start]
    for stop in NEGATION_STOPS:
        cut = window.rfind(stop)
        if cut >= 0:
            window = window[cut + 1:]
    return any(marker in window for marker in NEGATION_MARKERS)


def _ban_needles(banned_terms, instruction):
    """banned_terms 归一成 needle 字符串列表；没有就从句子里兜底抽招名。"""
    out = []
    if isinstance(banned_terms, str):
        banned_terms = [banned_terms]
    for item in banned_terms or ():
        if isinstance(item, dict):
            needle = str(item.get("needle") or item.get("label") or "").strip()
        else:
            needle = str(item or "").strip()
        if needle and needle not in out:
            out.append(needle)
    if out or not instruction:
        return out
    text = str(instruction)
    seen = set()
    for match in BAN_TRIGGER.finditer(text):
        clause = match.group(1)
        for word in METHOD_WORDS:
            if word in clause and word not in seen:
                seen.add(word)
                out.append(word)
    return out


def _feedback(hits):
    if not hits:
        return ""
    rows = ["这版没过确定性硬拦截，下面每一条都必须改："]
    rows.extend("- " + hit for hit in hits)
    rows.append("只改 derivation：保持 3–5 行，末行必须是「——> 中文释义」；上面点名的词一个都不许留。")
    return "\n".join(rows)


def check(derivation, instruction="", banned_terms=None):
    """机械扫描最终 3–5 步推导，返回 HardResult。

    依次检查：行数、末行箭头、教材腔、翻译腔、用户禁令。
    所有命中都写进 hits / feedback，ok 只有在零命中时为 True。
    """
    lines = _clean_lines(derivation)
    hits = []

    def add(hit):
        if hit and hit not in hits:
            hits.append(hit)

    if not lines:
        add("derivation 为空")
    else:
        if not (MIN_STEPS <= len(lines) <= MAX_STEPS):
            add("步数 %d（必须 3–5 行）" % len(lines))
        last = lines[-1]
        if ARROW not in last:
            # 末行是「注：……」这类旁注时，箭头在倒数第二行也认（与 pipeline.normalize 一致）。
            note_after_arrow = (
                len(lines) >= 2
                and ARROW in lines[-2]
                and NOTE_PREFIX.match(last)
            )
            if not note_after_arrow:
                add("末行缺「——>」（最后一步必须落到中文释义）")

        for label, pattern in TEXTBOOK_PATTERNS + TRANSLATION_PATTERNS:
            for line in lines:
                match = pattern.search(line)
                if match:
                    add("%s：%s" % (label, match.group(0)))
                    break

    bans = _ban_needles(banned_terms, instruction)
    if bans:
        body = "\n".join(lines)
        lowered = body.lower()
        for needle in bans:
            start = lowered.find(needle.lower())
            while start >= 0:
                if not _negated(lowered, start):
                    add("用户禁令：%s" % needle)
                    break
                start = lowered.find(needle.lower(), start + 1)

    return HardResult(ok=not hits, hits=hits, feedback=_feedback(hits))

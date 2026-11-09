#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一条词的处理链：生成 → 润色 → 评分，不合格换一轮，仍不过就交历史最佳。

三段各发一次请求，评分那一段只拿到「词 + 用户原话 + 上一版成品 + 新版成品」，
拿不到前两段的思考过程 —— 否则评分模型看见自己写的东西就只会点头
（用户原话：「不能又当选手又当裁判」）。

用户原话（instruction）从「一句提示」升级成硬约束：生成侧会从里面抽「不要 X」
黑名单做后置校验，重试时原样再贴一遍并声明它高于评委意见；两轮都不过时
不再抛错交白卷，而是挑历史最好的一版带 unverified=True 交付。
"""

from __future__ import annotations

import difflib
import json
import os
import re
import time

import providers

HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT_DIR = os.path.join(HERE, "prompts")

PASS_LINE = 8  # judge.txt 的及格线：六维每一维都要 ≥8
COMPLIANCE_DIM = "诉求落实"  # 第 7 维：用户这一版指出的问题，这版到底处理了没有
COMPLIANCE_FLOOR = 6  # 两段式的第二段：诉求到位时，六维允许的最低分（真换了招的稿子常有一维 6–7）
JUDGE_DIMENSIONS = ("拆分成立", "方法选对", "谐音过关", "逻辑连贯", "文本充足", "像人说话")
# 维度改过名（链条闭合 → 逻辑连贯）：模型偶尔还回旧键，映射一下再记账，
# 免得旧键进了 scores、新键又被下面的 setdefault 记 0，把 min() 直接拽到 0。
JUDGE_ALIASES = {
    "链条闭合": "逻辑连贯",
    "链条完整": "逻辑连贯",
    "像不像人说话": "像人说话",
    "解决诉求": COMPLIANCE_DIM,
    "用户诉求": COMPLIANCE_DIM,
    "诉求落实度": COMPLIANCE_DIM,
    "诉求": COMPLIANCE_DIM,
}


def _canonical_dim(key):
    """把评委返回的维度名归一：先查别名，再做一次近似匹配。

    模型偶尔把「像人说话」写成「像人谐话」；精确查表查不到就会被当成新键，
    真维再被 setdefault 记 0 —— 一张好稿可能因为一个错字撞上「无 0 分」红线被判死。
    """
    raw = str(key).strip()
    if raw in JUDGE_DIMENSIONS or raw == COMPLIANCE_DIM:
        return raw
    if raw in JUDGE_ALIASES:
        return JUDGE_ALIASES[raw]
    best, best_ratio = raw, 0.0
    for dim in JUDGE_DIMENSIONS + (COMPLIANCE_DIM,):
        ratio = difflib.SequenceMatcher(None, raw, dim).ratio()
        if ratio > best_ratio:
            best, best_ratio = dim, ratio
    return best if best_ratio >= 0.6 else raw


MAX_ATTEMPTS = 2  # 首次 + 重试一次（spec §4.2）
# judge.txt 要求先判意图与「是不是同一条思路」，生成侧据此决定重试时强调什么
INTENTS = ("change_method", "explain", "fix_detail", "other")

NUM = re.compile(r"^[\s①-⑳0-9０-９]{0,3}(?:[.、,，:：)\]]\s*)?")
ARROW = re.compile(r"\s*(?:-{1,}>|={1,2}>|→|⇒|—{1,}>)\s*")
ARROW_TEXT = " ——> "

CIRCLED = ("①", "②", "③", "④", "⑤")  # 3–5 步，最多 5 个号
MIN_STEPS, MAX_STEPS = 3, 5

# generate.txt 让模型吐英文键，它偶尔还是回中文名；这里收一下口，手机端按 key 出「联想 · 词根」标签。
METHOD_KEYS = {
    "sound": "sound", "谐音": "sound", "谐音联想": "sound",
    "meaning": "meaning", "含义": "meaning", "含义联想": "meaning",
    "shape": "shape", "字形": "shape", "字形联想": "shape",
    "root": "root", "词根": "root", "词根词缀": "root",
    "contrast": "contrast", "对比": "contrast", "对比辨析": "contrast",
    "polysemy": "polysemy", "熟词生义": "polysemy",
}

# 用户原话里的禁令：「不要 / 别 / 禁止 / 不能用 / 别再 + 元素」。
# 中文没有词边界，先把「分别、特别、区别、个性、类别」这类复合词里的「别」排掉。
BAN_TRIGGER = re.compile(
    r"(?:不要|别再|别用|不需要|禁止|不能用|不许|不可以|不想用|(?<![分特个区性类])别)"
    r"([^，,。；;！!？?\n]{1,30})"
)
BAN_STOPWORDS = frozenset((
    "方法", "这个", "那个", "这种", "那种", "这套", "那套", "拆法", "记法", "背法",
    "记忆法", "别的", "其他", "一个", "一次", "一句话", "提一嘴", "再说", "再用",
    "最后", "然后", "接着", "其实", "所以", "但是", "而且", "如果", "可以", "就行",
    "一句", "一嘴", "换个", "换一个", "换种", "让我", "说法",
    "了", "的", "再", "用", "我", "你", "他", "它", "就", "也", "都", "还", "又",
))


def _ban_noise(token):
    """句子被截断时会切出「提一」这种半截词；口头语也不是可执行的禁令，丢掉。"""
    if token in BAN_STOPWORDS:
        return True
    return any(len(word) > len(token) and word.startswith(token) for word in BAN_STOPWORDS)


# 用户口中的招 → 六招键；长的词先匹配（「词根词缀」先于「词缀」）
METHOD_WORDS = {
    "root": ("词根词缀", "词根", "词缀", "词源", "root"),
    "sound": ("谐音法", "谐音联想", "谐音", "sound"),
    "contrast": ("对比辨析", "对比法", "对比", "辨析", "contrast"),
    "shape": ("字形联想", "拆字母", "字形", "shape"),
    "meaning": ("含义联想", "meaning"),
    "polysemy": ("熟词生义", "生义", "polysemy"),
}

# 输出形状只由 prompts/generate.txt 定义（split 是数组、elements 是对象数组）。
# 早先这里还追加过一段「split 是字符串」的提醒，两套契约互相打架：模型按提示词输出数组，
# 解析后又 str(list) 成 "['ap', 'propri']" 喂给润色。现在只保留一个真源。


class PipelineError(Exception):
    """这个词这批救不回来了。"""


def _prompt(name):
    with open(os.path.join(PROMPT_DIR, name + ".txt"), "r", encoding="utf-8-sig") as fh:
        return fh.read()


def extract_json(text):
    """模型爱把 JSON 包在 ```json 里或前后絮叨；抓最外层花括号试一次。"""
    if not text:
        return None
    body = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", body, re.S)
    if fence:
        body = fence.group(1).strip()
    start, end = body.find("{"), body.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(body[start : end + 1])
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def normalize(derivation):
    """3–5 行归一：按实际行数重挂 ①②③④⑤，末行箭头统一成 ——>，行数越界直接判废。"""
    lines = []
    if isinstance(derivation, str):
        derivation = [derivation]
    for raw in derivation or []:
        text = str(raw or "").strip()
        if not text:
            continue
        text = ARROW.sub(ARROW_TEXT, text)
        text = re.sub(r"\s+", " ", text).strip()
        text = NUM.sub("", text).strip()
        if text:
            lines.append(text)
    if not (MIN_STEPS <= len(lines) <= MAX_STEPS):
        return None  # 不到 3 步串不成链，多于 5 步是把一句话拆碎了
    last = len(lines) - 1
    lines[last] = ARROW.sub(ARROW_TEXT, lines[last])
    # 末行没箭头时再看倒数第二行：模型常把「顺带一提」的旁注放在结论后面，
    # 旁注不是结论，不该让整轮 90 秒的生成白跑。
    if "——>" not in lines[last] and not (last >= 1 and "——>" in lines[last - 1]):
        return None  # 末步没落到中文词义，等于没闭环
    return [CIRCLED[i] + " " + text for i, text in enumerate(lines)]


def _method_key(value):
    """六招收成英文键（sound/meaning/shape/root/contrast/polysemy）；认不出来就原样带回去。"""
    text = str(value or "").strip()
    if not text:
        return ""
    lowered = text.lower()
    if lowered in METHOD_KEYS:
        return METHOD_KEYS[lowered]
    for name, key in METHOD_KEYS.items():
        if name in lowered:
            return key
    return text


def _has_instruction(item):
    return bool(str(item.get("instruction") or "").strip())


def _brief(item):
    rows = ["单词：" + item["word"]]
    for label, key in (
        ("音标", "phonetic"),
        ("词性", "pos"),
        ("释义", "meaning"),
        ("用户这一版指出的问题（原话）", "instruction"),
    ):
        if item.get(key):
            rows.append("%s：%s" % (label, item[key]))
    if item.get("currentApproach"):
        rows.append("旧版思路（别再抄一遍，换个更顺的）：\n" + "\n".join(item["currentApproach"]))
    return "\n".join(rows)


def _judge_brief(item):
    """评委只看得到「词 + 用户原话 + 上一版成品」，看不到生成过程。

    上一版必须给：只给抱怨时，评委没法判断这版是不是「同一条思路换了措辞」。
    """
    rows = []
    for label, key in (("单词", "word"), ("音标", "phonetic"), ("词性", "pos"), ("释义", "meaning")):
        if item.get(key):
            rows.append("%s：%s" % (label, item[key]))
    instruction = str(item.get("instruction") or "").strip()
    if instruction:
        rows.append("【用户这一版指出的问题（原话，未改写）】\n" + instruction)
    prev = [str(x).strip() for x in (item.get("currentApproach") or []) if str(x).strip()]
    if prev:
        rows.append("【上一版成品（被用户否掉的那一版 3–5 步）】\n" + "\n".join(prev[:5]))
    return "\n".join(rows)


# ------------------------------------------------------------------ 用户禁令


def banned_terms(instruction):
    """从用户原话里抽禁令：返回 [{"label": 原话里的词, "method": 六招键或 "", "needle": 文本}]。

    只认「不要 / 别 / 禁止 / 不能用 / 别再 + 元素」这种显式否定；
    「换个方法」是意图不是禁令，交给评委的 intent / 诉求落实处理。
    """
    text = str(instruction or "").strip()
    if not text:
        return []
    found = []
    seen = set()

    def add(label, method, needle):
        key = (method, needle or label)
        if not label or key in seen:
            return
        if not method and _ban_noise(label):
            return
        seen.add(key)
        found.append({"label": label, "method": method, "needle": needle or label})

    for match in BAN_TRIGGER.finditer(text):
        clause = match.group(1).strip()
        clause = re.sub(r"^(?:再|用|使用|这种|这个|那种|那套|了|的)+", "", clause)
        rest = clause
        for method, words in sorted(METHOD_WORDS.items()):
            for word in sorted(words, key=len, reverse=True):
                if word in rest:
                    add(word, method, word)
                    rest = rest.replace(word, " ")
        for word in BAN_STOPWORDS:
            rest = rest.replace(word, " ")
        for token in re.split(r"[、\s/]+", rest):
            token = re.sub(r"[的了法吧啊呢！!]+$", "", token.strip())
            if len(token) < 2:
                continue
            method = ""
            for key, words in METHOD_WORDS.items():
                if token.lower() in words:
                    method = key
                    break
            add(token, method, token)
    return found


NEGATION_MARKERS = (
    "不", "别", "没", "勿", "禁止", "避免", "而非", "无需", "无须", "不用", "不要", "不再",
)
NEGATION_STOPS = "，。；：！？、\n\r\t—…"


def _negated(body, pos):
    """判断 pos 前的短窗口是不是否定语境（如「不像词根那样拆」「别用词根」）。

    只回看 6 个字符、遇标点即停：再长容易把上一句的「不」算进来。
    """
    window = body[max(0, pos - 6):pos]
    for stop in NEGATION_STOPS:
        cut = window.rfind(stop)
        if cut >= 0:
            window = window[cut + 1:]
    return any(marker in window for marker in NEGATION_MARKERS)


def _text_hits(bans, texts, allow_last_note=False):
    """在一段文本里找禁令命中；allow_last_note=True 时放行最后一行（用户常说「末行提一嘴就行」）。"""
    lines = list(texts)
    # 只有多行时才放行末行；单行文本不能整段跳过，否则禁令检查直接失效。
    if allow_last_note and len(lines) > 1:
        lines = lines[:-1]
    body = "\n".join(lines)
    if not body:
        return []
    lowered = body.lower()
    hits = []
    for ban in bans:
        if ban["method"]:
            words = METHOD_WORDS.get(ban["method"], (ban["needle"],))
        else:
            words = (ban["needle"],)
        # 否定式提及不算命中：好卡解释「这版不像词根那样拆」不该被自己的说明判违规。
        # 同一个词出现多处时，只要有一处不在否定语境里，就算命中。
        for word in words:
            needle = str(word).lower()
            if not needle:
                continue
            start = lowered.find(needle)
            while start >= 0:
                if not _negated(lowered, start):
                    hits.append(ban["needle"])
                    break
                start = lowered.find(needle, start + 1)
    return hits


def _draft_hits(bans, draft):
    """扫草稿的 split / elements / derivation 有没有踩用户禁令。

    末行放行：用户原话里出现过「最后再提一嘴词根词缀就行」这种许可，
    把末行的顺带一提也判违规会把正确的稿子打回去。
    """
    if not bans:
        return []
    hits = []
    if draft.get("method"):
        for ban in bans:
            if ban["method"] and draft["method"] == ban["method"]:
                hits.append(ban["needle"])
    head = [str(draft.get("split") or "")] + [str(x) for x in (draft.get("elements") or [])]
    hits += _text_hits(bans, head)
    lines = [str(x) for x in (draft.get("derivation") or [])]
    hits += _text_hits(bans, lines, allow_last_note=True)
    out = []
    for hit in hits:
        if hit not in out:
            out.append(hit)
    return out


# ------------------------------------------------------------------ 三步


def _generate_once(item, feedback=""):
    user = _brief(item)
    instruction = str(item.get("instruction") or "").strip()
    if instruction:
        user += (
            "\n\n用户这一版的原话是硬约束，优先级高于任何评委意见："
            "他明令不要的招或元素，不许再当主线。"
        )
    if feedback:
        if instruction:
            user += (
                "\n\n【硬约束·用户原话再贴一遍（照抄，不许改写）】\n" + instruction
            )
        user += (
            "\n\n上一版没过。下面是评委的软建议（参考为主，可反驳）：\n" + feedback
            + "\n\n先满足用户原话，再考虑评委意见；两者冲突时一律以用户原话为准 —— "
            "用户明令不要的，不许为了讨好评委请回来。"
        )
    text = providers.complete(
        item["model"],
        _prompt("generate"),
        user,
        max_tokens=12000,  # 推理型模型（step-5 这类）会把几千 token 花在思考上，6000 不够会返回空 content
        temperature=0.9,
        timeout=300,
    )
    draft = extract_json(text)
    if not draft:
        raise PipelineError("生成结果不是 JSON：%s" % text[:200])
    # split 允许数组或字符串：统一成「元素、元素」的短句，给润色当拆法参考。
    raw_split = draft.get("split") or []
    if isinstance(raw_split, str):
        raw_split = [raw_split]
    split = "、".join(str(x).strip() for x in raw_split if str(x).strip())
    # elements 现在是对象数组（part/method/reasoning），这里只要纯净的元素名。
    raw_elements = draft.get("elements") or []
    elements = []
    for item_el in raw_elements[:8]:
        if isinstance(item_el, dict):
            part = str(item_el.get("part") or "").strip()
        else:
            part = str(item_el).strip()
        if part:
            elements.append(part)
    return {
        "method": _method_key(draft.get("method")),
        "split": split,
        "elements": elements,
        "derivation": draft.get("derivation") or [],
    }


def generate(item, feedback=""):
    """第一步：出草稿。feedback 只在重试那一轮有 —— 把评委的意见还给选手。

    用户原话同时是硬约束：草稿踩到「不要 X」黑名单就带着违规项重生成一次。
    只加一次生成调用，不动 run 的轮数上限。
    """
    bans = banned_terms(item.get("instruction"))
    draft = _generate_once(item, feedback)
    hits = _draft_hits(bans, draft)
    if hits:
        note = (
            "你违反了用户禁令：%s。用户原话：%s\n"
            "这是硬约束：这些招／元素不许出现在拆法与推导里（只有末行顺带提一嘴才放行）。"
            % ("、".join(hits[:3]), str(item.get("instruction") or "").strip())
        )
        draft = _generate_once(item, (feedback + "\n\n" + note) if feedback else note)
        hits = _draft_hits(bans, draft)
    draft["violations"] = hits
    return draft


def polish(item, draft):
    """第二步：单独开一次请求磨文字。磨坏了就退回草稿，别让润色拖垮整条链。"""
    draft_lines = normalize(draft["derivation"])
    if not draft_lines:
        raise PipelineError("草稿的 derivation 不是合规的 3–5 步：%s" % draft["derivation"])
    user = "%s\n\n拆法：%s\n\n待润色的 3–5 步：\n%s" % (
        _brief(item),
        draft["split"] or draft["method"],
        "\n".join(draft_lines),
    )
    bans = banned_terms(item.get("instruction"))
    if bans:
        user += (
            "\n\n【用户禁令·润色只许收紧】用户原话：%s\n"
            "上面这些被用户否掉的招／元素，不许借润色回到主线，也不许为了顺口补一个新的。"
            % "、".join(ban["needle"] for ban in bans)
        )
    text = providers.complete(
        item["model"],
        _prompt("polish"),
        user,
        max_tokens=12000,  # 润色同样要给推理留足余量：step-5 在 6000 时也会返回空 content
        temperature=0.4,
        timeout=240,
    )
    data = extract_json(text)
    polished = normalize((data or {}).get("derivation") or [])
    return polished or draft_lines


def _intent(value):
    """评委的意图判定；模型偶尔写中文或漏项，收敛到四个键上。"""
    text = str(value or "").strip().lower()
    if text in INTENTS:
        return text
    for key, words in (
        ("change_method", ("换方法", "换个方法", "换招", "换思路", "change")),
        ("explain", ("解释", "看不懂", "说明", "explain")),
        ("fix_detail", ("具体", "某一步", "细节", "fix")),
    ):
        if any(word in text for word in words):
            return key
    return "other"


def _truthy(value):
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in ("true", "1", "yes", "是", "同一条思路")


def judge(item, draft, approach):
    """第三步：另一个脑子打分。只给它成品，不给前面「怎么想出来的」过程。

    返回 (scores, issues, intent, same_method)。scores 在用户提了意见时含
    「诉求落实」；用户没提意见时这一维不出现，免得把「没抱怨」误判成 0 分。
    """
    target = providers.judge_target(item["model"])
    rows = [_judge_brief(item)]
    if not _has_instruction(item):
        rows.append("【本轮没有用户意见：第 7 维「诉求落实」不判，也不要输出这个键】")
    rows.append("【新版成品 3–5 步】\n" + "\n".join(approach))
    user = "\n\n".join(rows)
    user_short = user + chr(10) * 2 + "（输出从简：issues 最多 2 条，每条不超过 40 字。）"
    text = providers.complete(
        target,
        _prompt("judge"),
        user,
        max_tokens=8192,  # 3000 时 issues 一长就把 JSON 截断，9 分的词会被误判成「不是 JSON」
        temperature=0.2,
        timeout=240,
    )
    data = extract_json(text)
    if not data:
        # 截断兜底：再要一次，但只让给两条短评，别让长评把预算吃光
        text = providers.complete(
            target,
            _prompt("judge"),
            user_short,
            max_tokens=8192,
            temperature=0.2,
            timeout=240,
        )
        data = extract_json(text)
    if not data:
        raise PipelineError("评分结果不是 JSON：%s" % text[:200])
    scores = {}
    for key, value in (data.get("scores") or {}).items():
        try:
            scores[_canonical_dim(key)] = int(round(float(value)))
        except (TypeError, ValueError):
            continue
    if not scores:
        raise PipelineError("评分没给出任何维度分数")
    # 漏项按 0 处理：只给一两个维度打分时，min() 会误判成「全过」
    for key in JUDGE_DIMENSIONS:
        scores.setdefault(key, 0)
    if _has_instruction(item):
        # 用户提了意见却拿不到「诉求落实」→ 按 0，绝不当成「没意见所以不扣分」
        scores.setdefault(COMPLIANCE_DIM, 0)
    else:
        scores.pop(COMPLIANCE_DIM, None)
    return (
        scores,
        [str(x) for x in (data.get("issues") or [])][:6],
        _intent(data.get("intent")),
        _truthy(data.get("same_method")),
    )


def _min_six(scores):
    return min(scores.get(dim, 0) for dim in JUDGE_DIMENSIONS)


def _passed(item, scores, intent=None, same_method=False):
    """两段式验收（判据见 prompts/judge.txt）。

    用户提了意见时，第一段除了六维全 ≥8，还要「诉求落实」≥8：只判六维的话，
    把用户刚否掉的走法换个措辞就能靠 8/8/8/8/8/8 过关，问题原样复现。
    同方法硬闸：用户要「换方法」而评委也判了同一条思路，分数再高也不放行 ——
    这正是「把用户否掉的走法换身措辞再来一遍」，只靠提示词挡不住人情分。
    """
    if _has_instruction(item) and intent == "change_method" and same_method:
        return False
    low_six = _min_six(scores)
    if not _has_instruction(item):
        return low_six >= PASS_LINE
    compliance = scores.get(COMPLIANCE_DIM, 0)
    if low_six >= PASS_LINE and compliance >= PASS_LINE:
        return True
    # 第二段：真换了招／真补了说明，允许六维里有一项落到 6–7 分，但硬红线 0 分不算
    return compliance >= PASS_LINE and low_six >= COMPLIANCE_FLOOR and 0 not in (
        [scores.get(dim, 0) for dim in JUDGE_DIMENSIONS] + [compliance]
    )


def _rank(scores):
    """兜底挑稿顺序：先看诉求分，再看六维最低分（用户要的东西优先于通用质量）。"""
    return (scores.get(COMPLIANCE_DIM, 0), _min_six(scores))


def run(item, log=None, on_stage=None):
    """跑完一条词。返回 {approach, method, scores, attempts, timings, unverified, reason}；失败抛 PipelineError。

    两轮都没过时不再交白卷：挑历史最佳交付，带 unverified=True 与 reason。
    只有一次可用结果都没有（全是模型/JSON 错误）才抛错。

    on_stage 在每次阶段切换时被调用（generate → polish → judge → done），只做埋点：
    它自己出问题不该拖垮这条词的生成。
    """
    timings = {}
    feedback = ""
    problems = []
    history = []  # 拿到成品+分数的轮次，兜底从这里挑
    errors = []  # 模型/JSON 错误（这些轮次没有产物）
    fatal = None
    started = time.time()

    def stage(name):
        if not on_stage:
            return
        try:
            on_stage(name)
        except Exception:  # noqa: BLE001 - 埋点坏了照常跑
            pass

    for attempt in range(1, MAX_ATTEMPTS + 1):
        stage("generate")
        stamp = time.time()
        try:
            draft = generate(item, feedback)
            timings["generate_%d" % attempt] = round(time.time() - stamp, 1)
            if log:
                log("[%s] 生成 %.1fs" % (item["word"], timings["generate_%d" % attempt]))

            stage("polish")
            stamp = time.time()
            approach = polish(item, draft)
            # 润色可能把用户禁用的招捡回来：以最终稿再扫一遍，命中就记进 violations。
            _bans = banned_terms(item.get("instruction"))
            if _bans:
                for hit in _text_hits(_bans, approach, allow_last_note=True):
                    if hit not in draft.setdefault("violations", []):
                        draft["violations"].append(hit)
            timings["polish_%d" % attempt] = round(time.time() - stamp, 1)

            stage("judge")
            stamp = time.time()
            scores, issues, intent, same_method = judge(item, draft, approach)
            timings["judge_%d" % attempt] = round(time.time() - stamp, 1)
        except (PipelineError, providers.ProviderError) as exc:
            # 一轮坏了不该让整条词蒸发：记下来换下一轮，历史里还有稿子就继续兜底
            errors.append("第%d轮：%s" % (attempt, str(exc)[:200]))
            fatal = fatal or exc
            if log:
                log("[%s] 第%d轮作废：%s" % (item["word"], attempt, str(exc)[:200]))
            feedback = ""
            continue

        low = _min_six(scores)
        history.append(
            {
                "attempt": attempt,
                "approach": approach,
                "method": draft["method"],
                "scores": scores,
                "violations": list(draft.get("violations") or []),
            }
        )
        if log:
            log(
                "[%s] 第%d轮 润色 %.1fs 评分 %.1fs 最低 %d 分 %s intent=%s same_method=%s"
                % (
                    item["word"], attempt, timings.get("polish_%d" % attempt, 0),
                    timings["judge_%d" % attempt], low,
                    json.dumps(scores, ensure_ascii=False), intent, same_method,
                )
            )
        # 质量过了但稿子还踩着用户明令禁止的招：不许当「已复核」交付。
        if _passed(item, scores, intent, same_method) and not draft.get("violations"):
            stage("done")
            timings["total"] = round(time.time() - started, 1)
            return {
                "approach": approach,
                "method": draft["method"],
                "scores": scores,
                "attempts": attempt,
                "timings": timings,
                "unverified": False,
                "reason": "",
            }

        problems = list(issues)
        if draft.get("violations"):
            problems.insert(
                0,
                "用户明令禁止的「%s」仍在稿子里（用户原话优先）" % "、".join(draft["violations"]),
            )
        if _has_instruction(item) and same_method and (
            intent == "change_method" or scores.get(COMPLIANCE_DIM, 0) < PASS_LINE
        ):
            problems.insert(0, "这版和用户否掉的那版是同一条思路（同方法），用户要的是换方法或补解释")
        if not problems:
            problems = ["最低分 %d（%s）" % (low, json.dumps(scores, ensure_ascii=False))]
        feedback = "\n".join("- " + p for p in problems)

    if not history:
        detail = "；".join(errors)[:300]
        if isinstance(fatal, providers.ProviderError):
            raise fatal  # 全是调用失败：保留原始类型，service 那边记成「模型调用失败」
        raise PipelineError("两轮都没有可用结果：%s" % detail)

    best = max(history, key=lambda row: _rank(row["scores"]))
    stage("done")
    timings["total"] = round(time.time() - started, 1)
    reason = "两轮都没过评审判定，交付历史最佳（第%d轮）；最后一轮问题：%s" % (
        best["attempt"],
        "；".join(problems)[:240],
    )
    if best["violations"]:
        reason += "；仍含用户禁令词：%s" % "、".join(best["violations"])
    if errors:
        reason += "；作废轮次：%s" % "；".join(errors)[:200]
    if log:
        log("[%s] 兜底交付（unverified）：%s" % (item["word"], reason[:240]))
    return {
        "approach": best["approach"],
        "method": best["method"],
        "scores": best["scores"],
        "attempts": len(history) + len(errors),
        "timings": timings,
        "unverified": True,
        "reason": reason[:400],
    }

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一条词的处理链：生成 → 润色 → 评分，不合格重来一次，仍不过就报失败。

三段各发一次请求，评分那一段只拿到「词 + 成品」，拿不到前两段的对话过程 ——
否则评分模型看见自己写的东西就只会点头（用户原话：「不能又当选手又当裁判」）。
"""

from __future__ import annotations

import json
import os
import re
import time

import providers

HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT_DIR = os.path.join(HERE, "prompts")

PASS_LINE = 8  # judge.txt 的及格线：每一维都要 ≥8
JUDGE_DIMENSIONS = ("拆分成立", "方法选对", "谐音过关", "链条闭合", "文本充足")
MAX_ATTEMPTS = 2  # 首次 + 重试一次（spec §4.2）

NUM = re.compile(r"^[\s①-⑳0-9０-９]{0,3}(?:[.、,，:：)\]]\s*)?")
ARROW = re.compile(r"\s*(?:-{1,}>|={1,2}>|→|⇒|—{1,}>)\s*")
ARROW_TEXT = " ——> "

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
    """三行归一：统一 ①②③ 编号，末行箭头统一成 ——>，多余行数直接判废。"""
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
    if len(lines) != 3:
        return None
    lines[2] = ARROW.sub(ARROW_TEXT, lines[2])
    if "——>" not in lines[2]:
        return None  # 末步没落到中文词义，等于没闭环
    return ["① " + lines[0], "② " + lines[1], "③ " + lines[2]]


def _brief(item):
    rows = ["单词：" + item["word"]]
    for label, key in (
        ("音标", "phonetic"),
        ("词性", "pos"),
        ("释义", "meaning"),
        ("用户指出的问题（上一版）", "instruction"),
    ):
        if item.get(key):
            rows.append("%s：%s" % (label, item[key]))
    if item.get("currentApproach"):
        rows.append("旧版思路（别再抄一遍，换个更顺的）：\n" + "\n".join(item["currentApproach"]))
    return "\n".join(rows)


def generate(item, feedback=""):
    """第一步：出草稿。feedback 只在重试那一轮有 —— 把评委的原话还给选手。"""
    user = _brief(item)
    if feedback:
        user += "\n\n上一版没过，评委指出：\n" + feedback + "\n请针对这些点重写，别只改标点。"
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
        "method": str(draft.get("method") or "").strip(),
        "split": split,
        "elements": elements,
        "derivation": draft.get("derivation") or [],
    }


def polish(item, draft):
    """第二步：单独开一次请求磨文字。磨坏了就退回草稿，别让润色拖垮整条链。"""
    draft_lines = normalize(draft["derivation"])
    if not draft_lines:
        raise PipelineError("草稿的 derivation 不是合规三行：%s" % draft["derivation"])
    user = "%s\n\n拆法：%s\n\n待润色的三行：\n%s" % (
        _brief(item),
        draft["split"] or draft["method"],
        "\n".join(draft_lines),
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


def judge(item, draft, approach):
    """第三步：另一个脑子打分。只给它成品，不给前面「怎么想出来的」过程。"""
    target = providers.judge_target(item["model"])
    user = "%s\n\n成品三行：\n%s" % (
        _brief(item).split("旧版思路")[0].strip(),
        "\n".join(approach),
    )
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
            scores[str(key)] = int(round(float(value)))
        except (TypeError, ValueError):
            continue
    if not scores:
        raise PipelineError("评分没给出任何维度分数")
    # 漏项按 0 处理：只给一两个维度打分时，min() 会误判成「全过」
    for key in JUDGE_DIMENSIONS:
        scores.setdefault(key, 0)
    return scores, [str(x) for x in (data.get("issues") or [])][:6]


def run(item, log=None, on_stage=None):
    """跑完一条词。返回 {approach, scores, attempts, timings}；失败抛 PipelineError。

    on_stage 在每次阶段切换时被调用（generate → polish → judge → done），只做埋点：
    它自己出问题不该拖垮这条词的生成。
    """
    timings = {}
    feedback = ""
    problems = []
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
        draft = generate(item, feedback)
        timings["generate_%d" % attempt] = round(time.time() - stamp, 1)
        if log:
            log("[%s] 生成 %.1fs" % (item["word"], timings["generate_%d" % attempt]))

        stage("polish")
        stamp = time.time()
        approach = polish(item, draft)
        timings["polish_%d" % attempt] = round(time.time() - stamp, 1)

        stage("judge")
        stamp = time.time()
        scores, issues = judge(item, draft, approach)
        timings["judge_%d" % attempt] = round(time.time() - stamp, 1)
        low = min(scores.values())
        if log:
            log(
                "[%s] 第%d轮 润色 %.1fs 评分 %.1fs 最低 %d 分 %s"
                % (item["word"], attempt, timings["polish_%d" % attempt],
                   timings["judge_%d" % attempt], low, json.dumps(scores, ensure_ascii=False))
            )
        if low >= PASS_LINE:
            stage("done")
            timings["total"] = round(time.time() - started, 1)
            return {"approach": approach, "scores": scores, "attempts": attempt, "timings": timings}
        problems = issues or ["最低分 %d（%s）" % (low, json.dumps(scores, ensure_ascii=False))]
        feedback = "\n".join("- " + p for p in problems)

    raise PipelineError("两轮都没过：%s" % "；".join(problems)[:300])

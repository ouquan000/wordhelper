#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Milestone 2：小批量生成 / 评审通道。

批调用只负责省请求：一次 ``providers.complete`` 处理 2–4 个词。质量规则全部
沿用 pipeline 单条链路的现有实现（``banned_terms`` / ``_draft_hits`` /
``normalize`` / ``JudgeResult`` + ``_passed`` 的语义），不在这里另造一套。

失败隔离是硬要求：
- 批请求网络异常 / JSON 解析失败 → 整批逐词回退 ``pipeline.generate`` /
  ``pipeline.judge``，绝不把整批错误抛给调用方；
- 批结果里某个词缺项、缺字段、草稿不过规则 → 只回退这个词，同批其他词照常；
- 单条回退也失败 → 该词不进入返回 dict，原因记在 ``.stats["errors"]``。

返回的是 ``BatchResult``（dict 子类）：主结构仍是 ``{word: draft}`` /
``{word: JudgeResult}``，统计只挂在 ``.stats`` 属性上，不污染词表。
"""

from __future__ import annotations

import json
import os
import re

import pipeline
import providers

DEFAULT_BATCH_SIZE = 3
MIN_BATCH_SIZE = 2
MAX_BATCH_SIZE = 4

_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.S)


class BatchResult(dict):
    """``{word: value}`` 的主返回类型不变；统计挂在属性上。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.stats = {
            "batch_calls": 0,
            "fallbacks": 0,
            "fallback_words": [],
            "errors": [],
        }


def batch_size():
    """当前生效的批大小：读 BATCH_SIZE，夹在 2..4；坏值回落默认 3。"""
    raw = os.environ.get("BATCH_SIZE")
    if raw is None or not str(raw).strip():
        return DEFAULT_BATCH_SIZE
    try:
        value = int(str(raw).strip())
    except (TypeError, ValueError):
        return DEFAULT_BATCH_SIZE
    return max(MIN_BATCH_SIZE, min(MAX_BATCH_SIZE, value))


# 兼容内部/测试里的私有叫法
_batch_size = batch_size


def _extract_array(text):
    """抓模型输出里的 JSON 数组（允许 ```json 围栏和前后絮叨）。"""
    if not text:
        return None
    body = str(text).strip()
    fence = _FENCE.search(body)
    if fence:
        body = fence.group(1).strip()
    start, end = body.find("["), body.rfind("]")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(body[start:end + 1])
    except ValueError:
        return None
    return data if isinstance(data, list) else None


def _model_key(model):
    if isinstance(model, str):
        return model
    try:
        return providers.label(providers.resolve(model))
    except Exception:  # noqa: BLE001 - 分组键坏了也不能拖垮批
        return str(model)


def _chunks(items, size):
    """按模型分组后再按批大小切块：一次请求只打一个模型。"""
    groups = {}
    order = []
    for item in items:
        key = _model_key(item.get("model"))
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(item)
    for key in order:
        group = groups[key]
        for start in range(0, len(group), size):
            yield group[start:start + size]


def _lookup(mapping, word, default=None):
    """feedback_map / hard_map / approaches 支持大小写不敏感的词键。"""
    if not isinstance(mapping, dict):
        return default
    if word in mapping:
        return mapping[word]
    folded = word.casefold()
    for key, value in mapping.items():
        if str(key).strip().casefold() == folded:
            return value
    return default


def _words(items):
    return [str(item.get("word") or "").strip() for item in items if str(item.get("word") or "").strip()]


def _entries_by_word(entries, words):
    """数组条目按 word 对齐输入词；大小写不一致也认，多余词忽略。"""
    wanted = {word.casefold(): word for word in words}
    found = {}
    for entry in entries or []:
        if not isinstance(entry, dict):
            continue
        raw = str(entry.get("word") or "").strip()
        if not raw:
            continue
        target = wanted.get(raw.casefold())
        if target is not None:
            found.setdefault(target, entry)
    return found


# ------------------------------------------------------------------ 生成


def _feedback_block(item, feedback, hard):
    """重试轮的反馈措辞与 pipeline._generate_once 保持一致。"""
    if not feedback:
        return ""
    rows = []
    instruction = str(item.get("instruction") or "").strip()
    if instruction:
        rows.append("【硬约束·用户原话再贴一遍（照抄，不许改写）】\n" + instruction)
    if hard:
        rows.append(
            "上一版被确定性硬拦截打回。下面是机械规则查出的问题，必须逐条改掉"
            "（这不是评委建议，不能反驳）：\n" + str(feedback)
        )
    else:
        rows.append("上一版没过。下面是评委的软建议（参考为主，可反驳）：\n" + str(feedback))
    return "\n\n".join(rows)


def _generate_user(chunk, feedback_map, hard_map):
    rows = ["【本批共 %d 个词：必须逐词返回，一个都不能少，顺序与下面一致】" % len(chunk)]
    for index, item in enumerate(chunk, 1):
        word = str(item["word"]).strip()
        rows.append("")
        rows.append("### 第 %d 个词（返回项的 word 必须原样写：%s）" % (index, word))
        rows.append(pipeline._brief(item))
        instruction = str(item.get("instruction") or "").strip()
        if instruction:
            rows.append(
                "用户原话是硬约束，优先级高于任何评委意见：他明令不要的招或元素，不许再当主线。"
            )
        block = _feedback_block(item, _lookup(feedback_map, word, ""), _lookup(hard_map, word, False))
        if block:
            rows.append(block)
    return "\n".join(rows)


def _draft_from_entry(item, entry):
    """把批结果里的单项校验/归一成 pipeline.generate() 的 draft schema。"""
    if not isinstance(entry, dict):
        return None, "条目不是对象"
    method = pipeline._method_key(entry.get("method"))
    if not method:
        return None, "缺 method"

    raw_split = entry.get("split") or []
    if isinstance(raw_split, str):
        raw_split = [raw_split]
    if not isinstance(raw_split, (list, tuple)):
        return None, "split 形状不对"
    split = "、".join(str(part).strip() for part in raw_split if str(part).strip())
    if not split:
        return None, "缺 split"

    raw_elements = entry.get("elements") or []
    if not isinstance(raw_elements, (list, tuple)):
        return None, "elements 形状不对"
    elements = []
    for element in raw_elements[:8]:
        part = element.get("part") if isinstance(element, dict) else element
        part = str(part or "").strip()
        if part:
            elements.append(part)
    if not elements:
        return None, "缺 elements"

    raw_lines = entry.get("derivation")
    if isinstance(raw_lines, str):
        raw_lines = [raw_lines]
    if not isinstance(raw_lines, (list, tuple)) or not raw_lines:
        return None, "缺 derivation"
    if any(not isinstance(line, str) for line in raw_lines):
        return None, "derivation 含非字符串"
    lines = [line.strip() for line in raw_lines if line.strip()]
    if pipeline.normalize(lines) is None:
        return None, "derivation 不是合规的 2–5 步"

    draft = {
        "method": method,
        "split": split,
        "elements": elements,
        "derivation": lines,
    }
    bans = pipeline.banned_terms(item.get("instruction"))
    hits = pipeline._draft_hits(bans, draft)
    if hits:
        return None, "踩用户禁令：%s" % "、".join(hits[:3])
    draft["violations"] = hits
    return draft, ""


def _generate_one(item, feedback_map, hard_map, stats):
    word = str(item.get("word") or "").strip()
    try:
        return pipeline.generate(
            item,
            _lookup(feedback_map, word, ""),
            hard=bool(_lookup(hard_map, word, False)),
        )
    except Exception as exc:  # noqa: BLE001 - 单词失败只记账，不拖垮同批
        stats["errors"].append({"word": word, "stage": "generate", "error": str(exc)[:200]})
        return None


def generate_batch(items, feedback_map=None, hard_map=None):
    """一次 complete 生成 2–4 个词的草稿，返回 ``{word: draft}``（``BatchResult``）。

    draft 与 ``pipeline.generate()`` 同 schema：method / split / elements /
    derivation / violations。每个词都过 ``pipeline.banned_terms`` 与
    ``pipeline._draft_hits``；缺词、缺字段、不合规或踩禁令的词单独回退
    ``pipeline.generate(item)``，不影响同批其他词。

    ``feedback_map`` / ``hard_map``：重试轮的逐词反馈与硬拦截标记，
    批路径与单条路径用同一份措辞和语义。
    """
    selected = [
        item for item in (items or [])
        if isinstance(item, dict) and str(item.get("word") or "").strip()
    ]
    feedback_map = feedback_map or {}
    hard_map = hard_map or {}
    out = BatchResult()
    if not selected:
        return out
    stats = out.stats

    for chunk in _chunks(selected, batch_size()):
        words = _words(chunk)
        entries = None
        batch_error = ""
        stats["batch_calls"] += 1
        try:
            text = providers.complete(
                chunk[0].get("model"),
                pipeline._prompt("generate_batch"),
                _generate_user(chunk, feedback_map, hard_map),
                max_tokens=16000,
                temperature=0.9,
                timeout=300,
            )
            entries = _extract_array(text)
            if entries is None:
                batch_error = "批结果不是 JSON 数组：%s" % str(text)[:200]
        except Exception as exc:  # noqa: BLE001 - 批调用坏了整批回退，绝不整批抛错
            batch_error = "批调用失败：%s" % str(exc)[:200]

        by_word = _entries_by_word(entries, words) if entries is not None else {}
        for item in chunk:
            word = str(item["word"]).strip()
            draft = None
            reason = batch_error
            entry = by_word.get(word)
            if entry is None:
                reason = reason or "批结果缺这个词"
            else:
                try:
                    draft, reason = _draft_from_entry(item, entry)
                except Exception as exc:  # noqa: BLE001 - 单条解析坏了只回退这个词
                    draft, reason = None, "批结果解析失败：%s" % str(exc)[:200]
            if draft is None:
                stats["fallbacks"] += 1
                stats["fallback_words"].append({"word": word, "reason": reason or "不合规"})
                draft = _generate_one(item, feedback_map, hard_map, stats)
            if draft is not None:
                out[word] = draft
    return out


# ------------------------------------------------------------------ 评审


def _approach_list(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    try:
        return [str(line) for line in value]
    except TypeError:
        return [str(value)]


def _approach_map(items, approaches):
    if isinstance(approaches, dict):
        return approaches
    result = {}
    if isinstance(approaches, (list, tuple)):
        for item, value in zip(items, approaches):
            result[str(item.get("word") or "").strip()] = value
    return result


def _judge_user(chunk, approach_map, drafts):
    rows = ["【本批共 %d 个词：必须逐词返回，一个都不能少，顺序与下面一致】" % len(chunk)]
    for index, item in enumerate(chunk, 1):
        word = str(item["word"]).strip()
        rows.append("")
        rows.append("### 第 %d 个词（返回项的 word 必须原样写：%s）" % (index, word))
        rows.extend((pipeline._judge_brief(item), pipeline.judge_context(_lookup(drafts, word, {}))))
        rows.append("【新版成品 2–5 步】\n" + "\n".join(_approach_list(_lookup(approach_map, word, []))))
        if not pipeline._has_instruction(item):
            rows.append(
                "【本轮没有用户意见：第 7 维「诉求落实」不判，也不要输出这个键；"
                "violates_user_intent 可省略】"
            )
    return "\n".join(rows)


def _judge_from_entry(item, entry):
    """批结果单项 → pipeline.JudgeResult；字段缺失/含糊时返回 None 交给单条回退。

    解析口径与 pipeline.judge() 对齐：维度别名归一、二值字段映射回旧 scores、
    漏项补 0、有用户原话才保留「诉求落实」。这样下游 pipeline._passed 拿到的
    语义与单条评审完全一致。
    """
    if not isinstance(entry, dict):
        return None, "条目不是对象"

    need_intent = pipeline._has_instruction(item)
    history_present = bool(str(item.get("history") or "").strip())
    binary = {key: pipeline._bool_field(entry, key) for key in pipeline.BINARY_FIELDS}

    required_binary = [
        key for key in pipeline.BINARY_FIELDS
        if key != "violates_user_intent" or need_intent
    ]
    missing = [key for key in required_binary if binary[key] is None]
    if missing:
        return None, "缺二值字段：%s" % ",".join(missing)
    if need_intent:
        if pipeline._intent_field(entry.get("intent")) is None:
            return None, "缺 intent"
        if pipeline._bool_field(entry, "same_method") is None:
            return None, "缺 same_method"
    if history_present and pipeline._bool_field(entry, "history_repeat") is None:
        return None, "缺 history_repeat"

    scores = {}
    raw_scores = entry.get("scores")
    if isinstance(raw_scores, dict):
        for key, value in raw_scores.items():
            try:
                scores[pipeline._canonical_dim(key)] = int(round(float(value)))
            except (TypeError, ValueError, OverflowError):
                continue
    if not scores:
        return None, "缺 scores"

    binary_present = any(value is not None for value in binary.values())
    if binary_present:
        for field, dims in pipeline.BINARY_TO_DIMS.items():
            value = binary[field]
            score = 10 if value is False else (0 if value is True else 5)
            for dim in dims:
                scores.setdefault(dim, score)
    # 漏项按 0：与 pipeline.judge 一样，避免 min() 把残缺 scores 误判成全过。
    for key in pipeline.JUDGE_DIMENSIONS:
        scores.setdefault(key, 0)
    if need_intent:
        scores.setdefault(pipeline.COMPLIANCE_DIM, 0)
    else:
        scores.pop(pipeline.COMPLIANCE_DIM, None)

    issues = [str(issue) for issue in (entry.get("issues") or [])][:6]
    return (
        pipeline.JudgeResult(
            scores,
            issues,
            pipeline._intent(entry.get("intent")),
            bool(pipeline._bool_field(entry, "same_method")),
            bool(pipeline._bool_field(entry, "history_repeat")),
            binary=binary if binary_present else None,
        ),
        "",
    )


def _judge_one(item, draft, approach, stats):
    word = str(item.get("word") or "").strip()
    if not isinstance(draft, dict):
        stats["errors"].append({"word": word, "stage": "judge", "error": "缺草稿，无法单条回退"})
        return None
    try:
        return pipeline.judge(item, draft, approach)
    except Exception as exc:  # noqa: BLE001 - 单词失败只记账，不拖垮同批
        stats["errors"].append({"word": word, "stage": "judge", "error": str(exc)[:200]})
        return None


def judge_batch(items, drafts, approaches):
    """一次 complete 批量评审，返回 ``{word: pipeline.JudgeResult}``。

    要求模型返回 JSON 数组，每项含 word / scores / 五道二值字段 /
    intent / same_method / history_repeat / issues。单条解析失败或缺字段时，
    只把该词回退到 ``pipeline.judge(item, draft, approach)``；批调用整体失败
    或数组解析失败时，整批逐词回退，不抛整批错误。

    ``approaches`` 可以是 ``{word: 2–5 步}``，也可以是与 items 等长的列表。
    """
    selected = [
        item for item in (items or [])
        if isinstance(item, dict) and str(item.get("word") or "").strip()
    ]
    out = BatchResult()
    if not selected:
        return out
    stats = out.stats
    approach_map = _approach_map(selected, approaches)

    for chunk in _chunks(selected, batch_size()):
        words = _words(chunk)
        entries = None
        batch_error = ""
        stats["batch_calls"] += 1
        try:
            target = providers.judge_target(chunk[0].get("model"))
            text = providers.complete(
                target,
                pipeline._prompt("judge_batch"),
                _judge_user(chunk, approach_map, drafts or {}),
                max_tokens=8192,
                temperature=0.2,
                timeout=300,
            )
            entries = _extract_array(text)
            if entries is None:
                batch_error = "批评审结果不是 JSON 数组：%s" % str(text)[:200]
        except Exception as exc:  # noqa: BLE001 - 批调用坏了整批回退，绝不整批抛错
            batch_error = "批评审调用失败：%s" % str(exc)[:200]

        by_word = _entries_by_word(entries, words) if entries is not None else {}
        for item in chunk:
            word = str(item["word"]).strip()
            approach = _approach_list(_lookup(approach_map, word, []))
            judged = None
            reason = batch_error
            entry = by_word.get(word)
            if entry is None:
                reason = reason or "批评审结果缺这个词"
            else:
                try:
                    judged, reason = _judge_from_entry(item, entry)
                except Exception as exc:  # noqa: BLE001 - 单条解析坏了只回退这个词
                    judged, reason = None, "批评审解析失败：%s" % str(exc)[:200]
            if judged is None:
                stats["fallbacks"] += 1
                stats["fallback_words"].append({"word": word, "reason": reason or "不合规"})
                judged = _judge_one(
                    item, _lookup(drafts if isinstance(drafts, dict) else {}, word), approach, stats
                )
            if judged is not None:
                out[word] = judged
    return out

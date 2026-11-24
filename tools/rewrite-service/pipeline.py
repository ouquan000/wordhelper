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
import hashlib
import json
import os
import re
import sys
import time

import exemplars
import profile
import providers
import rules_hard

import pipeline_style
from pipeline_style import (
    _NUM_PREFIX_LINE, _SOUND_EVIDENCE,
)
import pipeline_text
from pipeline_text import (
    ARROW, ARROW_TEXT, BAN_STOPWORDS, BAN_TRIGGER, CIRCLED, INTENTS, MAX_ATTEMPTS, MAX_STEPS,
    METHOD_KEYS, METHOD_WORDS, MIN_STEPS, NEGATION_MARKERS, NEGATION_STOPS, NUM, PipelineError,
    _IPA_RE, _USAGE_REQ_BEFORE,
)
import pipeline_generation
from pipeline_generation import (
    _brief, _has_instruction, _judge_brief,
)
import pipeline_intent
from pipeline_intent import (
    _CHANGE_PATTERN, _CHANGE_WORDS, _EN_DIRECT_NEGATION, _INTENT_CLAUSE_END, _INTENT_QUOTE,
    _INTENT_WORDS, _QUOTE_AS_DIRECT_REQUEST, _QUOTE_FOLLOWUP_REQUEST, _QUOTE_REQUEST_END,
    _REPORTED_CHANGE_PREFIX, _ZH_CHANGE_PROPOSAL, _ZH_CURRENT_INTENT, _ZH_DIRECT_NEGATION,
    _ZH_PAST_INTENT, _bool_field,
)
import pipeline_judge
from pipeline_judge import (
    BINARY_FIELDS, BINARY_LABELS, BINARY_TO_DIMS, COMPLIANCE_DIM, COMPLIANCE_FLOOR,
    JUDGE_ALIASES, JUDGE_DIMENSIONS, JudgeResult, PASS_LINE,
)
import pipeline_run
from pipeline_run import (
    _safe_log,
)

# 子模块每次调用从此入口读取可替换依赖。
_api = sys.modules[__name__]
HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT_DIR = os.path.join(HERE, "prompts")

# --------------------------------------------------------------- 风格规则（v1.5.3）
# 红黑榜从 data/queue.json 的 95 条真实吐槽 + 用户口述里沉淀出来，落在
# config/style_rules.json：生成/润色侧照红榜手感写，评委侧按黑榜硬扣分/打回。
# 文件缺失或坏了就退回下面这套兜底常量，流水线照跑 —— 风格配置不该变成单点故障。
STYLE_RULES_PATH = os.environ.get("REWRITE_STYLE_RULES") or os.path.join(
    HERE, "config", "style_rules.json"
)
DEFAULT_METHOD_WEIGHTS = {
    "sound": 73.9,  # 实测吃得最开的招：一跳谐音
    "root": 8.2,
    "shape": 6.5,
    "contrast": 2.0,
    "meaning": 9.4,  # 保底，不许当首选
    "polysemy": 0.0,  # 实测 0%：只在熟词生义的高频考点上才用
}
DEFAULT_METHOD_PENALTY = {"meaning": 1, "polysemy": 2}
DEFAULT_REJECT_ON = ("slow_start", "advanced_teaches_basic", "root_overreach", "label_mismatch")
_STYLE_CACHE = {"path": None, "data": None}


def load_style_rules(path=None):
    return pipeline_style.load_style_rules(_api, path)


def style_rules():
    return pipeline_style.style_rules(_api)


def reload_style_rules(path=None):
    return pipeline_style.reload_style_rules(_api, path)


def style_active():
    return pipeline_style.style_active(_api)


def method_weights():
    return pipeline_style.method_weights(_api)


def method_weight(method):
    return pipeline_style.method_weight(_api, method)


def method_penalty():
    return pipeline_style.method_penalty(_api)


def black_rules():
    return pipeline_style.black_rules(_api)


def black_rule(rule_id):
    return pipeline_style.black_rule(_api, rule_id)


def reject_on():
    return pipeline_style.reject_on(_api)


def _black_rule_block(limit=6):
    return pipeline_style._black_rule_block(_api, limit)


def _red_sample_block(method='', limit=2):
    return pipeline_style._red_sample_block(_api, method, limit)


def style_block(method='', judge=False):
    return pipeline_style.style_block(_api, method, judge)


def _first_lines(approach, count=2):
    return pipeline_style._first_lines(_api, approach, count)


def style_hits(item, draft, approach):
    return pipeline_style.style_hits(_api, item, draft, approach)


def style_penalty_scores(hits, scores):
    return pipeline_style.style_penalty_scores(_api, hits, scores)


def style_gate(item, draft, approach, scores):
    return pipeline_style.style_gate(_api, item, draft, approach, scores)


def _ban_noise(token):
    return pipeline_text._ban_noise(_api, token)


def _is_usage_request(text, match_start, clause):
    return pipeline_text._is_usage_request(_api, text, match_start, clause)


def extract_json(text):
    return pipeline_text.extract_json(_api, text)


def normalize(derivation):
    return pipeline_text.normalize(_api, derivation)


def _method_key(value):
    return pipeline_text._method_key(_api, value)


def _ensure_phonetic_warning(item, method, lines):
    return pipeline_text._ensure_phonetic_warning(_api, item, method, lines)


def banned_terms(instruction):
    return pipeline_text.banned_terms(_api, instruction)


def _negated(body, pos):
    return pipeline_text._negated(_api, body, pos)


def _text_hits(bans, texts, allow_last_note=False):
    return pipeline_text._text_hits(_api, bans, texts, allow_last_note)


def _draft_hits(bans, draft):
    return pipeline_text._draft_hits(_api, bans, draft)


def _prompt(name):
    return pipeline_generation._prompt(_api, name)


def prompt_hash():
    return pipeline_generation.prompt_hash(_api)


def instruction_hash(item):
    return pipeline_generation.instruction_hash(_api, item)


def _recommended_methods(item):
    return pipeline_generation._recommended_methods(_api, item)


def _exemplar_block(method):
    return pipeline_generation._exemplar_block(_api, method)


def _zero_scores(item):
    return pipeline_generation._zero_scores(_api, item)


def _generate_once(item, feedback='', hard=False):
    return pipeline_generation._generate_once(_api, item, feedback, hard)


def generate(item, feedback='', hard=False):
    return pipeline_generation.generate(_api, item, feedback, hard)


def polish(item, draft):
    return pipeline_generation.polish(_api, item, draft)


def _intent_prose(text):
    return pipeline_intent._intent_prose(_api, text)


def _prefix_negates(prefix):
    return pipeline_intent._prefix_negates(_api, prefix)


def _negated_near(text, word):
    return pipeline_intent._negated_near(_api, text, word)


def _intent_words(text, words):
    return pipeline_intent._intent_words(_api, text, words)


def _change_directive(text):
    return pipeline_intent._change_directive(_api, text)


def _explicit_change_request(text):
    return pipeline_intent._explicit_change_request(_api, text)


def _negated_change_request(text):
    return pipeline_intent._negated_change_request(_api, text)


def _intent_field(value):
    return pipeline_intent._intent_field(_api, value)


def _intent(value):
    return pipeline_intent._intent(_api, value)


def _requires_change_method(item, intent):
    return pipeline_intent._requires_change_method(_api, item, intent)


def _canonical_dim(key):
    return pipeline_judge._canonical_dim(_api, key)


def _judge_flags(item, approach):
    return pipeline_judge._judge_flags(_api, item, approach)


def judge(item, draft, approach):
    return pipeline_judge.judge(_api, item, draft, approach)


def _min_six(scores):
    return pipeline_judge._min_six(_api, scores)


def _passed(item, scores, intent=None, same_method=False, history_repeat=False, binary=None, hard_ok=True):
    return pipeline_judge._passed(_api, item, scores, intent, same_method, history_repeat, binary, hard_ok)


def _rank(row):
    return pipeline_judge._rank(_api, row)


def run(item, log=None, on_stage=None):
    return pipeline_run.run(_api, item, log, on_stage)

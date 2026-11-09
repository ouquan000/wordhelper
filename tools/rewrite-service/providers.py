#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模型调用：wb 本地网关（OpenAI chat）+ portdan（openai-responses）。

- 只用标准库，零依赖。
- 密钥在运行期从 ~/.dsh/.credentials.yaml 现读现用，绝不落进任何仓库文件。
- 换型号只改 MODELS 这张表；手机端从 /models 动态读，不用发版（spec §4.3）。
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.error
import urllib.request

CREDENTIALS_PATH = os.environ.get("REWRITE_CREDENTIALS") or os.path.join(
    os.path.expanduser("~"), ".dsh", ".credentials.yaml"
)

# 本地网关的固定占位 key，不是秘密，也没必要藏
WB_KEY = "sk-wb-local-8788"

# 中转站的 grok 在两条并发时会间歇 502（串行连打三次都正常），
# 单独给它一把锁：同一个供应商同时只放一条，别让并发把整批词拖成失败。
_PROVIDER_LOCKS = {"portdan-grok": threading.Lock()}

PROVIDER_BASE = {
    "wb": "http://127.0.0.1:8788/v1",
    "portdan-gpt": "https://portdan.com/v1",
    "portdan-grok": "https://portdan.com/v1",
}

# 对外 id（手机存的）→ 上游型号 + 推理强度。表里的顺序就是 /models 的顺序。
MODELS = {
    "ds-v41-flash": {
        "name": "DeepSeek V4.1 Flash",
        "provider": "wb",
        "model": "cn:deepseek-v4.1-flash",
        "reasoning": "max",
    },
    "step5": {
        "name": "Step 5",
        "provider": "wb",
        "model": "cn:step-5-preview",
        "reasoning": "high",
    },
    "glm53": {
        "name": "GLM 5.3",
        "provider": "wb",
        "model": "cn:glm-5.3",
        "reasoning": "high",
    },
    "kimi-k31-pro": {
        "name": "Kimi K3.1 Pro",
        "provider": "wb",
        "model": "cn:kimi-k3.1-pro",
        "reasoning": "high",
    },
    "portdan-gpt": {
        "name": "GPT-6.1 Sol",
        "provider": "portdan-gpt",
        "model": "gpt-6.1-sol",
        "reasoning": "high",
    },
    "portdan-grok": {
        "name": "Grok 4.7",
        "provider": "portdan-grok",
        "model": "grok-4.7",
        "reasoning": "high",
    },
}

# 评分换一个模型交叉把关：同一个模型自评等于「又当选手又当裁判」（用户原话）
JUDGE_CANDIDATES = (
    {"provider": "wb", "model": "cn:deepseek-v4.1-flash", "reasoning": "high"},
    {"provider": "wb", "model": "cn:glm-5.3", "reasoning": "high"},
)


class ProviderError(Exception):
    """调用彻底失败（已重试过）。"""


class _Retry(ProviderError):
    """这次失败值得再试一次（网络、5xx、空 content）。"""


def describe():
    """给 GET /models 的清单，字段照 spec §3.3。"""
    return [
        {
            "id": key,
            "name": spec["name"],
            "provider": spec["provider"],
            "reasoning": spec["reasoning"],
        }
        for key, spec in MODELS.items()
    ]


def resolve(model_id):
    """公开 id → 内部目标；也接受直接写死的内部目标 dict（评分模型走这条）。"""
    if isinstance(model_id, dict):
        return dict(model_id)
    spec = MODELS.get(model_id)
    if spec is None:
        raise ProviderError("未知模型：%s" % model_id)
    return {
        "provider": spec["provider"],
        "model": spec["model"],
        "reasoning": spec["reasoning"],
    }


def label(target):
    return "%s/%s" % (target["provider"], target["model"])


def judge_target(generator):
    """挑一个和生成模型不同的脑子来打分。"""
    forced = os.environ.get("REWRITE_JUDGE_MODEL")
    if forced:
        return {"provider": "wb", "model": forced, "reasoning": "high"}
    gen = resolve(generator)
    for cand in JUDGE_CANDIDATES:
        if cand["model"] != gen["model"]:
            return dict(cand)
    return dict(JUDGE_CANDIDATES[0])


def credential(name):
    """从扁平 YAML 里抠一行出来；找不到就抛错（绝不回落到硬编码）。"""
    try:
        with open(CREDENTIALS_PATH, "r", encoding="utf-8-sig") as fh:
            text = fh.read()
    except OSError as exc:
        raise ProviderError("读不到凭据文件 %s：%s" % (CREDENTIALS_PATH, exc))
    match = re.search(r"^\s*%s\s*:\s*['\"]?([^'\"\s#]+)" % re.escape(name), text, re.M)
    if not match:
        raise ProviderError("凭据文件里没有 %s" % name)
    return match.group(1)


def _headers(provider):
    if provider == "wb":
        key = WB_KEY
    else:
        key = credential("PORTDANGPT_API_KEY" if provider == "portdan-gpt" else "PORTDAN1_API_KEY")
    return {"Authorization": "Bearer " + key, "Content-Type": "application/json"}


def _post(url, payload, headers, timeout):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")[:300]
        except Exception:  # noqa: BLE001 - 读错误体失败不能盖掉原始错误码
            pass
        if exc.code in (408, 409, 425, 429) or exc.code >= 500:
            raise _Retry("HTTP %d %s" % (exc.code, body))
        raise ProviderError("HTTP %d %s" % (exc.code, body))
    except (urllib.error.URLError, OSError) as exc:
        raise _Retry("网络错误：%s" % exc)
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except ValueError:
        raise _Retry("响应不是 JSON：%s" % raw[:200])


def _chat_text(payload):
    choices = payload.get("choices") or []
    if not choices:
        raise _Retry("响应里没有 choices")
    message = choices[0].get("message") or {}
    text = (message.get("content") or "").strip()
    if not text:
        # 思考型模型把预算吃光就返回空 content —— 实测最常见的失败模式，值得重试
        raise _Retry("空 content（finish_reason=%s）" % choices[0].get("finish_reason"))
    return text


def _responses_text(payload):
    if payload.get("error"):
        raise ProviderError("上游报错：%s" % json.dumps(payload["error"], ensure_ascii=False)[:300])
    if payload.get("status") not in (None, "completed"):
        raise _Retry("响应未完成（status=%s）" % payload.get("status"))
    parts = []
    for item in payload.get("output") or []:
        if item.get("type") != "message":
            continue
        for chunk in item.get("content") or []:
            if chunk.get("type") in ("output_text", "text"):
                parts.append(chunk.get("text") or "")
    text = "".join(parts).strip()
    if not text:
        raise _Retry("上游返回空内容")
    return text


def _call(target, system, user, max_tokens, temperature, timeout):
    base = PROVIDER_BASE.get(target["provider"])
    if not base:
        raise ProviderError("未知供应商：%s" % target["provider"])
    headers = _headers(target["provider"])

    if target["provider"] == "wb":
        # 该网关忽略 system 角色，提示词一律并进 user（spec §4.3）
        body = {
            "model": target["model"],
            "messages": [{"role": "user", "content": system.rstrip() + "\n\n" + user}],
            "max_tokens": max_tokens,
            "temperature": temperature,
        }
        if target.get("reasoning"):
            body["reasoning_effort"] = target["reasoning"]
        return _chat_text(_post(base + "/chat/completions", body, headers, timeout))

    # openai-responses：system 走 instructions，temperature 不传（推理模型常拒）
    body = {
        "model": target["model"],
        "instructions": system,
        "input": [{"role": "user", "content": [{"type": "input_text", "text": user}]}],
        "max_output_tokens": max_tokens,
    }
    if target.get("reasoning"):
        body["reasoning"] = {"effort": target["reasoning"]}
    return _responses_text(_post(base + "/responses", body, headers, timeout))


def complete(model_id, system, user, *, max_tokens=6000, temperature=0.8, timeout=300):
    """调一次模型拿纯文本；网络类错误重试 3 次（共 4 次）。

    中转站的 502 是间歇性的（同一条词隔几分钟就能过），退避给足一点，
    别让一次抖动把整个词判成失败。
    """
    target = resolve(model_id)
    last = ""
    backoff = (2, 5, 10)
    lock = _PROVIDER_LOCKS.get(target.get("provider"))
    for attempt in range(4):
        try:
            if lock is None:
                return _call(target, system, user, max_tokens, temperature, timeout)
            with lock:
                return _call(target, system, user, max_tokens, temperature, timeout)
        except _Retry as exc:
            last = str(exc)
            if attempt < 3:
                time.sleep(backoff[attempt])
    raise ProviderError("%s 四次失败：%s" % (label(target), last))


def probe(model_id, timeout=90):
    """轻量连通性检查：能拿到 200 且有一句话就算通。返回 (秒, 字符数)。"""
    started = time.time()
    text = complete(
        model_id,
        "你是连通性探针，只回一句话。",
        "回一句「通了」即可。",
        max_tokens=2000,
        temperature=0,
        timeout=timeout,
    )
    return round(time.time() - started, 1), len(text)

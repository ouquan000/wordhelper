#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""20 词真实金丝雀压测（2026-11-20）。

串行跑 20 个词的 generate -> polish -> rules_hard -> binary judge。
生成固定用 glm53；judge 由 providers.judge_target 自动选另一个模型。
每个词在独立子进程里跑，父进程严格串行调度，单次上限 10 分钟。

用法（从 tools/rewrite-service 目录）：
    python tests/canary_20.py                  # 跑全部 20 词，写正式结果与报告
    python tests/canary_20.py --only cathedral --tag smoke
                                               # 只冒烟一个词，写 *_smoke 文件
    python tests/canary_20.py --resume         # 断点续跑（复用已完成的 worker 结果）

数据目录固定为 tests/_canary_data_20261120（REWRITE_DATA_DIR），
其中包含 wb_gate 状态、逐词日志、worker 中间结果。该目录不提交 git。
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE_DIR = os.path.dirname(HERE)
DATA_DIR = os.path.join(HERE, "_canary_data_20261120")
LOG_DIR = os.path.join(DATA_DIR, "logs")

RESULTS_JSON = os.path.join(HERE, "canary_20_results_20261120.json")
REPORT_MD = os.path.join(HERE, "canary_report_20261120.md")

MODEL = os.environ.get("CANARY_MODEL", "glm53")
BOOK_ID = "canary-20261120"
PER_WORD_TIMEOUT = 600  # 单次 10 分钟

NAIL_WORDS = [
    "cathedral", "obscure", "endeavor", "monopoly", "subtle",
    "conducive", "subsidy", "adolescent", "substantial", "casual",
]
CONTROL_WORDS = [
    "diminish", "random", "avoid", "allege", "pledge",
    "beforehand", "stumble", "message", "behavior", "emotion",
]
WORDS = NAIL_WORDS + CONTROL_WORDS

BINARY_FIELDS = (
    "has_unknown_part",
    "is_robot_tone",
    "is_logic_broken",
    "phonetic_drift_unwarned",
    "violates_user_intent",
)
BINARY_LABELS = {
    "has_unknown_part": "生僻词根/偏僻四级词",
    "is_robot_tone": "讲师报幕/词典腔/翻译腔",
    "is_logic_broken": "逻辑断链/单字母算命/纯抽象",
    "phonetic_drift_unwarned": "谐音偏离音标/拆法未提醒读音",
    "violates_user_intent": "没落实用户原话/请回被否路线",
}
METHOD_LABELS = {
    "sound": "谐音",
    "meaning": "含义联想",
    "shape": "字形联想",
    "root": "词根词缀",
    "contrast": "对比辨析",
    "polysemy": "熟词生义",
}


def now_iso() -> str:
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def category_of(word: str) -> str:
    return "高踩钉子户" if word in NAIL_WORDS else "常规对照"


def _is_gateway_error(message: str) -> bool:
    text = str(message or "")
    keys = ("冷却", "HTTP 429", "HTTP 503", "GateBusy", "串行槽", "429", "503")
    return any(key in text for key in keys)


# ------------------------------------------------------------------ worker


def worker_main(word: str, result_file: str) -> int:
    """子进程：真实跑一个词，记录每次 attempt 的 hard.hits 与 binary。"""
    os.environ["REWRITE_DATA_DIR"] = DATA_DIR
    os.environ["PYTHONIOENCODING"] = "utf-8"
    os.environ["PYTHONUTF8"] = "1"
    if SERVICE_DIR not in sys.path:
        sys.path.insert(0, SERVICE_DIR)

    import pipeline  # noqa: E402
    import providers  # noqa: E402
    import rules_hard  # noqa: E402
    import wb_gate  # noqa: E402

    item = {
        "word": word,
        "model": MODEL,
        "bookId": BOOK_ID,
        "clientId": "%s-%s" % (BOOK_ID, word),
        "instruction": "",
    }

    hard_calls = []
    judge_calls = []
    state = {"attempt": 0}

    original_hard = rules_hard.check
    original_judge = pipeline.judge

    def wrapped_hard(derivation, instruction="", banned_terms=None):
        state["attempt"] += 1
        result = original_hard(
            derivation, instruction=instruction, banned_terms=banned_terms
        )
        hard_calls.append(
            {
                "attempt": state["attempt"],
                "ok": bool(result.ok),
                "hits": list(result.hits),
            }
        )
        return result

    def wrapped_judge(item_arg, draft, approach):
        result = original_judge(item_arg, draft, approach)
        binary = getattr(result, "binary", None)
        judge_calls.append(
            {
                "attempt": state["attempt"],
                "binary": dict(binary) if isinstance(binary, dict) else None,
                "scores": dict(result[0]) if result and isinstance(result[0], dict) else {},
                "issues": list(result[1]) if result and len(result) > 1 else [],
                "intent": result[2] if result and len(result) > 2 else None,
                "same_method": result[3] if result and len(result) > 3 else None,
                "history_repeat": result[4] if result and len(result) > 4 else None,
            }
        )
        return result

    pipeline.rules_hard.check = wrapped_hard
    pipeline.judge = wrapped_judge

    judge_label = providers.label(providers.judge_target(MODEL))
    started = time.time()
    errors = []
    result = None
    run_count = 0

    for run_no in (1, 2):
        run_count = run_no
        remaining = wb_gate.cooldown_remaining()
        if remaining > 0:
            wait = min(remaining + 2.0, 900.0)
            print("[%s] WB 冷却中，等待 %.0fs 后重试" % (word, wait), flush=True)
            time.sleep(wait)
        try:
            result = pipeline.run(item, log=lambda msg: print(msg, flush=True))
            break
        except Exception as exc:  # noqa: BLE001 - 单条词失败要继续下一条
            message = "%s: %s" % (type(exc).__name__, str(exc)[:500])
            errors.append("第%d次执行：%s" % (run_no, message))
            result = None
            print("[%s] 失败：%s" % (word, message), flush=True)
            if run_no == 1 and _is_gateway_error(message):
                wait = max(5.0, min(900.0, wb_gate.cooldown_remaining() + 2.0))
                print("[%s] 网关限流，按 wb_gate 冷却等 %.0fs 后整词重试" % (word, wait), flush=True)
                time.sleep(wait)
                continue
            break

    elapsed = round(time.time() - started, 1)
    timings = dict((result or {}).get("timings") or {})
    timings.setdefault("total", elapsed)

    flat_hits = []
    for call in hard_calls:
        for hit in call.get("hits") or []:
            if hit not in flat_hits:
                flat_hits.append(hit)
    last_binary = judge_calls[-1]["binary"] if judge_calls else None
    last_judge = judge_calls[-1] if judge_calls else {}
    binary_missing = (
        [key for key in BINARY_FIELDS if last_binary.get(key) is None]
        if isinstance(last_binary, dict)
        else list(BINARY_FIELDS)
    )

    record = {
        "word": word,
        "category": category_of(word),
        "model": MODEL,
        "judge_model": judge_label,
        "method": (result or {}).get("method") or "",
        "approach": list((result or {}).get("approach") or []),
        "passed": bool(result and not result.get("unverified")),
        "unverified": bool(result.get("unverified", True)) if result else True,
        "attempts": (result or {}).get("attempts", len(hard_calls)),
        "timings": timings,
        "binary": last_binary,
        "binary_missing": binary_missing,
        "judge_flags": {
            "intent": last_judge.get("intent"),
            "same_method": last_judge.get("same_method"),
            "history_repeat": last_judge.get("history_repeat"),
        },
        "hard_hits": flat_hits,
        "hard_blocks": sum(1 for call in hard_calls if call.get("hits")),
        "hard_attempts": hard_calls,
        "judge_calls": judge_calls,
        "scores": (result or {}).get("scores") or {},
        "reason": (result or {}).get("reason") or "",
        "error": "；".join(errors),
        "elapsed_s": elapsed,
        "runs": run_count,
    }

    os.makedirs(os.path.dirname(result_file) or ".", exist_ok=True)
    with open(result_file, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(record, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(
        "[%s] 完成：passed=%s attempts=%s method=%s 用时 %.1fs"
        % (word, record["passed"], record["attempts"], record["method"], elapsed),
        flush=True,
    )
    return 0


# ------------------------------------------------------------------ parent


def heal_gate_slot(force: bool = False, max_age: float = 660.0) -> None:
    """清理被 kill 的 worker 留下的 active 标记；保留 cooldown 不动。"""
    path = os.path.join(DATA_DIR, "wb_gate.json")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return
    if not isinstance(data, dict) or not data.get("active"):
        return
    age = time.time() - float(data.get("acquiredAt") or 0)
    if not force and age <= max_age:
        return
    data["active"] = False
    data["holder"] = ""
    data["acquiredAt"] = 0.0
    data["updatedAt"] = time.time()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, sort_keys=True)
    os.replace(tmp, path)


def read_record(path: str):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def run_word(word: str, tag: str, resume: bool = False) -> dict:
    result_path = os.path.join(DATA_DIR, "worker_%s_%s.json" % (tag, word))
    log_path = os.path.join(LOG_DIR, "%s_%s.log" % (tag, word))

    if resume:
        cached = read_record(result_path)
        if cached and "error" in cached:
            print("[%s] 复用已完成结果（resume）" % word, flush=True)
            return cached

    os.makedirs(LOG_DIR, exist_ok=True)
    heal_gate_slot()

    env = os.environ.copy()
    env["REWRITE_DATA_DIR"] = DATA_DIR
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"

    cmd = [
        sys.executable,
        "-u",
        os.path.abspath(__file__),
        "--worker",
        word,
        "--result-file",
        result_path,
    ]
    started = time.time()
    timed_out = False
    rc = None
    with open(log_path, "wb") as fh:
        proc = subprocess.Popen(
            cmd, cwd=SERVICE_DIR, env=env, stdout=fh, stderr=subprocess.STDOUT
        )
        try:
            rc = proc.wait(timeout=PER_WORD_TIMEOUT)
        except subprocess.TimeoutExpired:
            timed_out = True
            proc.kill()
            try:
                rc = proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                rc = -999
    elapsed = round(time.time() - started, 1)

    record = read_record(result_path)
    if timed_out:
        heal_gate_slot(force=True)
    if not record:
        tail = ""
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
                tail = fh.read()[-2000:]
        except OSError:
            pass
        record = {
            "word": word,
            "category": category_of(word),
            "model": MODEL,
            "judge_model": "",
            "method": "",
            "approach": [],
            "passed": False,
            "unverified": True,
            "attempts": 0,
            "timings": {"total": elapsed},
            "binary": None,
            "binary_missing": list(BINARY_FIELDS),
            "judge_flags": {"intent": None, "same_method": None, "history_repeat": None},
            "hard_hits": [],
            "hard_blocks": 0,
            "hard_attempts": [],
            "judge_calls": [],
            "scores": {},
            "reason": "",
            "error": (
                "worker 超时（%d 秒）" % PER_WORD_TIMEOUT
                if timed_out
                else "worker 退出码 %s，没有结果文件；日志尾部：%s" % (rc, tail[-800:])
            ),
            "elapsed_s": elapsed,
            "runs": 0,
        }
    else:
        record["elapsed_s"] = elapsed
        record["worker_rc"] = rc
        record["timed_out"] = timed_out
        record["log"] = os.path.relpath(log_path, SERVICE_DIR)
    return record


# ------------------------------------------------------------------ report


def load_old(word: str):
    """data/results/<word>.json 的最后一条 entry（旧版）。"""
    path = os.path.join(SERVICE_DIR, "data", "results", word + ".json")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    entries = data.get("entries") if isinstance(data, dict) else None
    if not isinstance(entries, list) or not entries:
        return None
    last = entries[-1]
    if not isinstance(last, dict):
        return None
    approach = last.get("approach") or []
    if isinstance(approach, str):
        approach = [approach]
    return {
        "method": last.get("method") or "",
        "updatedAt": last.get("updatedAt") or "",
        "approach": [str(x) for x in approach],
    }


def load_smoke_evidence():
    """读取修复门禁前的 cathedral smoke 结果（若还在），供报告引用。"""
    path = os.path.join(HERE, "canary_20_results_20261120_smoke.json")
    data = read_record(path)
    if not data:
        return None
    rows = data.get("results")
    if not isinstance(rows, list) or not rows:
        return None
    row = rows[0]
    intents = []
    log_path = os.path.join(SERVICE_DIR, row.get("log") or "")
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                match = re.search(r"intent=([A-Za-z_]+)", line)
                if match:
                    intents.append(match.group(1))
    except OSError:
        pass
    return {
        "meta": data.get("meta") or {},
        "row": row,
        "intents": intents,
    }


def render_smoke_section(smoke) -> list:
    if not smoke:
        return []
    row = smoke["row"]
    meta = smoke["meta"]
    timings = row.get("timings") or {}
    lines = [
        "## 附：修复前门禁 smoke（cathedral，真实调用）",
        "",
        "> 本节是 v8-backend 修复 `_passed` 空 instruction 兼容前的历史证据，"
        "不计入上方 20 词通过率；数字全部来自 `%s`。" % os.path.basename(
            "canary_20_results_20261120_smoke.json"
        ),
        "",
        "- 运行时间：%s → %s" % (meta.get("started_at") or "—", meta.get("finished_at") or "—"),
        "- 模型：%s；Judge：%s" % (row.get("model") or MODEL, row.get("judge_model") or "—"),
        "- 总耗时：%s；attempts：%s；结果：%s" % (
            fmt_seconds(row.get("elapsed_s")),
            row.get("attempts", 0),
            "通过" if row.get("passed") else "unverified",
        ),
        "- 新版方法：%s" % fmt_method(row.get("method") or ""),
        "",
    ]
    calls = row.get("judge_calls") or []
    for index, call in enumerate(calls, 1):
        binary = call.get("binary")
        missing = [key for key in BINARY_FIELDS if not isinstance(binary, dict) or binary.get(key) is None]
        false_count = sum(
            1 for key in BINARY_FIELDS[:4]
            if isinstance(binary, dict) and binary.get(key) is False
        )
        round_key = "judge_%d" % index
        gen_key = "generate_%d" % index
        polish_key = "polish_%d" % index
        lines.append(
            "- 第%d轮：generate %s / polish %s / judge %s；排雷 %s；scores %s"
            % (
                index,
                fmt_seconds(timings.get(gen_key)),
                fmt_seconds(timings.get(polish_key)),
                fmt_seconds(timings.get(round_key)),
                fmt_binary(binary),
                " · ".join("%s %s" % (key, value) for key, value in (call.get("scores") or {}).items()) or "—",
            )
        )
        if false_count == 4 and missing == ["violates_user_intent"]:
            lines.append(
                "  - 本轮前四项排雷全 false、scores 无 0 分，但 `violates_user_intent` 缺失（null）→ `_passed` 判不过。"
            )
    if smoke["intents"]:
        lines.append("- 评委 intent（日志）：%s" % " / ".join("第%d轮 %s" % (i, v) for i, v in enumerate(smoke["intents"], 1)))
    lines.append("")
    return lines


def fmt_seconds(value) -> str:
    try:
        return "%.1fs" % float(value)
    except (TypeError, ValueError):
        return "—"


def fmt_duration(seconds: float) -> str:
    seconds = float(seconds or 0)
    minutes, rest = divmod(int(round(seconds)), 60)
    if minutes:
        return "%d 分 %d 秒" % (minutes, rest)
    return "%d 秒" % rest


def fmt_method(method: str) -> str:
    if not method:
        return "—"
    return "%s（%s）" % (method, METHOD_LABELS.get(method, "未知"))


def fmt_timings(timings: dict) -> str:
    if not isinstance(timings, dict) or not timings:
        return "—"
    order = ["generate_1", "polish_1", "judge_1", "generate_2", "polish_2", "judge_2", "total"]
    keys = [key for key in order if key in timings]
    keys += sorted(key for key in timings if key not in keys)
    return " · ".join("%s %s" % (key, fmt_seconds(timings[key])) for key in keys)


def fmt_binary(binary) -> str:
    if not isinstance(binary, dict):
        return "无 judge 结果（硬拦截或调用失败）"
    return " · ".join(
        "%s=%s" % (key, str(binary.get(key)).lower() if binary.get(key) is not None else "null")
        for key in BINARY_FIELDS
    )


def render_report(payload: dict) -> str:
    results = payload["results"]
    meta = payload["meta"]
    total_words = len(results)
    passed = [row for row in results if row.get("passed")]
    failed = [row for row in results if not row.get("passed")]
    total_elapsed = sum(float(row.get("elapsed_s") or 0) for row in results)
    average = total_elapsed / total_words if total_words else 0.0
    wall = meta.get("run_wall_s")
    hard_blocks = sum(int(row.get("hard_blocks") or 0) for row in results)
    hard_hit_words = [row["word"] for row in results if int(row.get("hard_blocks") or 0) > 0]
    hard_hit_lines = sum(len(row.get("hard_hits") or []) for row in results)
    total_attempts = sum(int(row.get("attempts") or 0) for row in results)
    hard_checks = sum(len(row.get("hard_attempts") or []) for row in results)
    judge_calls = sum(len(row.get("judge_calls") or []) for row in results)
    not_judged = [
        row["word"] for row in results
        if int(row.get("attempts") or 0) > len(row.get("judge_calls") or [])
    ]

    binary_counts = dict((key, 0) for key in BINARY_FIELDS)
    binary_nulls = dict((key, 0) for key in BINARY_FIELDS)
    judged_words = 0
    missing_words = []
    for row in results:
        binary = row.get("binary")
        if isinstance(binary, dict):
            judged_words += 1
            for key in BINARY_FIELDS:
                if binary.get(key) is True:
                    binary_counts[key] += 1
                elif binary.get(key) is None:
                    binary_nulls[key] += 1
        if row.get("binary_missing"):
            missing_words.append((row["word"], list(row["binary_missing"])))

    nail_pass = sum(1 for row in results if row.get("category") == "高踩钉子户" and row.get("passed"))
    control_pass = sum(1 for row in results if row.get("category") == "常规对照" and row.get("passed"))

    lines = []
    lines.append("# %d 词真实金丝雀压测报告（%s）" % (total_words, meta.get("date") or ""))
    lines.append("")
    lines.append("## 汇总")
    lines.append("")
    lines.append("- 词数：%d（高踩钉子户 %d / 常规对照 %d）" % (total_words, len(NAIL_WORDS), len(CONTROL_WORDS)))
    lines.append(
        "- 通过率：**%d / %d（%.1f%%）**；高踩钉子户 %d/%d，常规对照 %d/%d"
        % (
            len(passed), total_words, 100.0 * len(passed) / total_words if total_words else 0.0,
            nail_pass, len(NAIL_WORDS), control_pass, len(CONTROL_WORDS),
        )
    )
    lines.append("- 失败词：%s" % ("、".join(row["word"] for row in failed) if failed else "无"))
    lines.append(
        "- 总耗时：**%s**（逐词耗时合计 %s，平均 %s/词；整轮墙钟 %s）"
        % (fmt_duration(total_elapsed), fmt_seconds(total_elapsed), fmt_seconds(average), fmt_duration(wall or 0))
    )
    lines.append(
        "- 硬拦截总次数：**%d 次**（%d 个词命中，命中条目共 %d 条）"
        % (hard_blocks, len(hard_hit_words), hard_hit_lines)
    )
    attempt_note = ""
    if not_judged:
        attempt_note = "；%s 有 attempt 未进入 judge（如生成非 JSON）" % "、".join(not_judged)
    lines.append(
        "- 总 attempts：%d（hard.check %d 次 / judge %d 次%s）"
        % (total_attempts, hard_checks, judge_calls, attempt_note)
    )
    lines.append(
        "- 排雷各项 true 次数（每词最后一次 judge，共 %d/%d 词有 judge 结果）：%s"
        % (
            judged_words, total_words,
            " · ".join("%s %d" % (key, binary_counts[key]) for key in BINARY_FIELDS),
        )
    )
    null_parts = ["%s %d" % (key, binary_nulls[key]) for key in BINARY_FIELDS if binary_nulls[key]]
    if null_parts:
        lines.append("- 排雷缺键（null）次数：%s" % " · ".join(null_parts))
    if missing_words:
        if all(keys == ["violates_user_intent"] for _, keys in missing_words):
            lines.append(
                "- 说明：%d 个词最后一次 judge 未输出 violates_user_intent（无用户原话时该题不适用，"
                "judge() 解析为 null；当前 `_passed` 已兼容 None，其余四项仍须严格 false）。"
                % len(missing_words)
            )
        else:
            lines.append(
                "- ⚠️ 门禁缺键：**%d 个词**最后一次 judge 仍缺二值键 → %s"
                % (
                    len(missing_words),
                    "；".join("%s（%s）" % (word, "、".join(keys)) for word, keys in missing_words),
                )
            )
    lines.append("- 生成模型：%s（GLM 5.3）" % MODEL)
    lines.append("- Judge：providers.judge_target 自动选择 → %s" % (meta.get("judge_model") or "未知"))
    lines.append("- 日期：%s" % (meta.get("date") or ""))
    lines.append("- 环境：REWRITE_DATA_DIR=%s；严格串行，无并发；单次上限 %d 秒" % (meta.get("data_dir"), PER_WORD_TIMEOUT))
    lines.append("- 脚本：`python tests/canary_20.py`（%s）" % meta.get("script"))
    lines.append("")
    lines.append("| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for index, row in enumerate(results, 1):
        if row.get("passed"):
            verdict = "✅ 通过"
        elif row.get("error"):
            verdict = "❌ 失败"
        else:
            verdict = "❌ 未通过"
        lines.append(
            "| %d | %s | %s | %s | %s | %s | %d | %s |"
            % (
                index, row["word"], row.get("category", ""), verdict,
                fmt_method(row.get("method") or ""), row.get("attempts", 0),
                int(row.get("hard_blocks") or 0), fmt_seconds(row.get("elapsed_s")),
            )
        )
    lines.append("")
    lines.append("## 逐词明细")
    lines.append("")

    for index, row in enumerate(results, 1):
        word = row["word"]
        lines.append("### %d. %s（%s）" % (index, word, row.get("category", "")))
        lines.append("")
        if row.get("passed"):
            verdict = "✅ 通过"
        elif row.get("error"):
            verdict = "❌ 失败"
        else:
            verdict = "❌ 未通过（unverified）"
        lines.append("- 结果：%s" % verdict)
        lines.append("- 新版方法：%s" % fmt_method(row.get("method") or ""))
        lines.append("- 耗时：%s" % fmt_timings(row.get("timings") or {}))
        lines.append("- attempts：%s" % row.get("attempts", 0))
        lines.append(
            "- 硬拦截：%d 次%s"
            % (
                int(row.get("hard_blocks") or 0),
                ("（%s）" % "；".join(row.get("hard_hits") or []) if row.get("hard_hits") else ""),
            )
        )
        lines.append("- 排雷：%s" % fmt_binary(row.get("binary")))
        if row.get("scores"):
            lines.append(
                "- scores：%s"
                % " · ".join("%s %s" % (key, value) for key, value in row["scores"].items())
            )
        if row.get("reason"):
            lines.append("- 备注：%s" % row["reason"])
        if row.get("error"):
            lines.append("- 错误：%s" % row["error"])
        lines.append("")
        lines.append("**新版全文**")
        lines.append("")
        if row.get("approach"):
            for line in row["approach"]:
                lines.append("> %s" % line)
                lines.append(">")
            if lines and lines[-1] == ">":
                lines.pop()
        else:
            lines.append("> （无成品）")
        lines.append("")

        old = load_old(word)
        if old:
            lines.append(
                "**旧版**（`data/results/%s.json` 最后一条，method=%s，updatedAt=%s）"
                % (word, fmt_method(old["method"]), old["updatedAt"] or "—")
            )
            lines.append("")
            for line in old["approach"]:
                lines.append("> %s" % line)
                lines.append(">")
            if lines and lines[-1] == ">":
                lines.pop()
        else:
            lines.append("**旧版**：无旧版记录")
        lines.append("")

    lines.append("---")
    lines.append("")
    lines.extend(render_smoke_section(load_smoke_evidence()))
    lines.append("---")
    lines.append("")
    lines.append("本报告由 `tests/canary_20.py` 根据本次真实运行输出自动生成；所有数字来自脚本结果 JSON，无手写。")
    lines.append("")
    return "\n".join(lines)


# ------------------------------------------------------------------ main


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", default="", help=argparse.SUPPRESS)
    parser.add_argument("--result-file", default="", help=argparse.SUPPRESS)
    parser.add_argument("--only", default="", help="只跑指定词，逗号分隔")
    parser.add_argument("--tag", default="", help="输出文件名标签（默认空=正式结果）")
    parser.add_argument("--resume", action="store_true", help="复用已完成的 worker 结果")
    args = parser.parse_args()

    if args.worker:
        return worker_main(args.worker, args.result_file)

    os.environ["REWRITE_DATA_DIR"] = DATA_DIR
    os.environ["PYTHONIOENCODING"] = "utf-8"
    os.environ["PYTHONUTF8"] = "1"
    os.makedirs(DATA_DIR, exist_ok=True)
    os.makedirs(LOG_DIR, exist_ok=True)
    heal_gate_slot()

    if args.only:
        wanted = [word.strip() for word in args.only.split(",") if word.strip()]
        unknown = [word for word in wanted if word not in WORDS]
        if unknown:
            print("未知词：%s" % "、".join(unknown), file=sys.stderr)
            return 2
        words = wanted
    else:
        words = list(WORDS)

    tag = args.tag or ""
    suffix = ("_" + tag) if tag else ""
    results_json = os.path.join(HERE, "canary_20_results_20261120%s.json" % suffix)
    report_md = os.path.join(HERE, "canary_report_20261120%s.md" % suffix)

    run_started = time.time()
    rows = []
    for index, word in enumerate(words, 1):
        print("[%d/%d] %s 开始 %s" % (index, len(words), word, now_iso()), flush=True)
        row = run_word(word, tag or "full", resume=args.resume)
        rows.append(row)
        payload = {
            "meta": {
                "date": "2026-11-20",
                "script": os.path.relpath(os.path.abspath(__file__), SERVICE_DIR),
                "data_dir": os.path.relpath(DATA_DIR, SERVICE_DIR),
                "model": MODEL,
                "judge_model": row.get("judge_model") or "",
                "started_at": datetime.datetime.fromtimestamp(run_started).astimezone().isoformat(timespec="seconds"),
                "updated_at": now_iso(),
                "run_wall_s": round(time.time() - run_started, 1),
            },
            "results": rows,
        }
        with open(results_json, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print(
            "[%d/%d] %s 结束 passed=%s attempts=%s 用时 %s"
            % (index, len(words), word, row.get("passed"), row.get("attempts"), fmt_seconds(row.get("elapsed_s"))),
            flush=True,
        )

    payload["meta"]["finished_at"] = now_iso()
    payload["meta"]["run_wall_s"] = round(time.time() - run_started, 1)
    with open(results_json, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    with open(report_md, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(render_report(payload))

    passed = sum(1 for row in rows if row.get("passed"))
    failed = [row["word"] for row in rows if not row.get("passed")]
    print("=" * 60, flush=True)
    print("结果 JSON：%s" % results_json, flush=True)
    print("报告 MD：%s" % report_md, flush=True)
    print("通过 %d/%d，失败：%s" % (passed, len(rows), "、".join(failed) if failed else "无"), flush=True)
    print("总耗时：%s" % fmt_duration(time.time() - run_started), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())


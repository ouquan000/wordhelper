"""闲时洗词 worker（v1.5.3）。

这个 worker 只在「手动队列彻底空着」的时候，选取明日新词、限额未学词与到期复习词，
用现有 pipeline/Judge 重跑一遍记忆思路，成品写进 results/<word>.json，
由现有的结果流推给前端。

三条硬边界（改代码前先读）：
1) 默认关闭。config/worker.json 不存在、或 enabled=false，它就不跑。
2) 绝不占用 6 个手动高优槽。它不走 store.claim()/_enqueue()，不往队列里塞任何东西；
   模型调用一律 priority=10（前台相位见 wb_gate._priority_ok 只放行 >=100，
   所以它连抢都抢不到前台槽位，忙时自动被挡回去）。
3) 手动队列 pending+running>0 时一个词都不洗（only_when_manual_idle）。
   另外用户手改锁定（data/locks.json）的词永远不碰，写库那一刻也再确认一次。

配置只读不写：本模块永远不会创建/修改 config/worker.json。
运行开关（toggle）落在 data/worker_state.json，属于运行态数据。
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

import history
import pipeline
import profile
import providers
import store as store_mod
import worker_state
import worker_target
import worker_candidates
import worker_run

_api = sys.modules[__name__]

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

# 闲时任务的优先级。前台相位只接受 >=100，所以 10 在忙时会被 wb_gate 直接挡掉。
OFFLINE_PRIORITY = 10

#: 只在没配置文件时兜底。真配置一律从 config/worker.json 读。
DEFAULTS = {
    "enabled": False,
    "interval_sec": 300,
    "max_per_hour": 15,
    "max_per_day": 50,
    "max_new_per_day": 25,
    "include_review": False,
    "new_word_fallback_limit": 25,
    "only_when_manual_idle": True,
    "min_downvotes": 2,
    "target_methods": ["meaning", "polysemy"],
    "per_run": 1,
    "done_cooldown_sec": 7 * 24 * 3600,
}

#: 洗词优先级：先 meaning/polysemy（这两类最吃「思路」，重写收益最高）。
METHOD_ORDER = ("meaning", "polysemy", "contrast", "shape", "root", "sound")

MAX_FAILURES_PER_WORD = 3

_state = {
    "data_dir": "",
    "thread": None,
    "stop": None,
    "lock": threading.RLock(),
    "last": {},
}
_runtime_cache = {"path": "", "data": None}

# 词库只读兜底：feedback 行缺音标/释义时从这里补，不改写任何数据文件。
_WORD_SOURCE_PATHS = (
    os.path.join(REPO_ROOT, "content", "seed", "cet6_cards.json"),
    os.path.join(REPO_ROOT, "tools", "cardback-editor", "published", "cards", "cet6_cards.json"),
)
_word_index = {"loaded": False, "by_word": {}}


# ----------------------------------------------------------------- 工具

def _now():
    return worker_state._now(_api)


def _log(message):
    """worker 自己的日志，不往 service.log 里掺。"""
    return worker_state._log(_api, message)


def data_dir():
    """和 service.DATA_DIR 同一套解析规则；service 启动时会显式覆盖。"""
    return worker_state.data_dir(_api)


def configure(path):
    """service.startup() 用它把 DATA_DIR 告诉我们，保证和队列/结果流同一个目录。"""
    return worker_state.configure(_api, path)


def _state_path():
    return worker_state._state_path(_api)


# --------------------------------------------------------------- 配置

def config_path():
    """返回实际会读的配置路径（可能是空文件不存在，那就用默认值）。"""
    return worker_state.config_path(_api)


def _clamp_int(value, default, low, high):
    return worker_state._clamp_int(_api, value, default, low, high)


def normalize_config(raw):
    """把外部 JSON 收成一份一定能用的配置；坏字段一律退回默认值。"""
    return worker_state.normalize_config(_api, raw)


def load_config(path=None):
    """读配置：env → 仓库根 config/worker.json → 本目录 config/worker.json → 默认值。

    文件不存在/读坏 → 默认值（enabled=False）。本函数从不写配置。
    """
    return worker_state.load_config(_api, path)


# ----------------------------------------------------------- 运行态

def _load_runtime():
    return worker_state._load_runtime(_api)


def _save_runtime(runtime):
    return worker_state._save_runtime(_api, runtime)


def _parse_iso(value):
    return worker_state._parse_iso(_api, value)


def _recent(timestamp, seconds):
    return worker_state._recent(_api, timestamp, seconds)


def runs_last_hour(runtime):
    return worker_state.runs_last_hour(_api, runtime)


def enabled_now(config=None, runtime=None):
    return worker_state.enabled_now(_api, config, runtime)


# --------------------------------------------------------- 选词

def _feedback_rows(store):
    """流式读 feedback.jsonl，坏行跳过（这份日志是多进程 append 的）。"""
    return worker_candidates._feedback_rows(_api, store)


def _row_count(row):
    return worker_candidates._row_count(_api, row)


def _reasons_text(row):
    """feedback 行的 reasons（list / JSON 字符串）合成一行文本；缺失返回空串。"""
    return worker_candidates._reasons_text(_api, row)


def _row_complaint(row):
    """一条反馈行的用户原话：reasons + comment + instruction 合成，喂进生成 pipeline。"""
    return worker_candidates._row_complaint(_api, row)


def _word_source_lookup(word):
    """best-effort 只读：从 cet6_cards.json 读音标/释义，补 feedback 行缺失字段。"""
    return worker_candidates._word_source_lookup(_api, word)


def candidates(store, config=None, runtime=None, limit=20):
    """从学习日靶心中选择未受保护的六级词。"""
    return worker_candidates.candidates(_api, store, config, runtime, limit)


def manual_queue_busy(store):
    """手动队列是否还在忙（pending+running>0）。忙就一个词都别洗。"""
    return worker_run.manual_queue_busy(_api, store)


# --------------------------------------------------------- 洗一个词

def _candidate_item(bucket):
    """拼成 pipeline.run() 要的 item —— 字段跟 store._enqueue 的队列项保持一致。"""
    return worker_run._candidate_item(_api, bucket)


def run_candidate(bucket, store):
    """真正跑 pipeline 的那一步。priority=10 ⇒ 忙时被 wb_gate 挡回来，不抢前台槽位。"""
    return worker_run.run_candidate(_api, bucket, store)


_cycle_lock = threading.Lock()


def run_once(store=None, runner=None, config=None, limit=None):
    """跑一轮。runner 可注入 —— 测试绝不碰真模型。

    返回 {"ok","reason","washed":[{word,key,seq}],"candidates","at"}；不抛异常。
    """
    with _cycle_lock:
        return worker_run.run_once(_api, store, runner, config, limit)


# ------------------------------------------------------------ 线程

def _current_store():
    """worker 自己不去建 store —— 一律用 service 起好的那一个。"""
    return _state.get("store")


def attach(store, path=None):
    """只登记 store / 数据目录，不起线程。service.startup() 先 attach 再按开关起线程。"""
    configure(path)
    if store is not None:
        _state["store"] = store
    return status()


def _loop():
    stop = _state["stop"]
    while stop is not None and not stop.is_set():
        config = load_config()
        interval = config["interval_sec"]
        try:
            store = _current_store()
            if store is not None and enabled_now(config):
                result = run_once(store, config=config)
                _state["last"] = result
                if result.get("reason") not in ("no_candidates", "manual_queue_busy", "disabled"):
                    _log("一轮结束：%s" % json.dumps(result, ensure_ascii=False)[:400])
        except BaseException as exc:            # noqa: BLE001
            _state["last"] = {"ok": False, "reason": "%s: %s" % (type(exc).__name__, exc)}
            _log("worker 异常：%s" % exc)
        # 分段睡，方便 stop() 立刻退出
        waited = 0.0
        while waited < interval and not stop.is_set():
            time.sleep(min(5.0, interval - waited))
            waited += 5.0


def start(path=None, store=None):
    """开 worker 线程。已开着就复用。enabled 由 config/runtime 决定，不看这里。"""
    configure(path)
    if store is not None:
        _state["store"] = store
    with _state["lock"]:
        thread = _state.get("thread")
        if thread is not None and thread.is_alive():
            return status()
        stop = threading.Event()
        _state["stop"] = stop
        thread = threading.Thread(target=_loop, name="rewrite-worker", daemon=True)
        _state["thread"] = thread
        thread.start()
    _log("worker 线程启动")
    return status()


def stop(timeout=5.0):
    with _state["lock"]:
        thread = _state.get("thread")
        stop_event = _state.get("stop")
        if stop_event is not None:
            stop_event.set()
        _state["thread"] = None
        _state["stop"] = None
    if thread is not None and thread.is_alive():
        thread.join(timeout)
    _log("worker 线程停止")
    return status()


def review_enabled(config=None, runtime=None):
    """是否允许洗已学/到期复习词；运行态覆盖优先，默认 False（只洗新词）。"""
    config = config or load_config()
    runtime = runtime or _load_runtime()
    value = runtime.get("includeReview")
    if isinstance(value, bool):
        return value
    return bool(config.get("include_review", False))


def preview(limit=8):
    """明日靶心预览：只读候选，忽略当日配额，不启动线程。"""
    config = load_config()
    runtime = _load_runtime()
    store = _state.get("store")
    if store is None:
        return []
    try:
        rows = worker_target.select(_api, store, config, runtime, limit=limit, ignore_budget=True)
    except Exception as exc:  # noqa: BLE001 - 预览失败不影响控制台
        _log("候选预览失败：%r" % (exc,))
        return []
    out = []
    for bucket in rows:
        out.append({
            "word": bucket.get("word", ""),
            "wordId": bucket.get("word_id"),
            "book": bucket.get("book", ""),
            "meaning": bucket.get("meaning", ""),
            "why": bucket.get("why", ""),
            "new": bool(bucket.get("new")),
        })
    return out


def toggle(enabled, path=None, store=None, review=None):
    """面板开关：写运行态（不动 config/worker.json），并立刻启停线程。"""
    configure(path)
    if store is not None:
        _state["store"] = store
    runtime = _load_runtime()
    runtime["enabled"] = bool(enabled)
    if isinstance(review, bool):
        runtime["includeReview"] = review
    _save_runtime(runtime)
    if enabled:
        start(path=path, store=store)
    else:
        stop()
    return status()


def status(detail=False):
    """给 /rewrite/worker/status 与 service._status() 用。

    默认不做候选扫描（那是读整份 feedback.jsonl），只在 detail=True 时算。
    """
    config = load_config()
    runtime = _load_runtime()
    thread = _state.get("thread")
    running = bool(thread is not None and thread.is_alive())
    enabled = enabled_now(config, runtime)
    done = runtime.get("done") or {}
    day = worker_target.learning_window(_now())[0]
    usage = worker_target.daily(runtime, day)
    payload = {
        "enabled": enabled,
        "running": running,
        "configEnabled": bool(config["enabled"]),
        "runtimeEnabled": runtime.get("enabled"),
        "includeReview": review_enabled(config, runtime),
        "configPath": config.get("configPath") or "",
        "configFound": bool(config.get("configFound")),
        "intervalSec": config["interval_sec"],
        "maxPerHour": config["max_per_hour"],
        "maxPerDay": config["max_per_day"],
        "maxNewPerDay": config["max_new_per_day"],
        "attemptsToday": int(usage.get("attempts") or 0),
        "newWordsToday": int(usage.get("new") or 0),
        "fallbackToday": int(usage.get("fallback") or 0),
        "minDownvotes": config["min_downvotes"],
        "onlyWhenManualIdle": config["only_when_manual_idle"],
        "targetMethods": list(config["target_methods"]),
        "priority": OFFLINE_PRIORITY,
        "runsLastHour": runs_last_hour(runtime),
        "budgetLeft": max(0, config["max_per_hour"] - runs_last_hour(runtime)),
        "washedTotal": int(runtime.get("washed") or 0),
        "doneCount": len(done),
        "lastRun": runtime.get("lastRun") or {},
        "lastError": runtime.get("lastError") or "",
        "lastCycle": _state.get("last") or {},
        "dataDir": data_dir(),
    }
    if detail:
        store = _state.get("store")
        if store is not None:
            payload["manualQueueBusy"] = manual_queue_busy(store)
            payload["candidates"] = len(candidates(store, config, runtime))
        else:
            payload["manualQueueBusy"] = None
            payload["candidates"] = None
    return payload

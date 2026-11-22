"""闲时洗词 worker（v1.5.3）。

这个 worker 只在「手动队列彻底空着」的时候，捡用户踩过差评的旧词，
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
import threading
import time
from datetime import datetime, timedelta, timezone

import history
import pipeline
import profile
import providers
import store as store_mod

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

# 闲时任务的优先级。前台相位只接受 >=100，所以 10 在忙时会被 wb_gate 直接挡掉。
OFFLINE_PRIORITY = 10

#: 只在没配置文件时兜底。真配置一律从 config/worker.json 读。
DEFAULTS = {
    "enabled": False,
    "interval_sec": 300,
    "max_per_hour": 6,
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


# ----------------------------------------------------------------- 工具

def _now():
    return datetime.now(timezone.utc).astimezone().replace(microsecond=0).isoformat()


def _log(message):
    """worker 自己的日志，不往 service.log 里掺。"""
    path = os.path.join(data_dir(), "logs", "worker.log")
    line = "%s %s\n" % (_now(), message)
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(line)
    except OSError:
        pass


def data_dir():
    """和 service.DATA_DIR 同一套解析规则；service 启动时会显式覆盖。"""
    explicit = str(_state.get("data_dir") or "")
    if explicit:
        return explicit
    return os.environ.get("REWRITE_DATA_DIR") or os.path.join(HERE, "data")


def configure(path):
    """service.startup() 用它把 DATA_DIR 告诉我们，保证和队列/结果流同一个目录。"""
    with _state["lock"]:
        wanted = str(path or "")
        if wanted and wanted != _state["data_dir"]:
            _state["data_dir"] = wanted
            _runtime_cache["path"] = ""
            _runtime_cache["data"] = None
    return data_dir()


def _state_path():
    return os.path.join(data_dir(), "worker_state.json")


# --------------------------------------------------------------- 配置

def config_path():
    """返回实际会读的配置路径（可能是空文件不存在，那就用默认值）。"""
    env = os.environ.get("REWRITE_WORKER_CONFIG")
    if env:
        return env
    return os.path.join(REPO_ROOT, "config", "worker.json")


def _clamp_int(value, default, low, high):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


def normalize_config(raw):
    """把外部 JSON 收成一份一定能用的配置；坏字段一律退回默认值。"""
    raw = raw if isinstance(raw, dict) else {}
    methods = []
    for item in raw.get("target_methods") or []:
        key = profile.normalize_method(item)
        if key and key not in methods:
            methods.append(key)
    if not methods:
        methods = list(DEFAULTS["target_methods"])
    config = {
        "enabled": bool(raw.get("enabled", DEFAULTS["enabled"])),
        "interval_sec": _clamp_int(raw.get("interval_sec"), DEFAULTS["interval_sec"], 30, 86400),
        "max_per_hour": _clamp_int(raw.get("max_per_hour"), DEFAULTS["max_per_hour"], 0, 60),
        "only_when_manual_idle": bool(raw.get("only_when_manual_idle", DEFAULTS["only_when_manual_idle"])),
        "min_downvotes": _clamp_int(raw.get("min_downvotes"), DEFAULTS["min_downvotes"], 1, 20),
        "target_methods": methods,
        "per_run": _clamp_int(raw.get("per_run"), DEFAULTS["per_run"], 1, 5),
        "done_cooldown_sec": _clamp_int(
            raw.get("done_cooldown_sec"), DEFAULTS["done_cooldown_sec"], 3600, 30 * 24 * 3600
        ),
    }
    return config


def load_config(path=None):
    """读配置：env → 仓库根 config/worker.json → 本目录 config/worker.json → 默认值。

    文件不存在/读坏 → 默认值（enabled=False）。本函数从不写配置。
    """
    candidates = []
    if path:
        candidates.append(path)
    else:
        candidates.append(config_path())
        candidates.append(os.path.join(HERE, "config", "worker.json"))
    for candidate in candidates:
        raw = store_mod._read_json(candidate)
        if isinstance(raw, dict):
            config = normalize_config(raw)
            config["configPath"] = candidate
            config["configFound"] = True
            return config
    config = normalize_config({})
    config["configPath"] = candidates[0] if candidates else ""
    config["configFound"] = False
    return config


# ----------------------------------------------------------- 运行态

def _load_runtime():
    path = _state_path()
    if _runtime_cache["path"] == path and _runtime_cache["data"] is not None:
        return _runtime_cache["data"]
    raw = store_mod._read_json(path)
    data = raw if isinstance(raw, dict) else {}
    runtime = {
        "enabled": data.get("enabled") if isinstance(data.get("enabled"), bool) else None,
        "runs": [str(x) for x in (data.get("runs") or []) if isinstance(x, str)],
        "done": data.get("done") if isinstance(data.get("done"), dict) else {},
        "washed": int(data.get("washed") or 0) if str(data.get("washed") or 0).lstrip("-").isdigit() else 0,
        "lastRun": data.get("lastRun") if isinstance(data.get("lastRun"), dict) else {},
        "lastError": str(data.get("lastError") or ""),
    }
    _runtime_cache["path"] = path
    _runtime_cache["data"] = runtime
    return runtime


def _save_runtime(runtime):
    runtime["updatedAt"] = _now()
    _runtime_cache["path"] = _state_path()
    _runtime_cache["data"] = runtime
    store_mod._write_json(_state_path(), runtime)


def _parse_iso(value):
    try:
        return datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None


def _recent(timestamp, seconds):
    moment = _parse_iso(timestamp)
    if moment is None:
        return False
    return (datetime.now(moment.tzinfo) - moment).total_seconds() < seconds


def runs_last_hour(runtime):
    return len([ts for ts in runtime.get("runs") or [] if _recent(ts, 3600)])


def enabled_now(config=None, runtime=None):
    config = config or load_config()
    runtime = runtime or _load_runtime()
    if isinstance(runtime.get("enabled"), bool):
        return runtime["enabled"]
    return bool(config["enabled"])


# --------------------------------------------------------- 选词

def _feedback_rows(store):
    """流式读 feedback.jsonl，坏行跳过（这份日志是多进程 append 的）。"""
    path = os.path.join(store.data_dir, "feedback.jsonl")
    rows = []
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict):
                    rows.append(row)
    except OSError:
        return []
    return rows


def _row_count(row):
    count, error = store_mod.feedback_count(row.get("count"))
    return 0 if error else count


def candidates(store, config=None, runtime=None, limit=20):
    """挑出「该洗」的词：差评够多、差评多于好评、没锁定、最近没洗过。

    返回按优先级排好的列表（meaning/polysemy 优先，然后按差评票数）。
    """
    config = config or load_config()
    runtime = runtime or _load_runtime()
    minimum = config["min_downvotes"]
    cooldown = config["done_cooldown_sec"]
    methods = set(config["target_methods"])
    done = runtime.get("done") or {}

    buckets = {}
    for row in _feedback_rows(store):
        word = str(row.get("word") or "").strip()
        if not word:
            continue
        word_id = store_mod._lock_part(row.get("wordId"))
        book_id, book = store_mod.book_scope(row)
        key = store_mod.lock_key(word_id or word.lower(), book_id)
        bucket = buckets.get(key)
        if bucket is None:
            bucket = buckets[key] = {
                "key": key,
                "word": word,
                "word_id": word_id,
                "book_id": book_id,
                "book": book,
                "downVotes": 0,
                "upVotes": 0,
                "method": "",
                "instruction": "",
                "phonetic": "",
                "pos": "",
                "meaning": "",
                "approach": [],
                "model": "",
                "lastAt": "",
            }
        rating = str(row.get("rating") or "")
        count = _row_count(row)
        if rating == "down":
            bucket["downVotes"] += count
        elif rating == "up":
            bucket["upVotes"] += count
        else:
            continue
        stamp = str(row.get("receivedAt") or row.get("createdAt") or "")
        if stamp >= bucket["lastAt"]:
            bucket["lastAt"] = stamp
            method = profile.normalize_method(row.get("method"))
            if method:
                bucket["method"] = method
            instruction = str(row.get("instruction") or "")
            if instruction:
                bucket["instruction"] = instruction
            for field in ("phonetic", "pos", "meaning", "model"):
                value = row.get(field)
                if isinstance(value, str) and value.strip():
                    bucket[field] = value.strip()
            fresh = [str(x) for x in (row.get("approach") or row.get("currentApproach") or []) if str(x).strip()]
            if fresh:
                bucket["approach"] = fresh[:5]

    picked = []
    for key, bucket in buckets.items():
        if not bucket["word"]:
            continue
        if bucket["downVotes"] < minimum:
            continue
        if bucket["downVotes"] <= bucket["upVotes"]:
            continue    # 已被点赞救回来的词不再动它
        if store.is_locked(bucket["word_id"] or bucket["word"], bucket["book_id"]):
            continue
        history_row = done.get(key)
        if isinstance(history_row, dict):
            if history_row.get("ok"):
                if _recent(history_row.get("at"), cooldown):
                    continue
            elif int(history_row.get("failures") or 0) >= MAX_FAILURES_PER_WORD:
                if _recent(history_row.get("at"), cooldown):
                    continue
        picked.append(bucket)

    def sort_key(bucket):
        method = bucket.get("method") or "meaning"
        in_target = 0 if method in methods else 1
        try:
            order = METHOD_ORDER.index(method)
        except ValueError:
            order = len(METHOD_ORDER)
        return (in_target, order, -bucket["downVotes"], bucket["key"])

    picked.sort(key=sort_key)
    return picked[:limit]


def manual_queue_busy(store):
    """手动队列是否还在忙（pending+running>0）。忙就一个词都别洗。"""
    status = store.status()
    return (int(status.get("pending") or 0) + int(status.get("running") or 0)) > 0


# --------------------------------------------------------- 洗一个词

def _candidate_item(bucket):
    """拼成 pipeline.run() 要的 item —— 字段跟 store._enqueue 的队列项保持一致。"""
    return {
        "clientId": "worker:%s" % (bucket["key"][:24] or "anon"),
        "word": bucket["word"],
        "wordId": bucket["word_id"],
        "bookId": bucket["book_id"],
        "book": bucket["book"],
        "phonetic": bucket.get("phonetic") or "",
        "pos": bucket.get("pos") or "",
        "meaning": bucket.get("meaning") or "",
        "instruction": bucket.get("instruction") or "",
        "currentApproach": list(bucket.get("approach") or []),
        "model": bucket.get("model") or "",
        "priority": OFFLINE_PRIORITY,
    }


def run_candidate(bucket, store):
    """真正跑 pipeline 的那一步。priority=10 ⇒ 忙时被 wb_gate 挡回来，不抢前台槽位。"""
    item = _candidate_item(bucket)
    item["history"] = history.build(store.data_dir, item["word"], item["instruction"], scope=item)
    prompt_hash = pipeline.prompt_hash()
    instruction_hash = pipeline.instruction_hash(item)
    providers.set_task_priority(OFFLINE_PRIORITY)
    try:
        out = pipeline.run(item, log=_log, on_stage=None)
        if not isinstance(out, dict):
            raise RuntimeError("pipeline 没有返回结果")
        if out.get("quality_rejected"):
            return {"ok": False, "reason": "quality_rejected", "rejected": True}
        approach = [str(x) for x in (out.get("approach") or []) if str(x).strip()]
        if not approach:
            return {"ok": False, "reason": "empty_approach"}
        entry = store.record_worker_result(
            item["word"],
            approach,
            meta={
                "method": out.get("method"),
                "scores": out.get("scores"),
                "attempts": out.get("attempts", 1),
                "unverified": out.get("unverified"),
                "reason": out.get("reason"),
                "promptHash": prompt_hash,
                "instructionHash": instruction_hash,
            },
            word_id=item["wordId"],
            book_id=item["bookId"],
            book=item["book"],
            model=item["model"],
        )
        if entry is None:
            return {"ok": False, "reason": "locked"}
        return {"ok": True, "entry": entry}
    finally:
        providers.set_task_priority(100)


def run_once(store=None, runner=None, config=None, limit=None):
    """跑一轮。runner 可注入 —— 测试绝不碰真模型。

    返回 {"ok","reason","washed":[{word,key,seq}],"candidates","at"}；不抛异常。
    """
    started = _now()
    store = store or _current_store()
    if store is None:
        return {"ok": False, "reason": "no_store", "washed": [], "at": started}
    config = config or load_config()
    runtime = _load_runtime()

    if not enabled_now(config, runtime):
        return {"ok": False, "reason": "disabled", "washed": [], "at": started}
    if config["only_when_manual_idle"] and manual_queue_busy(store):
        return {"ok": False, "reason": "manual_queue_busy", "washed": [], "at": started}
    budget = config["max_per_hour"] - runs_last_hour(runtime)
    if budget <= 0:
        return {"ok": False, "reason": "hourly_budget", "washed": [], "at": started}

    pool = candidates(store, config, runtime, limit=max(20, (limit or config["per_run"]) * 4))
    if not pool:
        return {"ok": True, "reason": "no_candidates", "washed": [], "candidates": 0, "at": started}

    want = min(budget, config["per_run"], limit or config["per_run"])
    runner = runner or run_candidate
    washed = []
    for bucket in pool[:want]:
        if store.is_locked(bucket["word_id"] or bucket["word"], bucket["book_id"]):
            continue
        key = bucket["key"]
        record = runtime["done"].get(key)
        if not isinstance(record, dict):
            record = {"failures": 0}
        try:
            result = runner(bucket, store) or {}
        except BaseException as exc:            # noqa: BLE001 - worker 线程绝不能死
            result = {"ok": False, "reason": "%s: %s" % (type(exc).__name__, exc)}
            runtime["lastError"] = str(result["reason"])[:400]
            _log("洗词失败 %s：%s" % (bucket["word"], result["reason"]))
        if result.get("ok"):
            record = {"at": _now(), "ok": True, "failures": 0, "seq": (result.get("entry") or {}).get("seq", 0)}
            runtime["washed"] = int(runtime.get("washed") or 0) + 1
            washed.append({
                "word": bucket["word"],
                "key": key,
                "seq": record["seq"],
            })
        else:
            record = {
                "at": _now(),
                "ok": False,
                "failures": int(record.get("failures") or 0) + 1,
                "reason": str(result.get("reason") or "")[:200],
            }
        runtime["done"][key] = record
        runtime["runs"].append(_now())

    runtime["lastRun"] = {
        "at": _now(),
        "washed": len(washed),
        "candidates": len(pool),
        "reason": "ok" if washed else "nothing_washed",
    }
    _save_runtime(runtime)
    return {
        "ok": True,
        "reason": "ok" if washed else "nothing_washed",
        "washed": washed,
        "candidates": len(pool),
        "at": started,
    }


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


def toggle(enabled, path=None, store=None):
    """面板开关：写运行态（不动 config/worker.json），并立刻启停线程。"""
    configure(path)
    if store is not None:
        _state["store"] = store
    runtime = _load_runtime()
    runtime["enabled"] = bool(enabled)
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
    payload = {
        "enabled": enabled,
        "running": running,
        "configEnabled": bool(config["enabled"]),
        "runtimeEnabled": runtime.get("enabled"),
        "configPath": config.get("configPath") or "",
        "configFound": bool(config.get("configFound")),
        "intervalSec": config["interval_sec"],
        "maxPerHour": config["max_per_hour"],
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

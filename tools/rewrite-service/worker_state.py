"""闲时配置的读取、路径选择、运行态恢复与保存。"""

from __future__ import annotations


def _now(api):
    return api.datetime.now(api.timezone.utc).astimezone().replace(microsecond=0).isoformat()


def _log(api, message):
    """worker 自己的日志，不往 service.log 里掺。"""
    path = api.os.path.join(api.data_dir(), "logs", "worker.log")
    line = "%s %s\n" % (api._now(), message)
    try:
        api.os.makedirs(api.os.path.dirname(path), exist_ok=True)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(line)
    except OSError:
        pass


def data_dir(api):
    """和 service.DATA_DIR 同一套解析规则；service 启动时会显式覆盖。"""
    explicit = str(api._state.get("data_dir") or "")
    if explicit:
        return explicit
    return api.os.environ.get("REWRITE_DATA_DIR") or api.os.path.join(api.HERE, "data")


def configure(api, path):
    """service.startup() 用它把 DATA_DIR 告诉我们，保证和队列/结果流同一个目录。"""
    with api._state["lock"]:
        wanted = str(path or "")
        if wanted and wanted != api._state["data_dir"]:
            api._state["data_dir"] = wanted
            api._runtime_cache["path"] = ""
            api._runtime_cache["data"] = None
    return api.data_dir()


def _state_path(api):
    return api.os.path.join(api.data_dir(), "worker_state.json")


def config_path(api):
    """返回实际会读的配置路径（可能是空文件不存在，那就用默认值）。"""
    env = api.os.environ.get("REWRITE_WORKER_CONFIG")
    if env:
        return env
    return api.os.path.join(api.REPO_ROOT, "config", "worker.json")


def _clamp_int(api, value, default, low, high):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


def normalize_config(api, raw):
    """把外部 JSON 收成一份一定能用的配置；坏字段一律退回默认值。"""
    raw = raw if isinstance(raw, dict) else {}
    methods = []
    for item in raw.get("target_methods") or []:
        key = api.profile.normalize_method(item)
        if key and key not in methods:
            methods.append(key)
    if not methods:
        methods = list(api.DEFAULTS["target_methods"])
    config = {
        "enabled": bool(raw.get("enabled", api.DEFAULTS["enabled"])),
        "interval_sec": api._clamp_int(raw.get("interval_sec"), api.DEFAULTS["interval_sec"], 30, 86400),
        "max_per_hour": api._clamp_int(raw.get("max_per_hour"), api.DEFAULTS["max_per_hour"], 0, 60),
        "only_when_manual_idle": bool(raw.get("only_when_manual_idle", api.DEFAULTS["only_when_manual_idle"])),
        "sync_path": api.os.path.join(api.REPO_ROOT, raw.get("sync_path") or "tools/cardback-editor/sync.json"),
        "upcoming_new_word_ids": raw.get("upcoming_new_word_ids") or [],
        "max_per_day": api._clamp_int(raw.get("max_per_day"), api.DEFAULTS["max_per_day"], 0, 1440),
        "max_new_per_day": api._clamp_int(
            raw.get("max_new_per_day"), api.DEFAULTS["max_new_per_day"], 0, 1440
        ),
        "include_review": bool(raw.get("include_review", api.DEFAULTS["include_review"])),
        "new_word_fallback_limit": api._clamp_int(raw.get("new_word_fallback_limit"), 25, 0, 1440),
        "min_downvotes": api._clamp_int(raw.get("min_downvotes"), api.DEFAULTS["min_downvotes"], 1, 20),
        "target_methods": methods,
        "per_run": api._clamp_int(raw.get("per_run"), api.DEFAULTS["per_run"], 1, 5),
        "done_cooldown_sec": api._clamp_int(
            raw.get("done_cooldown_sec"), api.DEFAULTS["done_cooldown_sec"], 3600, 30 * 24 * 3600
        ),
    }
    return config


def load_config(api, path=None):
    """读配置：env → 仓库根 config/worker.json → 本目录 config/worker.json → 默认值。

    文件不存在/读坏 → 默认值（enabled=False）。本函数从不写配置。
    """
    candidates = []
    if path:
        candidates.append(path)
    else:
        candidates.append(api.config_path())
        candidates.append(api.os.path.join(api.HERE, "config", "worker.json"))
    for candidate in candidates:
        raw = api.store_mod._read_json(candidate)
        if isinstance(raw, dict):
            config = api.normalize_config(raw)
            config["configPath"] = candidate
            config["configFound"] = True
            return config
    config = api.normalize_config({})
    config["configPath"] = candidates[0] if candidates else ""
    config["configFound"] = False
    return config


def _load_runtime(api):
    path = api._state_path()
    if api._runtime_cache["path"] == path and api._runtime_cache["data"] is not None:
        return api._runtime_cache["data"]
    raw = api.store_mod._read_json(path)
    data = raw if isinstance(raw, dict) else {}
    runtime = {
        "enabled": data.get("enabled") if isinstance(data.get("enabled"), bool) else None,
        "daily": data.get("daily") or {},
        "runs": [str(x) for x in (data.get("runs") or []) if isinstance(x, str)],
        "done": data.get("done") if isinstance(data.get("done"), dict) else {},
        "washed": int(data.get("washed") or 0) if str(data.get("washed") or 0).lstrip("-").isdigit() else 0,
        "lastRun": data.get("lastRun") if isinstance(data.get("lastRun"), dict) else {},
        "lastError": str(data.get("lastError") or ""),
    }
    # 兼容旧运行态：只有显式布尔值才覆盖配置里的 include_review。
    if isinstance(data.get("includeReview"), bool):
        runtime["includeReview"] = data["includeReview"]
    api._runtime_cache["path"] = path
    api._runtime_cache["data"] = runtime
    return runtime


def _save_runtime(api, runtime):
    runtime["updatedAt"] = api._now()
    api._runtime_cache["path"] = api._state_path()
    api._runtime_cache["data"] = runtime
    api.store_mod._write_json(api._state_path(), runtime)


def _parse_iso(api, value):
    try:
        return api.datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None


def _recent(api, timestamp, seconds):
    moment = api._parse_iso(timestamp)
    if moment is None:
        return False
    return (api.datetime.now(moment.tzinfo) - moment).total_seconds() < seconds


def runs_last_hour(api, runtime):
    return len([ts for ts in runtime.get("runs") or [] if api._recent(ts, 3600)])


def enabled_now(api, config=None, runtime=None):
    config = config or api.load_config()
    runtime = runtime or api._load_runtime()
    if isinstance(runtime.get("enabled"), bool):
        return runtime["enabled"]
    return bool(config["enabled"])

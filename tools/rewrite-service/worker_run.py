"""闲时单轮调度、配额和锁复查、调用流水线及记账。"""

from __future__ import annotations

import rewrite_protection as protection
from worker_target import daily, learning_window


def manual_queue_busy(api, store):
    """手动队列是否还在忙（pending+running>0）。忙就一个词都别洗。"""
    status = store.status()
    return (int(status.get("pending") or 0) + int(status.get("running") or 0)) > 0


def _candidate_item(api, bucket):
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
        "priority": api.OFFLINE_PRIORITY,
    }


def run_candidate(api, bucket, store):
    """真正跑 pipeline 的那一步。priority=10 ⇒ 忙时被 wb_gate 挡回来，不抢前台槽位。"""
    item = api._candidate_item(bucket)
    item["history"] = api.history.build(store.data_dir, item["word"], item["instruction"], scope=item)
    prompt_hash = api.pipeline.prompt_hash()
    instruction_hash = api.pipeline.instruction_hash(item)
    api.providers.set_task_priority(api.OFFLINE_PRIORITY)
    try:
        out = api.pipeline.run(item, log=api._log, on_stage=None)
        if not isinstance(out, dict):
            raise RuntimeError("pipeline 没有返回结果")
        if out.get("quality_rejected") or out.get("unverified"):
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
        api.providers.set_task_priority(100)


def run_once(api, store=None, runner=None, config=None, limit=None):
    """跑一轮。runner 可注入 —— 测试绝不碰真模型。

    返回 {"ok","reason","washed":[{word,key,seq}],"candidates","at"}；不抛异常。
    """
    started = api._now()
    store = store or api._current_store()
    if store is None:
        return {"ok": False, "reason": "no_store", "washed": [], "at": started}
    config = config or api.load_config()
    runtime = api._load_runtime()

    if not api.enabled_now(config, runtime):
        return {"ok": False, "reason": "disabled", "washed": [], "at": started}
    if config["only_when_manual_idle"] and api.manual_queue_busy(store):
        return {"ok": False, "reason": "manual_queue_busy", "washed": [], "at": started}
    budget = config["max_per_hour"] - api.runs_last_hour(runtime)
    if budget <= 0:
        return {"ok": False, "reason": "hourly_budget", "washed": [], "at": started}

    day, _ = learning_window(started)
    usage = daily(runtime, day)
    if usage["attempts"] >= config["max_per_day"]:
        return {"ok": True, "reason": "daily_budget", "washed": [], "at": started}
    if not api.review_enabled(config, runtime) and usage.get("new", 0) >= config["max_new_per_day"]:
        return {"ok": True, "reason": "new_word_budget", "washed": [], "at": started}

    pool = api.candidates(store, config, runtime, limit=max(20, (limit or config["per_run"]) * 4))
    if not pool:
        return {"ok": True, "reason": "no_candidates", "washed": [], "candidates": 0, "at": started}

    want = min(budget, config["per_run"], limit or config["per_run"])
    runner = runner or api.run_candidate
    washed = []
    for bucket in pool[:want]:
        if learning_window(api._now())[0] != day or (config["only_when_manual_idle"] and api.manual_queue_busy(store)):
            break
        votes = protection.latest_votes(api._feedback_rows(store))
        vote = votes.get(bucket["word"].casefold())
        if (vote and vote[0][1]) or bucket["word"].casefold() in protection.locked_words(store.locks(), votes):
            continue
        if store.is_locked(bucket["word_id"] or bucket["word"], bucket["book_id"]):
            continue
        key = bucket["key"]
        record = runtime["done"].get(key)
        if not isinstance(record, dict):
            record = {"failures": 0}
        usage["attempts"] += 1
        usage["fallback"] += int(bucket.get("fallback", False))
        if bucket.get("new"):
            usage["new"] = int(usage.get("new") or 0) + 1
        runtime["daily"] = usage
        runtime["runs"] = [ts for ts in runtime["runs"] if api._recent(ts, 3600)] + [api._now()]
        api._save_runtime(runtime)
        try:
            result = runner(bucket, store) or {}
        except BaseException as exc:            # noqa: BLE001 - worker 线程绝不能死
            result = {"ok": False, "reason": "%s: %s" % (type(exc).__name__, exc)}
            runtime["lastError"] = str(result["reason"])[:400]
            api._log("洗词失败 %s：%s" % (bucket["word"], result["reason"]))
        prior_quality = record.get("qualityAttempts", 0) if protection.timestamp(record.get("at")) >= (vote[0][0] if vote else 0) else 0
        if result.get("ok"):
            record = {"at": api._now(), "ok": True, "failures": 0, "seq": (result.get("entry") or {}).get("seq", 0)}
            runtime["washed"] = int(runtime.get("washed") or 0) + 1
            washed.append({
                "word": bucket["word"],
                "key": key,
                "seq": record["seq"],
            })
        else:
            record = {
                "at": api._now(),
                "ok": False,
                "failures": int(record.get("failures") or 0) + 1,
                "reason": str(result.get("reason") or "")[:200],
            }
        if result.get("rejected"):
            record["qualityAttempts"] = prior_quality + 1
            if record["qualityAttempts"] >= 5:
                record["reason"] = "quality_rejected_at_limit"
        record["engine"] = protection.ENGINE
        record["day"] = day
        runtime["done"][key] = record
        api._save_runtime(runtime)

    runtime["lastRun"] = {
        "at": api._now(),
        "washed": len(washed),
        "candidates": len(pool),
        "reason": "ok" if washed else "nothing_washed",
    }
    api._save_runtime(runtime)
    return {
        "ok": True,
        "reason": "ok" if washed else "nothing_washed",
        "washed": washed,
        "candidates": len(pool),
        "at": started,
    }

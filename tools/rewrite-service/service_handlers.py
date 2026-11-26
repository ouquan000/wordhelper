"""重写请求的校验、反馈、锁词、状态和闲时开关处理。"""

from __future__ import annotations


def _marks(api, body):
    payload = api._read_body(body)
    marks = payload.get("marks") if isinstance(payload, dict) else payload
    if not isinstance(marks, list):
        raise api.ApiError("marks 必须是数组")
    if len(marks) > 200:
        raise api.ApiError("一次最多 200 条标记")
    accepted, rejected = api._state["store"].add_marks(marks)
    api.log("收标 %d 条：接受 %d 拒绝 %d" % (len(marks), len(accepted), len(rejected)))
    return {"accepted": accepted, "rejected": rejected}


def _feedback(api, body):
    """卡背 👍/👎（1.4.6）：落盘，同时喂跨词画像与样例池。

    1.5.2 起每条反馈可带可选整数 count（1..50，缺省 1），表示「这条票代表几次」：
    卡背连点两下不是手抖就是真觉得有用。count 只放大画像里的这一次投票，
    不改变 clientId 幂等语义；旧客户端不带 count 时行为与 1.4.6 完全一致。
    """
    payload = api._read_body(body)
    items = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(items, list):
        raise api.ApiError("items 必须是数组")
    if len(items) > 200:
        raise api.ApiError("一次最多 200 条反馈")
    accepted, rejected = api._state["store"].add_feedback(items)
    api.log("收反馈 %d 条：接受 %d 拒绝 %d" % (len(items), len(accepted), len(rejected)))
    # add_feedback 对重复 clientId 也回 accepted，不能拿返回值判断新增；
    # 直接用原始 items，profile/exemplars 各自按 id 幂等。
    # store 只拒收「非对象 / 缺 clientId / rating 非 up/down / count 非法」，下面几道校验
    # 天然排除，被拒的非法反馈不会算进画像。
    try:
        current = api._profile_state()
        for raw in items:
            if not isinstance(raw, dict):
                continue
            client_id = str(raw.get("clientId") or "").strip()
            method = api.profile.normalize_method(raw.get("method"))
            rating = str(raw.get("rating") or "").strip().lower()
            count, count_error = api.store_mod.feedback_count(raw.get("count"))
            if count_error:  # store 已把这条拒了，画像不能偷偷记票
                continue
            if client_id and method and rating in ("up", "down"):
                current.update_feedback(method, rating, event_id="feedback:" + client_id, count=count)
        api._exemplar_state().ingest_feedback(items)
    except Exception as exc:  # noqa: BLE001 - 旁路学习失败不能影响已落盘的反馈
        api.log("更新画像/样例失败：%r" % (exc,))
    return {"ok": True, "accepted": accepted, "rejected": rejected}


def _memory_signal(api, body):
    """复习结果信号（v8）：retained / lapsed → 更新六招画像。"""
    data = api._read_body(body)
    if not isinstance(data, dict):
        raise api.ApiError("请求体要是对象")
    client_id = str(data.get("clientId") or "").strip()
    word = str(data.get("word") or "").strip()
    book_id = str(data.get("bookId") or "0").strip()
    method = api.profile.normalize_method(data.get("method"))
    event = str(data.get("event") or "").strip().lower()
    if not client_id:
        raise api.ApiError("缺 clientId")
    if not word:
        raise api.ApiError("缺 word")
    if not method:
        raise api.ApiError("method 只能是 sound/root/contrast/shape/polysemy/meaning")
    if event not in ("retained", "lapsed"):
        raise api.ApiError("event 只能是 retained/lapsed")
    applied, reason = api._state["store"].record_memory_signal({
        "clientId": client_id,
        "word": word,
        "bookId": book_id,
        "method": method,
        "event": event,
        "stability": data.get("stability"),
        "difficulty": data.get("difficulty"),
        "lapses": data.get("lapses"),
    })
    if applied:
        try:
            api._profile_state().update_memory_signal(
                method,
                event,
                event_id="memory:" + "|".join((client_id, word, book_id, method, event)),
            )
        except Exception as exc:  # noqa: BLE001
            api.log("更新记忆画像失败：%r" % (exc,))
        api.log("记忆信号 %s/%s：%s" % (word, method, event))
    out = {"ok": True, "applied": applied}
    if not applied and reason:
        out["reason"] = reason
    return out


def _page(api, name):
    """自包含的静态页：/rewrite 是重写记录页，/rewrite/panel 是老调试面板。"""
    filename = api.PAGE_FILES.get(name, api.PAGE_FILES["panel"])
    try:
        with open(api.os.path.join(api.HERE, filename), "rb") as fh:
            return 200, "text/html; charset=utf-8", fh.read()
    except OSError:
        return 500, "text/plain; charset=utf-8", ("%s 不见了" % filename).encode("utf-8")


def _status(api):
    store = api._state["store"]
    out = store.status()
    out["version"] = api.SERVICE_VERSION
    out["concurrency"] = api.CONCURRENCY
    out["startedAt"] = api._state["startedAt"]
    out["judge"] = api.providers.label(api.providers.judge_target("ds-v41-flash"))
    out["wbGate"] = api.wb_gate.status()
    try:
        out["worker"] = api.worker_mod.status()
    except Exception as exc:  # noqa: BLE001 - 状态接口不能因为 worker 出问题就整包挂掉
        out["worker"] = {"enabled": False, "error": "%r" % (exc,)}
    return out


def _stop(api, body):
    """停止重写：给了 word/clientId 停那一条，什么都不给就停全部。

    运行中的任务不能真杀线程，但 generation+1 后晚到的结果会被 finish_* 丢掉。
    """
    data = api._read_body(body)
    payload = data if isinstance(data, dict) else {}
    word = str(payload.get("word") or "").strip()
    client_id = str(payload.get("clientId") or "").strip()
    hit = api._state["store"].stop(word=word, client_id=client_id)
    api.log("停止 %s：%d 条" % (client_id or word or "全部", hit))
    return {"stopped": hit, "word": word, "clientId": client_id}


def _gate(api, body):
    """手动切换 WB 阶段：foreground / batch / auto / paused。"""
    data = api._read_body(body)
    payload = data if isinstance(data, dict) else {}
    value = str(payload.get("phase") or "").strip().lower()
    try:
        phase = api.wb_gate.set_phase(value)
    except ValueError as exc:
        raise api.ApiError(str(exc))
    api.log("WB 阶段切到 %s" % phase)
    return {"phase": phase, "gate": api.wb_gate.status()}


def _lock_fields(api, raw):
    """单条锁定指令 → (wordId, bookId, locked, note, word, book)，字段不合规就报错。"""
    if not isinstance(raw, dict):
        raise api.ApiError("锁定指令不是对象")
    word_id = raw.get("wordId")
    if word_id is None or (isinstance(word_id, str) and not word_id.strip()):
        raise api.ApiError("缺 wordId")
    book_id = raw.get("bookId")
    if book_id is None or (isinstance(book_id, str) and not book_id.strip()):
        raise api.ApiError("缺 bookId")
    locked = raw.get("locked")
    locked = True if locked is None else bool(locked)
    note = str(raw.get("note") or "").strip()
    word = str(raw.get("word") or "").strip()
    book = str(raw.get("book") or "").strip()
    return word_id, book_id, locked, note, word, book


def _set_lock(api, body):
    """锁定/解锁：body 可以是单条 {wordId, bookId, locked?, note?, word?}，也可以 {items: [...]}。"""
    data = api._read_body(body)
    if isinstance(data, list):
        payload = {"items": data}
    elif isinstance(data, dict):
        payload = data
    else:
        raise api.ApiError("请求体要是对象或数组")
    items = payload.get("items")
    rows = items if isinstance(items, list) else [payload]
    if not rows:
        raise api.ApiError("没有要锁定的词")
    if len(rows) > 200:
        raise api.ApiError("一次最多锁定 200 条")
    store = api._state["store"]
    out = []
    for raw in rows:
        word_id, book_id, locked, note, word, book = api._lock_fields(raw)
        out.append(store.set_lock(word_id, book_id, locked=locked, note=note, word=word, book=book))
    locked_count = sum(1 for row in out if row.get("locked"))
    api.log("锁定更新 %d 条（锁定 %d 条）" % (len(out), locked_count))
    return {
        "ok": True,
        "locks": out,
        "locked": bool(out) and all(row.get("locked") for row in out),
        "lockedCount": locked_count,
        "updatedAt": store.locks_updated_at(),
    }


def _locks(api, query):
    """读锁定表；book 可选，按书筛。"""
    book = (query.get("book") or [""])[0].strip()[:60]
    store = api._state["store"]
    rows = store.lock_records(book)
    return {
        "ok": True,
        "locks": rows,
        "count": len(rows),
        "lockedCount": sum(1 for row in rows if row.get("locked")),
        "book": book,
        "updatedAt": store.locks_updated_at(),
    }


def _worker_status(api):
    store = api._state["store"]
    try:
        api.worker_mod.attach(store, api.DATA_DIR)
    except Exception as exc:  # noqa: BLE001 - 只读接口，attach 失败也要能回包
        api.log("worker attach 失败：%r" % (exc,))
    out = {"ok": True, "version": api.SERVICE_VERSION}
    out.update(api.worker_mod.status())
    return out


def _worker_toggle(api, body):
    """运行时开关闲时洗词：默认关，只有显式 enabled=true 才会开始跑。"""
    data = api._read_body(body)
    payload = data if isinstance(data, dict) else {}
    if "enabled" not in payload:
        raise api.ApiError("缺 enabled")
    enabled = bool(payload.get("enabled"))
    store = api._state["store"]
    status = api.worker_mod.toggle(enabled, api.DATA_DIR, store=store)
    api.log("闲时洗词 worker：%s" % ("启用" if enabled else "关闭"))
    return {"ok": True, "enabled": bool(status.get("enabled")), "status": status}


def _taste(api):
    """读取动态审美画像（只读，不改文件）。"""
    profile = api.taste_profile.load(api.DATA_DIR)
    return {"ok": True, "profile": profile, "text": api.taste_profile.block(api.DATA_DIR)}


def _taste_save(api, body):
    """界面编辑：rules / lockedRules 可单独改；locked 自动并入 rules 且排最前。"""
    payload = api._read_body(body)
    if not isinstance(payload, dict):
        raise api.ApiError("请求体要是对象")
    rules = payload.get("rules")
    locked = payload.get("lockedRules")
    if rules is not None and not isinstance(rules, list):
        raise api.ApiError("rules 必须是数组")
    if locked is not None and not isinstance(locked, list):
        raise api.ApiError("lockedRules 必须是数组")
    profile = api.taste_profile.set_rules(api.DATA_DIR, rules, locked)
    api.log("审美画像更新：%d 条规则，锁定 %d 条" % (len(profile["rules"]), len(profile["lockedRules"])))
    return {"ok": True, "profile": profile, "text": api.taste_profile.block(api.DATA_DIR)}


def _taste_distill(api, body):
    """触发一次蒸馏（默认 ds-v41-flash）；lockedRules 原样保留。"""
    payload = api._read_body(body)
    payload = payload if isinstance(payload, dict) else {}
    model = str(payload.get("model") or api.taste_profile.DEFAULT_MODEL).strip()
    if model not in api.providers.MODELS:
        raise api.ApiError("未知模型：%s" % model)
    profile = api.taste_profile.distill(api.DATA_DIR, model=model)
    api.log("审美画像蒸馏完成：%d 条规则（来源 %d 条反馈）" % (
        len(profile["rules"]), profile["sourceFeedbackCount"]))
    return {"ok": True, "profile": profile, "text": api.taste_profile.block(api.DATA_DIR)}

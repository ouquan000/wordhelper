#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""卡背重写服务的 HTTP 接口 + 后台工人。

不自己占端口：被 tools/cardback-editor/server.py 以 /rewrite 前缀挂进 8765，
手机和编辑器同源，省掉跨域和内网穿透两件麻烦事。
单独调试可以 `python service.py --port 8799`。
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
import urllib.parse
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import history  # noqa: E402
import exemplars  # noqa: E402
import batch_pipeline  # noqa: E402
import pipeline  # noqa: E402
import profile  # noqa: E402
import providers  # noqa: E402
import store as store_mod  # noqa: E402
import wb_gate  # noqa: E402
import worker as worker_mod  # noqa: E402

DATA_DIR = os.environ.get("REWRITE_DATA_DIR") or os.path.join(HERE, "data")
LOG_PATH = os.path.join(DATA_DIR, "logs", "service.log")


def _int_env(name, default, low=1, high=64):
    try:
        value = int(str(os.environ.get(name, default)).strip())
    except (TypeError, ValueError):
        value = default
    return max(low, min(high, value))


# 工人数默认跟 gate 槽位对齐；两者分开配只会让一半工人堵在锁上。
def _default_concurrency():
    try:
        return int(wb_gate.slot_count())
    except (AttributeError, TypeError, ValueError):
        return _int_env("WB_GATE_SLOTS", 6)


CONCURRENCY = _int_env("REWRITE_CONCURRENCY", _default_concurrency())
REUSE_HISTORY = os.environ.get("REUSE_HISTORY", "1").strip().lower() not in (
    "0", "false", "no", "off",
)
BATCH_SIZE = batch_pipeline.batch_size()
BATCH_ENABLED = os.environ.get("BATCH_MODE", "0").strip().lower() not in (
    "0", "false", "no", "off",
)
MAX_LOG_BYTES = 2 * 1024 * 1024
#: 服务版本。1.5.3 起了点踩结构化 / 手改锁定 / 闲时洗词三件事，面板按它判断功能有没有。
SERVICE_VERSION = "1.5.3v2"
CAPABILITIES = ("rewrite_scoped",)

_state = {"store": None, "threads": [], "startedAt": "", "ready": False}
_lock = threading.Lock()


# ------------------------------------------------------------------ 基础设施


def log(message):
    """按行追加，超阈值就截掉旧的一半 —— 日志是给人看的，不该无限长。"""
    try:
        os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
        if os.path.exists(LOG_PATH) and os.path.getsize(LOG_PATH) > MAX_LOG_BYTES:
            with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as fh:
                tail = fh.read()[-MAX_LOG_BYTES // 2 :]
            with open(LOG_PATH, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(tail)
        with open(LOG_PATH, "a", encoding="utf-8", newline="\n") as fh:
            fh.write("%s %s\n" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), message))
    except OSError:
        pass


def _json(payload, status=200):
    return status, "application/json; charset=utf-8", json.dumps(
        payload, ensure_ascii=False
    ).encode("utf-8")


class ApiError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


# ------------------------------------------------------------------ 工人


def _work(item):
    store = _state["store"]
    started = time.time()

    def on_stage(stage):
        """把流水线的阶段切换落到队列记录上（失败/被覆盖由 finish_* 兜底）。"""
        store.set_stage(item["clientId"], stage)

    try:
        # 同词历史在 claim 之后、生成之前才组装：上一轮结果刚落盘时也能读到。
        item = dict(item)
        item["history"] = history.build(
            store.data_dir, item.get("word") or "", item.get("instruction") or "", scope=item,
        )
        # 同词历史复用：提示词/用户原话/模型/书全一致才命中。默认开，
        # REUSE_HISTORY=0 可关掉做全量重跑对照。
        prompt_hash = pipeline.prompt_hash()
        instruction_hash = pipeline.instruction_hash(item)
        if REUSE_HISTORY:
            reused = store.find_reusable_result(item, prompt_hash, instruction_hash)
            if reused:
                meta = {
                    "method": reused.get("method") or "",
                    "scores": reused.get("scores") or {},
                    "attempts": 0,
                    "unverified": False,
                    "reason": "复用历史结果",
                    "promptHash": prompt_hash,
                    "instructionHash": instruction_hash,
                    "reused": True,
                    "reuseOf": reused.get("seq"),
                }
                entry = store.finish_ok(item, reused.get("approach") or [], meta)
                if entry:
                    log("[%s] 复用历史 seq=%s -> 新 seq=%s（未调用模型）" % (
                        item["word"], reused.get("seq"), entry["seq"]))
                return
        providers.set_task_priority(item.get("priority", 100))
        out = pipeline.run(item, log=log, on_stage=on_stage)
        # 1.4.7.2：质量没过不再「照发 unverified」——那会让用户永远卡在两轮都不过
        # 的稿子上。这里只留最佳候选，按指数退避排下一次；合格的那次才 finish_ok。
        if out.get("quality_rejected"):
            rejected = store.finish_quality_rejected(
                item,
                out.get("approach") or [],
                {
                    "method": out.get("method") or "",
                    "scores": out.get("scores") or {},
                    "attempts": out.get("attempts", 1),
                    "reason": out.get("reason") or "",
                },
            )
            if rejected:
                log("[%s] 质量未过，第 %d 次，%s 后重试：%s" % (
                    item["word"], rejected["failures"], rejected["nextRunAt"],
                    (out.get("reason") or "")[:200]))
            return
        meta = {
            "method": out.get("method") or "",
            "scores": out.get("scores") or {},
            "attempts": out.get("attempts", 1),
            "unverified": False,
            "reason": str(out.get("reason") or ""),
        }
        entry = store.finish_ok(item, out["approach"], meta)
        if entry:
            log("[%s] 完成 seq=%s rev=%s %s" % (item["word"], entry["seq"], entry["rev"], out["timings"]))
    except providers.ProviderError as exc:
        retry = store.finish_retry(item, str(exc))
        log("[%s] 模型调用失败，%s 后重试：%s" % (
            item["word"], (retry or {}).get("nextRunAt", "稍后"), exc))
    except pipeline.PipelineError as exc:
        retry = store.finish_retry(item, str(exc))
        log("[%s] 流水线失败，%s 后重试：%s" % (
            item["word"], (retry or {}).get("nextRunAt", "稍后"), exc))
    except Exception as exc:  # noqa: BLE001 - 工人不能死，一条词的意外不该拖垮整队
        retry = store.finish_retry(item, "未预期错误：%r" % exc)
        log("[%s] 未预期错误，%s 后重试：%r" % (
            item["word"], (retry or {}).get("nextRunAt", "稍后"), exc))
    finally:
        log("[%s] 本条用时 %.1fs" % (item["word"], time.time() - started))


def _finish_reuse(item, reused, prompt_hash, instruction_hash):
    store = _state["store"]
    meta = {
        "method": reused.get("method") or "",
        "scores": reused.get("scores") or {},
        "attempts": 0,
        "unverified": False,
        "reason": "复用历史结果",
        "promptHash": prompt_hash,
        "instructionHash": instruction_hash,
        "reused": True,
        "reuseOf": reused.get("seq"),
    }
    entry = store.finish_ok(item, reused.get("approach") or [], meta)
    if entry:
        log("[%s] 复用历史 seq=%s -> 新 seq=%s（未调用模型）" % (
            item["word"], reused.get("seq"), entry["seq"]))
    return entry


def _claim_batch(limit, claimed=None):
    """先等一条，再尽量多抓几条组成小批；抓不到就按实际条数跑。

    claimed 由调用方传入时把每条认领立即记进去：中途 claim 抛异常，_worker
    也能看到本轮已经拿到的任务并逐个释放，不会留下僵尸 running。
    """
    store = _state["store"]
    items = claimed if claimed is not None else []
    first = store.claim(60)
    if first is None:
        return items
    items.append(first)
    while len(items) < limit:
        nxt = store.claim(0.05)
        if nxt is None:
            break
        items.append(nxt)
    return items


def _work_batch(items):
    """小批量通道：批量 generate + 批量 judge，坏一条只回退那一条。

    polish 仍是单条：它只改文字，批量收益小，且失败隔离必须精确到词。
    任何批量/单项异常都交给 _work(item) 走原有单词精修通道，不拖累同批。
    """
    store = _state["store"]
    prepared = []
    for raw in items:
        item = dict(raw)
        item["history"] = history.build(
            store.data_dir, item.get("word") or "", item.get("instruction") or "", scope=item,
        )
        prompt_hash = pipeline.prompt_hash()
        instruction_hash = pipeline.instruction_hash(item)
        if REUSE_HISTORY:
            reused = store.find_reusable_result(item, prompt_hash, instruction_hash)
            if reused:
                _finish_reuse(item, reused, prompt_hash, instruction_hash)
                continue
        prepared.append((item, prompt_hash, instruction_hash))
    if not prepared:
        return

    # 批量通道也要按本轮任务重设优先级：providers 的优先级是 thread-local，
    # 复用上一单残留值会让前台批被 foreground 阶段误挡（或让离线批冒充前台）。
    # 一批可能混有不同优先级，取最高的一个，避免把前台任务挡在门外。
    priorities = []
    for item, _, _ in prepared:
        try:
            priorities.append(int(item.get("priority", 100)))
        except (TypeError, ValueError):
            priorities.append(100)
    providers.set_task_priority(max(priorities))

    log("批处理 %d 词：%s" % (len(prepared), "、".join(i["word"] for i, _, _ in prepared)))
    for item, _, _ in prepared:
        try:
            store.set_stage(item["clientId"], "generate")
        except Exception:  # noqa: BLE001 - 埋点别拖垮生成
            pass
    try:
        drafts = batch_pipeline.generate_batch([item for item, _, _ in prepared])
    except Exception as exc:  # noqa: BLE001 - 批通道坏了整批回退单条
        log("批生成失败，整批回退单条：%s" % str(exc)[:200])
        for item, _, _ in prepared:
            _work(item)
        return

    ready = []
    fallback = []
    for item, prompt_hash, instruction_hash in prepared:
        word = item["word"]
        draft = drafts.get(word) or drafts.get(word.casefold()) or drafts.get(word.lower())
        if not isinstance(draft, dict):
            fallback.append((item, prompt_hash, instruction_hash))
            continue
        try:
            store.set_stage(item["clientId"], "polish")
            approach = pipeline.polish(item, draft)
            bans = pipeline.banned_terms(item.get("instruction"))
            violations = list(draft.get("violations") or [])
            if bans:
                for hit in pipeline._text_hits(bans, approach, allow_last_note=True):
                    if hit not in violations:
                        violations.append(hit)
            hard = pipeline.rules_hard.check(
                approach,
                instruction=str(item.get("instruction") or ""),
                banned_terms=[ban["needle"] for ban in bans] if bans else None,
            )
            if hard.ok and not violations:
                ready.append((item, draft, approach, prompt_hash, instruction_hash))
            else:
                fallback.append((item, prompt_hash, instruction_hash))
        except Exception as exc:  # noqa: BLE001 - 单条润色坏了只回退该词
            log("[%s] 批润色失败，回退单条：%s" % (word, str(exc)[:160]))
            fallback.append((item, prompt_hash, instruction_hash))

    if ready:
        try:
            judged = batch_pipeline.judge_batch(
                [item for item, _, _, _, _ in ready],
                {item["word"]: draft for item, draft, _, _, _ in ready},
                {item["word"]: approach for item, _, approach, _, _ in ready},
            )
        except Exception as exc:  # noqa: BLE001 - 批评审坏了整批回退
            log("批评审失败，整批回退单条：%s" % str(exc)[:200])
            judged = {}
        for item, draft, approach, prompt_hash, instruction_hash in ready:
            result = judged.get(item["word"]) or judged.get(item["word"].casefold())
            if result is None:
                fallback.append((item, prompt_hash, instruction_hash))
                continue
            scores, issues, intent, same_method, history_repeat = result
            binary = getattr(result, "binary", None)
            if not pipeline._passed(
                item, scores, intent, same_method, history_repeat,
                binary=binary, hard_ok=True,
            ):
                fallback.append((item, prompt_hash, instruction_hash))
                continue
            meta = {
                "method": draft.get("method") or "",
                "scores": scores,
                "attempts": 1,
                "unverified": False,
                "reason": "",
                "promptHash": prompt_hash,
                "instructionHash": instruction_hash,
            }
            if store.finish_ok(item, approach, meta):
                log("[%s] 批完成（未回退）" % item["word"])

    for item, _, _ in fallback:
        _work(item)


def _worker(index):
    store = _state["store"]
    while True:
        # 整轮包住：_claim_batch / _work / _work_batch 任何一步抛异常，
        # 都把本轮已经拿到、仍停在 running 的任务退回 retry_wait，
        # 杜绝 chair 类僵尸 running 占着工位却没人再管。
        claimed = []
        try:
            if BATCH_ENABLED and BATCH_SIZE > 1:
                items = _claim_batch(BATCH_SIZE, claimed)
                if not items:
                    log("工人 %d 退出" % index)
                    return
                if len(items) == 1:
                    _work(items[0])
                else:
                    _work_batch(items)
            else:
                item = store.claim(60)
                if item is None:
                    log("工人 %d 退出" % index)
                    return
                claimed.append(item)
                _work(item)
        except Exception as exc:  # noqa: BLE001 - 工人不能因为一次抢单/落盘抖动就永久消失
            log("工人 %d 异常，1s 后继续：%r" % (index, exc))
            for item in claimed:
                try:
                    released = store.release_claim(item, "工人异常：%r" % (exc,))
                except Exception as release_exc:  # noqa: BLE001 - 一条释放失败不能挡住其余
                    log("[%s] 释放认领失败：%r" % (item.get("word") or "?", release_exc))
                    continue
                # release_claim 只在 generation 未变且 state 仍 running 时生效，
                # 已经完成的同轮任务会返回 None，这里不会误释放。
                if released:
                    log("[%s] 工人异常，已释放回重试：%s" % (
                        item.get("word") or "?", released.get("nextRunAt", "")))
            time.sleep(1.0)


# ------------------------------------------------------------------ 生命周期


def _profile_state():
    """按当前 DATA_DIR 取画像实例；测试改 DATA_DIR 后也不会串库。"""
    return profile.get_profile(os.path.join(DATA_DIR, "profile.json"))


def _exemplar_state():
    return exemplars.get_exemplars(DATA_DIR)


def startup():
    """幂等挂载：导入后调一次，重启整个编辑器进程时由它把队列接回来。"""
    with _lock:
        if _state["ready"]:
            return _state["store"]
        os.makedirs(os.path.join(DATA_DIR, "results"), exist_ok=True)
        os.makedirs(os.path.join(DATA_DIR, "logs"), exist_ok=True)
        store = store_mod.Store(DATA_DIR).load()
        _state["store"] = store
        _state["startedAt"] = datetime.now().astimezone().isoformat(timespec="seconds")
        _state["ready"] = True
        # 预热跨词画像与样例池：坏了也只记日志，不能让重写服务起不来。
        try:
            _profile_state()
            _exemplar_state()
        except Exception as exc:  # noqa: BLE001
            log("预热 profile/exemplars 失败：%r" % (exc,))
        pending = store.status()["pending"]
        log("重写服务启动：并发 %d，待跑 %d 条，seq=%s" % (CONCURRENCY, pending, store.seq))
        for index in range(CONCURRENCY):
            thread = threading.Thread(target=_worker, args=(index,), name="rewrite-%d" % index, daemon=True)
            thread.start()
            _state["threads"].append(thread)
        # 1.5.3 闲时洗词 worker：默认关。config/worker.json 里 enabled=true 才起线程，
        # 起来也只是个空闲哨兵，真跑不跑还要看手动队列是否空着。
        try:
            worker_mod.attach(store, DATA_DIR)
            if worker_mod.enabled_now():
                worker_mod.start(DATA_DIR, store=store)
                log("闲时洗词 worker 已启用")
        except Exception as exc:  # noqa: BLE001 - worker 起不来不能拖垮主服务
            log("启动闲时洗词 worker 失败：%r" % (exc,))
        return store


def shutdown():
    with _lock:
        try:
            worker_mod.stop()
        except Exception as exc:  # noqa: BLE001
            log("停止闲时洗词 worker 失败：%r" % (exc,))
        if _state["store"] is not None:
            _state["store"].close()
            log("重写服务停止")


# ------------------------------------------------------------------ 路由


def _parse(raw_path):
    parts = urllib.parse.urlsplit(raw_path or "/")
    return parts.path, urllib.parse.parse_qs(parts.query)


def _int(query, key, default, low, high):
    try:
        value = int((query.get(key) or [default])[0])
    except (TypeError, ValueError):
        value = default
    return max(low, min(high, value))


def _read_body(body):
    if not body:
        return {}
    try:
        data = json.loads(body.decode("utf-8", "replace") if isinstance(body, bytes) else body)
    except ValueError:
        raise ApiError("请求体不是 JSON")
    if not isinstance(data, (dict, list)):
        raise ApiError("请求体要是对象或数组")
    return data


def _marks(body):
    payload = _read_body(body)
    marks = payload.get("marks") if isinstance(payload, dict) else payload
    if not isinstance(marks, list):
        raise ApiError("marks 必须是数组")
    if len(marks) > 200:
        raise ApiError("一次最多 200 条标记")
    accepted, rejected = _state["store"].add_marks(marks)
    log("收标 %d 条：接受 %d 拒绝 %d" % (len(marks), len(accepted), len(rejected)))
    return {"accepted": accepted, "rejected": rejected}


def _feedback(body):
    """卡背 👍/👎（1.4.6）：落盘，同时喂跨词画像与样例池。

    1.5.2 起每条反馈可带可选整数 count（1..50，缺省 1），表示「这条票代表几次」：
    卡背连点两下不是手抖就是真觉得有用。count 只放大画像里的这一次投票，
    不改变 clientId 幂等语义；旧客户端不带 count 时行为与 1.4.6 完全一致。
    """
    payload = _read_body(body)
    items = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(items, list):
        raise ApiError("items 必须是数组")
    if len(items) > 200:
        raise ApiError("一次最多 200 条反馈")
    accepted, rejected = _state["store"].add_feedback(items)
    log("收反馈 %d 条：接受 %d 拒绝 %d" % (len(items), len(accepted), len(rejected)))
    # add_feedback 对重复 clientId 也回 accepted，不能拿返回值判断新增；
    # 直接用原始 items，profile/exemplars 各自按 id 幂等。
    # store 只拒收「非对象 / 缺 clientId / rating 非 up/down / count 非法」，下面几道校验
    # 天然排除，被拒的非法反馈不会算进画像。
    try:
        current = _profile_state()
        for raw in items:
            if not isinstance(raw, dict):
                continue
            client_id = str(raw.get("clientId") or "").strip()
            method = profile.normalize_method(raw.get("method"))
            rating = str(raw.get("rating") or "").strip().lower()
            count, count_error = store_mod.feedback_count(raw.get("count"))
            if count_error:  # store 已把这条拒了，画像不能偷偷记票
                continue
            if client_id and method and rating in ("up", "down"):
                current.update_feedback(method, rating, event_id="feedback:" + client_id, count=count)
        _exemplar_state().ingest_feedback(items)
    except Exception as exc:  # noqa: BLE001 - 旁路学习失败不能影响已落盘的反馈
        log("更新画像/样例失败：%r" % (exc,))
    return {"ok": True, "accepted": accepted, "rejected": rejected}


def _memory_signal(body):
    """复习结果信号（v8）：retained / lapsed → 更新六招画像。"""
    data = _read_body(body)
    if not isinstance(data, dict):
        raise ApiError("请求体要是对象")
    client_id = str(data.get("clientId") or "").strip()
    word = str(data.get("word") or "").strip()
    book_id = str(data.get("bookId") or "0").strip()
    method = profile.normalize_method(data.get("method"))
    event = str(data.get("event") or "").strip().lower()
    if not client_id:
        raise ApiError("缺 clientId")
    if not word:
        raise ApiError("缺 word")
    if not method:
        raise ApiError("method 只能是 sound/root/contrast/shape/polysemy/meaning")
    if event not in ("retained", "lapsed"):
        raise ApiError("event 只能是 retained/lapsed")
    applied, reason = _state["store"].record_memory_signal({
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
            _profile_state().update_memory_signal(
                method,
                event,
                event_id="memory:" + "|".join((client_id, word, book_id, method, event)),
            )
        except Exception as exc:  # noqa: BLE001
            log("更新记忆画像失败：%r" % (exc,))
        log("记忆信号 %s/%s：%s" % (word, method, event))
    out = {"ok": True, "applied": applied}
    if not applied and reason:
        out["reason"] = reason
    return out


PAGE_FILES = {"records": "rewrite.html", "panel": "panel.html"}


def _page(name):
    """自包含的静态页：/rewrite 是重写记录页，/rewrite/panel 是老调试面板。"""
    filename = PAGE_FILES.get(name, PAGE_FILES["panel"])
    try:
        with open(os.path.join(HERE, filename), "rb") as fh:
            return 200, "text/html; charset=utf-8", fh.read()
    except OSError:
        return 500, "text/plain; charset=utf-8", ("%s 不见了" % filename).encode("utf-8")


def _status():
    store = _state["store"]
    out = store.status()
    out["version"] = SERVICE_VERSION
    out["concurrency"] = CONCURRENCY
    out["startedAt"] = _state["startedAt"]
    out["judge"] = providers.label(providers.judge_target("ds-v41-flash"))
    out["wbGate"] = wb_gate.status()
    try:
        out["worker"] = worker_mod.status()
    except Exception as exc:  # noqa: BLE001 - 状态接口不能因为 worker 出问题就整包挂掉
        out["worker"] = {"enabled": False, "error": "%r" % (exc,)}
    return out


def _stop(body):
    """停止重写：给了 word/clientId 停那一条，什么都不给就停全部。

    运行中的任务不能真杀线程，但 generation+1 后晚到的结果会被 finish_* 丢掉。
    """
    data = _read_body(body)
    payload = data if isinstance(data, dict) else {}
    word = str(payload.get("word") or "").strip()
    client_id = str(payload.get("clientId") or "").strip()
    hit = _state["store"].stop(word=word, client_id=client_id)
    log("停止 %s：%d 条" % (client_id or word or "全部", hit))
    return {"stopped": hit, "word": word, "clientId": client_id}


def _gate(body):
    """手动切换 WB 阶段：foreground / batch / auto / paused。"""
    data = _read_body(body)
    payload = data if isinstance(data, dict) else {}
    value = str(payload.get("phase") or "").strip().lower()
    try:
        phase = wb_gate.set_phase(value)
    except ValueError as exc:
        raise ApiError(str(exc))
    log("WB 阶段切到 %s" % phase)
    return {"phase": phase, "gate": wb_gate.status()}


# --------------------------------------------------- 手改锁定 / 闲时洗词（1.5.3）
#
# 锁定是「用户手改过的词不许被自动流程覆盖」的唯一权威来源：
#   * 洗词 worker 取候选时就问 is_locked，锁了直接跳过；
#   * 结果流 results_page 也会把「锁定之后才回来的 worker 结果」挡住（竞态兜底）；
#   * 手工结果永远不过滤 —— 用户自己改的东西不能被他自己的锁吞掉。


def _lock_fields(raw):
    """单条锁定指令 → (wordId, bookId, locked, note, word, book)，字段不合规就报错。"""
    if not isinstance(raw, dict):
        raise ApiError("锁定指令不是对象")
    word_id = raw.get("wordId")
    if word_id is None or (isinstance(word_id, str) and not word_id.strip()):
        raise ApiError("缺 wordId")
    book_id = raw.get("bookId")
    if book_id is None or (isinstance(book_id, str) and not book_id.strip()):
        raise ApiError("缺 bookId")
    locked = raw.get("locked")
    locked = True if locked is None else bool(locked)
    note = str(raw.get("note") or "").strip()
    word = str(raw.get("word") or "").strip()
    book = str(raw.get("book") or "").strip()
    return word_id, book_id, locked, note, word, book


def _set_lock(body):
    """锁定/解锁：body 可以是单条 {wordId, bookId, locked?, note?, word?}，也可以 {items: [...]}。"""
    data = _read_body(body)
    if isinstance(data, list):
        payload = {"items": data}
    elif isinstance(data, dict):
        payload = data
    else:
        raise ApiError("请求体要是对象或数组")
    items = payload.get("items")
    rows = items if isinstance(items, list) else [payload]
    if not rows:
        raise ApiError("没有要锁定的词")
    if len(rows) > 200:
        raise ApiError("一次最多锁定 200 条")
    store = _state["store"]
    out = []
    for raw in rows:
        word_id, book_id, locked, note, word, book = _lock_fields(raw)
        out.append(store.set_lock(word_id, book_id, locked=locked, note=note, word=word, book=book))
    locked_count = sum(1 for row in out if row.get("locked"))
    log("锁定更新 %d 条（锁定 %d 条）" % (len(out), locked_count))
    return {
        "ok": True,
        "locks": out,
        "locked": bool(out) and all(row.get("locked") for row in out),
        "lockedCount": locked_count,
        "updatedAt": store.locks_updated_at(),
    }


def _locks(query):
    """读锁定表；book 可选，按书筛。"""
    book = (query.get("book") or [""])[0].strip()[:60]
    store = _state["store"]
    rows = store.lock_records(book)
    return {
        "ok": True,
        "locks": rows,
        "count": len(rows),
        "lockedCount": sum(1 for row in rows if row.get("locked")),
        "book": book,
        "updatedAt": store.locks_updated_at(),
    }


def _worker_status():
    store = _state["store"]
    try:
        worker_mod.attach(store, DATA_DIR)
    except Exception as exc:  # noqa: BLE001 - 只读接口，attach 失败也要能回包
        log("worker attach 失败：%r" % (exc,))
    out = {"ok": True, "version": SERVICE_VERSION}
    out.update(worker_mod.status())
    return out


def _worker_toggle(body):
    """运行时开关闲时洗词：默认关，只有显式 enabled=true 才会开始跑。"""
    data = _read_body(body)
    payload = data if isinstance(data, dict) else {}
    if "enabled" not in payload:
        raise ApiError("缺 enabled")
    enabled = bool(payload.get("enabled"))
    store = _state["store"]
    status = worker_mod.toggle(enabled, DATA_DIR, store=store)
    log("闲时洗词 worker：%s" % ("启用" if enabled else "关闭"))
    return {"ok": True, "enabled": bool(status.get("enabled")), "status": status}


def _dispatch(method, raw_path, body):
    path, query = _parse(raw_path)
    if method == "GET" and path in ("/models",):
        return _json({"models": providers.describe(), "version": SERVICE_VERSION})
    if not path.startswith("/rewrite"):
        return None
    if method == "GET" and path in ("/rewrite", "/rewrite/", "/rewrite/index.html"):
        return _page("records")
    if method == "GET" and path in ("/rewrite/panel", "/rewrite/panel.html"):
        return _page("panel")
    if method == "GET" and path == "/rewrite/status":
        return _json(_status())
    if method == "GET" and path == "/rewrite/records":
        limit = _int(query, "limit", 50, 1, 200)
        offset = _int(query, "offset", 0, 0, 10 ** 6)
        book = (query.get("book") or [""])[0].strip()[:60]
        return _json(_state["store"].records_page(limit, offset, book))
    if method == "GET" and path == "/rewrite/results":
        since = _int(query, "since", 0, 0, 10 ** 9)
        limit = _int(query, "limit", 50, 1, 200)
        return _json(_state["store"].results_page(since, limit))
    if method == "GET" and path == "/rewrite/word":
        word = (query.get("word") or [""])[0].strip()
        if not word:
            raise ApiError("缺 word")
        return _json({"word": word, "entries": _state["store"].word_history(word)})
    if method == "GET" and path == "/rewrite/locks":
        return _json(_locks(query))
    if method == "GET" and path == "/rewrite/worker/status":
        return _json(_worker_status())
    if method == "POST" and path == "/rewrite/marks":
        return _json(_marks(body))
    if method == "POST" and path == "/rewrite/lock":
        return _json(_set_lock(body))
    if method == "POST" and path == "/rewrite/worker/toggle":
        return _json(_worker_toggle(body))
    if method == "POST" and path == "/rewrite/feedback":
        return _json(_feedback(body))
    if method == "POST" and path == "/rewrite/memory-signal":
        return _json(_memory_signal(body))
    if method == "POST" and path == "/rewrite/stop":
        return _json(_stop(body))
    if method == "POST" and path == "/rewrite/gate":
        return _json(_gate(body))
    if method == "POST" and path == "/rewrite/retry":
        data = _read_body(body)
        payload = data if isinstance(data, dict) else {}
        word = str(payload.get("word") or "").strip()
        client_id = str(payload.get("clientId") or "").strip()
        if not word and not client_id:
            raise ApiError("缺 word")
        ok = _state["store"].retry(word, client_id)
        log("重试 %s：%s" % (client_id or word, "已重新入队" if ok else "没有失败的记录"))
        return _json({"word": word, "clientId": client_id, "queued": ok})
    return None


def respond(method, raw_path, body=b""):
    """给宿主服务器的入口。返回 (status, content_type, bytes)；None 表示不是我的路径。"""
    try:
        startup()
        return _dispatch(method, raw_path, body)
    except ApiError as exc:
        return _json({"error": str(exc)}, exc.status)
    except Exception as exc:  # noqa: BLE001 - 接口层必须回包，不能把异常冒给宿主
        log("路由异常 %s %s：%r" % (method, raw_path, exc))
        return _json({"error": "服务内部错误：%s" % exc}, 500)


# ------------------------------------------------------------------ 独立调试


def _serve(port):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _run(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            result = respond(self.command, self.path, body)
            if result is None:
                result = _json({"error": "not found"}, 404)
            status, ctype, payload = result
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

        do_GET = _run
        do_POST = _run

        def log_message(self, fmt, *args):
            pass

    startup()
    print("重写服务独立模式：http://127.0.0.1:%d/rewrite/" % port)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    argv = sys.argv[1:]
    port_arg = 8799
    if "--port" in argv:
        port_arg = int(argv[argv.index("--port") + 1])
    try:
        _serve(port_arg)
    except KeyboardInterrupt:
        shutdown()

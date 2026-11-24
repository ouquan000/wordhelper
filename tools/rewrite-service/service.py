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
import service_execution  # noqa: E402
import service_handlers  # noqa: E402

_api = sys.modules[__name__]

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
    return service_execution._work(_api, item)


def _finish_reuse(item, reused, prompt_hash, instruction_hash):
    return service_execution._finish_reuse(_api, item, reused, prompt_hash, instruction_hash)


def _claim_batch(limit, claimed=None):
    """先等一条，再尽量多抓几条组成小批；抓不到就按实际条数跑。

    claimed 由调用方传入时把每条认领立即记进去：中途 claim 抛异常，_worker
    也能看到本轮已经拿到的任务并逐个释放，不会留下僵尸 running。
    """
    return service_execution._claim_batch(_api, limit, claimed)


def _work_batch(items):
    """小批量通道：批量 generate + 批量 judge，坏一条只回退那一条。

    polish 仍是单条：它只改文字，批量收益小，且失败隔离必须精确到词。
    任何批量/单项异常都交给 _work(item) 走原有单词精修通道，不拖累同批。
    """
    return service_execution._work_batch(_api, items)


def _worker(index):
    return service_execution._worker(_api, index)


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
    return service_handlers._marks(_api, body)


def _feedback(body):
    """卡背 👍/👎（1.4.6）：落盘，同时喂跨词画像与样例池。

    1.5.2 起每条反馈可带可选整数 count（1..50，缺省 1），表示「这条票代表几次」：
    卡背连点两下不是手抖就是真觉得有用。count 只放大画像里的这一次投票，
    不改变 clientId 幂等语义；旧客户端不带 count 时行为与 1.4.6 完全一致。
    """
    return service_handlers._feedback(_api, body)


def _memory_signal(body):
    """复习结果信号（v8）：retained / lapsed → 更新六招画像。"""
    return service_handlers._memory_signal(_api, body)


PAGE_FILES = {"records": "rewrite.html", "panel": "panel.html"}


def _page(name):
    """自包含的静态页：/rewrite 是重写记录页，/rewrite/panel 是老调试面板。"""
    return service_handlers._page(_api, name)


def _status():
    return service_handlers._status(_api)


def _stop(body):
    """停止重写：给了 word/clientId 停那一条，什么都不给就停全部。

    运行中的任务不能真杀线程，但 generation+1 后晚到的结果会被 finish_* 丢掉。
    """
    return service_handlers._stop(_api, body)


def _gate(body):
    """手动切换 WB 阶段：foreground / batch / auto / paused。"""
    return service_handlers._gate(_api, body)


# --------------------------------------------------- 手改锁定 / 闲时洗词（1.5.3）
#
# 锁定是「用户手改过的词不许被自动流程覆盖」的唯一权威来源：
#   * 洗词 worker 取候选时就问 is_locked，锁了直接跳过；
#   * 结果流 results_page 也会把「锁定之后才回来的 worker 结果」挡住（竞态兜底）；
#   * 手工结果永远不过滤 —— 用户自己改的东西不能被他自己的锁吞掉。


def _lock_fields(raw):
    """单条锁定指令 → (wordId, bookId, locked, note, word, book)，字段不合规就报错。"""
    return service_handlers._lock_fields(_api, raw)


def _set_lock(body):
    """锁定/解锁：body 可以是单条 {wordId, bookId, locked?, note?, word?}，也可以 {items: [...]}。"""
    return service_handlers._set_lock(_api, body)


def _locks(query):
    """读锁定表；book 可选，按书筛。"""
    return service_handlers._locks(_api, query)


def _worker_status():
    return service_handlers._worker_status(_api)


def _worker_toggle(body):
    """运行时开关闲时洗词：默认关，只有显式 enabled=true 才会开始跑。"""
    return service_handlers._worker_toggle(_api, body)


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

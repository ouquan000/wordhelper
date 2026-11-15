#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""项目内 WB 网关串行槽 + 共享冷却（1.4.7.2 G01）。

为什么需要它：providers.py 原来只给 portdan-grok 加了锁，wb 没有统一入口；
service.py 起两个 worker 时，生成、润色、评委和补判可以同时打 WB，429/503
再被 providers.complete 的 4 次重试放大，额度会被短循环烧掉。

设计边界：
- 只管本项目的进程（rewrite-service 及其测试实例）。DSH 自己的模型调用不经过
  这里，无法从服务端强控；用 wb_phase 分阶段让路，并在报告里写清楚。
- 锁是文件锁（Windows msvcrt / POSIX flock），同一 gate 路径的多个进程共享。
- 冷却状态也写在 gate 文件里，进程崩溃/重启后仍保留，到期自动恢复。
"""

from __future__ import annotations

import json
import os
import time
from contextlib import contextmanager

HERE = os.path.dirname(os.path.abspath(__file__))

# gate 文件默认放在本项目 data 下；测试用 REWRITE_WB_GATE 指到临时文件。
DEFAULT_GATE = os.path.join(
    os.environ.get("REWRITE_DATA_DIR") or os.path.join(HERE, "data"),
    "wb_gate.json",
)

# 进程被强杀时 OS 文件锁会释放，但 gate 文件里的 active 标记不会；
# 超过这个时间的 active 视为上次崩溃留下的僵尸槽，允许回收。
STALE_ACTIVE_SECONDS = 1800.0


def _now() -> float:
    return time.time()


def _as_float(value, default=0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


class GateBusy(Exception):
    """拿不到槽位/还在冷却；调用方应把本条排到以后，而不是紧循环重试。"""


class _FileLock:
    """跨进程互斥；拿不到就等，超时抛 GateBusy。"""

    def __init__(self, path: str):
        self.path = path
        self._fh = None

    def __enter__(self):
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        deadline = _now() + 60.0
        while True:
            try:
                # open() 本身也可能被另一个进程的字节锁挡住（Windows 共享模式），
                # 所以整个 open + 初始化 + 加锁都必须放在重试里。
                self._fh = open(self.path, "a+b")
                self._fh.seek(0, os.SEEK_END)
                if self._fh.tell() == 0:
                    self._fh.write(b"0")
                    self._fh.flush()
                self._fh.seek(0)
                if os.name == "nt":
                    import msvcrt

                    msvcrt.locking(self._fh.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(self._fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                return self
            except (OSError, IOError):
                if self._fh is not None:
                    try:
                        self._fh.close()
                    except (OSError, IOError):
                        pass
                    self._fh = None
                if _now() >= deadline:
                    raise GateBusy("WB 串行槽等待超时（60s）")
                time.sleep(0.05)

    def __exit__(self, exc_type, exc, tb):
        if self._fh is None:
            return False
        try:
            self._fh.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(self._fh.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(self._fh.fileno(), fcntl.LOCK_UN)
        except (OSError, IOError):
            pass
        finally:
            self._fh.close()
            self._fh = None
        return False


def _gate_path() -> str:
    return os.environ.get("REWRITE_WB_GATE") or DEFAULT_GATE


def _lock_path() -> str:
    return _gate_path() + ".lock"


def _state_lock_path() -> str:
    # 状态写入用单独的锁：调用方持有串行槽时，note_http 不能再去抢同一把锁。
    return _gate_path() + ".state.lock"


def _phase_path() -> str:
    return os.environ.get("REWRITE_WB_PHASE_FILE") or (_gate_path() + ".phase")


def _read_json(path: str, default: dict) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else default
    except (OSError, ValueError):
        return default


def _write_json(path: str, data: dict) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, sort_keys=True)
    os.replace(tmp, path)


def _default_state() -> dict:
    return {
        "active": False,
        "holder": "",
        "acquiredAt": 0.0,
        "cooldownUntil": 0.0,
        "cooldownReason": "",
        "lastStatus": 0,
        "consecutive5xx": 0,
        "updatedAt": 0.0,
    }


def phase() -> str:
    """auto / foreground / batch / paused。文件优先，其次环境变量。"""
    try:
        with open(_phase_path(), "r", encoding="utf-8") as fh:
            value = fh.read().strip().lower()
    except OSError:
        value = (os.environ.get("REWRITE_WB_PHASE") or "auto").strip().lower()
    return value if value in ("auto", "foreground", "batch", "paused") else "auto"


def set_phase(value: str) -> str:
    value = (value or "").strip().lower()
    if value not in ("auto", "foreground", "batch", "paused"):
        raise ValueError("phase 必须是 auto/foreground/batch/paused")
    path = _phase_path()
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(value)
    os.replace(tmp, path)
    return value


def _load() -> dict:
    state = _default_state()
    state.update(_read_json(_gate_path(), {}))
    return state


def status() -> dict:
    state = _load()
    remaining = max(0.0, _as_float(state.get("cooldownUntil")) - _now())
    state["cooldownRemaining"] = round(remaining, 1)
    state["phase"] = phase()
    return state


def cooldown_remaining() -> float:
    return max(0.0, _as_float(_load().get("cooldownUntil")) - _now())


def _priority_ok(priority: int, current_phase: str) -> bool:
    if current_phase == "paused":
        return False
    if current_phase == "foreground":
        return priority >= 100
    if current_phase == "batch":
        return priority < 100
    return True


@contextmanager
def acquire(priority: int = 100, timeout: float = 120.0):
    """拿 WB 槽位；冷却中先等，等不到就 GateBusy（由上层排到以后）。"""
    priority = int(priority)
    deadline = _now() + max(0.1, float(timeout))
    while True:
        with _FileLock(_lock_path()):
            state = _load()
            current_phase = phase()
            if not _priority_ok(priority, current_phase):
                raise GateBusy("WB 当前阶段 %s 不接受优先级 %d 的任务" % (current_phase, priority))
            remaining = _as_float(state.get("cooldownUntil")) - _now()
            if remaining > 0:
                if remaining > (deadline - _now()):
                    raise GateBusy("WB 冷却中，剩 %.0fs：%s" % (remaining, state.get("cooldownReason") or "未知"))
                time.sleep(min(remaining, 1.0))
                continue
            active_age = _now() - _as_float(state.get("acquiredAt"))
            if state.get("active") and active_age > STALE_ACTIVE_SECONDS:
                # 上次进程被强杀，文件锁已释放但 active 标记还在；回收僵尸槽。
                state["active"] = False
                state["holder"] = ""
                state["acquiredAt"] = 0.0
                state["updatedAt"] = _now()
                _write_json(_gate_path(), state)
            if state.get("active"):
                # 另一个进程刚拿到槽位；释放文件锁后等它。
                pass
            else:
                state["active"] = True
                state["holder"] = "pid=%d,pri=%d" % (os.getpid(), priority)
                state["acquiredAt"] = _now()
                state["updatedAt"] = _now()
                _write_json(_gate_path(), state)
                try:
                    yield state
                finally:
                    latest = _load()
                    latest["active"] = False
                    latest["holder"] = ""
                    latest["acquiredAt"] = 0.0
                    latest["updatedAt"] = _now()
                    _write_json(_gate_path(), latest)
                return
        if _now() >= deadline:
            raise GateBusy("WB 串行槽等待超时")
        time.sleep(0.05)


def note_http(status_code: int, retry_after=None, body: str = "") -> None:
    """在持有槽位时记录一次上游 HTTP 结果，维护共享冷却。"""
    code = int(status_code or 0)
    with _FileLock(_state_lock_path()):
        state = _load()
        state["lastStatus"] = code
        state["updatedAt"] = _now()
        if 200 <= code < 300:
            state["consecutive5xx"] = 0
            state["cooldownUntil"] = 0.0
            state["cooldownReason"] = ""
        elif code == 429:
            seconds = _retry_after_seconds(retry_after)
            state["cooldownUntil"] = _now() + min(1800.0, max(5.0, seconds or 60.0))
            state["cooldownReason"] = "HTTP 429 限流"
        elif code == 503:
            text = (body or "").lower()
            quota = any(k in text for k in ("quota", "额度", "balance", "insufficient", "model_busy"))
            seconds = _retry_after_seconds(retry_after)
            state["cooldownUntil"] = _now() + min(
                1800.0, max(10.0, seconds or (60.0 if quota else 30.0))
            )
            state["cooldownReason"] = "HTTP 503 " + ("额度/繁忙" if quota else "上游不可用")
        elif code >= 500:
            state["consecutive5xx"] = int(state.get("consecutive5xx") or 0) + 1
            seconds = min(1800.0, 30.0 * (2 ** (state["consecutive5xx"] - 1)))
            state["cooldownUntil"] = _now() + seconds
            state["cooldownReason"] = "HTTP %d 连续第 %d 次" % (code, state["consecutive5xx"])
        _write_json(_gate_path(), state)


def _retry_after_seconds(value) -> float:
    try:
        return max(0.0, float(value))
    except (TypeError, ValueError):
        return 0.0


def note_success() -> None:
    note_http(200)


def reset() -> None:
    """测试用：清空 gate 状态，不删除 phase 文件。"""
    with _FileLock(_state_lock_path()):
        _write_json(_gate_path(), _default_state())

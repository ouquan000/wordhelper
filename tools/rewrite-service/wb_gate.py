#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""项目内 WB 网关 N 槽池 + 共享冷却降档。

为什么需要它：providers.py 原来只给 portdan-grok 加了锁，wb 没有统一入口；
service.py 起两个 worker 时，生成、润色、评委和补判可以同时打 WB，429/503
再被 providers.complete 的 4 次重试放大，额度会被短循环烧掉。

设计边界：
- 只管本项目的进程（rewrite-service 及其测试实例）。DSH 自己的模型调用不经过
  这里，无法从服务端强控；用 wb_phase 分阶段让路，并在报告里写清楚。
- 槽位数由 WB_GATE_SLOTS 控制（默认 5）。进程内用 BoundedSemaphore 排队，
  同一 gate 路径 + 同一 N 共享一个信号量；跨进程用每个槽独立的文件锁
  （<gate_path>.slot_0.lock … <gate_path>.slot_{N-1}.lock）互斥。
- 共享状态仍写 <gate_path>（默认 data/wb_gate.json），用 .state.lock 保护；
  旧 <gate_path>.lock 不再作为主锁，也不主动删除（兼容旧版本残留）。
- 冷却状态也写在 gate 文件里，进程崩溃/重启后仍保留，到期自动恢复。
- 429/503 把 effectiveLimit 临时降到 2（不低于 2、不超过 N）；连续成功
  WB_GATE_RECOVERY_SUCCESSES 次（默认 5）后每次 +1 恢复到 N。
"""

from __future__ import annotations

import json
import os
import threading
import time
from contextlib import contextmanager

HERE = os.path.dirname(os.path.abspath(__file__))

# gate 文件默认放在本项目 data 下；测试用 REWRITE_WB_GATE 指到临时文件。
DEFAULT_GATE = os.path.join(
    os.environ.get("REWRITE_DATA_DIR") or os.path.join(HERE, "data"),
    "wb_gate.json",
)

# 进程被强杀时 OS 文件锁会释放，但 gate 文件里的 holder 标记不会；
# 超过这个时间的持有记录视为上次崩溃留下的僵尸槽，允许回收。
STALE_ACTIVE_SECONDS = 1800.0

DEFAULT_SLOTS = 5
DEFAULT_WAIT_TIMEOUT = 300.0
DEFAULT_RECOVERY_SUCCESSES = 5
# 限流降档的下限；N < 2 时以 N 为准。
MIN_EFFECTIVE_LIMIT = 2

# 进程内信号量：key = (规范化的 gate 路径, 槽位数)。
# 同一路径/同一 N 共享；不同临时 gate 路径互不串扰。
_SEMAPHORES: dict = {}
_SEMAPHORES_LOCK = threading.Lock()


def _now() -> float:
    return time.time()


def _as_float(value, default=0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _as_int(value, default=0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _env_int(name: str, default: int, minimum: int = 1) -> int:
    value = _as_int(os.environ.get(name), default)
    return max(minimum, value)


def _env_float(name: str, default: float, minimum: float = 0.1) -> float:
    value = _as_float(os.environ.get(name), default)
    return max(minimum, value)


class GateBusy(Exception):
    """拿不到槽位/还在冷却；调用方应把本条排到以后，而不是紧循环重试。"""


class _FileLock:
    """跨进程互斥；拿不到就等，超时抛 GateBusy。

    timeout=0 表示只尝试一次（用于扫描空闲槽位）。
    """

    def __init__(self, path: str, timeout: float = 60.0):
        self.path = path
        self.timeout = timeout
        self._fh = None

    def try_acquire(self) -> bool:
        """非阻塞抢锁；成功返回 True 并持有，失败返回 False。"""
        if self._fh is not None:
            return True
        fh = None
        try:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
            # open() 本身也可能被另一个进程的字节锁挡住（Windows 共享模式），
            # 所以整个 open + 初始化 + 加锁都必须放在 try 里。
            fh = open(self.path, "a+b")
            fh.seek(0, os.SEEK_END)
            if fh.tell() == 0:
                fh.write(b"0")
                fh.flush()
            fh.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._fh = fh
            return True
        except (OSError, IOError):
            if fh is not None:
                try:
                    fh.close()
                except (OSError, IOError):
                    pass
            self._fh = None
            return False

    def __enter__(self):
        deadline = _now() + max(0.0, float(self.timeout))
        while True:
            if self.try_acquire():
                return self
            if _now() >= deadline:
                raise GateBusy("WB 锁等待超时（%.0fs）：%s" % (self.timeout, os.path.basename(self.path)))
            time.sleep(0.02)

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


def _state_lock_path(gate_path: str = "") -> str:
    # 状态写入用单独的锁：调用方持有槽位时，note_http 不能再去抢同一把锁。
    return (gate_path or _gate_path()) + ".state.lock"


def _slot_lock_path(gate_path: str, index: int) -> str:
    return "%s.slot_%d.lock" % (gate_path, index)


def slot_count() -> int:
    """当前配置的槽位数（WB_GATE_SLOTS，默认 5）。"""
    return _env_int("WB_GATE_SLOTS", DEFAULT_SLOTS)


def wait_timeout() -> float:
    """acquire 未显式传 timeout 时的默认等槽超时（WB_GATE_WAIT_TIMEOUT，默认 300s）。"""
    return _env_float("WB_GATE_WAIT_TIMEOUT", DEFAULT_WAIT_TIMEOUT)


def _recovery_successes() -> int:
    return _env_int("WB_GATE_RECOVERY_SUCCESSES", DEFAULT_RECOVERY_SUCCESSES)


def _clamp_limit(value, slots: int) -> int:
    """effectiveLimit 归一化：默认 N；N>=2 时不低于 2、不高于 N。"""
    slots = max(1, int(slots))
    if value in (None, "", "0", 0):
        return slots
    limit = _as_int(value, slots)
    floor = min(MIN_EFFECTIVE_LIMIT, slots)
    return max(floor, min(slots, limit))


def _semaphore_for(gate_path: str, slots: int) -> threading.BoundedSemaphore:
    key = (os.path.normcase(os.path.abspath(gate_path)), slots)
    with _SEMAPHORES_LOCK:
        sem = _SEMAPHORES.get(key)
        if sem is None:
            sem = threading.BoundedSemaphore(slots)
            _SEMAPHORES[key] = sem
        return sem


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
        "activeCount": 0,
        "holder": "",
        "holders": {},
        "acquiredAt": 0.0,
        "cooldownUntil": 0.0,
        "cooldownReason": "",
        "lastStatus": 0,
        "consecutive5xx": 0,
        "effectiveLimit": 0,
        "successStreak": 0,
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


def _load(gate_path: str = "") -> dict:
    path = gate_path or _gate_path()
    state = _default_state()
    state.update(_read_json(path, {}))
    if not isinstance(state.get("holders"), dict):
        state["holders"] = {}
    state["effectiveLimit"] = _clamp_limit(state.get("effectiveLimit"), slot_count())
    return state


def _normalize_active(state: dict) -> None:
    """按 holders 重算 active/activeCount/holder/acquiredAt（单槽字段保持兼容）。"""
    holders = state.get("holders")
    if not isinstance(holders, dict):
        holders = {}
        state["holders"] = holders
    state["activeCount"] = len(holders)
    state["active"] = bool(holders)
    if not holders:
        state["holder"] = ""
        state["acquiredAt"] = 0.0
        return
    ordered = sorted(holders.items(), key=lambda item: _as_int(item[0], 0))
    labels = []
    acquired = []
    for index, entry in ordered:
        entry = entry if isinstance(entry, dict) else {}
        labels.append("slot%s:%s" % (index, entry.get("holder") or ""))
        acquired.append(_as_float(entry.get("acquiredAt")))
    state["holder"] = "; ".join(labels)
    state["acquiredAt"] = min(acquired) if acquired else 0.0


def _save_state(state: dict, gate_path: str = "") -> None:
    state["updatedAt"] = _now()
    _write_json(gate_path or _gate_path(), state)


def status() -> dict:
    path = _gate_path()
    state = _load(path)
    holders = state.get("holders") or {}
    if holders:
        _normalize_active(state)
    else:
        # 兼容旧版单槽状态文件（只有 active/holder，没有 holders）。
        state["activeCount"] = _as_int(
            state.get("activeCount"), 1 if state.get("active") else 0
        )
        state["active"] = bool(state.get("active")) or state["activeCount"] > 0
    state["cooldownRemaining"] = round(_cooldown_remaining(state), 1)
    state["phase"] = phase()
    state["slots"] = slot_count()
    state["waitTimeout"] = wait_timeout()
    return state


def cooldown_remaining() -> float:
    return _cooldown_remaining(_load(_gate_path()))


def _cooldown_remaining(state: dict) -> float:
    return max(0.0, _as_float(state.get("cooldownUntil")) - _now())


def _priority_ok(priority: int, current_phase: str) -> bool:
    if current_phase == "paused":
        return False
    if current_phase == "foreground":
        return priority >= 100
    if current_phase == "batch":
        return priority < 100
    return True


def _wait_cooldown(deadline: float, remaining: float, state: dict, wait: float) -> None:
    if remaining > (deadline - _now()):
        raise GateBusy(
            "WB 冷却中，剩 %.0fs：%s"
            % (remaining, state.get("cooldownReason") or "未知")
        )
    time.sleep(min(remaining, 0.5))


def _prune_holders(state: dict, gate_path: str, taken_index: int) -> None:
    """清理僵尸持有记录。

    taken_index 的锁已经被当前线程拿到，原记录必然是上次崩溃残留，直接删。
    其他槽位只在超过 STALE_ACTIVE_SECONDS 且锁确实空闲时删除，避免误删活着的
    长任务（锁还被别的进程拿着时 try_acquire 会失败）。
    """
    holders = state.get("holders")
    if not isinstance(holders, dict):
        holders = {}
    for key in list(holders):
        index = _as_int(key, -1)
        if index < 0:
            del holders[key]
            continue
        if index == taken_index:
            del holders[key]
            continue
        entry = holders.get(key)
        entry = entry if isinstance(entry, dict) else {}
        age = _now() - _as_float(entry.get("acquiredAt"))
        if age <= STALE_ACTIVE_SECONDS:
            continue
        probe = _FileLock(_slot_lock_path(gate_path, index), timeout=0.0)
        if probe.try_acquire():
            probe.__exit__(None, None, None)
            del holders[key]
    # 旧版单槽状态：没有 holders，但有很久以前的 active 标记。
    if not holders and state.get("active"):
        age = _now() - _as_float(state.get("acquiredAt"))
        if age > STALE_ACTIVE_SECONDS:
            state["active"] = False
            state["holder"] = ""
            state["acquiredAt"] = 0.0
    state["holders"] = holders


@contextmanager
def acquire(priority: int = 100, timeout: float = None):
    """拿一个 WB 槽位；冷却中先等，等不到就 GateBusy（由上层排到以后）。

    timeout 显式给出时优先；否则用 WB_GATE_WAIT_TIMEOUT（默认 300s）。
    进程内先过 BoundedSemaphore(N) 排队，再抢一个独立的槽文件锁。
    """
    priority = int(priority)
    wait = wait_timeout() if timeout is None else float(timeout)
    deadline = _now() + max(0.1, wait)
    gate_path = _gate_path()
    slots = slot_count()
    sem = _semaphore_for(gate_path, slots)

    current_phase = phase()
    if not _priority_ok(priority, current_phase):
        raise GateBusy(
            "WB 当前阶段 %s 不接受优先级 %d 的任务" % (current_phase, priority)
        )

    sem_held = False
    slot_lock = None
    slot_index = -1
    snapshot = None
    try:
        # 1) 先等共享冷却结束（不占信号量/槽位）。
        while True:
            state = _load(gate_path)
            remaining = _cooldown_remaining(state)
            if remaining <= 0:
                break
            _wait_cooldown(deadline, remaining, state, wait)

        # 2) 进程内排队；这里用完整 wait 预算，不会在 60 秒就失败。
        budget = deadline - _now()
        if budget <= 0:
            raise GateBusy("WB 槽位排队超时（%.1fs）" % wait)
        if not sem.acquire(timeout=budget):
            raise GateBusy("WB 槽位排队超时（%.1fs）" % wait)
        sem_held = True

        # 3) 找一个空闲槽位（只在前 effectiveLimit 个槽里找）。
        while True:
            current_phase = phase()
            if not _priority_ok(priority, current_phase):
                raise GateBusy(
                    "WB 当前阶段 %s 不接受优先级 %d 的任务"
                    % (current_phase, priority)
                )
            state = _load(gate_path)
            remaining = _cooldown_remaining(state)
            if remaining > 0:
                _wait_cooldown(deadline, remaining, state, wait)
                continue

            limit = _clamp_limit(state.get("effectiveLimit"), slots)
            taken = None
            for index in range(limit):
                candidate = _FileLock(_slot_lock_path(gate_path, index), timeout=0.0)
                if not candidate.try_acquire():
                    continue
                try:
                    with _FileLock(
                        _state_lock_path(gate_path),
                        timeout=max(1.0, deadline - _now()),
                    ):
                        state = _load(gate_path)
                        limit_now = _clamp_limit(state.get("effectiveLimit"), slots)
                        remaining = _cooldown_remaining(state)
                        if remaining > 0 or index >= limit_now:
                            # 拿锁期间冷却开始 / 限流降档了；释放后重新判断。
                            candidate.__exit__(None, None, None)
                            candidate = None
                        else:
                            _prune_holders(state, gate_path, index)
                            state["holders"][str(index)] = {
                                "holder": "pid=%d,pri=%d" % (os.getpid(), priority),
                                "acquiredAt": _now(),
                            }
                            _normalize_active(state)
                            _save_state(state, gate_path)
                            taken = (index, candidate, dict(state))
                            candidate = None  # 所有权移交给外层
                finally:
                    if candidate is not None:
                        candidate.__exit__(None, None, None)
                if taken is not None:
                    break
                # 状态在拿锁后变化，回到外层重新读状态。
                break

            if taken is not None:
                slot_index, slot_lock, snapshot = taken
                break
            if _now() >= deadline:
                raise GateBusy("WB 槽位等待超时（%.1fs）" % wait)
            time.sleep(0.05)

        try:
            yield snapshot
        finally:
            if slot_lock is not None:
                try:
                    with _FileLock(_state_lock_path(gate_path), timeout=5.0):
                        latest = _load(gate_path)
                        holders = latest.get("holders")
                        if isinstance(holders, dict):
                            holders.pop(str(slot_index), None)
                        _normalize_active(latest)
                        _save_state(latest, gate_path)
                finally:
                    slot_lock.__exit__(None, None, None)
                    slot_lock = None
    finally:
        if slot_lock is not None:
            slot_lock.__exit__(None, None, None)
        if sem_held:
            sem.release()


def note_http(status_code: int, retry_after=None, body: str = "") -> None:
    """记录一次上游 HTTP 结果，维护共享冷却与限流降档。"""
    code = int(status_code or 0)
    gate_path = _gate_path()
    slots = slot_count()
    with _FileLock(_state_lock_path(gate_path)):
        state = _load(gate_path)
        state["lastStatus"] = code
        if 200 <= code < 300:
            state["consecutive5xx"] = 0
            state["cooldownUntil"] = 0.0
            state["cooldownReason"] = ""
            state["successStreak"] = _as_int(state.get("successStreak")) + 1
            if state["successStreak"] >= _recovery_successes():
                state["successStreak"] = 0
                state["effectiveLimit"] = min(
                    slots, _clamp_limit(state.get("effectiveLimit"), slots) + 1
                )
        elif code == 429:
            state["successStreak"] = 0
            seconds = _retry_after_seconds(retry_after)
            state["cooldownUntil"] = _now() + min(1800.0, max(5.0, seconds or 60.0))
            state["cooldownReason"] = "HTTP 429 限流"
            state["effectiveLimit"] = min(slots, MIN_EFFECTIVE_LIMIT)
        elif code == 503:
            state["successStreak"] = 0
            text = (body or "").lower()
            quota = any(k in text for k in ("quota", "额度", "balance", "insufficient", "model_busy"))
            seconds = _retry_after_seconds(retry_after)
            state["cooldownUntil"] = _now() + min(
                1800.0, max(10.0, seconds or (60.0 if quota else 30.0))
            )
            state["cooldownReason"] = "HTTP 503 " + ("额度/繁忙" if quota else "上游不可用")
            state["effectiveLimit"] = min(slots, MIN_EFFECTIVE_LIMIT)
        elif code >= 500:
            state["successStreak"] = 0
            state["consecutive5xx"] = _as_int(state.get("consecutive5xx")) + 1
            seconds = min(1800.0, 30.0 * (2 ** (state["consecutive5xx"] - 1)))
            state["cooldownUntil"] = _now() + seconds
            state["cooldownReason"] = "HTTP %d 连续第 %d 次" % (code, state["consecutive5xx"])
        _save_state(state, gate_path)


def _retry_after_seconds(value) -> float:
    try:
        return max(0.0, float(value))
    except (TypeError, ValueError):
        return 0.0


def note_success() -> None:
    note_http(200)


def reset() -> None:
    """测试用：清空 gate 状态，不删除 phase 文件。"""
    gate_path = _gate_path()
    state = _default_state()
    state["effectiveLimit"] = slot_count()
    with _FileLock(_state_lock_path(gate_path)):
        _write_json(gate_path, state)

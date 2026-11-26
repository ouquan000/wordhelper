#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""测试隔离：临时数据目录统一进 %TEMP%，并堵死真实 8788 网络。

所有 import service 的测试模块都应在导入后立即调用：
    from _isolation import guard_network, isolate
    isolate(service)
    guard_network()
"""
from __future__ import annotations

import atexit
import os
import shutil
import socket
import tempfile
import uuid
from unittest.mock import patch

_ROOT = None
_GUARD = []


def _make_root():
    """在 %TEMP% 下建唯一测试根；权限受限时退回系统临时目录策略。"""
    base = os.environ.get("WAE_TEST_TEMP") or os.environ.get("TEMP") or tempfile.gettempdir()
    candidate = os.path.join(base, "wae-tests-" + uuid.uuid4().hex[:8])
    try:
        # 不用 mkdtemp：低完整性进程在 %TEMP% 会卡在重试循环，makedirs 会立即报错。
        os.makedirs(candidate)
        return candidate
    except OSError:
        # 低完整性进程可能写不了宿主 %TEMP%；临时目录仍需可清理。
        return tempfile.mkdtemp(prefix="wae-tests-")


def _root():
    global _ROOT
    if _ROOT is None:
        _ROOT = _make_root()
        tempfile.tempdir = _ROOT
        atexit.register(shutil.rmtree, _ROOT, True)
    return _ROOT


def isolate(service_module):
    """把 service 的 DATA_DIR / LOG_PATH 指向本次测试专属目录。"""
    root = _root()
    os.makedirs(os.path.join(root, "logs"), exist_ok=True)
    service_module.DATA_DIR = root
    service_module.LOG_PATH = os.path.join(root, "logs", "service.log")
    return root


def guard_network():
    """拦截 8788 真实网关；其余端口放行（wb_gate 测试用本地随机端口）。"""
    if _GUARD:
        return
    real_connect = socket.socket.connect

    def guarded(self, address):
        port = None
        if isinstance(address, tuple) and len(address) >= 2:
            port = address[1]
        if port == 8788:
            raise ConnectionRefusedError("测试环境禁止连接真实 WB 网关 8788")
        return real_connect(self, address)

    patcher = patch.object(socket.socket, "connect", guarded)
    patcher.start()
    _GUARD.append(patcher)
    atexit.register(patcher.stop)

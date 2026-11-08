#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""卡背清单本地服务（App 1.4.2）

- 手机：GET /cardback.json（拉配置）
- 编辑器网页：/api/state、/api/publish、/api/history、/api/rollback
- 只用 Python 3 标准库，零第三方依赖
- 所有落盘都在本文件同级的 published/ 下

用法：
    python server.py               # 起服务 + 自动打开浏览器
    python server.py --no-browser  # 自测用：不开浏览器
"""

from __future__ import annotations

import hmac
import html
import ipaddress
import json
import math
import mimetypes
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

# ---------------------------------------------------------------- 常量

HOST = "0.0.0.0"
DEFAULT_PORT = 8765
PORT = DEFAULT_PORT

# 打包成 exe 后（PyInstaller）：网页文件在解包出来的临时目录里，
# 而 published/ 要落在 exe 旁边，用户才找得到、才不会每次重装就丢。
# 直接用 python server.py 跑时，两个目录都是本文件所在目录。
FROZEN = bool(getattr(sys, "frozen", False))
BUNDLE_DIR = getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.dirname(os.path.abspath(sys.executable)) if FROZEN else BUNDLE_DIR

PUBLISHED_DIR = os.path.join(BASE_DIR, "published")
HISTORY_DIR = os.path.join(PUBLISHED_DIR, "history")
CONFIG_PATH = os.path.join(PUBLISHED_DIR, "cardback.json")
STATE_PATH = os.path.join(PUBLISHED_DIR, "state.json")
INDEX_PATH = os.path.join(BUNDLE_DIR, "index.html")

# ---- 学习进度同步（1.4.4）：手机把 tb_progress 的结果态当 op 发过来，别的设备按 rev 拉。
# 存储和读写规则都在 sync_store.py 里；这里只做 HTTP 壳。
SYNC_PATH = os.path.join(BASE_DIR, "sync.json")
_SYNC = None


def sync_db():
    """惰性打开，别在 import 阶段就写文件（测试和 exe 打包都省事）。"""
    global _SYNC
    if _SYNC is None:
        import sync_store

        _SYNC = sync_store.SyncStore(SYNC_PATH)
    return _SYNC

# ---- 口令（1.4.4）：一台电脑一个，首次启动生成后落盘，之后一直复用。
# 手机每个请求都带 X-Cardback-Token；本机 127.0.0.1 / ::1 免票，
# 所以电脑上开着编辑器点发布不受影响。
TOKEN_PATH = os.path.join(BASE_DIR, ".cardback_token")
TOKEN_HEADER = "X-Cardback-Token"
TOKEN_LENGTH = 16  # secrets.token_urlsafe(16) → 22 个字符
TOKEN = ""  # 启动时填；见 ensure_token()

# ---- 限流：只卡「会调模型 / 会改数据」的写请求，读接口不限（手机轮询不受影响）。
# (窗口秒数, 窗口内次数)；本机也计，否则额度漏了照样能刷穿。
RATE_BUDGETS = ((60, 20), (86400, 200))
RATE_LIMITED_PATHS = ("/api/publish", "/api/rollback")
_RATE_LOCK = threading.Lock()
_RATE_HITS = {}

# 契约 §2：1.4.2 手机端认识的 11 种块。
# REWRITE_BUTTON 是 1.4.3 预留，不在集合里 —— 发布时放行并给 warning。
ALLOWED_TYPES = (
    "TITLE",
    "PHONETIC",
    "DIVIDER",
    "MEANING",
    "MEMORY",
    "COLLOCATIONS",
    "EXAMPLE",
    "PHRASES",
    "SYNONYMS",
    "REL_WORDS",
    "EF_STATS",
)
COLOR_NAMES = ("default", "primary", "secondary", "accent", "muted", "warning")
SIZE_NAMES = ("S", "M", "L")
MOTION_FIELDS = (
    "titleDurationMs",
    "staggerGapMs",
    "entryDurationMs",
    "overshootPercent",
    "overshootPx",
    "overshootScale",
)

HISTORY_RE = re.compile(r"^cardback-(\d+)-(\d{8}-\d{6})(?:-(\d+))?\.json$")
MAX_BODY = 4 * 1024 * 1024  # 4MB，够放几千个块了

# 契约 §2「1.4.2 的默认块表」：11 个块，顺序照抄（EF_STATS 自带分隔线，别在它前面再放 DIVIDER）
DEFAULT_BLOCKS = (
    ("word", "TITLE", None),
    ("ipa", "PHONETIC", None),
    ("div1", "DIVIDER", None),
    ("mean", "MEANING", "释义"),
    ("memory", "MEMORY", "记忆思路"),
    ("collocations", "COLLOCATIONS", "常用搭配"),
    ("example", "EXAMPLE", "例句"),
    ("phrases", "PHRASES", "短语"),
    ("synonyms", "SYNONYMS", "近义词"),
    ("relwords", "REL_WORDS", "同根词"),
    ("ef", "EF_STATS", ""),
)

# 契约 §5 的动效默认值
DEFAULT_MOTION = {
    "titleDurationMs": 820,
    "staggerGapMs": 90,
    "entryDurationMs": 220,
    "overshootPercent": 72,
    "overshootPx": -1.0,
    "overshootScale": 1.0015,
}

_LAN_NETS = tuple(
    ipaddress.IPv4Network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)

# 启动时算好，/api/state 直接复用
LAN_URL = "http://127.0.0.1:%d/cardback.json" % PORT

_LOCK = threading.RLock()


# ---------------------------------------------------------------- 口令与限流


def ensure_token():
    """读已有口令；没有就生成一个落盘（和数据同目录，用户找得到）。"""
    global TOKEN
    if TOKEN:
        return TOKEN
    try:
        with open(TOKEN_PATH, "r", encoding="utf-8") as fh:
            existing = fh.read().strip()
    except FileNotFoundError:
        existing = ""
    except OSError as exc:
        print("  读口令文件失败（这次用临时口令）：%s" % exc, flush=True)
        existing = ""
    if existing:
        TOKEN = existing
        return TOKEN

    token = secrets.token_urlsafe(TOKEN_LENGTH)
    try:
        with open(TOKEN_PATH, "w", encoding="utf-8") as fh:
            fh.write(token + "\n")
        try:
            os.chmod(TOKEN_PATH, 0o600)
        except OSError:
            pass  # Windows 上 chmod 基本无效，失败无所谓
    except OSError as exc:
        print("  口令写不进 %s（这次只在内存里）：%s" % (TOKEN_PATH, exc), flush=True)
    TOKEN = token
    return TOKEN


def _token_ok(supplied):
    expected = ensure_token()
    if not supplied or not supplied.strip() or not expected:
        return False
    # 定时比较：别让响应时间泄露口令前缀
    return hmac.compare_digest(supplied.strip(), expected)


def pair_url():
    """手机要粘贴的那一整行：`http://ip:8765#<口令>`。

    `#` 后面那截客户端从不发送——不进请求行、Referer、代理日志；手机解析后只用
    host:port 和 X-Cardback-Token 头，所以鉴权逻辑（_token_ok / loopback 免票）一行不用改。
    这里不带文件路径，手机端 normalizeUrl 会补 /cardback.json。
    """
    base = LAN_URL
    suffix = "/cardback.json"
    if base.endswith(suffix):
        base = base[: -len(suffix)]
    return "%s#%s" % (base, ensure_token())


def _is_loopback_peer(peer):
    try:
        return ipaddress.ip_address(peer).is_loopback
    except ValueError:
        return peer in ("localhost", "localhost.")


def _origin_host_ok(host):
    host = (host or "").strip().lower().strip("[]")
    if not host:
        return False
    if host in ("localhost", "127.0.0.1", "::1"):
        return True
    try:
        addr = ipaddress.ip_address(host)
    except ValueError:
        return False
    # 本机/局域网里的页面才算「同源」；公网域名一律不认——那正是 CSRF 的来源。
    return addr.is_private or addr.is_loopback or addr.is_link_local


def _origin_ok(origin):
    text = (origin or "").strip().rstrip("/")
    if not text:
        return False
    if text.lower() == "null":
        return True  # file:// 打开的页面，浏览器给的就是 null
    try:
        parts = urlsplit(text)
    except ValueError:
        return False
    scheme = parts.scheme.lower()
    if scheme not in ("http", "https"):
        return False
    try:
        port = parts.port
    except ValueError:
        return False
    if port is None:
        port = 443 if scheme == "https" else 80
    if port != PORT:
        return False
    return _origin_host_ok(parts.hostname)


def _rate_limited(method, path):
    """要记一笔限流的请求：会改数据或触发模型的写请求（记录页的只读轮询不算）。"""
    return (method == "POST" and path.startswith("/rewrite")) or path in RATE_LIMITED_PATHS


def _prune_rate(now):
    for peer in list(_RATE_HITS):
        windows = _RATE_HITS[peer]["windows"]
        if all(
            now - (slot[0] + RATE_BUDGETS[i][0]) > RATE_BUDGETS[i][0]
            for i, slot in enumerate(windows)
        ):
            _RATE_HITS.pop(peer, None)


def rate_limit_take(peer, now=None):
    """按 IP 记一笔。返回 (ok, retry_after_seconds, 窗口名)。"""
    now = time.time() if now is None else now
    with _RATE_LOCK:
        if len(_RATE_HITS) > 256:
            _prune_rate(now)
        entry = _RATE_HITS.get(peer)
        if entry is None:
            entry = {"windows": [[0, 0] for _ in RATE_BUDGETS]}
            _RATE_HITS[peer] = entry
        for index, (window, limit) in enumerate(RATE_BUDGETS):
            slot = entry["windows"][index]
            start = int(now // window) * window
            if slot[0] != start:
                slot[0] = start
                slot[1] = 0
            if slot[1] >= limit:
                return False, max(1, int(start + window - now)), ("分钟" if window < 3600 else "天")
        for index, _budget in enumerate(RATE_BUDGETS):
            entry["windows"][index][1] += 1
        return True, 0, ""


class ApiError(Exception):
    """给接口用的、能直接回给编辑器的错误（默认 400）。"""

    def __init__(self, message, status=400):
        super().__init__(message)
        self.message = message
        self.status = status


# ---------------------------------------------------------------- 小工具


def _now():
    return datetime.now().astimezone()


def _iso(dt):
    return dt.isoformat(timespec="seconds")


def _is_int(value):
    # bool 是 int 的子类，得排掉，不然 true 会被当成 1
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _show(value):
    try:
        text = json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        text = repr(value)
    return text if len(text) <= 40 else text[:40] + "…"


def _load_json(path):
    """读 JSON；不存在/坏了都返回 None，不抛异常。"""
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def _atomic_write_json(path, data):
    """先写 .tmp 再 os.replace，避免手机读到写了一半的文件。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)


def _parse_stamp(stamp):
    try:
        return datetime.strptime(stamp, "%Y%m%d-%H%M%S").astimezone()
    except ValueError:
        return None


# ---------------------------------------------------------------- 默认清单


def default_config():
    blocks = []
    for block_id, block_type, title in DEFAULT_BLOCKS:
        blocks.append(
            {
                "id": block_id,
                "type": block_type,
                "visible": True,
                "title": title,
                "style": None,
            }
        )
    return {
        "schemaVersion": 1,
        "book": "六级记忆卡",
        "revision": 1,
        "updatedAt": _iso(_now()),
        "spacing": 12,
        "motion": dict(DEFAULT_MOTION),
        "blocks": blocks,
    }


# ---------------------------------------------------------------- 校验
# 规则编号对应交付说明里的 1..8；单条错误文案里带 block 序号（从 1 数）和 id，
# 方便编辑器直接显示给用户看。
# 未知 type 按交付说明的「以本条为准」处理：放行 + warning（不判失败）。


def validate_config(data):
    """返回 (errors, warnings)。errors 非空就是 400。"""
    errors = []
    warnings = []

    # 0. 顶层必须是个对象
    if not isinstance(data, dict):
        return ["配置必须是一个 JSON 对象（顶层是 { ... }）"], warnings

    # 2. schemaVersion 是 int 且 <= 1
    schema_version = data.get("schemaVersion")
    if not _is_int(schema_version):
        errors.append("schemaVersion 必须是整数（当前：%s）" % _show(schema_version))
    elif schema_version > 1:
        errors.append(
            "schemaVersion 只能是 1 或更小，手机端会整份拒收（当前：%d）" % schema_version
        )

    # 3. blocks 是数组且非空
    blocks = data.get("blocks")
    if not isinstance(blocks, list):
        errors.append("blocks 必须是数组")
        blocks = []
    elif not blocks:
        errors.append("blocks 不能是空数组（卡背不能空着，手机端会退回上一版）")

    # 4~6. 逐个 block
    seen_ids = {}
    for index, block in enumerate(blocks, start=1):
        label = "block #%d" % index
        if not isinstance(block, dict):
            errors.append("%s: 必须是一个对象" % label)
            continue

        block_id = block.get("id")
        if not isinstance(block_id, str) or not block_id.strip():
            errors.append("%s: id 必须是非空字符串" % label)
        else:
            label = "block #%d (id=%s)" % (index, block_id)
            if block_id in seen_ids:
                errors.append(
                    "%s: id 重复（和 block #%d 撞了，DIVIDER 这种也要唯一）"
                    % (label, seen_ids[block_id])
                )
            else:
                seen_ids[block_id] = index

        block_type = block.get("type")
        if not isinstance(block_type, str) or not block_type.strip():
            errors.append("%s: type 必须是非空字符串" % label)
        elif block_type not in ALLOWED_TYPES:
            # 手机端不认识就跳过并提示，所以这里放行
            warnings.append("%s: 未知类型 %s（手机端会跳过）" % (label, block_type))

        if "visible" in block and not isinstance(block["visible"], bool):
            errors.append("%s: visible 必须是 true 或 false" % label)

        if "title" in block:
            title = block["title"]
            if title is not None and not isinstance(title, str):
                errors.append(
                    '%s: title 必须是字符串或 null（空串 "" 表示不显示标题）' % label
                )

        style = block.get("style")
        if style is not None:
            if not isinstance(style, dict):
                errors.append("%s: style 必须是对象或 null" % label)
            else:
                if "color" in style and style["color"] is not None:
                    if style["color"] not in COLOR_NAMES:
                        errors.append(
                            "%s: style.color 只能是 %s"
                            % (label, "/".join(COLOR_NAMES))
                        )
                if "size" in style and style["size"] is not None:
                    if style["size"] not in SIZE_NAMES:
                        errors.append("%s: style.size 只能是 S/M/L" % label)

    # 7. spacing：4..32 的整数
    spacing = data.get("spacing")
    if spacing is not None:
        if not _is_int(spacing):
            errors.append("spacing 必须是整数（当前：%s）" % _show(spacing))
        elif not 4 <= spacing <= 32:
            errors.append("spacing 必须在 4..32 之间（当前：%d）" % spacing)

    # 8. motion 各字段是数字
    motion = data.get("motion")
    if motion is not None:
        if not isinstance(motion, dict):
            errors.append("motion 必须是对象")
        else:
            for key, value in motion.items():
                if key not in MOTION_FIELDS:
                    continue  # 不认识的动效字段忽略，方便以后加
                if not _is_number(value) or not math.isfinite(float(value)):
                    errors.append("motion.%s 必须是数字（当前：%s）" % (key, _show(value)))
                elif key == "overshootPx":
                    # 契约 §5 的默认值就是 -1.0（先冲过再回落），负号是语义的一部分
                    if float(value) == 0:
                        errors.append(
                            "motion.overshootPx 不能是 0（默认 -1.0，负值表示先上冲再回落）"
                        )
                elif float(value) <= 0:
                    errors.append("motion.%s 必须是正数（当前：%s）" % (key, _show(value)))

    return errors, warnings


def format_errors(errors, limit=8):
    if len(errors) <= limit:
        return "；".join(errors)
    return "；".join(errors[:limit]) + "；…还有 %d 处" % (len(errors) - limit)


# ---------------------------------------------------------------- 存储层


def _history_entries():
    """history 目录下的所有版本，新的在前。"""
    entries = []
    try:
        names = os.listdir(HISTORY_DIR)
    except OSError:
        return []
    for name in names:
        match = HISTORY_RE.match(name)
        if not match:
            continue
        revision = int(match.group(1))
        path = os.path.join(HISTORY_DIR, name)
        when = _parse_stamp(match.group(2))
        if when is None:
            try:
                when = datetime.fromtimestamp(os.path.getmtime(path)).astimezone()
            except OSError:
                continue
        entries.append(
            {"revision": revision, "savedAt": _iso(when), "path": path, "_ts": when, "_name": name}
        )
    entries.sort(key=lambda it: (it["_ts"], it["revision"], it["_name"]), reverse=True)
    return entries


def _history_public():
    return [{"revision": it["revision"], "savedAt": it["savedAt"]} for it in _history_entries()]


def _find_history(revision):
    for entry in _history_entries():
        if entry["revision"] == revision:
            return entry
    return None


def _current_revision():
    """配置文件和 state.json 里取大的那个，保证 revision 只增不减。"""
    candidates = []
    cfg = _load_json(CONFIG_PATH)
    if isinstance(cfg, dict) and _is_int(cfg.get("revision")):
        candidates.append(cfg["revision"])
    state = _load_json(STATE_PATH)
    if isinstance(state, dict) and _is_int(state.get("revision")):
        candidates.append(state["revision"])
    return max(candidates) if candidates else 1


def _write_state(revision, updated_at, published_at=None):
    state = {
        "revision": revision,
        "updatedAt": updated_at or _iso(_now()),
        "publishedAt": published_at or _iso(_now()),
    }
    _atomic_write_json(STATE_PATH, state)
    return state


def ensure_initialized():
    """首次启动写默认清单；顺手把 state.json 对齐。返回要打印的提示行。"""
    notes = []
    with _LOCK:
        os.makedirs(HISTORY_DIR, exist_ok=True)

        if not os.path.exists(CONFIG_PATH):
            cfg = default_config()
            _atomic_write_json(CONFIG_PATH, cfg)
            _write_state(cfg["revision"], cfg["updatedAt"], cfg["updatedAt"])
            notes.append(
                "首次启动：已写入内置默认清单（%d 个块，revision=1）" % len(cfg["blocks"])
            )
            return notes

        cfg = _load_json(CONFIG_PATH)
        if not isinstance(cfg, dict):
            notes.append(
                "警告：published/cardback.json 不是合法 JSON，保持原样没覆盖 —— "
                "手机端会退回上一版，请手动修复或删掉它后重启"
            )
            return notes

        revision = cfg.get("revision") if _is_int(cfg.get("revision")) else 1
        updated = cfg.get("updatedAt") if isinstance(cfg.get("updatedAt"), str) else ""
        state = _load_json(STATE_PATH)
        prev_published = ""
        if isinstance(state, dict) and isinstance(state.get("publishedAt"), str):
            prev_published = state["publishedAt"]
        if (
            not isinstance(state, dict)
            or state.get("revision") != revision
            or not prev_published
        ):
            _write_state(revision, updated or _iso(_now()), prev_published)
        return notes


def _archive_current(revision, when):
    """把当前这份拷进 history：cardback-<revision>-<yyyymmdd-HHMMSS>.json"""
    os.makedirs(HISTORY_DIR, exist_ok=True)
    stamp = when.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(HISTORY_DIR, "cardback-%d-%s.json" % (revision, stamp))
    suffix = 2
    while os.path.exists(dest):
        dest = os.path.join(HISTORY_DIR, "cardback-%d-%s-%d.json" % (revision, stamp, suffix))
        suffix += 1
    with open(CONFIG_PATH, "rb") as src, open(dest, "wb") as dst:
        shutil.copyfileobj(src, dst)
    return dest


def publish_config(data):
    """校验已过。旧版进 history → 写新版 → revision+1 → 落 state。"""
    with _LOCK:
        current = _current_revision()
        now = _now()
        if os.path.exists(CONFIG_PATH):
            _archive_current(current, now)

        new_revision = current + 1
        # revision 由服务端说了算，不信前端传的，否则手机那边可能不认新版本
        out = dict(data)
        out["revision"] = new_revision
        if not isinstance(out.get("updatedAt"), str) or not out["updatedAt"].strip():
            out["updatedAt"] = _iso(now)

        _atomic_write_json(CONFIG_PATH, out)
        _write_state(new_revision, out["updatedAt"], _iso(now))
        return new_revision


def rollback_to(target):
    with _LOCK:
        entry = _find_history(target)
        if entry is None:
            raise ApiError("history 里没有 revision=%d 的版本" % target)
        cfg = _load_json(entry["path"])
        if not isinstance(cfg, dict):
            raise ApiError("history 里 revision=%d 的文件读不出 JSON，换一版试试" % target)

        current = _current_revision()
        now = _now()
        # 取 max(当前, 该版)+1：手机只会看「比手上新」，不能让它以为退化了
        new_revision = max(current, target) + 1

        if os.path.exists(CONFIG_PATH):
            _archive_current(current, now)

        out = dict(cfg)
        out["revision"] = new_revision
        updated = out.get("updatedAt")
        if not isinstance(updated, str) or not updated.strip():
            updated = _iso(now)
            out["updatedAt"] = updated

        _atomic_write_json(CONFIG_PATH, out)
        _write_state(new_revision, updated, _iso(now))
        return new_revision


def state_snapshot():
    cfg = _load_json(CONFIG_PATH)
    if not isinstance(cfg, dict):
        cfg = {}
    state = _load_json(STATE_PATH)
    if not isinstance(state, dict):
        state = {}

    revision = _current_revision()
    updated = state.get("updatedAt")
    if not isinstance(updated, str) or not updated:
        updated = cfg.get("updatedAt") if isinstance(cfg.get("updatedAt"), str) else ""
    published = state.get("publishedAt")
    if not isinstance(published, str) or not published:
        published = updated
    if not updated:
        try:
            updated = _iso(datetime.fromtimestamp(os.path.getmtime(CONFIG_PATH)).astimezone())
        except OSError:
            updated = ""
    if not published:
        published = updated

    return {
        "revision": revision,
        "updatedAt": updated,
        "publishedAt": published,
        "historyCount": len(_history_entries()),
    }


# ---------------------------------------------------------------- 网卡枚举


def _is_lan_ip(ip):
    try:
        addr = ipaddress.IPv4Address(ip)
    except (ValueError, TypeError):
        return False
    if addr.is_loopback or addr.is_link_local or addr.is_unspecified:
        return False
    return any(addr in net for net in _LAN_NETS)


def _ipconfig_ips():
    """socket 那几招都不灵时的兜底（只在 Windows 上有）。"""
    if os.name != "nt":
        return []
    try:
        proc = subprocess.run(
            ["ipconfig"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8
        )
    except (OSError, subprocess.SubprocessError):
        return []
    raw = proc.stdout or b""
    try:
        text = raw.decode("mbcs", errors="replace")
    except LookupError:
        text = raw.decode("utf-8", errors="replace")
    return re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", text)


def detect_lan_ips():
    """本机非回环 IPv4，私网地址排前面（同组内保持发现顺序）。"""
    found = []

    def add(ip):
        ip = (ip or "").strip()
        if not ip or ip in found:
            return
        try:
            addr = ipaddress.IPv4Address(ip)
        except ValueError:
            return
        if addr.is_loopback or addr.is_link_local or addr.is_unspecified:
            return
        found.append(ip)

    # 1) UDP connect 拿默认出口地址（不会真的发包）
    for target in ("8.8.8.8", "192.168.1.1", "10.0.0.1"):
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(0.2)
            sock.connect((target, 9))
            add(sock.getsockname()[0])
        except OSError:
            pass
        finally:
            if sock is not None:
                sock.close()

    # 2) 主机名解析
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            add(ip)
    except OSError:
        pass

    # 3) ipconfig 兜底，但只在一个私网地址都没找着时才用
    if not any(_is_lan_ip(ip) for ip in found):
        for ip in _ipconfig_ips():
            add(ip)

    lan = [ip for ip in found if _is_lan_ip(ip)]
    others = [ip for ip in found if not _is_lan_ip(ip)]
    return lan + others


# ---------------------------------------------------------------- HTTP


def _placeholder_page(note=""):
    hint = '<p class="warn">%s</p>' % html.escape(note) if note else ""
    base = html.escape(BASE_DIR)
    return (
        """<!doctype html>
<html lang="zh-CN">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>卡背编辑器</title>
<style>
  body { margin: 0; padding: 48px 24px; background: #15161a; color: #e6e6e6;
         font: 15px/1.7 "Microsoft YaHei", system-ui, sans-serif; }
  .box { max-width: 640px; margin: 0 auto; }
  h1 { font-size: 22px; margin: 0 0 10px; color: #fff; }
  p { margin: 10px 0; color: #b9bcc4; }
  code, pre { background: #22242b; color: #9fd3ff; border-radius: 6px;
              font-family: Consolas, monospace; }
  code { padding: 2px 6px; }
  pre { padding: 12px 14px; overflow: auto; }
  .warn { color: #ffb454; }
  ul { color: #b9bcc4; padding-left: 20px; }
</style>
<div class="box">
  <h1>编辑器页面还没生成</h1>
  <p>服务已经跑起来了，但同目录下还没有 <code>index.html</code>。</p>
  <p>把前端写的那几个文件放进这个目录，然后刷新本页：</p>
  <pre>"""  # noqa: E501
        + base
        + """\\index.html
""" + base + """\\app.js
""" + base + """\\styles.css</pre>
  """ + hint + """
  <p>不想等也可以先直接调接口：</p>
  <ul>
    <li><code>GET /api/state</code> —— 当前发布状态</li>
    <li><code>GET /cardback.json</code> —— 手机拉的那份配置</li>
    <li><code>GET /api/history</code> —— 历史版本</li>
  </ul>
</div>
</html>
"""
    )


STATIC_EXTS = {
    ".html",
    ".htm",
    ".js",
    ".mjs",
    ".css",
    ".json",
    ".svg",
    ".png",
    ".jpg",
    ".jpeg",
    ".ico",
    ".webp",
    ".gif",
    ".woff",
    ".woff2",
    ".ttf",
    ".map",
    ".txt",
    ".md",
}


class CardbackServer(ThreadingHTTPServer):
    daemon_threads = True
    # Windows 的 SO_REUSEADDR 允许两个进程绑同一端口，端口冲突就检测不出来了
    allow_reuse_address = os.name != "nt"


class Handler(BaseHTTPRequestHandler):
    server_version = "CardbackEditor/1.4.2"
    # 关掉 keep-alive：本地小工具不需要，省得连接复用时一个异常把后面全带崩
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------ 鉴权 / 限流

    def _peer(self):
        try:
            return str(self.client_address[0] or "")
        except (IndexError, TypeError, AttributeError):
            return ""

    def _is_local(self):
        """本机浏览器（编辑器页面）免票。"""
        return _is_loopback_peer(self._peer())

    def _post_origin_ok(self):
        """挡跨站 POST —— 尤其是 text/plain 那种不用预检的「简单请求」。"""
        origin = (self.headers.get("Origin") or "").strip()
        if origin:
            return _origin_ok(origin)
        referer = (self.headers.get("Referer") or "").strip()
        if referer:
            return referer.lower().startswith("file://") or _origin_ok(referer)
        ctype = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
        # Origin/Referer 都没有：只有「浏览器必须先预检才能发」的形态才放行。
        # text/plain / 表单那几种是 CORS 简单请求，跨站页面不预检就能打进来，必须挡住。
        return ctype not in (
            "",
            "text/plain",
            "application/x-www-form-urlencoded",
            "multipart/form-data",
        )

    def _guard(self, method, path):
        """返回 True = 已经回包拒绝了，路由别再走。"""
        # 只掐「会改数据 / 会触发模型」的：记录页 5 秒轮询 /rewrite/status 和 /rewrite/records，
        # 把它们也算进 20 次/分钟会把自己的页面 429 掉。
        if _rate_limited(method, path):
            ok, retry_after, window = rate_limit_take(self._peer())
            if not ok:
                self._reject(
                    429,
                    "rate_limited",
                    "请求太密：单 IP 每分钟 %d 次 / 每天 %d 次，%d 秒后再试"
                    % (RATE_BUDGETS[0][1], RATE_BUDGETS[1][1], retry_after),
                    extra_headers={"Retry-After": str(retry_after)},
                )
                return True
        if not self._is_local() and not _token_ok(self.headers.get(TOKEN_HEADER, "")):
            self._reject(401, "token_required", "核对口令")
            return True
        if method == "POST" and not self._post_origin_ok():
            self._reject(403, "origin_rejected", "只接受本机或局域网页面发起的写请求")
            return True
        return False

    def _reject(self, status, code, hint, extra_headers=None):
        payload = {"ok": False, "error": code, "hint": hint}
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(
            body,
            "application/json; charset=utf-8",
            status=status,
            extra_headers=extra_headers,
        )

    # ------------------------------------------------------------ 入口

    def do_GET(self):
        try:
            self._responded = False
            path = urlsplit(self.path).path or "/"
            if self._guard("GET", path):
                return
            self._route_get(path)
        except ApiError as exc:
            self._fail(exc.status, exc.message)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:  # noqa: BLE001 - 兜底，绝不让请求挂死
            self._internal_error(exc, "GET", self.path)

    def do_POST(self):
        try:
            self._responded = False
            path = urlsplit(self.path).path or "/"
            body, error = self._read_body()
            if error:
                return self._fail(400, error)
            # 先读完 body 再判：回包之后客户端还在写，容易把自己写成 broken pipe
            if self._guard("POST", path):
                return
            self._route_post(path, body)
        except ApiError as exc:
            self._fail(exc.status, exc.message)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:  # noqa: BLE001
            self._internal_error(exc, "POST", self.path)

    # ------------------------------------------------------------ 路由

    def _route_get(self, path):
        if path in ("/", "/index.html"):
            return self._serve_index()
        if path == "/cardback.json":
            return self._serve_config()
        if path == "/api/state":
            return self._api_state()
        if path == "/api/history":
            return self._send_json({"ok": True, "items": _history_public()})
        if path == "/sync/pull":
            return self._api_sync_pull()
        if path in ("/api/publish", "/api/rollback"):
            return self._fail(405, "%s 只接受 POST" % path)
        if path == "/favicon.ico":
            return self._send_bytes(b"", "image/x-icon", status=204)
        if path.startswith("/rewrite") or path == "/models":
            return self._rewrite_passthrough("GET")
        static = self._read_static(path)
        if static is not None:
            return self._send_bytes(static[0], static[1])
        return self._fail(404, "没有这个路径：%s" % path)

    def _route_post(self, path, body):
        if path.startswith("/rewrite"):
            return self._rewrite_passthrough("POST", body)
        if path == "/sync/push":
            return self._api_sync_push(body)
        if path == "/api/publish":
            return self._api_publish(body)
        if path == "/api/rollback":
            return self._api_rollback(body)
        if path in ("/api/state", "/api/history"):
            return self._fail(405, "%s 只接受 GET" % path)
        return self._fail(404, "没有这个路径：%s" % path)

    # ------------------------------------------------------------ 接口

    def _api_state(self):
        payload = {"ok": True}
        payload.update(state_snapshot())
        payload["lanUrl"] = LAN_URL
        # 编辑页「一键复制」要的就是这一整行；lanUrl 留着给旧页面/调试用。
        payload["pairUrl"] = pair_url()
        self._send_json(payload)

    def _api_sync_pull(self):
        query = parse_qs(urlsplit(self.path).query)
        try:
            since_rev = int((query.get("since_rev") or ["0"])[0] or 0)
        except (TypeError, ValueError):
            return self._fail(400, "since_rev 要是整数")
        if since_rev < 0:
            return self._fail(400, "since_rev 不能是负数")
        try:
            result = sync_db().pull(since_rev)
        except Exception as exc:  # noqa: BLE001 - 存储坏了要让人看得见
            return self._fail(500, "同步库读不出来：%s" % exc)
        payload = {"ok": True}
        payload.update(result)
        self._send_json(payload)

    def _api_sync_push(self, body):
        data, error = self._parse_json_body(body, "同步")
        if error:
            return self._fail(400, error)
        if not isinstance(data, dict):
            return self._fail(400, 'body 要这样：{"device_id":"...","ops":[...]}')
        ops = data.get("ops")
        if ops is None:
            ops = []
        if not isinstance(ops, list):
            return self._fail(400, "ops 要是数组")
        if len(ops) > 500:
            return self._fail(400, "一次最多 500 条")
        try:
            base_rev = int(data.get("base_rev") or 0)
        except (TypeError, ValueError):
            return self._fail(400, "base_rev 要是整数")
        device_id = str(data.get("device_id") or "").strip()[:64]
        try:
            result = sync_db().push(device_id, base_rev, ops)
        except Exception as exc:  # noqa: BLE001
            return self._fail(500, "同步库写不进去：%s" % exc)
        payload = {"ok": True}
        payload.update(result)
        self._send_json(payload)

    def _api_publish(self, body):
        data, error = self._parse_json_body(body, "发布")
        if error:
            return self._fail(400, error)

        errors, warnings = validate_config(data)
        if errors:
            return self._fail(400, format_errors(errors))

        revision = publish_config(data)
        payload = {"ok": True, "revision": revision}
        if warnings:
            payload["warnings"] = warnings
        self._send_json(payload)

    def _api_rollback(self, body):
        data, error = self._parse_json_body(body, "回退")
        if error:
            return self._fail(400, error)
        if not isinstance(data, dict):
            return self._fail(400, 'body 要这样：{"revision": 3}')

        revision = data.get("revision")
        if not _is_int(revision):
            return self._fail(400, 'body 里要有整数 revision，例如 {"revision": 3}')

        new_revision = rollback_to(revision)
        self._send_json({"ok": True, "revision": new_revision, "restoredFrom": revision})

    def _serve_config(self):
        try:
            with open(CONFIG_PATH, "rb") as fh:
                data = fh.read()
        except FileNotFoundError:
            return self._fail(404, "published/cardback.json 还没生成，重启一下服务")
        except OSError as exc:
            return self._fail(500, "读 published/cardback.json 失败：%s" % exc)
        # 手机每次都该拿到最新的，任何缓存都可能让「发布完没变」变成灵异事件
        self._send_bytes(data, "application/json; charset=utf-8", cache="no-store")

    def _serve_index(self):
        try:
            with open(INDEX_PATH, "rb") as fh:
                data = fh.read()
        except FileNotFoundError:
            data = None
        except OSError as exc:
            return self._send_bytes(
                _placeholder_page("读 index.html 出错：%s" % exc).encode("utf-8"),
                "text/html; charset=utf-8",
            )
        if data is None:
            return self._send_bytes(
                _placeholder_page().encode("utf-8"), "text/html; charset=utf-8"
            )
        self._send_bytes(data, "text/html; charset=utf-8")

    def _rewrite_passthrough(self, method, body=b""):
        """路径拼错时按 404 回，别让它掉进静态文件兜底。"""
        try:
            if _serve_rewrite(self, method, body):
                return
        except Exception as exc:  # noqa: BLE001 - 重写服务坏了不能连带编辑器一起挂
            return self._fail(500, "重写服务出错：%s" % exc)
        return self._fail(404, "没有这个路径：%s" % self.path)

    # ------------------------------------------------------------ 静态文件
    # 编辑器前端会拆成 index.html / app.js / styles.css 三个文件，
    # 同源下得能把它们取走，否则页面直接白屏。只放行本目录的普通文件。

    def _read_static(self, path):
        rel = unquote(path).lstrip("/")
        if not rel or rel.startswith(".") or "/" in rel or "\\" in rel:
            return None
        full = os.path.join(BUNDLE_DIR, rel)
        if not os.path.isfile(full):
            return None
        ext = os.path.splitext(rel)[1].lower()
        if ext not in STATIC_EXTS:
            return None
        try:
            with open(full, "rb") as fh:
                data = fh.read()
        except OSError:
            return None
        return data, _content_type(rel, ext)

    # ------------------------------------------------------------ 读写 helper

    def _read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            return b"", "不支持 chunked 请求体，带上 Content-Length 再发一次"
        raw_length = self.headers.get("Content-Length")
        if not raw_length:
            return b"", None
        try:
            length = int(raw_length)
        except (TypeError, ValueError):
            return b"", "Content-Length 不是数字"
        if length <= 0:
            return b"", None
        if length > MAX_BODY:
            return b"", "请求体太大（%d 字节，上限 %d）" % (length, MAX_BODY)
        return self.rfile.read(length), None

    def _parse_json_body(self, body, action):
        if not body or not body.strip():
            return None, "%s需要一个完整的配置 JSON，但请求体是空的" % action
        try:
            text = body.decode("utf-8-sig")
        except UnicodeDecodeError:
            return None, "请求体不是 UTF-8 编码"
        try:
            return json.loads(text), None
        except json.JSONDecodeError as exc:
            return None, "JSON 解析失败：第 %d 行第 %d 列附近，%s" % (
                exc.lineno,
                exc.colno,
                exc.msg,
            )

    # ------------------------------------------------------------ 响应

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(body, "application/json; charset=utf-8", status=status)

    def _fail(self, status, message):
        self._send_json({"ok": False, "error": message}, status=status)

    def _send_bytes(self, data, content_type, status=200, cache="no-store", extra_headers=None):
        if getattr(self, "_responded", False):
            return
        self._responded = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            if cache:
                self.send_header("Cache-Control", cache)
            for name, value in (extra_headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            if data:
                self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _internal_error(self, exc, method, path):
        try:
            import traceback

            traceback.print_exc()
            self._fail(500, "服务端内部错误：%s" % exc)
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ------------------------------------------------------------ 日志

    def log_message(self, fmt, *args):
        stamp = datetime.now().strftime("%H:%M:%S")
        print("[%s] %s" % (stamp, fmt % args), flush=True)

    def log_error(self, fmt, *args):
        stamp = datetime.now().strftime("%H:%M:%S")
        print("[%s] %s" % (stamp, fmt % args), flush=True)


def _content_type(path, ext):
    if ext == ".js" or ext == ".mjs":
        return "text/javascript; charset=utf-8"
    if ext == ".css":
        return "text/css; charset=utf-8"
    if ext == ".json":
        return "application/json; charset=utf-8"
    if ext in (".html", ".htm"):
        return "text/html; charset=utf-8"
    if ext == ".svg":
        return "image/svg+xml"
    if ext in (".txt", ".md", ".map"):
        return "text/plain; charset=utf-8"
    guessed = mimetypes.guess_type(path)[0]
    return guessed or "application/octet-stream"


# ---------------------------------------------------------------- 启动


def _set_console_title(title):
    """.bat 里设过一次，这里再兜一手（中文标题在 cmd 里容易乱码）。"""
    if os.name != "nt":
        return
    try:
        import ctypes

        ctypes.windll.kernel32.SetConsoleTitleW(title)
    except Exception:  # noqa: BLE001
        pass


def _looks_like_our_server():
    try:
        with urllib.request.urlopen(
            "http://127.0.0.1:%d/api/state" % PORT, timeout=1.5
        ) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        return isinstance(data, dict) and data.get("ok") is True and "revision" in data
    except Exception:  # noqa: BLE001
        return False


def _report_port_busy(exc):
    print("", flush=True)
    print("启动失败：端口 %d 已被占用（%s）" % (PORT, exc), flush=True)
    if _looks_like_our_server():
        print(
            "  好像已经有一个卡背编辑器在跑了：http://127.0.0.1:%d/" % PORT, flush=True
        )
        print("  直接用它就行，不用再启动一个。", flush=True)
    else:
        print(
            "  查一下是谁占了端口：netstat -ano | findstr :%d" % PORT, flush=True
        )
        print("  关掉它之后，再双击「启动编辑器.bat」。", flush=True)
    print("", flush=True)
    return 1


def _pause_if_frozen():
    """双击 exe 时窗口是黑框，报完错一闪就没了 —— 停下来等人按回车。
    用 python server.py 跑的时候不拦（终端里能看见，Ctrl+C 也方便）。"""
    if not FROZEN:
        return
    try:
        input("按回车关闭这个窗口…")
    except (EOFError, OSError, KeyboardInterrupt):
        pass


# ---------------------------------------------------------------- 重写服务挂载
# 1.4.3 的卡背重写服务单独放在 tools/rewrite-service/，这里只负责把它的路由
# 接到同一个端口上（手机和编辑器同源，不用再开一个口子）。懒加载 + try/except：
# 重写服务出问题绝不能让编辑器本身起不来。

_REWRITE = {"module": None}

# 源码运行：tools/cardback-editor/../rewrite-service；打包 exe：exe 旁边的 rewrite-service
_REWRITE_DIRS = (
    os.path.join(BASE_DIR, "rewrite-service"),
    os.path.join(BASE_DIR, "..", "rewrite-service"),
)


def _rewrite_module():
    if _REWRITE["module"] is None:
        for path in _REWRITE_DIRS:
            if not os.path.isfile(os.path.join(path, "service.py")):
                continue
            path = os.path.abspath(path)
            if path not in sys.path:
                sys.path.insert(0, path)
            import service as rewrite_service  # noqa: PLC0415 - 懒加载，编辑器不依赖它

            _REWRITE["module"] = rewrite_service
            break
        else:
            raise RuntimeError("找不到 tools/rewrite-service/service.py")
    return _REWRITE["module"]


def _serve_rewrite(handler, method, body=b""):
    """接得住就回包返回 True，不是重写服务的路径返回 False。"""
    result = _rewrite_module().respond(method, handler.path, body)
    if result is None:
        return False
    status, content_type, payload = result
    handler._send_bytes(payload, content_type, status=status)
    return True


def _start_rewrite():
    try:
        _rewrite_module().startup()
        print("  重写面板：http://127.0.0.1:%d/rewrite/" % PORT, flush=True)
    except Exception as exc:  # noqa: BLE001 - 重写服务坏了也要让编辑器起来
        print("  重写服务没起来（编辑器照常可用）：%r" % exc, flush=True)


def main(argv=None):
    global LAN_URL, PORT

    args = list(sys.argv[1:] if argv is None else argv)

    # --port 8767 / --port=8767：默认 8765；被占用或要同时开两个时才用得上
    for i, arg in enumerate(args):
        value = None
        if arg == "--port" and i + 1 < len(args):
            value = args[i + 1]
        elif arg.startswith("--port="):
            value = arg.split("=", 1)[1]
        if value is None:
            continue
        try:
            port = int(value)
        except ValueError:
            port = 0
        if not 1 <= port <= 65535:
            print("端口不对：%s（要给 1-65535 之间的数字）" % value, flush=True)
            _pause_if_frozen()
            return 2
        PORT = port

    open_browser = "--no-browser" not in args and "--no-open" not in args

    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass
    _set_console_title("卡背编辑器")

    # 先抢端口：端口占着就直接说人话退出，别去动 published/
    try:
        httpd = CardbackServer((HOST, PORT), Handler)
    except OSError as exc:
        code = _report_port_busy(exc)
        _pause_if_frozen()
        return code

    os.makedirs(HISTORY_DIR, exist_ok=True)
    notes = ensure_initialized()
    ensure_token()

    lan_ips = detect_lan_ips()
    primary = next((ip for ip in lan_ips if _is_lan_ip(ip)), None)
    if primary:
        LAN_URL = "http://%s:%d/cardback.json" % (primary, PORT)
    else:
        LAN_URL = "http://127.0.0.1:%d/cardback.json" % PORT

    local_url = "http://127.0.0.1:%d/" % PORT
    print("卡背编辑器已启动", flush=True)
    print("  浏览器打开：%s" % local_url, flush=True)
    _start_rewrite()
    if primary:
        print("  手机：粘贴这一行 → %s" % pair_url(), flush=True)
    else:
        print(
            "  手机：粘贴这一行 →（没找到局域网 IP —— 确认电脑连着 WiFi 或网线，且和手机在同一网段）",
            flush=True,
        )
    print("  发布目录：%s" % PUBLISHED_DIR, flush=True)
    # 口令不再单独打印：它就在上面那一行末尾，复制一次就够。
    print("    （电脑上开编辑器不用填；口令文件 %s，删掉再启动会换新的）" % TOKEN_PATH, flush=True)
    others = [ip for ip in lan_ips if ip != primary]
    if others:
        print(
            "  其它网卡：%s（手机连的网段不是上面那个的话，把地址里的 IP 换成其中之一）"
            % "、".join(others),
            flush=True,
        )
    for note in notes:
        print("  %s" % note, flush=True)
    print("  按 Ctrl+C 停止", flush=True)

    if open_browser:
        try:
            webbrowser.open(local_url)
        except Exception:  # noqa: BLE001 - 打不开就算了，不能把服务带崩
            pass

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。", flush=True)
    finally:
        try:
            httpd.server_close()
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except KeyboardInterrupt:
        sys.exit(0)
    except Exception:  # noqa: BLE001 - 双击 exe 时别报完错一闪就没
        traceback.print_exc()
        _pause_if_frozen()
        sys.exit(1)

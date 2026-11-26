#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""卡背清单本地服务（App 1.5.0）

- 手机：GET /cardback.json（拉配置）
- 手机/运维：GET /health（版本与能力自报，1.5.0）
- 手机热更：GET /cards/manifest.json + GET /cards/cet6_cards.json（词卡包，no-store）
- 编辑器网页：/api/state、/api/publish、/api/history、/api/rollback、POST /cards/publish
- 只用 Python 3 标准库，零第三方依赖
- 所有落盘都在本文件同级的 published/ 下（词卡包在 published/cards/）

用法：
    python server.py               # 起服务 + 自动打开浏览器
    python server.py --no-browser  # 自测用：不开浏览器
"""

from __future__ import annotations

import hashlib
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

from types import MethodType

# 按文件位置加载入口时，调用方可能没有把编辑器目录放在 sys.path。
_editor_dir = os.path.dirname(os.path.abspath(__file__))
if _editor_dir not in sys.path:
    sys.path.insert(0, _editor_dir)

import cardback_api
import cardback_config
import cardback_http
import cardback_network
import cardback_rewrite
import cardback_security
import cardback_startup

try:  # 打包 exe / 单独拷走 server.py 时也不能因为缺这个文件就起不来
    import tls_support
except Exception:  # noqa: BLE001 - 只在启动 TLS 时降级，HTTP 不受影响
    tls_support = None

# ---------------------------------------------------------------- 常量

HOST = "0.0.0.0"
DEFAULT_PORT = 8765
PORT = DEFAULT_PORT

# ---- 版本与能力自报（1.4.7）----
# 单一来源：HTTP 的 Server 头、GET /health、启动日志都从这三个常量取，别在多处各写一份。
# 升版本只改 SERVICE_VERSION；加/删能力才动 HEALTH_PROTOCOL 和 CAPABILITIES。
SERVICE_NAME = "cardback-editor"  # /health 的 service 字段
SERVICE_PRODUCT = "CardbackEditor"  # HTTP Server 头里的产品名
SERVICE_VERSION = "1.5.3"
SERVER_VERSION = "%s/%s" % (SERVICE_PRODUCT, SERVICE_VERSION)
HEALTH_PROTOCOL = 1  # App 握手用：协议变了就说清楚，别靠字段猜

# /health 只列真实存在的接口 —— App 拿它判断「电脑端版本过旧」，所以宁缺毋滥：
# - rewrite：POST /rewrite/marks 入队 + GET /rewrite/results 回拉 + /rewrite/status
# - feedback：POST /rewrite/feedback（按 clientId 去重，可重复补发）
# - sync_push：POST /sync/push（按 lrt/op_id 去重，重复的也算 acked）
# - sync_cursor：GET /sync/pull?since_rev= 游标翻页（回 next_since_rev）
# - cards_ota：GET /cards/manifest.json + GET /cards/cet6_cards.json + POST /cards/publish
#   词卡包热更（1.5.0）：App 拿 manifest 比 version/sha256，变了才下整份词卡。
# 不列 skip / outbox：两者都是 App 本地队列（needs_reinsert、补发），服务端没有对应接口。
CAPABILITIES = ("rewrite", "feedback", "sync_push", "sync_cursor", "cards_ota")

# 进程启动时刻：/health 的 started_at / uptime_s。import 时先记一次，main() 会再对齐。
_STARTED_AT = datetime.now().astimezone()
_STARTED_MONO = time.monotonic()

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

# ---- 词卡热更（1.5.0）：词卡包落在 published/cards/，内容由 card_publisher 合并生成。
# 用函数现算而不是 import 时冻成常量：测试会把 PUBLISHED_DIR 整体改到临时目录。
CARDS_SUBDIR = "cards"
CARDS_FILENAME = "cet6_cards.json"
CARDS_MANIFEST_FILENAME = "manifest.json"




# ---- 局域网 TLS（1.4.7.2 W4）：同一个 Handler/CardbackServer，多包一层自签 TLS。
# 默认 8443；端口被占/证书生成失败只打印 warning，HTTP 监听照旧。
try:
    TLS_PORT = int(os.environ.get("CARDBACK_TLS_PORT", "8443"))
except ValueError:
    TLS_PORT = 8443
if not 0 <= TLS_PORT <= 65535:  # 0 只给测试用（系统分配），生产默认 8443
    TLS_PORT = 8443
TLS_HOST = HOST
TLS_DIR = os.path.join(BASE_DIR, ".cardback_tls")
TLS_CERT_PATH = os.path.join(TLS_DIR, "cert.pem")
TLS_KEY_PATH = os.path.join(TLS_DIR, "key.pem")
TLS_HTTPD = None  # 成功启动后的 CardbackServer；None = 没起来
TLS_THREAD = None
TLS_BOUND_PORT = 0  # 实际监听端口（TLS_PORT=0 时由系统分配）
TLS_SPKI_SHA256 = ""  # 证书 DER SPKI 的 SHA-256，小写 hex
TLS_NOT_AFTER = ""  # 证书到期时间，ISO8601

# ---- 学习进度同步（1.4.4）：手机把 tb_progress 的结果态当 op 发过来，别的设备按 rev 拉。
# 存储和读写规则都在 sync_store.py 里；这里只做 HTTP 壳。
SYNC_PATH = os.path.join(BASE_DIR, "sync.json")
_SYNC = None


# ---- 口令（1.4.4）：一台电脑一个，首次启动生成后落盘，之后一直复用。
# 手机每个请求都带 X-Cardback-Token；本机 127.0.0.1 / ::1 免票，
# 所以电脑上开着编辑器点发布不受影响。
TOKEN_PATH = os.path.join(BASE_DIR, ".cardback_token")
TOKEN_HEADER = "X-Cardback-Token"
TOKEN_LENGTH = 16  # secrets.token_urlsafe(16) → 22 个字符
TOKEN = ""  # 启动时填；见 ensure_token()

# ---- 服务身份（1.4.7.2 W4）：同一台电脑/同一个数据目录必须始终是同一个 id。
# 这个 id 不是口令，公开它不能授权；App 只在拿到它之后再用口令打 /identity 验一次，
# 确认「发现的这个 IP」确实是已配对的那台电脑，避免同名服务/换网串台。
INSTANCE_PATH = os.path.join(BASE_DIR, ".cardback_instance")
INSTANCE_ID = ""  # 启动时填；见 ensure_instance_id()

# ---- 局域网发现（1.4.7.2 W4）：UDP 广播应答，不带口令，只报身份和端口。
# 手机在同一 WiFi 里广播一句 DISCOVERY_MAGIC，电脑回一份 JSON；手机再拿口令验 /identity。
try:
    DISCOVERY_PORT = int(os.environ.get("CARDBACK_DISCOVERY_PORT", "8767"))
except ValueError:
    DISCOVERY_PORT = 8767
DISCOVERY_MAGIC = b"CARDBACK_EDITOR_DISCOVER_V1"

# ---- 限流：只卡「会调模型 / 会改数据」的请求，普通读接口不限（手机轮询不受影响）。
# (窗口秒数, 窗口内次数)；本机也计，否则额度漏了照样能刷穿。
# 鉴权在限流之前：401 不消耗额度，隧道下别人拿不到口令也打不空你的预算。
#
# 1.5.2：额度按「这一枪是谁打的」分两档——
#   * 公网匿名（没口令，走隧道进来的陌生人）：20/分钟、200/天，尽量小；
#   * 自家请求（带合法 X-Cardback-Token，或本机 loopback 免票）：120/分钟、5000/天。
#   分档的理由：一次批量发布 60 张卡是很正常的操作，20/分钟会把自家人也卡死；
#   而 anonymous 那条线不能松，否则别人拿着公网地址就能把额度烧光。
RATE_BUDGETS = ((60, 20), (86400, 200))
RATE_BUDGETS_VIP = ((60, 120), (86400, 5000))
RATE_LIMITED_PATHS = ("/api/publish", "/api/rollback")
# /sync/* 单独一档：App 约 30 秒自动同步一次，还要容忍补发突发。
# 120/min = 每 0.5 秒一次，正常同步（约 2 次/分钟）远远够；20000/天 ≈ 13.9 次/分钟。
# VIP 档只往上放宽（App 补发一大坨时不至于被自己的桶挡住），绝不往下降。
SYNC_RATE_BUDGETS = ((60, 120), (86400, 20000))
SYNC_RATE_BUDGETS_VIP = ((60, 240), (86400, 40000))
SYNC_RATE_LIMITED_PATHS = ("/sync/push", "/sync/pull")
# 运维接口：清空内存里的限流桶。它自己**不占额度**——被锁在 429 外面就没法自救了。
ADMIN_RESET_PATH = "/admin/rate-reset"
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
# 拒绝一个 POST 时，最多把这么点上行 body 读掉再关连接（1.5.2）。
# 不读就 close，Windows 端会回 RST，客户端看到的是「连接被重置」而不是我们的
# 401/429——手机和隧道那头会把它当成网络故障、反复重推。只丢有上限的一小段，
# 既解决 RST，又不至于为匿名流量把 4MB 读进内存。
DRAIN_LIMIT = 16 * 1024

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

_INSTANCE_LOCK = threading.Lock()
CF_IP_HEADER = "CF-Connecting-IP"
FORWARDED_FOR_HEADER = "X-Forwarded-For"

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

_REWRITE = {"module": None}

# 源码运行：tools/cardback-editor/../rewrite-service；打包 exe：exe 旁边的 rewrite-service
_REWRITE_DIRS = (
    os.path.join(BASE_DIR, "rewrite-service"),
    os.path.join(BASE_DIR, "..", "rewrite-service"),
)


class ApiError(Exception):
    """给接口用的、能直接回给编辑器的错误（默认 400）。"""

    def __init__(self, message, status=400):
        super().__init__(message)
        self.message = message
        self.status = status


class CardbackServer(ThreadingHTTPServer):
    daemon_threads = True
    # Windows 的 SO_REUSEADDR 允许两个进程绑同一端口，端口冲突就检测不出来了
    allow_reuse_address = os.name != "nt"


class _ServerContext:
    """子模块与本入口共享同一份命名空间，兼容未注册在 sys.modules 的 spec 加载。"""


_context = _ServerContext()
_context.__dict__ = globals()

ensure_token = MethodType(cardback_security.ensure_token, _context)
ensure_instance_id = MethodType(cardback_security.ensure_instance_id, _context)
_token_ok = MethodType(cardback_security._token_ok, _context)
pair_url = MethodType(cardback_security.pair_url, _context)
_is_loopback_peer = cardback_security._is_loopback_peer
_parse_ip = cardback_security._parse_ip
client_ip = MethodType(cardback_security.client_ip, _context)
_origin_host_ok = cardback_security._origin_host_ok
_origin_ok = MethodType(cardback_security._origin_ok, _context)
_rate_budget_for = MethodType(cardback_security._rate_budget_for, _context)
_rate_limited = MethodType(cardback_security._rate_limited, _context)
_prune_rate = MethodType(cardback_security._prune_rate, _context)
reset_rate_limits = MethodType(cardback_security.reset_rate_limits, _context)
rate_limit_take = MethodType(cardback_security.rate_limit_take, _context)
_now = cardback_config._now
mark_process_start = MethodType(cardback_config.mark_process_start, _context)
_iso = cardback_config._iso
_is_int = cardback_config._is_int
_is_number = cardback_config._is_number
_show = cardback_config._show
_load_json = cardback_config._load_json
_atomic_write_json = cardback_config._atomic_write_json
_parse_stamp = cardback_config._parse_stamp
default_config = MethodType(cardback_config.default_config, _context)
validate_config = MethodType(cardback_config.validate_config, _context)
format_errors = cardback_config.format_errors
_history_entries = MethodType(cardback_config._history_entries, _context)
_history_public = MethodType(cardback_config._history_public, _context)
_find_history = MethodType(cardback_config._find_history, _context)
_current_revision = MethodType(cardback_config._current_revision, _context)
_write_state = MethodType(cardback_config._write_state, _context)
ensure_initialized = MethodType(cardback_config.ensure_initialized, _context)
_archive_current = MethodType(cardback_config._archive_current, _context)
publish_config = MethodType(cardback_config.publish_config, _context)
rollback_to = MethodType(cardback_config.rollback_to, _context)
state_snapshot = MethodType(cardback_config.state_snapshot, _context)
cards_dir = MethodType(cardback_api.cards_dir, _context)
_cards_module = cardback_api._cards_module
_ensure_cards_published = MethodType(cardback_api._ensure_cards_published, _context)
_cards_manifest_ok = MethodType(cardback_api._cards_manifest_ok, _context)
sync_db = MethodType(cardback_api.sync_db, _context)
_placeholder_page = MethodType(cardback_http._placeholder_page, _context)
_content_type = cardback_http._content_type
_rewrite_module = MethodType(cardback_rewrite._rewrite_module, _context)
_health_capabilities = MethodType(cardback_rewrite._health_capabilities, _context)
_serve_rewrite = MethodType(cardback_rewrite._serve_rewrite, _context)
_start_rewrite = MethodType(cardback_rewrite._start_rewrite, _context)
_is_lan_ip = MethodType(cardback_network._is_lan_ip, _context)
_ipconfig_ips = cardback_network._ipconfig_ips
detect_lan_ips = MethodType(cardback_network.detect_lan_ips, _context)
_tls_public_fields = MethodType(cardback_network._tls_public_fields, _context)
start_tls_server = MethodType(cardback_network.start_tls_server, _context)
stop_tls_server = MethodType(cardback_network.stop_tls_server, _context)
_start_discovery = MethodType(cardback_network._start_discovery, _context)
_set_console_title = cardback_startup._set_console_title
_looks_like_our_server = MethodType(cardback_startup._looks_like_our_server, _context)
_report_port_busy = MethodType(cardback_startup._report_port_busy, _context)
_pause_if_frozen = MethodType(cardback_startup._pause_if_frozen, _context)
main = MethodType(cardback_startup.main, _context)


class Handler(
    cardback_security.RequestGuard, cardback_api.EditorAPI, cardback_rewrite.RewriteAPI,
    cardback_http.HTTPTransport, BaseHTTPRequestHandler,
):
    context = _context
    server_version = SERVER_VERSION
    # 关掉 keep-alive：本地小工具不需要，省得连接复用时一个异常把后面全带崩
    protocol_version = "HTTP/1.1"

    def _route_get(self, path):
        if path in ("/", "/index.html"):
            return self._serve_index()
        if path == "/health":
            return self._api_health()
        if path == "/identity":
            return self._api_identity()
        if path == "/cardback.json":
            return self._serve_config()
        # 词卡热更（1.5.0）：App 先拿 manifest 比 version/sha256，变了才下整份词卡
        if path == "/cards/manifest.json":
            return self._serve_cards_manifest()
        if path == "/cards/cet6_cards.json":
            return self._serve_cards_json()
        if path == "/api/state":
            return self._api_state()
        if path == "/api/history":
            return self._send_json({"ok": True, "items": _history_public()}, compress_large=True)
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
            data, content_type, headers = static
            return self._send_bytes(data, content_type, extra_headers=headers)
        return self._fail(404, "没有这个路径：%s" % path)

    def _route_post(self, path, body):
        if path == ADMIN_RESET_PATH:
            return self._api_rate_reset()
        if path.startswith("/rewrite"):
            return self._rewrite_passthrough("POST", body)
        if path == "/sync/push":
            return self._api_sync_push(body)
        if path == "/api/publish":
            return self._api_publish(body)
        if path == "/api/rollback":
            return self._api_rollback(body)
        if path == "/cards/publish":
            return self._api_cards_publish()
        if path in ("/api/state", "/api/history"):
            return self._fail(405, "%s 只接受 GET" % path)
        return self._fail(404, "没有这个路径：%s" % path)


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

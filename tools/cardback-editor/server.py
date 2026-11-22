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
SERVICE_VERSION = "1.5.2"
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


def cards_dir():
    """词卡发布目录：/cards/* 三个接口和启动时的 ensure_published 都用这一份。"""
    return os.path.join(PUBLISHED_DIR, CARDS_SUBDIR)


def _cards_module():
    """惰性导入 card_publisher —— 发布模块坏了不能连带整个编辑器起不来。"""
    import card_publisher

    return card_publisher


def _ensure_cards_published():
    """启动时保证词卡发布包就绪，返回一句状态（main 打进启动日志）。

    失败只打印警告、返回「未就绪」——词卡发布坏了不能挡住编辑器启动。
    """
    try:
        manifest = _cards_module().ensure_published(cards_dir())
        return "manifest v%s，%s 词" % (manifest.get("version"), manifest.get("count"))
    except Exception as exc:  # noqa: BLE001 - 绝不让词卡连累主服务启动
        print("  [警告] 词卡发布包初始化失败（不影响编辑器）：%s" % exc, flush=True)
        return "还没就绪（%s）" % exc


def _cards_manifest_ok(publish_dir):
    """manifest 存在且能解析成 JSON 对象才算「可发」。缺字段（老版本/手改）不在此判，
    ensure_published 那边会按 sha256 是否非空判断要不要重建。"""
    path = os.path.join(publish_dir, CARDS_MANIFEST_FILENAME)
    try:
        with open(path, "rb") as fh:
            payload = json.loads(fh.read().decode("utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    return isinstance(payload, dict)


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


_INSTANCE_LOCK = threading.Lock()


def ensure_instance_id():
    """读/生成服务身份；和口令不同，这个可以公开，只用于「这是同一台电脑」的比对。"""
    global INSTANCE_ID
    if INSTANCE_ID:
        return INSTANCE_ID
    # 并发首次请求可能同时进来；不锁会出现「文件里是 A、内存里是 B」的串台。
    with _INSTANCE_LOCK:
        if INSTANCE_ID:
            return INSTANCE_ID
        try:
            with open(INSTANCE_PATH, "r", encoding="utf-8") as fh:
                existing = fh.read().strip()
        except FileNotFoundError:
            existing = ""
        except OSError:
            existing = ""
        if existing:
            INSTANCE_ID = existing
            return INSTANCE_ID

        instance_id = "cb-" + secrets.token_hex(16)
        try:
            with open(INSTANCE_PATH, "w", encoding="utf-8") as fh:
                fh.write(instance_id + chr(10))
            try:
                os.chmod(INSTANCE_PATH, 0o600)
            except OSError:
                pass
        except OSError:
            pass
        INSTANCE_ID = instance_id
        return INSTANCE_ID


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

    1.4.7.2 W4：TLS 可用时把完整 SPKI pin 和 tls_port 也放进 `#` 片段。pin 是
    证书公钥摘要，不是口令；放在片段里同样不会进请求行/日志。手机把这一行
    当一次性配对输入，先有 pin 才可能自动发现局域网 HTTPS。
    """
    base = LAN_URL
    suffix = "/cardback.json"
    if base.endswith(suffix):
        base = base[: -len(suffix)]
    fragment = ensure_token()
    if TLS_BOUND_PORT and TLS_SPKI_SHA256:
        fragment += "&pin=%s&tls_port=%d" % (TLS_SPKI_SHA256, TLS_BOUND_PORT)
    return "%s#%s" % (base, fragment)


def _is_loopback_peer(peer):
    try:
        return ipaddress.ip_address(peer).is_loopback
    except ValueError:
        return peer in ("localhost", "localhost.")


# ---- 真实客户端 IP（1.5.2）：cloudflared 回源时 TCP peer 恒为 127.0.0.1，
# 只看 peer 等于让全网设备挤进同一个桶（一台刷爆，全都没得用）。
# 但反过来「无条件认头」更糟：直连 8765 的人随手写个 CF-Connecting-IP 就能换桶绕过限流。
# 所以只有 loopback peer（也就是必经本机反代/隧道）才信这两个头。
CF_IP_HEADER = "CF-Connecting-IP"
FORWARDED_FOR_HEADER = "X-Forwarded-For"


def _parse_ip(text):
    """把 header 里的一个地址段洗成规范 IP 字符串；不是合法 IP 就返回 ""。"""
    text = (text or "").strip()
    if not text:
        return ""
    if text.startswith("["):  # [2001:db8::1] 或 [2001:db8::1]:443
        text = text[1:].split("]", 1)[0]
    elif text.count(":") == 1 and "." in text:  # 1.2.3.4:5678（带端口的 IPv4）
        text = text.split(":", 1)[0]
    try:
        return str(ipaddress.ip_address(text))
    except ValueError:
        return ""


def client_ip(handler):
    """限流键用的「真实客户端 IP」。

    - TCP peer 不是 loopback：直接用 peer，**忽略**一切转发头（防伪造绕过）；
    - TCP peer 是 loopback：说明请求经本机隧道/反代进来，按可信度取
      CF-Connecting-IP → X-Forwarded-For 第一个地址；两个都拿不出合法 IP 才回落 peer。
    """
    peer = ""
    try:
        peer = str(getattr(handler, "client_address", ("", ""))[0] or "")
    except (IndexError, TypeError, AttributeError):
        peer = ""
    if not _is_loopback_peer(peer):
        return peer
    headers = getattr(handler, "headers", None)
    if headers is None:
        return peer
    try:
        direct = _parse_ip(headers.get(CF_IP_HEADER))
        if direct:
            return direct
        forwarded = headers.get(FORWARDED_FOR_HEADER) or ""
    except (AttributeError, TypeError):
        return peer
    for chunk in forwarded.split(","):
        hop = _parse_ip(chunk)
        if hop:
            return hop
    return peer


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


def _rate_budget_for(method, path, vip=False):
    """返回 (桶名, 预算)；不需要限流时返回 (None, None)。

    桶分开记，互不挤占：写接口 20/分钟（VIP 120），/sync/* 120/分钟（VIP 240）。
    记录页 5 秒轮询的 /rewrite/status、/rewrite/records 不是 POST /rewrite*，不算。
    `vip=True` 表示这一枪是自家打的（合法口令 / 本机 loopback），拿放宽的那一档。
    """
    if path == ADMIN_RESET_PATH:
        # 运维自救口：不占额度。被限流锁死时唯一还能用的就是它。
        return None, None
    if (method == "POST" and path.startswith("/rewrite")) or path in RATE_LIMITED_PATHS:
        return "write", (RATE_BUDGETS_VIP if vip else RATE_BUDGETS)
    if path in SYNC_RATE_LIMITED_PATHS:
        return "sync", (SYNC_RATE_BUDGETS_VIP if vip else SYNC_RATE_BUDGETS)
    return None, None


def _rate_limited(method, path):
    """兼容旧调用：这个请求要不要记一笔限流。"""
    bucket, _budgets = _rate_budget_for(method, path)
    return bucket is not None


def _prune_rate(now):
    for peer in list(_RATE_HITS):
        buckets = _RATE_HITS[peer].get("buckets", {})
        idle = True
        for bucket in list(buckets):
            slot_data = buckets[bucket]
            budgets = slot_data["budgets"]
            windows = slot_data["windows"]
            if any(
                now - (slot[0] + window) <= window
                for slot, (window, _limit) in zip(windows, budgets)
            ):
                idle = False
                break
            buckets.pop(bucket, None)
        if idle or not buckets:
            _RATE_HITS.pop(peer, None)


def reset_rate_limits():
    """清空限流计数（测试 / 排障 / POST /admin/rate-reset 用）。

    返回清掉的桶数（每个「客户端 IP × 桶」算一个），方便调用方自报清了多少。
    """
    with _RATE_LOCK:
        cleared = sum(len(entry.get("buckets", {})) for entry in _RATE_HITS.values())
        _RATE_HITS.clear()
    return cleared


def rate_limit_take(peer, now=None, budgets=None, bucket="default"):
    """按 IP + 桶记一笔。返回 (ok, retry_after_seconds, 窗口名)。"""
    now = time.time() if now is None else now
    budgets = RATE_BUDGETS if budgets is None else budgets
    with _RATE_LOCK:
        if len(_RATE_HITS) > 256:
            _prune_rate(now)
        entry = _RATE_HITS.get(peer)
        if entry is None:
            entry = {"buckets": {}}
            _RATE_HITS[peer] = entry
        slot_data = entry["buckets"].get(bucket)
        if slot_data is None:
            slot_data = {
                "budgets": budgets,
                "windows": [[0, 0] for _ in budgets],
            }
            entry["buckets"][bucket] = slot_data
        for index, (window, limit) in enumerate(budgets):
            slot = slot_data["windows"][index]
            start = int(now // window) * window
            if slot[0] != start:
                slot[0] = start
                slot[1] = 0
            if slot[1] >= limit:
                return False, max(1, int(start + window - now)), ("分钟" if window < 3600 else "天")
        for index, _budget in enumerate(budgets):
            slot_data["windows"][index][1] += 1
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


def mark_process_start():
    """真正开始服务时对一次表：import 到 main() 之间还隔着参数解析，别把那段算进 uptime。"""
    global _STARTED_AT, _STARTED_MONO
    _STARTED_AT = datetime.now().astimezone()
    _STARTED_MONO = time.monotonic()


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


def _tls_public_fields():
    """/health 和 /identity 共用：App 靠这三个字段找 TLS 并固定证书。

    TLS 没起来时 tls_port=0、指纹/到期时间为 None —— 让 App 明确知道「这台
    电脑这次没开 TLS」，而不是拿一个连不上的端口去重试。
    """
    return {
        "tls_port": TLS_BOUND_PORT,
        "tls_spki_sha256": TLS_SPKI_SHA256 or None,
        "tls_not_after": TLS_NOT_AFTER or None,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = SERVER_VERSION
    # 关掉 keep-alive：本地小工具不需要，省得连接复用时一个异常把后面全带崩
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------ 鉴权 / 限流

    def _peer(self):
        try:
            return str(self.client_address[0] or "")
        except (IndexError, TypeError, AttributeError):
            return ""

    def _is_local(self):
        """本机浏览器（编辑器页面）免票；Cloudflare 隧道回源不算本机。

        cloudflared 从 127.0.0.1 回源，若只看 TCP peer，公网请求会被误判成本机、
        直接跳过 token。Cloudflare 一定会带 CF-Connecting-IP，用它把隧道流量区分开。
        """
        return _is_loopback_peer(self._peer()) and not self.headers.get("CF-Connecting-IP")

    def _is_private_peer(self):
        """只允许局域网/本机做 nonce 挑战；公网隧道不暴露这条免口令分支。"""
        try:
            addr = ipaddress.ip_address(self._peer())
        except ValueError:
            return False
        return addr.is_private or addr.is_loopback or addr.is_link_local

    def _client_ip(self):
        """限流键用的真实客户端 IP —— 见模块级 client_ip()。

        和 _peer() 分开：_peer() 是 TCP 层事实（隧道下永远是 127.0.0.1），
        _client_ip() 才是「这一枪是谁打的」，限流必须按后者记。
        """
        return client_ip(self)

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
        """返回 True = 已经回包拒绝了，路由别再走。

        顺序是刻意的：**先鉴权，后限流**。
        隧道回源时所有请求的 TCP peer 都是 127.0.0.1，若先记额度，没口令的人
        随便打几下就能把本机 200 次/天的额度耗光。先 401 就能让未授权请求
        一个计数都不花；带上口令之后仍按每 IP 的额度限流。
        """
        # /identity 允许带 nonce 的「挑战应答」：电脑用口令对 nonce 做 HMAC，
        # 手机拿本地口令验算。这样手机不必把口令发给一个只凭 UDP 广播自称是
        # 已配对电脑的陌生 IP；没有 nonce 时仍走原来的口令鉴权。
        nonce_identity = (
            method == "GET"
            and path == "/identity"
            and not self.headers.get("CF-Connecting-IP")
            and self._is_private_peer()
            and bool((parse_qs(urlsplit(self.path).query).get("nonce") or [""])[0].strip())
        )
        token_ok = _token_ok(self.headers.get(TOKEN_HEADER, ""))
        local = self._is_local()
        if not nonce_identity and not local and not token_ok:
            self._reject(401, "token_required", "核对口令")
            return True
        if method == "POST" and not self._post_origin_ok():
            self._reject(403, "origin_rejected", "只接受本机或局域网页面发起的写请求")
            return True
        # 1.5.2：额度分两档。自家请求（合法口令 / 本机 loopback 免票 / nonce 挑战）
        # 走 VIP 档；公网匿名请求维持原来的低额度。判定必须放在鉴权之后——
        # token_ok 是这里才知道的，而且 401 的请求也已经 return 了，不花计数。
        vip = token_ok or local or nonce_identity
        bucket, budgets = _rate_budget_for(method, path, vip=vip)
        if bucket is not None:
            ok, retry_after, window = rate_limit_take(
                self._client_ip(), budgets=budgets, bucket=bucket
            )
            if not ok:
                self._reject(
                    429,
                    "rate_limited",
                    "请求太密：单 IP 每分钟 %d 次 / 每天 %d 次，%d 秒后再试（%s额度）"
                    % (budgets[0][1], budgets[1][1], retry_after, window),
                    extra_headers={"Retry-After": str(retry_after)},
                )
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
            # 先鉴权再读 body（1.5.0）：未认证来源不该让服务端把最多 4MB 的包读进内存 ——
            # 否则匿名扫端口的流量就能拿来放大内存/带宽。被拒时直接关连接、不读 body：
            # 客户端可能还在写，此时它看到 broken pipe 是预期内的（可接受，也好过放大）。
            if self._guard("POST", path):
                # 拒绝之后关连接前，先把这一段上行 body 读掉（1.5.2）：
                # 带着未读数据 close 会让 Windows 回 RST，客户端看到「连接被重置」
                # 而不是我们的 401/429，手机端会当成网络故障反复重推。
                self._discard_body()
                self.close_connection = True
                return
            body, error = self._read_body()
            if error:
                return self._fail(400, error)
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

    # ------------------------------------------------------------ 接口

    def _api_rate_reset(self):
        """POST /admin/rate-reset（1.5.2）：清空内存里所有限流桶。

        为什么本机也要求口令：这条接口能把别人的额度清零。若 loopback 免票就能调，
        电脑上随便打开的哪个网页（或者隧道另一端的陌生人）一句 fetch 就破了限流。
        所以这里**绕过 _guard 的免票口径，自己再验一次 token**。

        该路径不参与限流计数（见 _rate_budget_for）——被 429 锁死的时候，
        它是唯一还能把自己救出来的入口。
        """
        if not _token_ok(self.headers.get(TOKEN_HEADER, "")):
            self._reject(401, "token_required", "核对口令")
            return
        self._send_json({"ok": True, "cleared": reset_rate_limits()})

    def _api_health(self):
        """GET /health：给 App 握手，也给人 curl 一眼看服务状态。

        - 只报版本 / 协议 / 能力 / 进程信息；**不含口令**，也不把口令写进日志；
        - 鉴权走 do_GET 里那条 _guard，和别的接口同一套：loopback 且无 CF 头免票，
          其余必须带 X-Cardback-Token（App 走隧道时就是这一支）。
        - 不参与限流：_rate_budget_for() 只认写接口和 /sync/*，/health 不在里面。
        """
        payload = {
            "ok": True,
            "service": SERVICE_NAME,
            "version": SERVICE_VERSION,
            "server": SERVER_VERSION,
            "protocol": HEALTH_PROTOCOL,
            "started_at": _iso(_STARTED_AT),
            "uptime_s": max(0, int(time.monotonic() - _STARTED_MONO)),
            "pid": os.getpid(),
            "port": PORT,
            "instance_id": ensure_instance_id(),
            "capabilities": _health_capabilities(),
        }
        payload.update(_tls_public_fields())
        self._send_json(payload)

    def _api_identity(self):
        """GET /identity：给 App 做「同一台电脑」比对。

        走和 /health 一样的鉴权：LAN/隧道来源必须带口令。公开 UDP 应答里虽然也有
        instance_id，但那只是线索；App 必须再带 token 打到这里，拿到同一个 id 才算配对成功。
        """
        payload = {
            "ok": True,
            "service": SERVICE_NAME,
            "version": SERVICE_VERSION,
            "protocol": HEALTH_PROTOCOL,
            "instance_id": ensure_instance_id(),
            "port": PORT,
            "capabilities": _health_capabilities(),
        }
        payload.update(_tls_public_fields())
        # 带 nonce 时额外给一份 HMAC 证明：手机用本地口令验算，验过才把口令
        # 发给这个地址。instance_id 是公开线索，单凭它不能证明「这就是那台电脑」。
        nonce = (parse_qs(urlsplit(self.path).query).get("nonce") or [""])[0].strip()
        if nonce:
            if not re.fullmatch(r"[0-9a-fA-F]{16,64}", nonce):
                return self._fail(400, "nonce 必须是 16-64 位十六进制")
            proof_key = ensure_token().encode("utf-8")
            payload["proof"] = hmac.new(
                proof_key,
                nonce.encode("utf-8"),
                hashlib.sha256,
            ).hexdigest()
            # 把「谁在直接连我」绑进证明：同一 WiFi 的攻击者若伪造 UDP 候选、
            # 把 nonce 转发给真电脑，真电脑会按攻击者 IP 生成 proof_peer，
            # 手机用自己 IP 验不过，就不会把口令发出去。
            peer = self._peer()
            if peer:
                payload["proof_peer"] = hmac.new(
                    proof_key,
                    ("%s|%s" % (nonce, peer)).encode("utf-8"),
                    hashlib.sha256,
                ).hexdigest()
        self._send_json(payload)

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

    # ------------------------------------------------------------ 词卡热更（1.5.0）

    def _serve_cards_manifest(self):
        """GET /cards/manifest.json：原样回发布目录里的 manifest。

        文件不存在时由 _serve_cards_file 里 ensure_published() 现场生成；存在但解析不了
        （被手改坏、或半截写入残留）时也先让 ensure_published() 按 cards 的实际字节重建一次，
        否则 App 会一直拿到坏 manifest，热更永远停在「读不懂」。
        """
        if not _cards_manifest_ok(cards_dir()):
            try:
                _cards_module().ensure_published(cards_dir())
            except Exception as exc:  # noqa: BLE001 - 重建失败也要给出看得懂的原因
                return self._fail(
                    404,
                    "词卡 manifest 不可用（缺失或损坏），自动重建也失败了：%s。"
                    "先在电脑上跑一次 POST /cards/publish，或重启编辑器" % exc,
                )
        return self._serve_cards_file("manifest.json", CARDS_MANIFEST_FILENAME)

    def _serve_cards_json(self):
        """GET /cards/cet6_cards.json：整份词卡包（几 MB，同样不缓存）。"""
        return self._serve_cards_file("词卡包", CARDS_FILENAME)

    def _serve_cards_file(self, what, filename):
        path = os.path.join(cards_dir(), filename)
        if not os.path.isfile(path):
            # 发布包可能还没生成（首次启动）：现场初始化一次，仍失败就回 404 + 中文提示
            try:
                _cards_module().ensure_published(cards_dir())
            except Exception as exc:  # noqa: BLE001 - 初始化失败也要给出看得懂的原因
                return self._fail(
                    404,
                    "词卡还没发布（%s 不存在），自动初始化也失败了：%s。"
                    "先在电脑上跑一次 POST /cards/publish，或重启编辑器" % (what, exc),
                )
        try:
            with open(path, "rb") as fh:
                data = fh.read()
        except FileNotFoundError:
            return self._fail(
                404, "词卡还没发布（%s 不存在）：先在电脑上跑 POST /cards/publish" % what
            )
        except OSError as exc:
            return self._fail(500, "读 %s 失败：%s" % (path, exc))
        # 手机按 manifest 的 version/sha256 决定要不要下；任何缓存都会让「发布了没变」变灵异
        self._send_bytes(data, "application/json; charset=utf-8", cache="no-store")

    def _api_cards_publish(self):
        """POST /cards/publish：重新合并 results 并发布，返回新 manifest。"""
        try:
            manifest = _cards_module().publish(cards_dir())
        except Exception as exc:  # noqa: BLE001 - 失败也要有中文原因；旧发布包不受影响
            return self._fail(500, "词卡发布失败（旧发布包没动，publish 是原子写）：%s" % exc)
        self._send_json({"ok": True, "manifest": manifest})

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

    def _discard_body(self):
        """把还没读的上行 body 丢掉，好让「拒绝之后关连接」变成正常 FIN 而不是 RST。

        1.5.0 起鉴权先于读 body（不想为匿名流量把 4MB 读进内存），被拒时直接 close。
        但客户端往往已经把 body 发出去了：带着未读数据的 socket 一 close，Windows 就
        回 RST，客户端拿到的是 ConnectionAbortedError，而不是我们的 401/429。
        所以关之前把有上限的一小段（DRAIN_LIMIT）读掉——超限就照旧硬关，
        不为了这个把大包读进内存。读也设了短超时，极端客户端卡住不会拖死线程。
        """
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            return
        if length <= 0 or length > DRAIN_LIMIT:
            return
        sock = getattr(self, "connection", None)
        old_timeout = None
        try:
            if sock is not None:
                old_timeout = sock.gettimeout()
                sock.settimeout(0.5)
            self.rfile.read(length)
        except (OSError, ValueError):
            pass
        finally:
            if sock is not None and old_timeout is not None:
                try:
                    sock.settimeout(old_timeout)
                except OSError:
                    pass

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
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            if cache:
                self.send_header("Cache-Control", cache)
            for name, value in (extra_headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self._responded = True
            if data:
                self.wfile.write(data)
        except OSError:
            # 客户端断开、控制台被关掉导致 stdout 写失败等，都不能让请求线程带着
            # “半截响应”继续跑；标成已响应，避免兜底逻辑再写一次。
            self._responded = True

    def _internal_error(self, exc, method, path):
        try:
            import traceback

            self._log(traceback.format_exc().rstrip())
            self._fail(500, "服务端内部错误：%s" % exc)
        except (BrokenPipeError, ConnectionResetError, OSError):
            self._responded = True

    # ------------------------------------------------------------ 日志

    def _log(self, message):
        """stdout 可能已经被关掉（终端退出、父进程重定向到坏管道）。

        这里绝不能把 OSError 放出去：send_response() -> log_request() ->
        log_message() 是在响应头写出之前调用的，一旦抛异常，客户端只会看到
        “Empty reply from server”。日志失败就静默丢弃，服务必须继续回包。
        """
        try:
            print(message, flush=True)
        except (OSError, ValueError):
            pass

    def log_message(self, fmt, *args):
        stamp = datetime.now().strftime("%H:%M:%S")
        self._log("[%s] %s" % (stamp, fmt % args))

    def log_error(self, fmt, *args):
        stamp = datetime.now().strftime("%H:%M:%S")
        self._log("[%s] %s" % (stamp, fmt % args))


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


def _health_capabilities():
    caps = list(CAPABILITIES)
    try:
        # Import the mounted module, never start its workers for a health probe.
        if "rewrite_scoped" in getattr(_rewrite_module(), "CAPABILITIES", ()):
            caps.append("rewrite_scoped")
    except Exception:  # noqa: BLE001 - the editor remains healthy without the rewrite sidecar
        pass
    return caps


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


def start_tls_server(host=None, port=None):
    """起 TLS 监听（1.4.7.2 W4）：同一个 Handler/CardbackServer，多包一层。

    做法是先把 CardbackServer 正常 bind 到 0.0.0.0:8443（或 CARDBACK_TLS_PORT），
    再把它的监听 socket 用 SSLContext 包起来，丢进 daemon 线程 serve_forever。
    Handler、鉴权、/identity?nonce 的 peer 逻辑全部复用，不另写一套。

    任何失败（证书生成、端口占用、ssl 模块异常）都只打印 warning 并返回 None，
    HTTP 监听已经起好了，绝不能被 TLS 拖下水。
    """
    global TLS_HTTPD, TLS_THREAD, TLS_BOUND_PORT, TLS_SPKI_SHA256, TLS_NOT_AFTER
    global TLS_CERT_PATH, TLS_KEY_PATH

    if TLS_HTTPD is not None:  # 重复调用（测试/热路径）直接复用
        return TLS_HTTPD
    if os.environ.get("CARDBACK_DISABLE_TLS") == "1":
        print("  TLS：已按 CARDBACK_DISABLE_TLS=1 关闭（只跑 HTTP）", flush=True)
        return None
    if tls_support is None:
        print("  [warning] TLS 没起来（找不到 tls_support.py），HTTP 照常可用", flush=True)
        return None

    host = TLS_HOST if host is None else host
    port = TLS_PORT if port is None else port
    httpd = None
    try:
        info = tls_support.ensure_certificate(TLS_DIR, detect_lan_ips())
        context = tls_support.make_server_context(info.cert_path, info.key_path)
        httpd = CardbackServer((host, port), Handler)
        try:
            httpd.socket = tls_support.wrap_listening_socket(context, httpd.socket)
        except Exception:
            httpd.server_close()
            raise
        bound_port = int(httpd.server_address[1])
        thread = threading.Thread(
            target=httpd.serve_forever, name="tls-server", daemon=True
        )
        thread.start()
    except Exception as exc:  # noqa: BLE001 - TLS 是增强项，失败不能影响 HTTP
        if httpd is not None:
            try:
                httpd.server_close()
            except OSError:
                pass
        print("  [warning] TLS 没起来（HTTP 照常可用）：%s" % exc, flush=True)
        return None

    TLS_HTTPD = httpd
    TLS_THREAD = thread
    TLS_BOUND_PORT = bound_port
    TLS_SPKI_SHA256 = info.spki_sha256
    TLS_NOT_AFTER = info.not_after_iso
    TLS_CERT_PATH = info.cert_path
    TLS_KEY_PATH = info.key_path
    print(
        "  TLS：端口 %d（自签证书 %s，SPKI %s…）"
        % (bound_port, info.cert_path, info.spki_sha256[:16]),
        flush=True,
    )
    return httpd


def stop_tls_server(httpd=None):
    """关掉 TLS 监听（测试/退出用）；不动 HTTP。"""
    global TLS_HTTPD, TLS_THREAD, TLS_BOUND_PORT, TLS_SPKI_SHA256, TLS_NOT_AFTER
    target = TLS_HTTPD if httpd is None else httpd
    if target is None:
        return
    try:
        target.shutdown()
    except Exception:  # noqa: BLE001
        pass
    try:
        target.server_close()
    except OSError:
        pass
    if target is TLS_HTTPD:
        thread = TLS_THREAD
        TLS_HTTPD = None
        TLS_THREAD = None
        TLS_BOUND_PORT = 0
        TLS_SPKI_SHA256 = ""
        TLS_NOT_AFTER = ""
        if thread is not None:
            thread.join(timeout=3)


def _start_discovery():
    """在局域网里应答 UDP 广播（1.4.7.2 W4）。

    只回「我是谁、在哪个端口」，不回口令、不回数据；手机拿到后再用口令打 /identity
    验证。绑定失败（端口被另一个实例占用）就静默跳过，不影响主 HTTP 服务。
    """
    if os.environ.get("CARDBACK_DISABLE_DISCOVERY") == "1":
        return None
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", DISCOVERY_PORT))
    except OSError:
        sock.close()
        return None

    def loop():
        while True:
            try:
                data, addr = sock.recvfrom(1024)
                if data.strip() != DISCOVERY_MAGIC:
                    continue
                payload = {
                    "ok": True,
                    "service": SERVICE_NAME,
                    "version": SERVICE_VERSION,
                    "protocol": HEALTH_PROTOCOL,
                    "instance_id": ensure_instance_id(),
                    "port": PORT,
                    # UDP 是公开广播，只报「TLS 在哪个端口」；指纹必须走带口令的
                    # /identity 再核对，不能拿广播里的值当 pin。
                    "tls_port": TLS_BOUND_PORT,
                }
                sock.sendto(
                    json.dumps(payload, ensure_ascii=False).encode("utf-8"), addr
                )
            except OSError:
                return
            except Exception:  # noqa: BLE001 - 发现线程不能把服务拖死
                continue

    thread = threading.Thread(target=loop, name="lan-discovery", daemon=True)
    thread.start()
    return sock


def main(argv=None):
    global LAN_URL, PORT, TLS_PORT

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

    # --tls-port 8443 / --tls-port=8443：默认取 CARDBACK_TLS_PORT（或 8443）
    for i, arg in enumerate(args):
        value = None
        if arg == "--tls-port" and i + 1 < len(args):
            value = args[i + 1]
        elif arg.startswith("--tls-port="):
            value = arg.split("=", 1)[1]
        if value is None:
            continue
        try:
            tls_port = int(value)
        except ValueError:
            tls_port = -1
        if not 0 <= tls_port <= 65535:
            print("TLS 端口不对：%s（要给 0-65535 之间的数字）" % value, flush=True)
            _pause_if_frozen()
            return 2
        TLS_PORT = tls_port

    open_browser = "--no-browser" not in args and "--no-open" not in args

    # 进程真的开始干活了，从这一刻算 uptime（/health 的 started_at 用它）
    mark_process_start()

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
    cards_note = _ensure_cards_published()

    lan_ips = detect_lan_ips()
    primary = next((ip for ip in lan_ips if _is_lan_ip(ip)), None)
    if primary:
        LAN_URL = "http://%s:%d/cardback.json" % (primary, PORT)
    else:
        LAN_URL = "http://127.0.0.1:%d/cardback.json" % PORT

    local_url = "http://127.0.0.1:%d/" % PORT
    health_url = "http://127.0.0.1:%d/health" % PORT
    print(
        "卡背编辑器已启动（%s，pid %d）" % (SERVER_VERSION, os.getpid()), flush=True
    )
    print("  浏览器打开：%s" % local_url, flush=True)
    print("  自检：%s" % health_url, flush=True)
    _start_rewrite()
    tls_httpd = start_tls_server()
    if TLS_BOUND_PORT:
        print(
            "  TLS 自检：https://%s:%d/health（自签证书，浏览器会提示不受信任）"
            % (primary or "127.0.0.1", TLS_BOUND_PORT),
            flush=True,
        )
        print("  TLS pin（完整，配对行里已带）: %s" % TLS_SPKI_SHA256, flush=True)
    discovery_sock = _start_discovery()
    if primary:
        print("  手机：粘贴这一行 → %s" % pair_url(), flush=True)
    else:
        print(
            "  手机：粘贴这一行 →（没找到局域网 IP —— 确认电脑连着 WiFi 或网线，且和手机在同一网段）",
            flush=True,
        )
    print("  发布目录：%s" % PUBLISHED_DIR, flush=True)
    print("  词卡发布目录：%s（%s）" % (cards_dir(), cards_note), flush=True)
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
        stop_tls_server(tls_httpd)
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

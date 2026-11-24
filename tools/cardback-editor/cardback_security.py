"""身份、来源与限流策略，以及 HTTP 请求进入路由前的检查。"""

from __future__ import annotations

import hmac
import ipaddress
import json
import os
import secrets
import time
from urllib.parse import urlsplit, parse_qs


def ensure_token(ctx):
    """读已有口令；没有就生成一个落盘（和数据同目录，用户找得到）。"""
    if ctx.TOKEN:
        return ctx.TOKEN
    try:
        with open(ctx.TOKEN_PATH, "r", encoding="utf-8") as fh:
            existing = fh.read().strip()
    except FileNotFoundError:
        existing = ""
    except OSError as exc:
        print("  读口令文件失败（这次用临时口令）：%s" % exc, flush=True)
        existing = ""
    if existing:
        ctx.TOKEN = existing
        return ctx.TOKEN

    token = secrets.token_urlsafe(ctx.TOKEN_LENGTH)
    try:
        with open(ctx.TOKEN_PATH, "w", encoding="utf-8") as fh:
            fh.write(token + "\n")
        try:
            os.chmod(ctx.TOKEN_PATH, 0o600)
        except OSError:
            pass  # Windows 上 chmod 基本无效，失败无所谓
    except OSError as exc:
        print("  口令写不进 %s（这次只在内存里）：%s" % (ctx.TOKEN_PATH, exc), flush=True)
    ctx.TOKEN = token
    return ctx.TOKEN


def ensure_instance_id(ctx):
    """读/生成服务身份；和口令不同，这个可以公开，只用于「这是同一台电脑」的比对。"""
    if ctx.INSTANCE_ID:
        return ctx.INSTANCE_ID
    # 并发首次请求可能同时进来；不锁会出现「文件里是 A、内存里是 B」的串台。
    with ctx._INSTANCE_LOCK:
        if ctx.INSTANCE_ID:
            return ctx.INSTANCE_ID
        try:
            with open(ctx.INSTANCE_PATH, "r", encoding="utf-8") as fh:
                existing = fh.read().strip()
        except FileNotFoundError:
            existing = ""
        except OSError:
            existing = ""
        if existing:
            ctx.INSTANCE_ID = existing
            return ctx.INSTANCE_ID

        instance_id = "cb-" + secrets.token_hex(16)
        try:
            with open(ctx.INSTANCE_PATH, "w", encoding="utf-8") as fh:
                fh.write(instance_id + chr(10))
            try:
                os.chmod(ctx.INSTANCE_PATH, 0o600)
            except OSError:
                pass
        except OSError:
            pass
        ctx.INSTANCE_ID = instance_id
        return ctx.INSTANCE_ID


def _token_ok(ctx, supplied):
    expected = ctx.ensure_token()
    if not supplied or not supplied.strip() or not expected:
        return False
    # 定时比较：别让响应时间泄露口令前缀
    return hmac.compare_digest(supplied.strip(), expected)


def pair_url(ctx):
    """手机要粘贴的那一整行：`http://ip:8765#<口令>`。

    `#` 后面那截客户端从不发送——不进请求行、Referer、代理日志；手机解析后只用
    host:port 和 X-Cardback-Token 头，所以鉴权逻辑（_token_ok / loopback 免票）一行不用改。
    这里不带文件路径，手机端 normalizeUrl 会补 /cardback.json。

    1.4.7.2 W4：TLS 可用时把完整 SPKI pin 和 tls_port 也放进 `#` 片段。pin 是
    证书公钥摘要，不是口令；放在片段里同样不会进请求行/日志。手机把这一行
    当一次性配对输入，先有 pin 才可能自动发现局域网 HTTPS。
    """
    base = ctx.LAN_URL
    suffix = "/cardback.json"
    if base.endswith(suffix):
        base = base[: -len(suffix)]
    fragment = ctx.ensure_token()
    if ctx.TLS_BOUND_PORT and ctx.TLS_SPKI_SHA256:
        fragment += "&pin=%s&tls_port=%d" % (ctx.TLS_SPKI_SHA256, ctx.TLS_BOUND_PORT)
    return "%s#%s" % (base, fragment)


def _is_loopback_peer(peer):
    try:
        return ipaddress.ip_address(peer).is_loopback
    except ValueError:
        return peer in ("localhost", "localhost.")


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


def client_ip(ctx, handler):
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
    if not ctx._is_loopback_peer(peer):
        return peer
    headers = getattr(handler, "headers", None)
    if headers is None:
        return peer
    try:
        direct = ctx._parse_ip(headers.get(ctx.CF_IP_HEADER))
        if direct:
            return direct
        forwarded = headers.get(ctx.FORWARDED_FOR_HEADER) or ""
    except (AttributeError, TypeError):
        return peer
    for chunk in forwarded.split(","):
        hop = ctx._parse_ip(chunk)
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


def _origin_ok(ctx, origin):
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
    if port != ctx.PORT:
        return False
    return ctx._origin_host_ok(parts.hostname)


def _rate_budget_for(ctx, method, path, vip=False):
    """返回 (桶名, 预算)；不需要限流时返回 (None, None)。

    桶分开记，互不挤占：写接口 20/分钟（VIP 120），/sync/* 120/分钟（VIP 240）。
    记录页 5 秒轮询的 /rewrite/status、/rewrite/records 不是 POST /rewrite*，不算。
    `vip=True` 表示这一枪是自家打的（合法口令 / 本机 loopback），拿放宽的那一档。
    """
    if path == ctx.ADMIN_RESET_PATH:
        # 运维自救口：不占额度。被限流锁死时唯一还能用的就是它。
        return None, None
    if (method == "POST" and path.startswith("/rewrite")) or path in ctx.RATE_LIMITED_PATHS:
        return "write", (ctx.RATE_BUDGETS_VIP if vip else ctx.RATE_BUDGETS)
    if path in ctx.SYNC_RATE_LIMITED_PATHS:
        return "sync", (ctx.SYNC_RATE_BUDGETS_VIP if vip else ctx.SYNC_RATE_BUDGETS)
    return None, None


def _rate_limited(ctx, method, path):
    """兼容旧调用：这个请求要不要记一笔限流。"""
    bucket, _budgets = ctx._rate_budget_for(method, path)
    return bucket is not None


def _prune_rate(ctx, now):
    for peer in list(ctx._RATE_HITS):
        buckets = ctx._RATE_HITS[peer].get("buckets", {})
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
            ctx._RATE_HITS.pop(peer, None)


def reset_rate_limits(ctx):
    """清空限流计数（测试 / 排障 / POST /admin/rate-reset 用）。

    返回清掉的桶数（每个「客户端 IP × 桶」算一个），方便调用方自报清了多少。
    """
    with ctx._RATE_LOCK:
        cleared = sum(len(entry.get("buckets", {})) for entry in ctx._RATE_HITS.values())
        ctx._RATE_HITS.clear()
    return cleared


def rate_limit_take(ctx, peer, now=None, budgets=None, bucket="default"):
    """按 IP + 桶记一笔。返回 (ok, retry_after_seconds, 窗口名)。"""
    now = time.time() if now is None else now
    budgets = ctx.RATE_BUDGETS if budgets is None else budgets
    with ctx._RATE_LOCK:
        if len(ctx._RATE_HITS) > 256:
            ctx._prune_rate(now)
        entry = ctx._RATE_HITS.get(peer)
        if entry is None:
            entry = {"buckets": {}}
            ctx._RATE_HITS[peer] = entry
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


class RequestGuard:
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
        return self.context._is_loopback_peer(self._peer()) and not self.headers.get("CF-Connecting-IP")

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
        return self.context.client_ip(self)

    def _post_origin_ok(self):
        """挡跨站 POST —— 尤其是 text/plain 那种不用预检的「简单请求」。"""
        origin = (self.headers.get("Origin") or "").strip()
        if origin:
            return self.context._origin_ok(origin)
        referer = (self.headers.get("Referer") or "").strip()
        if referer:
            return referer.lower().startswith("file://") or self.context._origin_ok(referer)
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
        token_ok = self.context._token_ok(self.headers.get(self.context.TOKEN_HEADER, ""))
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
        bucket, budgets = self.context._rate_budget_for(method, path, vip=vip)
        if bucket is not None:
            ok, retry_after, window = self.context.rate_limit_take(
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

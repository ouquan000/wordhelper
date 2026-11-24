"""局域网地址选择、TLS 监听与 UDP 发现。"""

from __future__ import annotations

import ipaddress
import json
import os
import re
import socket
import subprocess
import threading


def _is_lan_ip(ctx, ip):
    try:
        addr = ipaddress.IPv4Address(ip)
    except (ValueError, TypeError):
        return False
    if addr.is_loopback or addr.is_link_local or addr.is_unspecified:
        return False
    return any(addr in net for net in ctx._LAN_NETS)


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


def detect_lan_ips(ctx):
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
    if not any(ctx._is_lan_ip(ip) for ip in found):
        for ip in ctx._ipconfig_ips():
            add(ip)

    lan = [ip for ip in found if ctx._is_lan_ip(ip)]
    others = [ip for ip in found if not ctx._is_lan_ip(ip)]
    return lan + others


def _tls_public_fields(ctx):
    """/health 和 /identity 共用：App 靠这三个字段找 TLS 并固定证书。

    TLS 没起来时 tls_port=0、指纹/到期时间为 None —— 让 App 明确知道「这台
    电脑这次没开 TLS」，而不是拿一个连不上的端口去重试。
    """
    return {
        "tls_port": ctx.TLS_BOUND_PORT,
        "tls_spki_sha256": ctx.TLS_SPKI_SHA256 or None,
        "tls_not_after": ctx.TLS_NOT_AFTER or None,
    }


def start_tls_server(ctx, host=None, port=None):
    """起 TLS 监听（1.4.7.2 W4）：同一个 Handler/CardbackServer，多包一层。

    做法是先把 CardbackServer 正常 bind 到 0.0.0.0:8443（或 CARDBACK_TLS_PORT），
    再把它的监听 socket 用 SSLContext 包起来，丢进 daemon 线程 serve_forever。
    Handler、鉴权、/identity?nonce 的 peer 逻辑全部复用，不另写一套。

    任何失败（证书生成、端口占用、ssl 模块异常）都只打印 warning 并返回 None，
    HTTP 监听已经起好了，绝不能被 TLS 拖下水。
    """

    if ctx.TLS_HTTPD is not None:  # 重复调用（测试/热路径）直接复用
        return ctx.TLS_HTTPD
    if os.environ.get("CARDBACK_DISABLE_TLS") == "1":
        print("  TLS：已按 CARDBACK_DISABLE_TLS=1 关闭（只跑 HTTP）", flush=True)
        return None
    if ctx.tls_support is None:
        print("  [warning] TLS 没起来（找不到 tls_support.py），HTTP 照常可用", flush=True)
        return None

    host = ctx.TLS_HOST if host is None else host
    port = ctx.TLS_PORT if port is None else port
    httpd = None
    try:
        info = ctx.tls_support.ensure_certificate(ctx.TLS_DIR, ctx.detect_lan_ips())
        context = ctx.tls_support.make_server_context(info.cert_path, info.key_path)
        httpd = ctx.CardbackServer((host, port), ctx.Handler)
        try:
            httpd.socket = ctx.tls_support.wrap_listening_socket(context, httpd.socket)
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

    ctx.TLS_HTTPD = httpd
    ctx.TLS_THREAD = thread
    ctx.TLS_BOUND_PORT = bound_port
    ctx.TLS_SPKI_SHA256 = info.spki_sha256
    ctx.TLS_NOT_AFTER = info.not_after_iso
    ctx.TLS_CERT_PATH = info.cert_path
    ctx.TLS_KEY_PATH = info.key_path
    print(
        "  TLS：端口 %d（自签证书 %s，SPKI %s…）"
        % (bound_port, info.cert_path, info.spki_sha256[:16]),
        flush=True,
    )
    return httpd


def stop_tls_server(ctx, httpd=None):
    """关掉 TLS 监听（测试/退出用）；不动 HTTP。"""
    target = ctx.TLS_HTTPD if httpd is None else httpd
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
    if target is ctx.TLS_HTTPD:
        thread = ctx.TLS_THREAD
        ctx.TLS_HTTPD = None
        ctx.TLS_THREAD = None
        ctx.TLS_BOUND_PORT = 0
        ctx.TLS_SPKI_SHA256 = ""
        ctx.TLS_NOT_AFTER = ""
        if thread is not None:
            thread.join(timeout=3)


def _start_discovery(ctx):
    """在局域网里应答 UDP 广播（1.4.7.2 W4）。

    只回「我是谁、在哪个端口」，不回口令、不回数据；手机拿到后再用口令打 /identity
    验证。绑定失败（端口被另一个实例占用）就静默跳过，不影响主 HTTP 服务。
    """
    if os.environ.get("CARDBACK_DISABLE_DISCOVERY") == "1":
        return None
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", ctx.DISCOVERY_PORT))
    except OSError:
        sock.close()
        return None

    def loop():
        while True:
            try:
                data, addr = sock.recvfrom(1024)
                if data.strip() != ctx.DISCOVERY_MAGIC:
                    continue
                payload = {
                    "ok": True,
                    "service": ctx.SERVICE_NAME,
                    "version": ctx.SERVICE_VERSION,
                    "protocol": ctx.HEALTH_PROTOCOL,
                    "instance_id": ctx.ensure_instance_id(),
                    "port": ctx.PORT,
                    # UDP 是公开广播，只报「TLS 在哪个端口」；指纹必须走带口令的
                    # /identity 再核对，不能拿广播里的值当 pin。
                    "tls_port": ctx.TLS_BOUND_PORT,
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

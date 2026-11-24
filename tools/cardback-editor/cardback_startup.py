"""命令行解析、启动顺序与关闭流程。"""

from __future__ import annotations

import json
import os
import sys
import urllib.request
import webbrowser


def _set_console_title(title):
    """.bat 里设过一次，这里再兜一手（中文标题在 cmd 里容易乱码）。"""
    if os.name != "nt":
        return
    try:
        import ctypes

        ctypes.windll.kernel32.SetConsoleTitleW(title)
    except Exception:  # noqa: BLE001
        pass


def _looks_like_our_server(ctx):
    try:
        with urllib.request.urlopen(
            "http://127.0.0.1:%d/api/state" % ctx.PORT, timeout=1.5
        ) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        return isinstance(data, dict) and data.get("ok") is True and "revision" in data
    except Exception:  # noqa: BLE001
        return False


def _report_port_busy(ctx, exc):
    print("", flush=True)
    print("启动失败：端口 %d 已被占用（%s）" % (ctx.PORT, exc), flush=True)
    if ctx._looks_like_our_server():
        print(
            "  好像已经有一个卡背编辑器在跑了：http://127.0.0.1:%d/" % ctx.PORT, flush=True
        )
        print("  直接用它就行，不用再启动一个。", flush=True)
    else:
        print(
            "  查一下是谁占了端口：netstat -ano | findstr :%d" % ctx.PORT, flush=True
        )
        print("  关掉它之后，再双击「启动编辑器.bat」。", flush=True)
    print("", flush=True)
    return 1


def _pause_if_frozen(ctx):
    """双击 exe 时窗口是黑框，报完错一闪就没了 —— 停下来等人按回车。
    用 python server.py 跑的时候不拦（终端里能看见，Ctrl+C 也方便）。"""
    if not ctx.FROZEN:
        return
    try:
        input("按回车关闭这个窗口…")
    except (EOFError, OSError, KeyboardInterrupt):
        pass


def main(ctx, argv=None):

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
            ctx._pause_if_frozen()
            return 2
        ctx.PORT = port

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
            ctx._pause_if_frozen()
            return 2
        ctx.TLS_PORT = tls_port

    open_browser = "--no-browser" not in args and "--no-open" not in args

    # 进程真的开始干活了，从这一刻算 uptime（/health 的 started_at 用它）
    ctx.mark_process_start()

    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass
    ctx._set_console_title("卡背编辑器")

    # 先抢端口：端口占着就直接说人话退出，别去动 published/
    try:
        httpd = ctx.CardbackServer((ctx.HOST, ctx.PORT), ctx.Handler)
    except OSError as exc:
        code = ctx._report_port_busy(exc)
        ctx._pause_if_frozen()
        return code

    os.makedirs(ctx.HISTORY_DIR, exist_ok=True)
    notes = ctx.ensure_initialized()
    ctx.ensure_token()
    cards_note = ctx._ensure_cards_published()

    lan_ips = ctx.detect_lan_ips()
    primary = next((ip for ip in lan_ips if ctx._is_lan_ip(ip)), None)
    if primary:
        ctx.LAN_URL = "http://%s:%d/cardback.json" % (primary, ctx.PORT)
    else:
        ctx.LAN_URL = "http://127.0.0.1:%d/cardback.json" % ctx.PORT

    local_url = "http://127.0.0.1:%d/" % ctx.PORT
    health_url = "http://127.0.0.1:%d/health" % ctx.PORT
    print(
        "卡背编辑器已启动（%s，pid %d）" % (ctx.SERVER_VERSION, os.getpid()), flush=True
    )
    print("  浏览器打开：%s" % local_url, flush=True)
    print("  自检：%s" % health_url, flush=True)
    ctx._start_rewrite()
    tls_httpd = ctx.start_tls_server()
    if ctx.TLS_BOUND_PORT:
        print(
            "  TLS 自检：https://%s:%d/health（自签证书，浏览器会提示不受信任）"
            % (primary or "127.0.0.1", ctx.TLS_BOUND_PORT),
            flush=True,
        )
        print("  TLS pin（完整，配对行里已带）: %s" % ctx.TLS_SPKI_SHA256, flush=True)
    discovery_sock = ctx._start_discovery()
    if primary:
        print("  手机：粘贴这一行 → %s" % ctx.pair_url(), flush=True)
    else:
        print(
            "  手机：粘贴这一行 →（没找到局域网 IP —— 确认电脑连着 WiFi 或网线，且和手机在同一网段）",
            flush=True,
        )
    print("  发布目录：%s" % ctx.PUBLISHED_DIR, flush=True)
    print("  词卡发布目录：%s（%s）" % (ctx.cards_dir(), cards_note), flush=True)
    # 口令不再单独打印：它就在上面那一行末尾，复制一次就够。
    print("    （电脑上开编辑器不用填；口令文件 %s，删掉再启动会换新的）" % ctx.TOKEN_PATH, flush=True)
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
        ctx.stop_tls_server(tls_httpd)
        try:
            httpd.server_close()
        except OSError:
            pass
    return 0

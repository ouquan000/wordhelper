"""静态页面、请求体读取与 HTTP 响应。"""

from __future__ import annotations

import html
import json
import mimetypes
import os
import traceback
from datetime import datetime
from urllib.parse import unquote, urlsplit


def _placeholder_page(ctx, note=""):
    hint = '<p class="warn">%s</p>' % html.escape(note) if note else ""
    base = html.escape(ctx.BASE_DIR)
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


class HTTPTransport:
    def do_GET(self):
        try:
            self._responded = False
            path = urlsplit(self.path).path or "/"
            if self._guard("GET", path):
                return
            self._route_get(path)
        except self.context.ApiError as exc:
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
        except self.context.ApiError as exc:
            self._fail(exc.status, exc.message)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:  # noqa: BLE001
            self._internal_error(exc, "POST", self.path)

    def _serve_index(self):
        try:
            with open(self.context.INDEX_PATH, "rb") as fh:
                data = fh.read()
        except FileNotFoundError:
            data = None
        except OSError as exc:
            return self._send_bytes(
                self.context._placeholder_page("读 index.html 出错：%s" % exc).encode("utf-8"),
                "text/html; charset=utf-8",
            )
        if data is None:
            return self._send_bytes(
                self.context._placeholder_page().encode("utf-8"), "text/html; charset=utf-8"
            )
        self._send_bytes(data, "text/html; charset=utf-8")

    def _read_static(self, path):
        rel = unquote(path).lstrip("/")
        if not rel or rel.startswith(".") or "/" in rel or "\\" in rel:
            return None
        full = os.path.join(self.context.BUNDLE_DIR, rel)
        if not os.path.isfile(full):
            return None
        ext = os.path.splitext(rel)[1].lower()
        if ext not in self.context.STATIC_EXTS:
            return None
        try:
            with open(full, "rb") as fh:
                data = fh.read()
        except OSError:
            return None
        return data, self.context._content_type(rel, ext)

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
        if length <= 0 or length > self.context.DRAIN_LIMIT:
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
        if length > self.context.MAX_BODY:
            return b"", "请求体太大（%d 字节，上限 %d）" % (length, self.context.MAX_BODY)
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

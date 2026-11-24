"""同源重写服务的惰性导入、能力探测、请求转发及启动。"""

from __future__ import annotations

import os
import sys


def _rewrite_module(ctx):
    if ctx._REWRITE["module"] is None:
        for path in ctx._REWRITE_DIRS:
            if not os.path.isfile(os.path.join(path, "service.py")):
                continue
            path = os.path.abspath(path)
            if path not in sys.path:
                sys.path.insert(0, path)
            import service as rewrite_service  # noqa: PLC0415 - 懒加载，编辑器不依赖它

            ctx._REWRITE["module"] = rewrite_service
            break
        else:
            raise RuntimeError("找不到 tools/rewrite-service/service.py")
    return ctx._REWRITE["module"]


def _health_capabilities(ctx):
    caps = list(ctx.CAPABILITIES)
    try:
        # Import the mounted module, never start its workers for a health probe.
        if "rewrite_scoped" in getattr(ctx._rewrite_module(), "CAPABILITIES", ()):
            caps.append("rewrite_scoped")
    except Exception:  # noqa: BLE001 - the editor remains healthy without the rewrite sidecar
        pass
    return caps


def _serve_rewrite(ctx, handler, method, body=b""):
    """接得住就回包返回 True，不是重写服务的路径返回 False。"""
    result = ctx._rewrite_module().respond(method, handler.path, body)
    if result is None:
        return False
    status, content_type, payload = result
    handler._send_bytes(payload, content_type, status=status)
    return True


def _start_rewrite(ctx):
    try:
        ctx._rewrite_module().startup()
        print("  重写面板：http://127.0.0.1:%d/rewrite/" % ctx.PORT, flush=True)
    except Exception as exc:  # noqa: BLE001 - 重写服务坏了也要让编辑器起来
        print("  重写服务没起来（编辑器照常可用）：%r" % exc, flush=True)


class RewriteAPI:
    def _rewrite_passthrough(self, method, body=b""):
        """路径拼错时按 404 回，别让它掉进静态文件兜底。"""
        try:
            if self.context._serve_rewrite(self, method, body):
                return
        except Exception as exc:  # noqa: BLE001 - 重写服务坏了不能连带编辑器一起挂
            return self._fail(500, "重写服务出错：%s" % exc)
        return self._fail(404, "没有这个路径：%s" % self.path)

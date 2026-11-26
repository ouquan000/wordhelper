"""词卡包、编辑发布、同步、健康与身份接口。"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import time
from urllib.parse import parse_qs, urlsplit


def cards_dir(ctx):
    """词卡发布目录：/cards/* 三个接口和启动时的 ensure_published 都用这一份。"""
    return os.path.join(ctx.PUBLISHED_DIR, ctx.CARDS_SUBDIR)


def _cards_module():
    """惰性导入 card_publisher —— 发布模块坏了不能连带整个编辑器起不来。"""
    import card_publisher

    return card_publisher


def _ensure_cards_published(ctx):
    """启动时保证词卡发布包就绪，返回一句状态（main 打进启动日志）。

    失败只打印警告、返回「未就绪」——词卡发布坏了不能挡住编辑器启动。
    """
    try:
        manifest = ctx._cards_module().ensure_published(ctx.cards_dir())
        return "manifest v%s，%s 词" % (manifest.get("version"), manifest.get("count"))
    except Exception as exc:  # noqa: BLE001 - 绝不让词卡连累主服务启动
        print("  [警告] 词卡发布包初始化失败（不影响编辑器）：%s" % exc, flush=True)
        return "还没就绪（%s）" % exc


def _cards_manifest_ok(ctx, publish_dir):
    """manifest 存在且能解析成 JSON 对象才算「可发」。缺字段（老版本/手改）不在此判，
    ensure_published 那边会按 sha256 是否非空判断要不要重建。"""
    path = os.path.join(publish_dir, ctx.CARDS_MANIFEST_FILENAME)
    try:
        with open(path, "rb") as fh:
            payload = json.loads(fh.read().decode("utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    return isinstance(payload, dict)


def sync_db(ctx):
    """惰性打开，别在 import 阶段就写文件（测试和 exe 打包都省事）。"""
    if ctx._SYNC is None:
        import sync_store

        ctx._SYNC = sync_store.SyncStore(ctx.SYNC_PATH)
    return ctx._SYNC


class EditorAPI:
    def _api_rate_reset(self):
        """POST /admin/rate-reset（1.5.2）：清空内存里所有限流桶。

        为什么本机也要求口令：这条接口能把别人的额度清零。若 loopback 免票就能调，
        电脑上随便打开的哪个网页（或者隧道另一端的陌生人）一句 fetch 就破了限流。
        所以这里**绕过 _guard 的免票口径，自己再验一次 token**。

        该路径不参与限流计数（见 _rate_budget_for）——被 429 锁死的时候，
        它是唯一还能把自己救出来的入口。
        """
        if not self.context._token_ok(self.headers.get(self.context.TOKEN_HEADER, "")):
            self._reject(401, "token_required", "核对口令")
            return
        self._send_json({"ok": True, "cleared": self.context.reset_rate_limits()})

    def _api_health(self):
        """GET /health：给 App 握手，也给人 curl 一眼看服务状态。

        - 只报版本 / 协议 / 能力 / 进程信息；**不含口令**，也不把口令写进日志；
        - 鉴权走 do_GET 里那条 _guard，和别的接口同一套：loopback 且无 CF 头免票，
          其余必须带 X-Cardback-Token（App 走隧道时就是这一支）。
        - 不参与限流：_rate_budget_for() 只认写接口和 /sync/*，/health 不在里面。
        """
        payload = {
            "ok": True,
            "service": self.context.SERVICE_NAME,
            "version": self.context.SERVICE_VERSION,
            "server": self.context.SERVER_VERSION,
            "protocol": self.context.HEALTH_PROTOCOL,
            "started_at": self.context._iso(self.context._STARTED_AT),
            "uptime_s": max(0, int(time.monotonic() - self.context._STARTED_MONO)),
            "pid": os.getpid(),
            "port": self.context.PORT,
            "instance_id": self.context.ensure_instance_id(),
            "capabilities": self.context._health_capabilities(),
        }
        payload.update(self.context._tls_public_fields())
        self._send_json(payload)

    def _api_identity(self):
        """GET /identity：给 App 做「同一台电脑」比对。

        走和 /health 一样的鉴权：LAN/隧道来源必须带口令。公开 UDP 应答里虽然也有
        instance_id，但那只是线索；App 必须再带 token 打到这里，拿到同一个 id 才算配对成功。
        """
        payload = {
            "ok": True,
            "service": self.context.SERVICE_NAME,
            "version": self.context.SERVICE_VERSION,
            "protocol": self.context.HEALTH_PROTOCOL,
            "instance_id": self.context.ensure_instance_id(),
            "port": self.context.PORT,
            "capabilities": self.context._health_capabilities(),
        }
        payload.update(self.context._tls_public_fields())
        # 带 nonce 时额外给一份 HMAC 证明：手机用本地口令验算，验过才把口令
        # 发给这个地址。instance_id 是公开线索，单凭它不能证明「这就是那台电脑」。
        nonce = (parse_qs(urlsplit(self.path).query).get("nonce") or [""])[0].strip()
        if nonce:
            if not re.fullmatch(r"[0-9a-fA-F]{16,64}", nonce):
                return self._fail(400, "nonce 必须是 16-64 位十六进制")
            proof_key = self.context.ensure_token().encode("utf-8")
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
        payload.update(self.context.state_snapshot())
        payload["lanUrl"] = self.context.LAN_URL
        # 编辑页「一键复制」要的就是这一整行；lanUrl 留着给旧页面/调试用。
        payload["pairUrl"] = self.context.pair_url()
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
            result = self.context.sync_db().pull(since_rev)
        except Exception as exc:  # noqa: BLE001 - 存储坏了要让人看得见
            return self._fail(500, "同步库读不出来：%s" % exc)
        payload = {"ok": True}
        payload.update(result)
        self._send_json(payload, compress_large=True)

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
            from upcoming_words import parse_upcoming
            metadata = parse_upcoming(data)
        except ValueError as exc:
            return self._fail(400, str(exc))
        try:
            result = self.context.sync_db().push(device_id, base_rev, ops, **metadata)
        except Exception as exc:  # noqa: BLE001
            return self._fail(500, "同步库写不进去：%s" % exc)
        payload = {"ok": True}
        payload.update(result)
        self._send_json(payload)

    def _api_publish(self, body):
        data, error = self._parse_json_body(body, "发布")
        if error:
            return self._fail(400, error)

        errors, warnings = self.context.validate_config(data)
        if errors:
            return self._fail(400, self.context.format_errors(errors))

        revision = self.context.publish_config(data)
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
        if not self.context._is_int(revision):
            return self._fail(400, 'body 里要有整数 revision，例如 {"revision": 3}')

        new_revision = self.context.rollback_to(revision)
        self._send_json({"ok": True, "revision": new_revision, "restoredFrom": revision})

    def _serve_cards_manifest(self):
        """GET /cards/manifest.json：原样回发布目录里的 manifest。

        文件不存在时由 _serve_cards_file 里 ensure_published() 现场生成；存在但解析不了
        （被手改坏、或半截写入残留）时也先让 ensure_published() 按 cards 的实际字节重建一次，
        否则 App 会一直拿到坏 manifest，热更永远停在「读不懂」。
        """
        if not self.context._cards_manifest_ok(self.context.cards_dir()):
            try:
                self.context._cards_module().ensure_published(self.context.cards_dir())
            except Exception as exc:  # noqa: BLE001 - 重建失败也要给出看得懂的原因
                return self._fail(
                    404,
                    "词卡 manifest 不可用（缺失或损坏），自动重建也失败了：%s。"
                    "先在电脑上跑一次 POST /cards/publish，或重启编辑器" % exc,
                )
        return self._serve_cards_file("manifest.json", self.context.CARDS_MANIFEST_FILENAME)

    def _serve_cards_json(self):
        """GET /cards/cet6_cards.json：整份词卡包（几 MB，同样不缓存）。"""
        return self._serve_cards_file("词卡包", self.context.CARDS_FILENAME)

    def _serve_cards_file(self, what, filename):
        path = os.path.join(self.context.cards_dir(), filename)
        if not os.path.isfile(path):
            # 发布包可能还没生成（首次启动）：现场初始化一次，仍失败就回 404 + 中文提示
            try:
                self.context._cards_module().ensure_published(self.context.cards_dir())
            except Exception as exc:  # noqa: BLE001 - 初始化失败也要给出看得懂的原因
                return self._fail(
                    404,
                    "词卡还没发布（%s 不存在），自动初始化也失败了：%s。"
                    "先在电脑上跑一次 POST /cards/publish，或重启编辑器" % (what, exc),
                )
        try:
            data, headers = self._read_json_file(path)
        except FileNotFoundError:
            return self._fail(
                404, "词卡还没发布（%s 不存在）：先在电脑上跑 POST /cards/publish" % what
            )
        except OSError as exc:
            return self._fail(500, "读 %s 失败：%s" % (path, exc))
        # 手机按 manifest 的 version/sha256 决定要不要下；任何缓存都会让「发布了没变」变灵异
        self._send_bytes(data, "application/json; charset=utf-8", extra_headers=headers)

    def _api_cards_publish(self):
        """POST /cards/publish：重新合并 results 并发布，返回新 manifest。"""
        try:
            manifest = self.context._cards_module().publish(self.context.cards_dir())
        except Exception as exc:  # noqa: BLE001 - 失败也要有中文原因；旧发布包不受影响
            return self._fail(500, "词卡发布失败（旧发布包没动，publish 是原子写）：%s" % exc)
        self._send_json({"ok": True, "manifest": manifest})

    def _serve_config(self):
        try:
            data, headers = self._read_json_file(self.context.CONFIG_PATH)
        except FileNotFoundError:
            return self._fail(404, "published/cardback.json 还没生成，重启一下服务")
        except OSError as exc:
            return self._fail(500, "读 published/cardback.json 失败：%s" % exc)
        # 手机每次都该拿到最新的，任何缓存都可能让「发布完没变」变成灵异事件
        self._send_bytes(data, "application/json; charset=utf-8", extra_headers=headers)

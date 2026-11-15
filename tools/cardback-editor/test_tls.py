#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""局域网 TLS 服务端自测（1.4.7.2 W4）。

覆盖：
  1. 自签证书落盘、复用，SAN 含 localhost/127.0.0.1/::1/局域网 IPv4；
  2. 同一个 TLS 端口用自签证书真连（证书可当 CA 验，SPKI 与 JSON 一致）；
  3. TLS 上的 /identity?nonce= 仍 200，proof / proof_peer 与口令 HMAC 一致；
  4. HTTP 的 /health 与 /identity 都带 tls_port / tls_spki_sha256 / tls_not_after；
  5. UDP 发现应答带 tls_port，但不带任何指纹（UDP 不可信）；
  6. TLS 启动失败（mock 证书生成抛错）只 warning，HTTP 仍正常服务。

不碰真实的 8765 / published/ / .cardback_token：全部走临时目录 + 系统分配端口。
用法：python -m unittest test_tls -v
"""

from __future__ import annotations

import hashlib
import hmac
import http.client
import json
import os
import socket
import ssl
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import server  # noqa: E402
import tls_support  # noqa: E402


def configure_data_dir(tmp):
    """把模块级落盘路径全指到临时目录，绝不碰真实数据。"""
    server.PUBLISHED_DIR = os.path.join(tmp, "published")
    server.HISTORY_DIR = os.path.join(server.PUBLISHED_DIR, "history")
    server.CONFIG_PATH = os.path.join(server.PUBLISHED_DIR, "cardback.json")
    server.STATE_PATH = os.path.join(server.PUBLISHED_DIR, "state.json")
    server.TOKEN_PATH = os.path.join(tmp, ".cardback_token")
    server.INSTANCE_PATH = os.path.join(tmp, ".cardback_instance")
    server.INSTANCE_ID = ""
    server.SYNC_PATH = os.path.join(tmp, "sync.json")
    server.TOKEN = ""
    server._SYNC = None
    server.TLS_DIR = os.path.join(tmp, ".cardback_tls")
    server.TLS_CERT_PATH = os.path.join(server.TLS_DIR, "cert.pem")
    server.TLS_KEY_PATH = os.path.join(server.TLS_DIR, "key.pem")
    server.TLS_HTTPD = None
    server.TLS_THREAD = None
    server.TLS_BOUND_PORT = 0
    server.TLS_SPKI_SHA256 = ""
    server.TLS_NOT_AFTER = ""
    server.ensure_initialized()


def http_json(port, path, context=None, headers=None, timeout=5):
    """返回 (status, headers, text)。HTTP/HTTPS 都走 http.client，绕开系统代理。"""
    if context is None:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    else:
        conn = http.client.HTTPSConnection(
            "127.0.0.1", port, context=context, timeout=timeout
        )
    try:
        conn.request("GET", path, headers=headers or {})
        resp = conn.getresponse()
        body = resp.read().decode("utf-8", "replace")
        return resp.status, dict(resp.getheaders()), body
    finally:
        conn.close()


def spki_from_peer_der(der):
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    cert = x509.load_der_x509_certificate(der)
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(spki).hexdigest()


class CertificateTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="cardback-tls-cert-")
        self.dir = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def test_generate_persist_and_san(self):
        lan_ips = ["192.168.1.50", "10.1.2.3", "127.0.0.1", "8.8.8.8"]
        info = tls_support.ensure_certificate(self.dir, lan_ips)
        self.assertTrue(os.path.isfile(info.cert_path))
        self.assertTrue(os.path.isfile(info.key_path))
        self.assertRegex(info.spki_sha256, r"^[0-9a-f]{64}$")
        self.assertIsInstance(info.not_after, datetime)
        self.assertGreater(info.not_after, datetime.now(timezone.utc))

        from cryptography import x509

        with open(info.cert_path, "rb") as fh:
            cert = x509.load_pem_x509_certificate(fh.read())
        san = cert.extensions.get_extension_for_class(
            x509.SubjectAlternativeName
        ).value
        dns = set(san.get_values_for_type(x509.DNSName))
        ips = {str(ip) for ip in san.get_values_for_type(x509.IPAddress)}
        self.assertIn("localhost", dns)
        self.assertIn("127.0.0.1", ips)
        self.assertIn("::1", ips)
        self.assertIn("192.168.1.50", ips)
        self.assertIn("10.1.2.3", ips)
        # 公网地址不进 SAN：这张证书只给局域网
        self.assertNotIn("8.8.8.8", ips)

    def test_second_call_reuses_same_certificate(self):
        first = tls_support.ensure_certificate(self.dir, ["192.168.1.50"])
        second = tls_support.ensure_certificate(self.dir, ["192.168.1.50"])
        self.assertEqual(first.spki_sha256, second.spki_sha256)
        self.assertEqual(
            os.path.getsize(first.cert_path), os.path.getsize(second.cert_path)
        )

    def test_corrupt_certificate_is_regenerated(self):
        good = tls_support.ensure_certificate(self.dir, ["192.168.1.50"])
        with open(good.cert_path, "w", encoding="utf-8") as fh:
            fh.write("not a certificate")
        repaired = tls_support.ensure_certificate(self.dir, ["192.168.1.50"])
        self.assertRegex(repaired.spki_sha256, r"^[0-9a-f]{64}$")
        self.assertNotEqual(good.spki_sha256, repaired.spki_sha256)

    def test_openssl_fingerprint_matches_cryptography(self):
        if not tls_support.shutil.which("openssl"):
            self.skipTest("没有 openssl 命令")
        info = tls_support.ensure_certificate(self.dir, ["192.168.1.50"])
        crypto_spki, crypto_not_after = tls_support._certificate_info_with_cryptography(
            info.cert_path, info.key_path
        )
        openssl_spki, openssl_not_after = tls_support._certificate_info_with_openssl(
            info.cert_path, info.key_path
        )
        self.assertEqual(crypto_spki, openssl_spki)
        self.assertEqual(
            int(crypto_not_after.timestamp()), int(openssl_not_after.timestamp())
        )


class TlsLiveServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="cardback-tls-live-")
        configure_data_dir(cls._tmp.name)
        cls.token = server.ensure_token()
        cls.httpd = server.CardbackServer(("127.0.0.1", 0), server.Handler)
        server.PORT = int(cls.httpd.server_address[1])
        server.LAN_URL = "http://127.0.0.1:%d/cardback.json" % server.PORT
        cls.http_thread = threading.Thread(
            target=cls.httpd.serve_forever, daemon=True
        )
        cls.http_thread.start()
        cls.tls_httpd = server.start_tls_server(host="127.0.0.1", port=0)
        if cls.tls_httpd is None:
            cls.tearDownClass()
            raise unittest.SkipTest("TLS 没起来，跳过在线用例")

    @classmethod
    def tearDownClass(cls):
        server.stop_tls_server(getattr(cls, "tls_httpd", None))
        for attr in ("httpd",):
            httpd = getattr(cls, attr, None)
            if httpd is not None:
                try:
                    httpd.shutdown()
                except Exception:  # noqa: BLE001
                    pass
                try:
                    httpd.server_close()
                except OSError:
                    pass
        thread = getattr(cls, "http_thread", None)
        if thread is not None:
            thread.join(timeout=3)
        tmp = getattr(cls, "_tmp", None)
        if tmp is not None:
            tmp.cleanup()

    def _verified_context(self):
        # 自签证书自己就是信任锚；主机名 127.0.0.1 在 SAN 里
        return ssl.create_default_context(cafile=server.TLS_CERT_PATH)

    def test_tls_identity_nonce_and_peer_spki(self):
        nonce = "0123456789abcdef0123456789abcdef"
        context = self._verified_context()
        conn = http.client.HTTPSConnection(
            "127.0.0.1", server.TLS_BOUND_PORT, context=context, timeout=5
        )
        try:
            conn.request("GET", "/identity?nonce=" + nonce)
            resp = conn.getresponse()
            body = resp.read().decode("utf-8")
            peer_der = conn.sock.getpeercert(binary_form=True)
            status = resp.status
        finally:
            conn.close()

        self.assertEqual(status, 200, body[:200])
        data = json.loads(body)
        self.assertTrue(data.get("ok"))
        self.assertEqual(data.get("tls_port"), server.TLS_BOUND_PORT)
        self.assertEqual(data.get("tls_spki_sha256"), server.TLS_SPKI_SHA256)
        self.assertEqual(data.get("tls_not_after"), server.TLS_NOT_AFTER)

        want_proof = hmac.new(
            self.token.encode("utf-8"), nonce.encode("utf-8"), hashlib.sha256
        ).hexdigest()
        self.assertEqual(data.get("proof"), want_proof)
        # TLS 连接时 peer 仍是手机 IP（本机测试就是 127.0.0.1），proof_peer 要绑它
        want_peer = hmac.new(
            self.token.encode("utf-8"),
            ("%s|127.0.0.1" % nonce).encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        self.assertEqual(data.get("proof_peer"), want_peer)

        # 对端证书的 SPKI 必须与 JSON 里报的一致（App 固定就是这个值）
        self.assertEqual(spki_from_peer_der(peer_der), data.get("tls_spki_sha256"))

    def test_http_health_and_identity_report_tls_fields(self):
        want_spki = tls_support.certificate_spki_sha256(server.TLS_CERT_PATH)
        for path in ("/health", "/identity"):
            status, _headers, body = http_json(server.PORT, path)
            self.assertEqual(status, 200, body[:200])
            data = json.loads(body)
            self.assertEqual(data.get("tls_port"), server.TLS_BOUND_PORT, path)
            self.assertEqual(data.get("tls_spki_sha256"), want_spki, path)
            self.assertRegex(data.get("tls_spki_sha256") or "", r"^[0-9a-f]{64}$")
            parsed = datetime.fromisoformat(data.get("tls_not_after"))
            self.assertIsNotNone(parsed.tzinfo)
            self.assertEqual(data.get("tls_not_after"), server.TLS_NOT_AFTER)
        self.assertEqual(want_spki, server.TLS_SPKI_SHA256)

    def test_udp_discovery_advertises_tls_port_without_pin(self):
        old_port = server.DISCOVERY_PORT
        server.DISCOVERY_PORT = 0
        sock = server._start_discovery()
        self.assertIsNotNone(sock, "UDP 发现端口没起来")
        try:
            udp_port = sock.getsockname()[1]
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.settimeout(3)
                client.sendto(server.DISCOVERY_MAGIC, ("127.0.0.1", udp_port))
                raw, _addr = client.recvfrom(4096)
        finally:
            sock.close()
            server.DISCOVERY_PORT = old_port
        payload = json.loads(raw.decode("utf-8"))
        self.assertEqual(payload.get("tls_port"), server.TLS_BOUND_PORT)
        self.assertNotIn("tls_spki_sha256", payload)
        self.assertNotIn("pin", json.dumps(payload).lower())


class TlsFailureTests(unittest.TestCase):
    """TLS 起不来时 HTTP 必须照常服务。"""

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="cardback-tls-fail-")
        configure_data_dir(cls._tmp.name)
        server.ensure_token()
        cls.httpd = server.CardbackServer(("127.0.0.1", 0), server.Handler)
        server.PORT = int(cls.httpd.server_address[1])
        server.LAN_URL = "http://127.0.0.1:%d/cardback.json" % server.PORT
        cls.http_thread = threading.Thread(
            target=cls.httpd.serve_forever, daemon=True
        )
        cls.http_thread.start()

    @classmethod
    def tearDownClass(cls):
        try:
            cls.httpd.shutdown()
        except Exception:  # noqa: BLE001
            pass
        try:
            cls.httpd.server_close()
        except OSError:
            pass
        cls.http_thread.join(timeout=3)
        cls._tmp.cleanup()

    def test_tls_startup_failure_does_not_break_http(self):
        self.assertIsNone(server.TLS_HTTPD)
        with mock.patch.object(
            server.tls_support,
            "ensure_certificate",
            side_effect=RuntimeError("模拟证书生成失败"),
        ):
            tls_httpd = server.start_tls_server(host="127.0.0.1", port=0)
        self.assertIsNone(tls_httpd)
        self.assertIsNone(server.TLS_HTTPD)
        self.assertEqual(server.TLS_BOUND_PORT, 0)

        status, _headers, body = http_json(server.PORT, "/health")
        self.assertEqual(status, 200, body[:200])
        data = json.loads(body)
        self.assertTrue(data.get("ok"))
        self.assertEqual(data.get("tls_port"), 0)
        self.assertIsNone(data.get("tls_spki_sha256"))
        self.assertIsNone(data.get("tls_not_after"))

    def test_tls_disabled_by_env_does_not_break_http(self):
        with mock.patch.dict(os.environ, {"CARDBACK_DISABLE_TLS": "1"}):
            self.assertIsNone(server.start_tls_server(host="127.0.0.1", port=0))
        status, _headers, _body = http_json(server.PORT, "/health")
        self.assertEqual(status, 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)

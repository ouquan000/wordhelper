"""Offline HTTP contracts for gzip negotiation and file cache invalidation."""

import gzip
import hashlib
import json
import os
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from pathlib import Path
from unittest.mock import Mock, patch

import cardback_http
import server


CARDS_URL = "/cards/cet6_cards.json"


def start_loopback(handler):
    httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    worker = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.01})
    worker.start()
    return httpd, worker


class QuietHandler(server.Handler):
    def _log(self, message):
        pass


class GzipContracts(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.cards = root / "cet6_cards.json"
        self.payload = '{"word":"词卡","words":["example","例句"]}\n'.encode("utf-8")
        self.cards.write_bytes(self.payload)
        self.digest = hashlib.sha256(self.payload).hexdigest()
        (root / "manifest.json").write_text(
            json.dumps({"version": 1, "sha256": self.digest}), encoding="utf-8"
        )
        for patcher in (
            patch.object(server._context, "cards_dir", return_value=str(root)),
            patch.object(server._context, "BUNDLE_DIR", str(root)),
            patch.object(server._context, "CONFIG_PATH", str(root / "config.json")),
            patch.object(server._context, "_token_ok", return_value=False),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.httpd, worker = start_loopback(QuietHandler)
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(worker.join)
        self.addCleanup(self.httpd.shutdown)

    def request(self, path=CARDS_URL, encoding=None):
        client = HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        try:
            client.putrequest("GET", path, skip_accept_encoding=True)
            if encoding is not None:
                client.putheader("Accept-Encoding", encoding)
            client.putheader("Connection", "close")
            client.endheaders()
            response = client.getresponse()
            data = response.read()
            self.assertEqual(response.status, 200)
            self.assertEqual(int(response.getheader("Content-Length")), len(data))
            self.assertEqual(response.getheader("Cache-Control"), "no-store")
            return response.headers, data
        finally:
            client.close()

    def assert_gzip(self, headers, encoded, expected):
        self.assertEqual(headers.get("Content-Encoding"), "gzip")
        self.assertEqual(headers.get("Vary"), "Accept-Encoding")
        self.assertEqual(headers.get_content_type(), "application/json")
        self.assertEqual(gzip.decompress(encoded), expected)

    def test_gzip_round_trip_matches_manifest_sha(self):
        headers, encoded = self.request(encoding="br, GZip; q=0.8")
        self.assert_gzip(headers, encoded, self.payload)
        _, manifest = self.request("/cards/manifest.json")
        self.assertEqual(
            hashlib.sha256(gzip.decompress(encoded)).hexdigest(), json.loads(manifest)["sha256"]
        )

    def test_missing_accept_encoding_serves_original(self):
        headers, body = self.request()
        self.assertIsNone(headers.get("Content-Encoding"))
        self.assertEqual(headers.get("Vary"), "Accept-Encoding")
        self.assertEqual(body, self.payload)

    def test_explicit_zero_quality_and_unrelated_codings_serve_original(self):
        for value in ("gzip;q=0, *;q=1", "br, x-gzip", "gzip;q=invalid", "gzip;q=2"):
            with self.subTest(value=value):
                headers, body = self.request(encoding=value)
                self.assertIsNone(headers.get("Content-Encoding"))
                self.assertEqual(body, self.payload)

    def test_repeated_request_reuses_compressed_bytes(self):
        with patch.object(cardback_http, "_gzip", wraps=cardback_http._gzip) as compress:
            _, first = self.request(encoding="gzip")
            _, second = self.request(encoding="gzip")
        self.assertEqual(first, second)
        compress.assert_called_once_with(self.payload)

    def test_atomic_replacement_invalidates_cache_on_mtime_change_at_same_size(self):
        self.request(encoding="gzip")
        before = self.cards.stat()
        replacement = self.cards.with_suffix(".tmp")
        expected = self.payload.replace(b"example", b"updated")
        replacement.write_bytes(expected)
        os.utime(replacement, ns=(before.st_atime_ns, before.st_mtime_ns - 2_000_000_000))
        replacement.replace(self.cards)
        self.assertEqual(self.cards.stat().st_size, before.st_size)
        self.assertNotEqual(self.cards.stat().st_mtime_ns, before.st_mtime_ns)
        headers, body = self.request(encoding="gzip")
        self.assert_gzip(headers, body, expected)

    def test_cache_invalidates_on_size_change_at_same_mtime(self):
        self.request(encoding="gzip")
        before = self.cards.stat()
        expected = self.payload + b" "
        self.cards.write_bytes(expected)
        os.utime(self.cards, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.assertEqual(self.cards.stat().st_mtime_ns, before.st_mtime_ns)
        headers, body = self.request(encoding="gzip")
        self.assert_gzip(headers, body, expected)

    def test_static_json_and_published_config_negotiate_gzip(self):
        (self.cards.parent / "config.json").write_bytes(self.payload)
        for path in ("/config.json", "/cardback.json"):
            with self.subTest(path=path):
                headers, body = self.request(path, encoding="gzip")
                self.assert_gzip(headers, body, self.payload)

    def test_large_sync_json_negotiates_gzip(self):
        store = Mock()
        store.pull.return_value = {"ops": ["example" * 10_000]}
        with patch.object(server._context, "sync_db", return_value=store):
            _, identity = self.request("/sync/pull")
            headers, encoded = self.request("/sync/pull", encoding="gzip")
        self.assert_gzip(headers, encoded, identity)

    def test_small_sync_json_stays_uncompressed(self):
        store = Mock()
        store.pull.return_value = {"ops": []}
        with patch.object(server._context, "sync_db", return_value=store):
            headers, body = self.request("/sync/pull", encoding="gzip")
        self.assertIsNone(headers.get("Content-Encoding"))
        self.assertEqual(json.loads(body), {"ok": True, "ops": []})


if __name__ == "__main__":
    unittest.main(verbosity=2)

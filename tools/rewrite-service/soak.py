#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""24h 隔离 soak（1.4.7.2 P5/L01）。

只跑本进程内的 rewrite-service + 假 provider，不连真实 WB / 8765 / 用户数据。
用法：
  python soak.py --hours 24 --port 8901 --out _recon/1.4.7.2/P5/soak-<ts>
可先 `--hours 0.01` 做冒烟。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import sys
import threading
import time
import traceback
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

FAKE_JSON = {
    "split": ["soak", "sock"],
    "elements": [
        {"part": "soak", "method": "谐音", "reasoning": "soak 读起来像把袜子泡进水里"},
        {"part": "sock", "method": "谐音", "reasoning": "袜子晾在绳子上滴水"},
    ],
    "derivation": [
        "soak 和 sock 只差一个字母，想象把湿袜子晾在绳子上",
        "袜子滴水，水珠顺着绳子往下滑",
        "滑到尽头，脚底终于踩到干爽的地面",
        "——> 浸泡； soak 记成“把袜子泡进水里”",
    ],
    "method": "谐音",
    "scores": {
        "拆分成立": 9,
        "方法选对": 9,
        "谐音过关": 9,
        "逻辑连贯": 9,
        "文本充足": 9,
        "像人说话": 9,
        "诉求落实": 9,
    },
    "intent": "change_method",
    "same_method": False,
    "violations": [],
    "reason": "",
}


class FakeProvider(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        body = json.dumps({
            "id": "chatcmpl-soak",
            "object": "chat.completion",
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": json.dumps(FAKE_JSON, ensure_ascii=False)},
                "finish_reason": "stop",
            }],
        }, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


def service_handler(service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def _run(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            result = service.respond(self.command, self.path, body)
            if result is None:
                result = (404, "application/json; charset=utf-8", b'{"error":"not found"}')
            status, ctype, payload = result
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

        do_GET = _run
        do_POST = _run

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=float, default=24.0)
    ap.add_argument("--port", type=int, default=8901)
    ap.add_argument("--out", default="")
    ap.add_argument("--submit-every", type=float, default=300.0)
    ap.add_argument("--sample-every", type=float, default=60.0)
    args = ap.parse_args()

    out = args.out or os.path.join(
        os.path.dirname(HERE), "_recon", "1.4.7.2", "P5",
        "soak-" + datetime.now().strftime("%Y%m%d-%H%M%S"),
    )
    os.makedirs(out, exist_ok=True)
    data_dir = os.path.join(out, "data")
    os.makedirs(data_dir, exist_ok=True)
    os.environ["REWRITE_DATA_DIR"] = data_dir
    os.environ["CARDBACK_DISABLE_DISCOVERY"] = "1"

    # 先起假 provider，再导入 service/providers（它们会读 env 和模块常量）。
    fake = ThreadingHTTPServer(("127.0.0.1", 0), FakeProvider)
    fake_port = fake.server_address[1]
    threading.Thread(target=fake.serve_forever, name="fake-provider", daemon=True).start()

    import providers  # noqa: E402
    import service    # noqa: E402

    providers.PROVIDER_BASE["wb"] = "http://127.0.0.1:%d/v1" % fake_port
    service.CONCURRENCY = 1
    service.startup()

    srv = ThreadingHTTPServer(("127.0.0.1", args.port), service_handler(service))
    threading.Thread(target=srv.serve_forever, name="soak-http", daemon=True).start()

    pid_path = os.path.join(out, "soak.pid")
    with open(pid_path, "w", encoding="utf-8") as fh:
        fh.write(str(os.getpid()))
    log_path = os.path.join(out, "soak.jsonl")
    start = time.time()
    deadline = start + max(0.01, args.hours) * 3600.0
    seq = 0
    stop = {"flag": False}

    def on_signal(_sig, _frm):
        stop["flag"] = True

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    def write(rec):
        rec["ts"] = datetime.now().isoformat(timespec="seconds")
        with open(log_path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")

    write({"event": "start", "pid": os.getpid(), "port": args.port,
           "hours": args.hours, "fake_provider": fake_port, "data_dir": data_dir})

    last_submit = 0.0
    last_sample = 0.0
    while not stop["flag"] and time.time() < deadline:
        now = time.time()
        if now - last_submit >= args.submit_every:
            seq += 1
            word = "soakword%04d" % seq
            body = json.dumps({"marks": [{
                "clientId": "soak-%04d" % seq,
                "word": word,
                "phonetic": "/səʊk/",
                "pos": "v.",
                "meaning": "浸泡",
                "instruction": "",
                "book": "soak-book",
                "model": "ds-v41-flash",
                "markedAt": datetime.now().isoformat(timespec="seconds"),
                "currentApproach": [],
            }]}, ensure_ascii=False).encode("utf-8")
            try:
                import urllib.request
                req = urllib.request.Request(
                    "http://127.0.0.1:%d/rewrite/marks" % args.port,
                    data=body, headers={"Content-Type": "application/json"}, method="POST")
                with urllib.request.urlopen(req, timeout=5) as resp:
                    write({"event": "submit", "seq": seq, "word": word,
                           "status": resp.status, "body": resp.read().decode("utf-8", "replace")[:300]})
            except Exception as exc:
                write({"event": "submit_error", "seq": seq, "error": repr(exc)})
            last_submit = now
        if now - last_sample >= args.sample_every:
            try:
                st = service._state["store"].status()
            except Exception:
                st = {}
            try:
                import psutil
                rss = int(psutil.Process().memory_info().rss / 1024)
            except Exception:
                try:
                    import resource
                    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
                except Exception:
                    rss = None
            write({"event": "sample", "elapsed_s": int(now - start),
                   "threads": threading.active_count(), "rss_kb": rss,
                   "status": st, "log_bytes": os.path.getsize(log_path) if os.path.exists(log_path) else 0})
            last_sample = now
        time.sleep(2)

    write({"event": "stop", "elapsed_s": int(time.time() - start), "seq": seq,
           "reason": "duration" if time.time() >= deadline else "signal"})
    try:
        service.shutdown()
    except Exception:
        pass
    try:
        srv.shutdown()
        srv.server_close()
    except Exception:
        pass
    try:
        fake.shutdown()
        fake.server_close()
    except Exception:
        pass
    print(out)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)

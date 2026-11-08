#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""学习记录同步的服务端存储（1.4.4）

抄的是墨墨那套最小骨架：本地一条队列把「结果态」发上来，服务端只做两件事——
记住每个词最新的一条，并给每次写入发一个递增号码；手机下次带上号码来取增量。

不做的事（明确划掉）：云端算排程、意图级 op、墓碑、整包覆盖。
冲突按「最后复习时间」判，晚的那条赢，不弹窗让用户选。

落的文件：tools/rewrite-service/data/sync.json
{
  "rev": 123,
  "rows": {"六级记忆卡\\u0000abandon": {...op..., "rev": 101}}
}
"""

from __future__ import annotations

import json
import os
import threading
import time

# 一次拉取最多返回多少行；手机端会带着新的 since_rev 再来。
PULL_LIMIT = 500

# op 里必须存在的字段；缺了就当脏数据丢掉，别让它污染库。
REQUIRED = ("op_id", "t", "w", "b", "st", "reps", "ivl", "nrt", "ef", "rc", "lrt")


def _key(book: str, word: str) -> str:
    return f"{book}\u0000{word}"


class SyncStore:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        self._data = self._load()

    # ------------------------------------------------------------ 落盘

    def _load(self) -> dict:
        if not os.path.exists(self.path):
            return {"rev": 0, "rows": {}}
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict) or "rows" not in data:
                return {"rev": 0, "rows": {}}
            data.setdefault("rev", 0)
            return data
        except Exception:
            # 文件坏了不要让服务起不来：备份一份再从来。
            try:
                os.replace(self.path, self.path + ".broken")
            except OSError:
                pass
            return {"rev": 0, "rows": {}}

    def _save(self) -> None:
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._data, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, self.path)

    # ------------------------------------------------------------ 接口

    def push(self, device_id: str, base_rev: int, ops: list) -> dict:
        """收下一批 op。返回 {server_rev, acked, rejected}。

        过期（lrt 更小）的 op 也算 acked：手机端已经把它发出来了，留在队列里只会反复重发。
        """
        acked: list[str] = []
        rejected: list[dict] = []
        now = int(time.time() * 1000)
        with self._lock:
            for op in ops or []:
                if not isinstance(op, dict):
                    continue
                op_id = str(op.get("op_id") or "")
                missing = [k for k in REQUIRED if op.get(k) is None]
                if not op_id or missing:
                    rejected.append({"op_id": op_id, "reason": f"缺字段 {','.join(missing)}"})
                    continue
                key = _key(str(op["b"]), str(op["w"]))
                old = self._data["rows"].get(key)
                if old is not None and int(old.get("lrt", 0)) > int(op["lrt"]):
                    acked.append(op_id)
                    continue
                if old is not None and int(old.get("lrt", 0)) == int(op["lrt"]) \
                        and str(old.get("op_id", "")) >= op_id:
                    acked.append(op_id)
                    continue
                self._data["rev"] += 1
                row = {k: op.get(k) for k in REQUIRED}
                row["device"] = str(device_id or "")[:64]
                row["rev"] = self._data["rev"]
                row["at"] = now
                self._data["rows"][key] = row
                acked.append(op_id)
            self._save()
            return {"server_rev": self._data["rev"], "acked": acked, "rejected": rejected}

    def pull(self, since_rev: int, limit: int = PULL_LIMIT) -> dict:
        """取 since_rev 之后写过的行，按 rev 升序，一次最多 limit 条。

        next_since_rev 是下一页该从哪继续：还有更多时只推进到本页最后一行，
        全部拉完才推到全局 rev。手机端用它推游标，一次超过 limit 条也不会漏。
        """
        with self._lock:
            rows = [r for r in self._data["rows"].values() if int(r.get("rev", 0)) > since_rev]
            rows.sort(key=lambda r: int(r.get("rev", 0)))
            page = rows[:limit]
            more = len(rows) > len(page)
            if page:
                next_rev = int(page[-1].get("rev", 0))
            else:
                next_rev = int(self._data["rev"])
            if not more:
                next_rev = int(self._data["rev"])
            return {
                "server_rev": self._data["rev"],
                "next_since_rev": next_rev,
                "rows": page,
                "more": more,
            }

    def stats(self) -> dict:
        with self._lock:
            return {"rev": self._data["rev"], "rows": len(self._data["rows"])}

"""队列视图、历史分页、复用与闲时结果。"""


import rewrite_protection as protection


class ResultOperations:
    def status(self):
        with self._lock:
            counts = {"pending": 0, "running": 0, "retry_wait": 0, "done": 0, "failed": 0, "stopped": 0}
            active, finished = [], []
            for item in self.items:
                state = item.get("state", "pending")
                counts[state] = counts.get(state, 0) + 1
                row = {
                    "clientId": item["clientId"],
                    "word": item["word"],
                    "model": item["model"],
                    "state": state,
                    "stage": self._api.stage_of(item),
                    "error": item.get("error") or item.get("note") or "",
                    "updatedAt": item.get("updatedAt", ""),
                    "unverified": bool(item.get("unverified")),
                    "reason": str(item.get("reason") or ""),
                    "failures": int(item.get("failures") or 0),
                    "nextRunAt": item.get("nextRunAt") or "",
                }
                (active if state in ("pending", "running", "retry_wait") else finished).append(row)
            finished.sort(key=lambda r: r["updatedAt"], reverse=True)
            recent = sorted(
                (e for rec in self.results.values() for e in rec["entries"]),
                key=lambda e: e["seq"],
                reverse=True,
            )[:8]
            return {
                "pending": counts.get("pending", 0),
                "running": counts.get("running", 0),
                "retryWait": counts.get("retry_wait", 0),
                "stopped": counts.get("stopped", 0),
                "done": counts.get("done", 0),
                "failed": counts.get("failed", 0),
                "items": (active + finished)[:30],
                "todayDone": self._count_today("ok"),
                "todayFailed": self._count_today("failed"),
                "seq": self.seq,
                "recent": [self._api._public(e) for e in recent],
            }

    def results_page(self, since, limit):
        with self._lock:
            self._load_locks()
            entries = [
                e
                for rec in self.results.values()
                for e in rec["entries"]
                if e["seq"] > since and not self._masked_by_lock(e)
            ]
            entries.sort(key=lambda e: e["seq"])
            page = entries[:limit]
            return {
                "results": [self._api._public(e) for e in page],
                "nextSince": page[-1]["seq"] if page else min(since, self.seq),
                "more": len(entries) > limit,
                "streamId": self.stream_id,
                "highWater": self.seq,
            }

    def word_history(self, word):
        with self._lock:
            record = self.results.get(word) or {"entries": []}
            return [self._api._public(e) for e in record["entries"]]

    def find_reusable_result(self, item, prompt_hash, instruction_hash):
        """同一词、同一本书、同一模型、同一提示词、同一用户原话的最近成品。

        只复用 status=ok 且非 unverified 的 entry；任何一项指纹不同都当没命中，
        宁可不复用，也不能把旧提示词/旧指令的稿子当成新结果交出去。
        """
        if not prompt_hash or not instruction_hash:
            return None
        word = str((item or {}).get("word") or "")
        if not word:
            return None
        want_book_id, want_book = self._api.book_scope(item)
        want_model = str((item or {}).get("model") or "")
        with self._lock:
            vote = protection.latest_votes(protection.feedback_rows(self.data_dir)).get(word.casefold())
            record = self.results.get(word) or {"entries": []}
            for entry in reversed(record.get("entries") or []):
                if entry.get("status") != "ok" or entry.get("unverified"):
                    continue
                if vote and not protection.may_publish(entry, vote):
                    continue
                if str(entry.get("model") or "") != want_model:
                    continue
                if str(entry.get("promptHash") or "") != prompt_hash:
                    continue
                if str(entry.get("instructionHash") or "") != instruction_hash:
                    continue
                entry_book_id, entry_book = self._api.book_scope(entry)
                # 精确书范围：知道 ID 就按 ID（同时知道书名时两者都要等），
                # 只剩书名按书名，两者都空只认 (0, "")。不能用「ID 或书名
                # 任一相等」——bookId=0 / book="" 的空值会跨书误命中。
                if want_book_id and want_book:
                    if entry_book_id != want_book_id or entry_book != want_book:
                        continue
                elif want_book_id:
                    if entry_book_id != want_book_id:
                        continue
                elif want_book:
                    if entry_book != want_book:
                        continue
                elif entry_book_id != 0 or entry_book:
                    continue
                found = dict(entry)
                found["approach"] = list(entry.get("approach") or [])
                return found
        return None

    def records_page(self, limit=50, offset=0, book=""):
        """重写记录页：每条标记一行，按最近更新倒序，附上成品分步思路与上一版。

        book 传书名只看那本；传 `__none__` 只看没标书目的（1.4.4 之前的老记录）。
        """
        with self._lock:
            rows = []
            for item in self.items:
                entry = self._entry_for(item["clientId"])
                if entry and entry.get("unverified"):
                    entry = dict(entry, status="failed", approach=[])
                ok = entry is not None and entry.get("status") == "ok"
                rows.append(
                    {
                        "word": item["word"],
                        "instruction": item.get("instruction") or "",
                        "book": item.get("book") or "",
                        "model": item["model"],
                        "stage": self._api.stage_of(item),
                        "state": item.get("state", "pending"),
                        "status": entry.get("status") if entry else item.get("state", "pending"),
                        "error": item.get("error") or item.get("note") or "",
                        "createdAt": item.get("createdAt", ""),
                        "updatedAt": item.get("updatedAt", ""),
                        "clientId": item["clientId"],
                        "seq": (entry or {}).get("seq"),
                        "rev": (entry or {}).get("rev"),
                        "attempts": int(
                            (entry or {}).get("attempts") or item.get("attempts") or 0
                        ),
                        "method": (entry or {}).get("method") or "",
                        "approach": list(entry["approach"]) if ok else [],
                        "prev": list((entry or {}).get("prev") or item.get("currentApproach") or []),
                        "unverified": bool((entry or item).get("unverified")),
                        "reason": str((entry or item).get("reason") or ""),
                    }
                )
            # 书目聚合按「全部记录」算，不看当前筛哪本 —— 否则选完一本，下拉里就只剩它自己
            counts = {}
            for row in rows:
                counts[row["book"]] = counts.get(row["book"], 0) + 1
            books = [
                {"name": name, "count": count}
                for name, count in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
            ]
            book = (book or "").strip()
            if book:
                wanted = "" if book == "__none__" else book
                rows = [row for row in rows if row["book"] == wanted]
            rows.sort(key=lambda r: (r["updatedAt"], r["clientId"]), reverse=True)
            page = rows[offset : offset + limit]
            return {
                "records": page,
                "total": len(rows),
                "limit": limit,
                "offset": offset,
                "more": offset + limit < len(rows),
                "books": books,
                "book": book,
            }

    def record_worker_result(self, word, approach, meta=None, word_id=None,
                             book_id=None, book="", model=""):
        """洗词 worker 的成品入库：只写 results/<word>.json，绝不碰队列。

        和 finish_ok 的差别只有两点：① 不走 claim/队列，所以结构上就不可能占用
        6 个手动高优槽；② 结果带 source="worker"，被用户手改锁定的词直接拒收。
        返回 entry；锁定了 / 没词 / 没稿子都返回 None。
        """
        meta = dict(meta or {})
        word = str(word or "").strip()
        if not word:
            return None
        approach = [str(x) for x in (approach or []) if str(x).strip()]
        if not approach:
            return None
        with self._cond:
            votes = protection.latest_votes(protection.feedback_rows(self.data_dir))
            key = word.casefold()
            vote = votes.get(key)
            if (meta.get("unverified") or meta.get("quality_rejected") or
                    self.is_locked(word_id, book_id) or key in protection.locked_words(self.locks(), votes)
                    or (vote and vote[0][1])):
                return None
            book_id_value, book_name = self._api.book_scope({"bookId": book_id, "book": book})
            record = self.results.setdefault(word, {"word": word, "entries": []})
            prev = self._api._current(record)
            own_prev = self._api._current_in_book(record, {"bookId": book_id_value, "book": book_name})
            entry = {
                "seq": self._next_seq(),
                "word": word,
                "wordId": self._api._lock_part(word_id),
                "bookId": book_id_value,
                "book": book_name,
                "rev": ((prev or {}).get("rev") or 0) + 1,
                "approach": approach,
                "method": str(meta.get("method") or ""),
                "prev": list((own_prev or prev or {}).get("approach") or []),
                "updatedAt": self._api._now(),
                "status": "ok",
                "model": str(model or meta.get("model") or ""),
                "clientId": str(meta.get("clientId") or "worker:%s" % self._api.uuid.uuid4().hex[:12]),
                "scores": meta.get("scores") or {},
                "attempts": meta.get("attempts", 1),
                "unverified": bool(meta.get("unverified")),
                "reason": str(meta.get("reason") or "")[:400],
                "promptHash": str(meta.get("promptHash") or ""),
                "instructionHash": str(meta.get("instructionHash") or ""),
                "reused": False,
                "reuseOf": None,
                "source": "worker",
                "engine": protection.ENGINE,
            }
            record["entries"] = self._api._trim(record["entries"] + [entry])
            self._save_word(word)
            self._prune()
            self._cond.notify_all()
            return entry

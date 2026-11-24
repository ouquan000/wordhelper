"""反馈、记忆信号与按书锁词。"""


class FeedbackOperations:
    def add_feedback(self, items):
        """收手机端卡背的 👍/👎：幂等追加到 data/feedback.jsonl。

        反馈是旁路数据，只追加、不碰队列。手机断网会把同一批重推，
        所以按 clientId 去重：重复的算「已接受」，但不会再写一行。
        返回 (accepted, rejected)，形状和 add_marks 保持一致。

        每条可选 `count`（1.5.2 起）：同一张卡一次按了几次 👍/👎。缺省 1，
        合法范围 1..50；非法（0、51、true、字符串……）整条拒掉，不影响同批其它条。
        1.5.3 起另有两个可选字段：`reasons`（list[str]，点踩原因标签）和
        `comment`（str，一句话补充）。两者都不规范时只做规范化（空列表/空串），
        绝不因为理由写歪了就把整条评价丢掉。
        未知字段照旧原样透传（record = dict(raw)），落盘行里一定有 count、reasons、
        comment 和 receivedAt。
        """
        accepted, rejected = [], []
        with self._lock:
            self._load_feedback_ids()
            self._api.os.makedirs(self.data_dir, exist_ok=True)
            with open(self.feedback_path, "a", encoding="utf-8") as fh:
                for raw in items:
                    if not isinstance(raw, dict):
                        rejected.append({"clientId": "", "reason": "反馈不是对象"})
                        continue
                    client_id = str(raw.get("clientId") or "").strip()
                    rating = str(raw.get("rating") or "").strip().lower()
                    if not client_id:
                        rejected.append({"clientId": "", "reason": "缺 clientId"})
                        continue
                    if rating not in ("up", "down"):
                        rejected.append({"clientId": client_id, "reason": "rating 只能是 up/down"})
                        continue
                    # count 可选（1.5.2）：老客户端不发就按 1 记；发了就必须是 1..50 的整数
                    count, count_error = self._api.feedback_count(raw.get("count"))
                    if count_error:
                        rejected.append({"clientId": client_id, "reason": count_error})
                        continue
                    if client_id in self._feedback_ids:
                        accepted.append(client_id)  # 幂等：之前收过了
                        continue
                    record = dict(raw)
                    record["rating"] = rating
                    record["count"] = count
                    # 1.5.3：点踩结构化字段；老客户端没发就是 [] / ""，不影响其它字段
                    record["reasons"] = self._api.feedback_reasons(raw.get("reasons"))
                    record["comment"] = self._api.feedback_comment(raw.get("comment"))
                    record["receivedAt"] = self._api._now()
                    # print 自带换行：不手写转义，避免不同编辑器把换行写歪
                    print(self._api.json.dumps(record, ensure_ascii=False), file=fh)
                    fh.flush()
                    self._feedback_ids.add(client_id)
                    accepted.append(client_id)
        return accepted, rejected

    # ------------------------------------------------------ 复习记忆信号（v8）

    def record_memory_signal(self, payload):
        """记录一条 retained/lapsed 复习信号。

        幂等键：clientId + word + bookId + method + event；同一事件只生效一次。
        lapsed 额外加 30 分钟冷却（同词同方法），避免手机端补传把一次遗忘记成多次。
        返回 (applied, reason)。
        """
        client_id = str(payload.get("clientId") or "").strip()
        word = str(payload.get("word") or "").strip()
        book_id = str(payload.get("bookId") or "0").strip()
        method = str(payload.get("method") or "").strip()
        event = str(payload.get("event") or "").strip().lower()
        key = "|".join((client_id, word, book_id, method, event))
        with self._lock:
            if key in self._memory_signals:
                return False, "重复信号，已生效过"
            if event == "lapsed":
                cooldown_key = word + "|" + method
                last = self._memory_cooldowns.get(cooldown_key)
                if last:
                    try:
                        last_at = self._api.datetime.fromisoformat(last)
                        if last_at.tzinfo is None:
                            last_at = last_at.replace(tzinfo=self._api.datetime.now().astimezone().tzinfo)
                        elapsed = (self._api.datetime.now().astimezone() - last_at).total_seconds()
                        if elapsed < self._api.MEMORY_COOLDOWN_SECONDS:
                            return False, "同词同方法 30 分钟冷却中"
                    except (TypeError, ValueError):
                        pass
            record = {
                "clientId": client_id,
                "word": word,
                "bookId": book_id,
                "method": method,
                "event": event,
                "stability": payload.get("stability"),
                "difficulty": payload.get("difficulty"),
                "lapses": payload.get("lapses"),
                "receivedAt": self._api._now(),
            }
            self._memory_signals[key] = record
            if event == "lapsed":
                self._memory_cooldowns[word + "|" + method] = record["receivedAt"]
            self._save_memory_signals()
        return True, ""

    def _save_memory_signals(self):
        self._api._write_json(self.memory_signals_path, {
            "signals": self._memory_signals,
            "cooldowns": self._memory_cooldowns,
        })

    def _load_feedback_ids(self):
        """第一次收到反馈时扫一遍文件，把已有 clientId 读进内存。"""
        if self._feedback_ids is not None:
            return
        ids = set()
        try:
            with open(self.feedback_path, "r", encoding="utf-8-sig") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        row = self._api.json.loads(line)
                    except ValueError:
                        continue
                    if not isinstance(row, dict):
                        continue
                    client_id = str(row.get("clientId") or "").strip()
                    if client_id:
                        ids.add(client_id)
        except OSError:
            pass
        self._feedback_ids = ids

    def _load_locks(self):
        """懒读 data/locks.json。锁是旁路数据，文件读坏了就当没人锁过。"""
        if self._locks is not None:
            return
        raw = self._api._read_json(self.locks_path)
        locks = {}
        if isinstance(raw, dict) and isinstance(raw.get("locks"), dict):
            for key, row in raw["locks"].items():
                if isinstance(row, dict):
                    locks[str(key)] = row
        self._locks = locks

    def locks(self, book=""):
        """锁定表快照：{key: {word_id, book_id, word, book, locked, updatedAt, note}}。

        book 既可以是书名也可以是 bookId：面板上两样都可能只拿到一个。
        """
        with self._lock:
            self._load_locks()
            wanted = str(book or "").strip()
            if not wanted:
                return {key: dict(row) for key, row in self._locks.items()}
            return {
                key: dict(row)
                for key, row in self._locks.items()
                if wanted in (str(row.get("book") or "").strip(), str(row.get("book_id") or "").strip())
            }

    def lock_records(self, book=""):
        """按最近改动倒序的锁定列表（面板 / GET /rewrite/locks 用）。"""
        rows = [dict(row) for row in self.locks(book).values()]
        rows.sort(
            key=lambda r: (str(r.get("updatedAt") or ""), str(r.get("word") or "")),
            reverse=True,
        )
        return rows

    def set_lock(self, word_id, book_id, locked=True, note="", word="", book=""):
        """写一条锁定/解锁。locked=False 也留档，面板上还看得出「已解锁」。

        note/word/book 不传就保留旧值：解锁时不该把用户写的备注顺手抹掉。
        """
        key = self._api.lock_key(word_id, book_id)
        with self._lock:
            self._load_locks()
            row = dict(self._locks.get(key) or {})
            row["word_id"] = self._api._lock_part(word_id)
            row["book_id"] = self._api._lock_part(book_id)
            row["word"] = str(word or row.get("word") or "").strip()[:120]
            row["book"] = str(book or row.get("book") or "").strip()[:60]
            row["locked"] = bool(locked)
            row["note"] = str(note or row.get("note") or "")[:400]
            row["updatedAt"] = self._api._now()
            self._locks[key] = row
            self._save_locks()
            return dict(row)

    def is_locked(self, word_id, book_id):
        """这个词在这本书里是否被用户手改锁定 —— 洗词 worker 与结果流都要先问它。"""
        key = self._api.lock_key(word_id, book_id)
        with self._lock:
            self._load_locks()
            row = self._locks.get(key)
            return bool(row and row.get("locked"))

    def locks_updated_at(self):
        """锁定文件最后写入时间（面板 / GET /rewrite/locks 展示用），文件不在就空串。"""
        raw = self._api._read_json(self.locks_path)
        if isinstance(raw, dict):
            return str(raw.get("updatedAt") or "")
        return ""

    def _save_locks(self):
        self._api._write_json(self.locks_path, {
            "version": 1,
            "updatedAt": self._api._now(),
            "locks": self._locks,
        })

    def _masked_by_lock(self, entry):
        """worker 结果撞上「之后才被锁上」的词：从结果流里挡掉，绝不覆盖手改稿。

        只挡 source=worker 的：用户自己手动重写的稿子永远照发。
        """
        if str(entry.get("source") or "") != "worker":
            return False
        if self._locks is None:
            return False
        row = self._locks.get(self._api.lock_key(entry.get("wordId"), self._api.book_scope(entry)[0]))
        return bool(row and row.get("locked"))

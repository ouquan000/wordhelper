"""入队、领取、退避唤醒、停止和手动重试。"""


class QueueOperations:
    def add_marks(self, marks):
        accepted, rejected = [], []
        with self._cond:
            for raw in marks:
                if not isinstance(raw, dict):
                    rejected.append({"clientId": "", "reason": "标记不是对象"})
                    continue
                client_id = str(raw.get("clientId") or "").strip()
                word = str(raw.get("word") or "").strip()
                model = str(raw.get("model") or "").strip()
                if not client_id:
                    rejected.append({"clientId": "", "reason": "缺 clientId"})
                elif not word:
                    rejected.append({"clientId": client_id, "reason": "缺 word"})
                elif model not in self._api.providers.MODELS:
                    rejected.append({"clientId": client_id, "reason": "unknown model"})
                elif client_id in self.accepted_mark_ids:
                    accepted.append(client_id)
                else:
                    self._enqueue(raw, client_id, word, model)
                    self.accepted_mark_ids.add(client_id)
                    accepted.append(client_id)
            self._save_queue()
            self._cond.notify_all()
        return accepted, rejected

    def _enqueue(self, raw, client_id, word, model):
        book_id, book = self._api.book_scope(raw)
        for item in self.items:
            if item["word"] != word or not self._api.same_book_scope(item, raw):
                continue
            if item.get("state") in ("pending", "retry_wait", "stopped"):
                # 同词未处理/等重试的标记：新的覆盖旧的（spec §3.1）
                self.items.remove(item)
                break
            if item.get("state") == "running":
                # 已经在跑了没法撤回，跑完直接丢结果，让新的这条说了算
                item["superseded"] = True
        self.items.append(
            {
                "clientId": client_id,
                "word": word,
                "phonetic": str(raw.get("phonetic") or ""),
                "pos": str(raw.get("pos") or ""),
                "meaning": str(raw.get("meaning") or ""),
                "instruction": str(raw.get("instruction") or ""),
                "bookId": book_id,
                "book": book,
                # 改稿/评委要看到完整上一版（推导 3–5 步）；App 端上报的是按行拆分的全文，
                # 旧的 [:3] 会把第 4、5 步截掉，评委只看到前 3 步。（1.4.7.1）
                "currentApproach": [str(x) for x in (raw.get("currentApproach") or [])][:5],
                "model": model,
                "markedAt": str(raw.get("markedAt") or "") or self._api._now(),
                "state": "pending",
                "stage": "queued",
                "attempts": 0,
                "error": "",
                "createdAt": self._api._now(),
                "updatedAt": self._api._now(),
                # 1.4.7.2：持续重写/停止/晚到结果都靠这三个字段
                "generation": 0,
                "failures": 0,
                "nextRunAt": "",
                "lastReason": "",
                "bestApproach": [],
                "bestScores": {},
                "qualityRejected": False,
                # G01：前台用户重写默认 100；离线批处理可显式传 10。
                "priority": max(0, min(100, self._api._priority_value(raw.get("priority")))),
            }
        )

    def _retire_superseded(self, states):
        """收尾无工人持有的过期任务，不生成结果或移除接收回执。"""
        changed = False
        for item in self.items:
            if item.get("superseded") and item.get("state") in states:
                item.update(state="done", stage="done", error="", nextRunAt="",
                            note="被同词新标记覆盖，取消后续运行", updatedAt=self._api._now())
                changed = True
        if changed:
            self._prune()
        return changed

    def _promote_retries_locked(self):
        """到点的 retry_wait 放回 pending；没写 nextRunAt 的旧数据立刻可跑。"""
        changed = self._retire_superseded(("pending", "retry_wait"))
        now = self._api._now()
        for item in self.items:
            if item.get("state") != "retry_wait":
                continue
            due = str(item.get("nextRunAt") or "")
            if not due or due <= now:
                item["state"] = "pending"
                item["stage"] = "queued"
                item["nextRunAt"] = ""
                item["updatedAt"] = now
                changed = True
        if changed:
            self._save_queue()

    def claim(self, timeout=60):
        """取一条到点的活；没有就等，等不到继续等，关门才返回 None。

        公平性：先按 nextRunAt（重试到点时间）/ createdAt 排序，再取第一条；
        单个词失败不会把整个队列堵死（spec §2.2）。
        """
        with self._cond:
            while not self._closing:
                self._promote_retries_locked()
                ready = [item for item in self.items if item.get("state") == "pending"]
                if ready:
                    item = min(
                        ready,
                        key=lambda x: (
                            -self._api._priority_value(x.get("priority"), 100),
                            str(x.get("nextRunAt") or ""),
                            str(x.get("createdAt") or ""),
                        ),
                    )
                    item["state"] = "running"
                    item["stage"] = "generate"
                    item["attempts"] = int(item.get("attempts") or 0) + 1
                    item["startedAt"] = self._api._now()
                    item["updatedAt"] = item["startedAt"]
                    self._save_queue()
                    return dict(item)
                self._cond.wait(timeout)
            return None

    def set_stage(self, client_id, stage):
        """阶段推进：只认固定取值，找不到这条记录就安静跳过。"""
        if stage not in self._api.STAGES:
            return False
        with self._cond:
            live = self._find(client_id)
            if live is None:
                return False
            live["stage"] = stage
            live["updatedAt"] = self._api._now()
            self._save_queue()
            self._cond.notify_all()
            return True

    def finish_retry(self, item, error, meta=None):
        """传输/格式/限流等可恢复错误：也走退避，不把词永久钉成 failed。"""
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None or not self._same_generation(item, live):
                return None
            if live.get("superseded"):
                live.update(state="failed", stage="failed", note="被同词新标记覆盖，失败记录丢弃", updatedAt=self._api._now())
                self._save_queue()
                self._cond.notify_all()
                return None
            failures = int(live.get("failures") or 0) + 1
            reason = str(error or "可恢复错误")[:400]
            next_at = self._api._retry_at(failures)
            live.update(
                state="retry_wait",
                stage="generate",
                failures=failures,
                lastReason=reason,
                error=reason,
                nextRunAt=next_at,
                qualityRejected=False,
                updatedAt=self._api._now(),
            )
            self._save_queue()
            self._cond.notify_all()
            return {"state": "retry_wait", "failures": failures, "nextRunAt": next_at}

    def release_claim(self, item, reason="工人异常"):
        """工人本轮异常：把还处于 running 的认领退回重试，杜绝僵尸 running。

        只在 generation 未变且记录仍是 running 时生效：本轮已经 finish_ok /
        finish_retry / 被 stop 的任务不能被一次迟到的释放覆盖。attempts 保留，
        面板上仍能看到真实尝试次数；nextRunAt 用短退避，让别的工人很快接手。
        """
        with self._cond:
            live = self._find((item or {}).get("clientId"))
            if live is None or not self._same_generation(item, live):
                return None
            if live.get("state") != "running":
                return None
            reason = str(reason or "工人异常")[:400]
            next_at = self._api._seconds_from_now(self._api.RELEASE_DELAY_SECONDS)
            live.update(
                state="retry_wait",
                stage="",
                error=reason,
                lastReason=reason,
                nextRunAt=next_at,
                updatedAt=self._api._now(),
            )
            self._save_queue()
            self._cond.notify_all()
            return {"state": "retry_wait", "nextRunAt": next_at}

    def stop(self, word="", client_id=""):
        """停止：待运行、等重试、运行中的都标 stopped；运行中的靠 generation+1 丢晚到结果。"""
        with self._cond:
            hit = 0
            for item in self.items:
                if item.get("state") not in ("pending", "running", "retry_wait"):
                    continue
                if client_id and item["clientId"] != client_id:
                    continue
                if word and item["word"] != word:
                    continue
                item["generation"] = int(item.get("generation") or 0) + 1
                item.update(
                    state="stopped",
                    stage="failed",
                    error="用户停止",
                    lastReason="用户停止",
                    nextRunAt="",
                    qualityRejected=False,
                    failures=0,
                    bestApproach=[],
                    bestScores={},
                    bestMethod="",
                    updatedAt=self._api._now(),
                )
                hit += 1
            if hit:
                self._save_queue()
                self._cond.notify_all()
            return hit

    def retry(self, word, client_id=""):
        """把这个词的失败项重新排进队列（保留原指令、模型与已尝试次数）。

        同词可能有多条失败标记：给了 clientId 就精确命中那一条，否则退回按词取最近一条。
        """
        with self._cond:
            hit = None
            for item in reversed(self.items):
                if item.get("superseded") or item.get("state") not in ("failed", "retry_wait", "stopped"):
                    continue
                if client_id:
                    if item["clientId"] == client_id:
                        hit = item
                        break
                    continue
                if item["word"] == word:
                    hit = item
                    break
            if hit is None:
                return False
            hit.update(
                state="pending",
                stage="queued",
                error="",
                nextRunAt="",
                failures=0,
                bestApproach=[],
                bestScores={},
                bestMethod="",
                qualityRejected=False,
                updatedAt=self._api._now(),
            )
            hit.pop("unverified", None)
            hit.pop("reason", None)
            self._save_queue()
            self._cond.notify_all()
            return True

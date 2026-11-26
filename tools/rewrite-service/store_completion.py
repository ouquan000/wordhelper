"""代次核对、结果提交与质量拒绝。"""


from rewrite_protection import ENGINE
from quality_retry_policy import QUALITY_ROUND_LIMIT, rejection_state, valid_candidate


class CompletionOperations:
    def _same_generation(self, item, live):
        """晚到的结果/停止后回来的结果：代次不一致就不许写。"""
        return int(item.get("generation") or 0) == int(live.get("generation") or 0)

    def finish_ok(self, item, approach, meta=None):
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None:
                return None
            if not self._same_generation(item, live):
                return None
            if live.get("superseded"):
                live.update(state="done", stage="done", note="被同词新标记覆盖，结果丢弃", updatedAt=self._api._now())
                self._save_queue()
                self._cond.notify_all()
                return None
            if meta.get("unverified") or meta.get("quality_rejected"):
                return self.finish_quality_rejected(item, approach, meta)
            record = self.results.setdefault(live["word"], {"word": live["word"], "entries": []})
            prev = self._api._current(record)
            own_prev = self._api._current_in_book(record, live)
            unverified = bool(meta.get("unverified"))
            reason = str(meta.get("reason") or "")[:400]
            entry = {
                "seq": self._next_seq(),
                "word": live["word"],
                "bookId": live.get("bookId", 0),
                "book": live.get("book", ""),
                "rev": ((prev or {}).get("rev") or 0) + 1,
                "approach": list(approach),
                "method": str(meta.get("method") or ""),  # 六招之一，手机端据此显示「联想 · 词根」标签
                "prev": list(live.get("currentApproach") or (own_prev or {}).get("approach") or []),
                "updatedAt": self._api._now(),
                "status": "ok",
                "engine": ENGINE,
                "model": live["model"],
                "clientId": live["clientId"],
                "scores": meta.get("scores") or {},
                "attempts": meta.get("attempts", 1),
                "unverified": unverified,
                "reason": reason,
                # 历史复用指纹：只有提示词/用户原话/模型完全一致的新结果才允许复用。
                "promptHash": str(meta.get("promptHash") or ""),
                "instructionHash": str(meta.get("instructionHash") or ""),
                "reused": bool(meta.get("reused")),
                "reuseOf": meta.get("reuseOf"),
            }
            record["entries"] = self._api._trim(record["entries"] + [entry])
            self._save_word(live["word"])
            live.update(
                state="done",
                stage="done",
                error="",
                updatedAt=entry["updatedAt"],
                resultSeq=entry["seq"],
                unverified=unverified,
                reason=reason,
                nextRunAt="",
                qualityRejected=False,
                failures=0,
                lastReason="",
                bestApproach=[],
                bestScores={},
                bestMethod="",
            )
            self._prune()
            self._save_queue()
            self._cond.notify_all()
            return entry

    def finish_fail(self, item, error):
        with self._cond:
            live = self._find(item["clientId"])
            if live is None:
                return None
            if not self._same_generation(item, live):
                return None
            # 与 finish_ok 对称：被同词新标记覆盖的旧任务，失败也不许写结果，
            # 否则面板/手机会收到一条本不该存在的 failed。
            if live.get("superseded"):
                live.update(state="failed", stage="failed", note="被同词新标记覆盖，失败记录丢弃", updatedAt=self._api._now())
                self._save_queue()
                self._cond.notify_all()
                return None
            record = self.results.setdefault(live["word"], {"word": live["word"], "entries": []})
            current = self._api._current(record)
            own_current = self._api._current_in_book(record, live)
            entry = {
                "seq": self._next_seq(),
                "word": live["word"],
                "bookId": live.get("bookId", 0),
                "book": live.get("book", ""),
                "rev": (current or {}).get("rev") or 0,
                "approach": [],
                "method": "",
                "prev": list(live.get("currentApproach") or (own_current or {}).get("approach") or []),
                "updatedAt": self._api._now(),
                "status": "failed",
                "model": live["model"],
                "clientId": live["clientId"],
                "error": error[:400],
                "reason": error[:400] if live.get("qualityRejected") else "",
            }
            record["entries"] = self._api._trim(record["entries"] + [entry])
            self._save_word(live["word"])
            live.update(state="failed", stage="failed", error=error[:400], updatedAt=entry["updatedAt"],
                        unverified=False, reason="")
            self._prune()
            self._save_queue()
            self._cond.notify_all()
            return entry

    def finish_quality_rejected(self, item, approach, meta=None):
        """仅待重试时返回调度回执，终态直接落盘。"""
        meta = meta or {}
        with self._cond:
            live = self._find(item["clientId"])
            if live is None or not self._same_generation(item, live):
                return None
            if live.get("state") in ("done", "failed", "stopped"):
                return None
            if live.get("superseded"):
                live.update(state="done", stage="done", note="被同词新标记覆盖，质量结果丢弃", updatedAt=self._api._now())
                self._save_queue()
                self._cond.notify_all()
                return None
            failures = int(live.get("failures") or 0) + 1
            reason = str(meta.get("reason") or "质量未过")[:400]
            if valid_candidate(approach):
                live["bestApproach"] = list(approach)
                live["bestScores"] = meta.get("scores") or {}
                live["bestMethod"] = meta.get("method") or ""
            candidate = live.get("bestApproach")
            attempts = int(live.get("attempts") or 0)
            state = rejection_state(attempts, failures, candidate)
            if state != "retry_wait":
                reason = f"质量重试已达 {QUALITY_ROUND_LIMIT} 轮上限：{reason}"[:400]
                live.update(failures=failures, nextRunAt="", lastReason=reason, qualityRejected=True,
                            bestApproach=[], bestScores={}, bestMethod="")
                self.finish_fail(item, reason)
                # service._work 仅对待重试回执打印下次运行时间。
                return None
            next_at = self._api._retry_at(failures)
            live.update(
                state="retry_wait",
                stage="judge",
                failures=failures,
                lastReason=reason,
                error=reason,
                nextRunAt=next_at,
                qualityRejected=True,
                updatedAt=self._api._now(),
            )
            self._save_queue()
            self._cond.notify_all()
            return {"state": "retry_wait", "failures": failures, "nextRunAt": next_at}

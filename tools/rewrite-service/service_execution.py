"""手动队列的领取、历史复用、单条与批量执行。"""

from __future__ import annotations


def _work(api, item):
    store = api._state["store"]
    started = api.time.time()

    def on_stage(stage):
        """把流水线的阶段切换落到队列记录上（失败/被覆盖由 finish_* 兜底）。"""
        store.set_stage(item["clientId"], stage)

    try:
        # 同词历史在 claim 之后、生成之前才组装：上一轮结果刚落盘时也能读到。
        item = dict(item)
        item["history"] = api.history.build(
            store.data_dir, item.get("word") or "", item.get("instruction") or "", scope=item,
        )
        # 同词历史复用：提示词/用户原话/模型/书全一致才命中。默认开，
        # REUSE_HISTORY=0 可关掉做全量重跑对照。
        prompt_hash = api.pipeline.prompt_hash()
        instruction_hash = api.pipeline.instruction_hash(item)
        if api.REUSE_HISTORY:
            reused = store.find_reusable_result(item, prompt_hash, instruction_hash)
            if reused:
                meta = {
                    "method": reused.get("method") or "",
                    "scores": reused.get("scores") or {},
                    "attempts": 0,
                    "unverified": False,
                    "reason": "复用历史结果",
                    "promptHash": prompt_hash,
                    "instructionHash": instruction_hash,
                    "reused": True,
                    "reuseOf": reused.get("seq"),
                }
                entry = store.finish_ok(item, reused.get("approach") or [], meta)
                if entry:
                    api.log("[%s] 复用历史 seq=%s -> 新 seq=%s（未调用模型）" % (
                        item["word"], reused.get("seq"), entry["seq"]))
                return
        api.providers.set_task_priority(item.get("priority", 100))
        out = api.pipeline.run(item, log=api.log, on_stage=on_stage)
        # 1.4.7.2：质量没过不再「照发 unverified」——那会让用户永远卡在两轮都不过
        # 的稿子上。这里只留最佳候选，按指数退避排下一次；合格的那次才 finish_ok。
        if out.get("quality_rejected") or out.get("unverified"):
            rejected = store.finish_quality_rejected(
                item,
                out.get("approach") or [],
                {
                    "method": out.get("method") or "",
                    "scores": out.get("scores") or {},
                    "attempts": out.get("attempts", 1),
                    "reason": out.get("reason") or "",
                },
            )
            if rejected:
                api.log("[%s] 质量未过，第 %d 次，%s 后重试：%s" % (
                    item["word"], rejected["failures"], rejected["nextRunAt"],
                    (out.get("reason") or "")[:200]))
            return
        meta = {
            "method": out.get("method") or "",
            "scores": out.get("scores") or {},
            "attempts": out.get("attempts", 1),
            "unverified": False,
            "reason": str(out.get("reason") or ""),
        }
        entry = store.finish_ok(item, out["approach"], meta)
        if entry:
            api.log("[%s] 完成 seq=%s rev=%s %s" % (item["word"], entry["seq"], entry["rev"], out["timings"]))
    except api.providers.ProviderError as exc:
        retry = store.finish_retry(item, str(exc))
        api.log("[%s] 模型调用失败，%s 后重试：%s" % (
            item["word"], (retry or {}).get("nextRunAt", "稍后"), exc))
    except api.pipeline.PipelineError as exc:
        retry = store.finish_retry(item, str(exc))
        api.log("[%s] 流水线失败，%s 后重试：%s" % (
            item["word"], (retry or {}).get("nextRunAt", "稍后"), exc))
    except Exception as exc:  # noqa: BLE001 - 工人不能死，一条词的意外不该拖垮整队
        retry = store.finish_retry(item, "未预期错误：%r" % exc)
        api.log("[%s] 未预期错误，%s 后重试：%r" % (
            item["word"], (retry or {}).get("nextRunAt", "稍后"), exc))
    finally:
        api.log("[%s] 本条用时 %.1fs" % (item["word"], api.time.time() - started))


def _finish_reuse(api, item, reused, prompt_hash, instruction_hash):
    store = api._state["store"]
    meta = {
        "method": reused.get("method") or "",
        "scores": reused.get("scores") or {},
        "attempts": 0,
        "unverified": False,
        "reason": "复用历史结果",
        "promptHash": prompt_hash,
        "instructionHash": instruction_hash,
        "reused": True,
        "reuseOf": reused.get("seq"),
    }
    entry = store.finish_ok(item, reused.get("approach") or [], meta)
    if entry:
        api.log("[%s] 复用历史 seq=%s -> 新 seq=%s（未调用模型）" % (
            item["word"], reused.get("seq"), entry["seq"]))
    return entry


def _claim_batch(api, limit, claimed=None):
    """先等一条，再尽量多抓几条组成小批；抓不到就按实际条数跑。

    claimed 由调用方传入时把每条认领立即记进去：中途 claim 抛异常，_worker
    也能看到本轮已经拿到的任务并逐个释放，不会留下僵尸 running。
    """
    store = api._state["store"]
    items = claimed if claimed is not None else []
    first = store.claim(60)
    if first is None:
        return items
    items.append(first)
    while len(items) < limit:
        nxt = store.claim(0.05)
        if nxt is None:
            break
        items.append(nxt)
    return items


def _work_batch(api, items):
    """小批量通道：批量 generate + 批量 judge，坏一条只回退那一条。

    polish 仍是单条：它只改文字，批量收益小，且失败隔离必须精确到词。
    任何批量/单项异常都交给 _work(item) 走原有单词精修通道，不拖累同批。
    """
    store = api._state["store"]
    prepared = []
    for raw in items:
        item = dict(raw)
        item["history"] = api.history.build(
            store.data_dir, item.get("word") or "", item.get("instruction") or "", scope=item,
        )
        prompt_hash = api.pipeline.prompt_hash()
        instruction_hash = api.pipeline.instruction_hash(item)
        if api.REUSE_HISTORY:
            reused = store.find_reusable_result(item, prompt_hash, instruction_hash)
            if reused:
                api._finish_reuse(item, reused, prompt_hash, instruction_hash)
                continue
        prepared.append((item, prompt_hash, instruction_hash))
    if not prepared:
        return

    # 批量通道也要按本轮任务重设优先级：providers 的优先级是 thread-local，
    # 复用上一单残留值会让前台批被 foreground 阶段误挡（或让离线批冒充前台）。
    # 一批可能混有不同优先级，取最高的一个，避免把前台任务挡在门外。
    priorities = []
    for item, _, _ in prepared:
        try:
            priorities.append(int(item.get("priority", 100)))
        except (TypeError, ValueError):
            priorities.append(100)
    api.providers.set_task_priority(max(priorities))

    api.log("批处理 %d 词：%s" % (len(prepared), "、".join(i["word"] for i, _, _ in prepared)))
    for item, _, _ in prepared:
        try:
            store.set_stage(item["clientId"], "generate")
        except Exception:  # noqa: BLE001 - 埋点别拖垮生成
            pass
    try:
        drafts = api.batch_pipeline.generate_batch([item for item, _, _ in prepared])
    except Exception as exc:  # noqa: BLE001 - 批通道坏了整批回退单条
        api.log("批生成失败，整批回退单条：%s" % str(exc)[:200])
        for item, _, _ in prepared:
            api._work(item)
        return

    ready = []
    fallback = []
    for item, prompt_hash, instruction_hash in prepared:
        word = item["word"]
        draft = drafts.get(word) or drafts.get(word.casefold()) or drafts.get(word.lower())
        if not isinstance(draft, dict):
            fallback.append((item, prompt_hash, instruction_hash))
            continue
        try:
            store.set_stage(item["clientId"], "polish")
            approach = api.pipeline.polish(item, draft)
            bans = api.pipeline.banned_terms(item.get("instruction"))
            violations = list(draft.get("violations") or [])
            if bans:
                for hit in api.pipeline._text_hits(bans, approach, allow_last_note=True):
                    if hit not in violations:
                        violations.append(hit)
            hard = api.pipeline.rules_hard.check(
                approach,
                elements=draft.get("elements"), target_word=item.get("word"),
                instruction=str(item.get("instruction") or ""),
                banned_terms=[ban["needle"] for ban in bans] if bans else None,
            )
            if hard.ok and not violations:
                ready.append((item, draft, approach, prompt_hash, instruction_hash))
            else:
                fallback.append((item, prompt_hash, instruction_hash))
        except Exception as exc:  # noqa: BLE001 - 单条润色坏了只回退该词
            api.log("[%s] 批润色失败，回退单条：%s" % (word, str(exc)[:160]))
            fallback.append((item, prompt_hash, instruction_hash))

    if ready:
        try:
            judged = api.batch_pipeline.judge_batch(
                [item for item, _, _, _, _ in ready],
                {item["word"]: draft for item, draft, _, _, _ in ready},
                {item["word"]: approach for item, _, approach, _, _ in ready},
            )
        except Exception as exc:  # noqa: BLE001 - 批评审坏了整批回退
            api.log("批评审失败，整批回退单条：%s" % str(exc)[:200])
            judged = {}
        for item, draft, approach, prompt_hash, instruction_hash in ready:
            result = judged.get(item["word"]) or judged.get(item["word"].casefold())
            if result is None:
                fallback.append((item, prompt_hash, instruction_hash))
                continue
            scores, issues, intent, same_method, history_repeat = result
            binary = getattr(result, "binary", None)
            scores, _, blocked = api.pipeline.style_gate(item, draft, approach, scores)
            if blocked or not api.pipeline._passed(
                item, scores, intent, same_method, history_repeat,
                binary=binary, hard_ok=True,
            ):
                fallback.append((item, prompt_hash, instruction_hash))
                continue
            meta = {
                "method": draft.get("method") or "",
                "scores": scores,
                "attempts": 1,
                "unverified": False,
                "reason": "",
                "promptHash": prompt_hash,
                "instructionHash": instruction_hash,
            }
            if store.finish_ok(item, approach, meta):
                api.log("[%s] 批完成（未回退）" % item["word"])

    for item, _, _ in fallback:
        api._work(item)


def _worker(api, index):
    store = api._state["store"]
    while True:
        # 整轮包住：_claim_batch / _work / _work_batch 任何一步抛异常，
        # 都把本轮已经拿到、仍停在 running 的任务退回 retry_wait，
        # 杜绝 chair 类僵尸 running 占着工位却没人再管。
        claimed = []
        try:
            if api.BATCH_ENABLED and api.BATCH_SIZE > 1:
                items = api._claim_batch(api.BATCH_SIZE, claimed)
                if not items:
                    api.log("工人 %d 退出" % index)
                    return
                if len(items) == 1:
                    api._work(items[0])
                else:
                    api._work_batch(items)
            else:
                item = store.claim(60)
                if item is None:
                    api.log("工人 %d 退出" % index)
                    return
                claimed.append(item)
                api._work(item)
        except Exception as exc:  # noqa: BLE001 - 工人不能因为一次抢单/落盘抖动就永久消失
            api.log("工人 %d 异常，1s 后继续：%r" % (index, exc))
            for item in claimed:
                try:
                    released = store.release_claim(item, "工人异常：%r" % (exc,))
                except Exception as release_exc:  # noqa: BLE001 - 一条释放失败不能挡住其余
                    api.log("[%s] 释放认领失败：%r" % (item.get("word") or "?", release_exc))
                    continue
                # release_claim 只在 generation 未变且 state 仍 running 时生效，
                # 已经完成的同轮任务会返回 None，这里不会误释放。
                if released:
                    api.log("[%s] 工人异常，已释放回重试：%s" % (
                        item.get("word") or "?", released.get("nextRunAt", "")))
            api.time.sleep(1.0)

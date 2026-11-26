"""阶段调度、断点重跑与最佳候选选择。"""

def _safe_log(sink, message):
    """写日志绝不能让流水线断掉。

    Windows GBK 控制台遇到 IPA（如 /ə/）会在 print 时抛 UnicodeEncodeError。
    先原样写；失败则把当前控制台编不了的字符替换掉再写一次，宁可日志丑，
    也不能让已经生成的 fallback 稿丢在最后一行日志上。
    """
    if not sink:
        return
    try:
        sink(message)
        return
    except UnicodeEncodeError:
        pass
    except Exception:  # noqa: BLE001 - 日志本身是旁路，不能反噬主流程
        return
    try:
        sink(str(message).encode("gbk", "replace").decode("gbk", "replace"))
    except Exception:  # noqa: BLE001
        try:
            sink(str(message).encode("ascii", "replace").decode("ascii"))
        except Exception:  # noqa: BLE001
            pass


def run(api, item, log=None, on_stage=None):
    """跑完一条词。返回 {approach, method, scores, attempts, timings, unverified, reason}；失败抛 PipelineError。

    两轮都没过时不再交白卷：挑历史最佳交付，带 unverified=True 与 reason。
    只有一次可用结果都没有（全是模型/JSON 错误）才抛错。

    on_stage 在每次阶段切换时被调用（generate → polish → judge → done），只做埋点：
    它自己出问题不该拖垮这条词的生成。
    """
    timings = {}
    feedback = ""
    feedback_hard = False
    problems = []
    history = []  # 拿到成品+分数的轮次，兜底从这里挑
    errors = []  # 模型/JSON 错误（这些轮次没有产物）
    fatal = None
    started = api.time.time()

    def stage(name):
        if not on_stage:
            return
        try:
            on_stage(name)
        except Exception:  # noqa: BLE001 - 埋点坏了照常跑
            pass

    draft = None
    approach = None
    resume = "generate"
    for attempt in range(1, api.MAX_ATTEMPTS + 1):
        stage(resume)
        stamp = api.time.time()
        try:
            if resume == "generate":
                draft = api.generate(item, feedback, hard=feedback_hard)
                timings["generate_%d" % attempt] = round(api.time.time() - stamp, 1)
                api._safe_log(log, "[%s] 生成 %.1fs" % (item["word"], timings["generate_%d" % attempt]))
                resume = "polish"
            else:
                # 断点续跑：上一次已经拿到 draft/approach，网络抖动或超时后
                # 不要重烧前面几十秒的模型调用。
                timings["generate_%d" % attempt] = 0.0
                if draft is None:
                    resume = "generate"
                    draft = api.generate(item, feedback, hard=feedback_hard)
                    timings["generate_%d" % attempt] = round(api.time.time() - stamp, 1)
                    resume = "polish"

            if resume == "polish":
                stage("polish")
                stamp = api.time.time()
                approach = api.polish(item, draft)
                timings["polish_%d" % attempt] = round(api.time.time() - stamp, 1)
                resume = "judge"
            else:
                timings["polish_%d" % attempt] = 0.0
            # 润色可能把用户禁用的招捡回来：以最终稿再扫一遍，命中就记进 violations。
            _bans = api.banned_terms(item.get("instruction"))
            if _bans:
                for hit in api._text_hits(_bans, approach, allow_last_note=True):
                    if hit not in draft.setdefault("violations", []):
                        draft["violations"].append(hit)

            # 0ms 硬拦截：行数/箭头/教材腔/翻译腔/用户禁令命中就直接打回，
            # 不花 Judge Token。feedback 直接进下一轮生成。
            hard = api.rules_hard.check(
                approach,
                elements=draft.get("elements"), target_word=item.get("word"),
                instruction=str(item.get("instruction") or ""),
                banned_terms=[ban["needle"] for ban in _bans] if _bans else None,
            )
            if hard.ok:
                stage("judge")
                stamp = api.time.time()
                judged = api.judge(item, draft, approach)
                scores, issues, intent, same_method, history_repeat = judged
                binary = getattr(judged, "binary", None)
                timings["judge_%d" % attempt] = round(api.time.time() - stamp, 1)
                # 评委之后再过一遍黑榜（config/style_rules.json）：命中按规则硬扣分，
                # block 级（慢动作铺垫 / 生僻硬拆 / 标签贴歪）当轮直接打回。
                scores, style_misses, style_rejected = api.style_gate(item, draft, approach, scores)
                if style_misses:
                    issues = [hit["message"] for hit in style_misses] + list(issues)
                    api._safe_log(log, "[%s] 第%d轮 黑榜命中：%s" % (
                        item["word"], attempt, "、".join(hit["id"] for hit in style_misses)))
            else:
                # 没进评委：给一套全 0 的兼容分数，兜底排序时排在有成品分数的稿后面。
                scores = api._zero_scores(item)
                issues = [hard.feedback]
                intent, same_method, history_repeat, binary = "other", False, False, None
                timings["judge_%d" % attempt] = 0.0
                style_misses, style_rejected = [], False
        except (api.PipelineError, api.providers.ProviderError) as exc:
            # 一轮坏了不该让整条词蒸发：记下来换下一轮，历史里还有稿子就继续兜底
            errors.append("第%d轮：%s" % (attempt, str(exc)[:200]))
            fatal = fatal or exc
            api._safe_log(log, "[%s] 第%d轮作废：%s" % (item["word"], attempt, str(exc)[:200]))
            feedback = ""
            if isinstance(exc, api.providers.ProviderError):
                # 网络/网关错误：保留当前断点，下一轮从失败阶段继续。
                pass
            elif resume == "judge" and approach:
                # 评委返回坏 JSON：成品还在，下一轮只重判，不重写。
                pass
            else:
                draft, approach, resume = None, None, "generate"
            continue

        low = api._min_six(scores)
        violations = list(draft.get("violations") or [])
        # 黑榜 block 级命中 = 这版不能当「已复核」交付，和硬拦截同等处理。
        hard_ok = hard.ok and not violations and not style_rejected
        history.append(
            {
                "attempt": attempt,
                "approach": approach,
                "method": draft["method"],
                "scores": scores,
                "violations": violations,
                "hard_ok": hard_ok,
                "hard_feedback": hard.feedback,
                "binary": binary,
                "style_hits": [hit["id"] for hit in style_misses],
            }
        )
        if hard.ok:
            api._safe_log(
                log,
                "[%s] 第%d轮 润色 %.1fs 评分 %.1fs 最低 %d 分 %s intent=%s same_method=%s 排雷=%s"
                % (
                    item["word"], attempt, timings.get("polish_%d" % attempt, 0),
                    timings["judge_%d" % attempt], low,
                    api.json.dumps(scores, ensure_ascii=False), intent, same_method,
                    api.json.dumps(binary, ensure_ascii=False) if binary else "legacy",
                ),
            )
        else:
            api._safe_log(log, "[%s] 第%d轮 硬拦截：%s" % (
                item["word"], attempt, "；".join(hard.hits or violations)[:240],
            ))
        # 硬拦截干净 + 五道排雷全 false + 历史不重复，才允许当「已复核」交付。
        if hard_ok and api._passed(
            item, scores, intent, same_method, history_repeat, binary=binary, hard_ok=True
        ):
            stage("done")
            timings["total"] = round(api.time.time() - started, 1)
            return {
                "approach": approach,
                "method": draft["method"],
                "scores": scores,
                "attempts": attempt,
                "timings": timings,
                "unverified": False,
                "reason": "",
            }

        problems = list(issues)
        if not hard.ok:
            problems = [hard.feedback] + [p for p in problems if p != hard.feedback]
        if violations:
            problems.insert(
                0,
                "用户明令禁止的「%s」仍在稿子里（用户原话优先）" % "、".join(violations),
            )
        if api._has_instruction(item) and same_method and api._requires_change_method(item, intent):
            problems.insert(0, "这版和上一版是同一条思路，没有解决换方法的诉求")
        if not problems:
            problems = ["最低分 %d（%s）" % (low, api.json.dumps(scores, ensure_ascii=False))]
        feedback = "\n".join("- " + p for p in problems)
        feedback_hard = not hard_ok
        # 质量未过不是网络断点：下一轮必须按 feedback 重新生成，不能复用同一稿。
        draft, approach, resume = None, None, "generate"

    if not history:
        detail = "；".join(errors)[:300]
        if isinstance(fatal, api.providers.ProviderError):
            raise fatal  # 全是调用失败：保留原始类型，service 那边记成「模型调用失败」
        raise api.PipelineError("两轮都没有可用结果：%s" % detail)

    best = max(history, key=api._rank)
    stage("done")
    timings["total"] = round(api.time.time() - started, 1)
    reason = "两轮都没过评审判定，交付历史最佳（第%d轮）；最后一轮问题：%s" % (
        best["attempt"],
        "；".join(problems)[:240],
    )
    if best["violations"]:
        reason += "；仍含用户禁令词：%s" % "、".join(best["violations"])
    if errors:
        reason += "；作废轮次：%s" % "；".join(errors)[:200]
    api._safe_log(log, "[%s] 质量未过（%d 次失败），保留最佳候选待重试：%s" % (
        item["word"], len(history) + len(errors), reason[:200]))
    return {
        "approach": best["approach"],
        "method": best["method"],
        "scores": best["scores"],
        "attempts": len(history) + len(errors),
        "timings": timings,
        "unverified": True,
        "quality_rejected": True,
        "reason": reason[:400],
    }

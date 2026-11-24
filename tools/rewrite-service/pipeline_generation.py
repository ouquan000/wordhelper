"""提示词指纹、输入组装、生成与润色。"""

def _prompt(api, name):
    with open(api.os.path.join(api.PROMPT_DIR, name + ".txt"), "r", encoding="utf-8-sig") as fh:
        return fh.read()


def prompt_hash(api):
    """提示词 + 硬规则文件的稳定指纹；任一变化都不能复用旧成品。"""
    digest = api.hashlib.sha256()
    for name in ("generate", "polish", "judge"):
        digest.update(name.encode("ascii"))
        digest.update(b"\0")
        digest.update(api._prompt(name).encode("utf-8"))
        digest.update(b"\0")
    # 批量通道的提示词也影响成品；文件存在就一起纳入指纹。
    for name in ("generate_batch", "judge_batch"):
        path = api.os.path.join(api.PROMPT_DIR, name + ".txt")
        try:
            with open(path, "r", encoding="utf-8-sig") as fh:
                digest.update(name.encode("ascii"))
                digest.update(b"\0")
                digest.update(fh.read().encode("utf-8"))
                digest.update(b"\0")
        except OSError:
            pass
    # rules_hard 改了，旧结果可能已不满足新硬门槛；一起纳指纹，宁可少复用。
    try:
        with open(api.os.path.join(api.HERE, "rules_hard.py"), "r", encoding="utf-8-sig") as fh:
            digest.update(b"rules_hard\0")
            digest.update(fh.read().encode("utf-8"))
    except OSError:
        pass
    # 风格规则（红黑榜/方法权重/扣分表）改了，成品手感就变了，同样不能复用旧稿。
    try:
        with open(api.STYLE_RULES_PATH, "r", encoding="utf-8-sig") as fh:
            digest.update(b"style_rules\0")
            digest.update(fh.read().encode("utf-8"))
    except OSError:
        pass
    return digest.hexdigest()


def instruction_hash(api, item):
    """用户原话指纹；空原话也有明确值，不能和任意旧结果误配。"""
    text = str((item or {}).get("instruction") or "").strip()
    return api.hashlib.sha256(text.encode("utf-8")).hexdigest()


def _has_instruction(item):
    return bool(str(item.get("instruction") or "").strip())


def _brief(item):
    rows = ["单词：" + item["word"]]
    for label, key in (
        ("音标", "phonetic"),
        ("词性", "pos"),
        ("释义", "meaning"),
        ("用户这一版指出的问题（原话）", "instruction"),
    ):
        if item.get(key):
            rows.append("%s：%s" % (label, item[key]))
    if item.get("currentApproach"):
        rows.append("旧版思路（别再抄一遍，换个更顺的）：\n" + "\n".join(item["currentApproach"]))
    history = str(item.get("history") or "").strip()
    if history:
        rows.append(
            "【这个词的历史（用户原话与失败路线是硬约束；被点赞的版本要保住）】\n" + history
        )
    return "\n".join(rows)


def _judge_brief(item):
    """评委只看得到「词 + 用户原话 + 上一版成品」，看不到生成过程。

    上一版必须给：只给抱怨时，评委没法判断这版是不是「同一条思路换了措辞」。
    """
    rows = []
    for label, key in (("单词", "word"), ("音标", "phonetic"), ("词性", "pos"), ("释义", "meaning")):
        if item.get(key):
            rows.append("%s：%s" % (label, item[key]))
    instruction = str(item.get("instruction") or "").strip()
    if instruction:
        rows.append("【用户这一版指出的问题（原话，未改写）】\n" + instruction)
    prev = [str(x).strip() for x in (item.get("currentApproach") or []) if str(x).strip()]
    if prev:
        rows.append("【上一版成品（被用户否掉的那一版 3–5 步）】\n" + "\n".join(prev[:5]))
    history = str(item.get("history") or "").strip()
    if history:
        # 后台自动重写没有新的用户原话时，评委只看上一版会漏掉「这个词已经被
        # 同一条路线坑过好几次」。历史必须进评委上下文，否则它会继续给同一招打高分。
        rows.append(
            "【这个词的历史（用户原话/被点踩路线/被点赞版本）】\n"
            "这是判 same_method 与诉求落实的硬证据；历史里同一方法被用户明确否掉过一次，"
            "新版仍以该方法为主线就算没解决（只有更晚的明确允许可解除）。\n" + history
        )
    return "\n".join(rows)


# ------------------------------------------------------------------ 用户禁令


# ------------------------------------------------------------------ 跨词经验
def _recommended_methods(api, item):
    """画像 Top-2：只作倾向提示，权重低于用户原话、高于默认模板。

    v1.5.3 起再过一道风格权重：实测吃不开的招（polysemy 0%）不给提示，
    其余按 sound 73.9% > meaning 9.4% > root 8.2% … 的顺序排，放大谐音优势。
    """
    try:
        rows = api.profile.recommend_top(2, item.get("history") or "")
    except Exception:  # noqa: BLE001 - 画像坏了不能拖垮生成
        return []
    rows = [row for row in rows if row in api.METHOD_KEYS.values()][:2]
    weights = api.method_weights()
    rows = [row for row in rows if weights.get(row, 1.0) > 0]
    return sorted(rows, key=lambda row: -weights.get(row, 0.0))


def _exemplar_block(api, method):
    """黄金正例 2 条 + 避雷反例 1 条；检索不到就返回空串，流水线照跑。"""
    try:
        data = api.exemplars.retrieve(method or "")
    except Exception:  # noqa: BLE001 - 样本库坏了不能拖垮生成
        return ""
    pos = (data or {}).get("pos") or []
    neg = (data or {}).get("neg") or []
    if not pos and not neg:
        return ""
    rows = []
    if pos:
        rows.append("【黄金正例·照这个手感写，不许抄词】")
        for item in pos[:2]:
            rows.append(
                "- %s（%s）：%s" % (
                    item.get("word") or "?",
                    item.get("method") or "?",
                    " ".join(item.get("derivation") or []),
                )
            )
    if neg:
        rows.append("【避雷反例·用户踩过的坑，别复现】")
        for item in neg[:1]:
            rows.append(
                "- %s（%s）：用户原话「%s」→ %s" % (
                    item.get("word") or "?",
                    item.get("method") or "?",
                    item.get("note") or "没给原因",
                    " ".join(item.get("derivation") or []),
                )
            )
    return "\n".join(rows)


def _zero_scores(api, item):
    scores = {dim: 0 for dim in api.JUDGE_DIMENSIONS}
    if api._has_instruction(item):
        scores[api.COMPLIANCE_DIM] = 0
    return scores


# ------------------------------------------------------------------ 三步


def _generate_once(api, item, feedback="", hard=False):
    user = api._brief(item)
    instruction = str(item.get("instruction") or "").strip()
    if instruction:
        user += (
            "\n\n用户这一版的原话是硬约束，优先级高于任何评委意见："
            "他明令不要的招或元素，不许再当主线。"
        )
    ranked = api._recommended_methods(item)
    if ranked:
        user += (
            "\n\n【跨词经验·这一版优先试（只是倾向，不是命令）】\n"
            + " > ".join(ranked)
            + "\n顺序仅供参考；与用户原话或本词实际情况冲突时，以用户原话为准。"
        )
    samples = api._exemplar_block(ranked[0] if ranked else "")
    if samples:
        user += "\n\n" + samples
    # 红黑榜（config/style_rules.json）：红榜给手感锚点，黑榜给硬约束。
    # 放在用户原话与重试反馈之前，越靠近末尾的指令优先级越高。
    style = api.style_block(ranked[0] if ranked else "")
    if style:
        user += "\n\n" + style
    if feedback:
        if instruction:
            user += (
                "\n\n【硬约束·用户原话再贴一遍（照抄，不许改写）】\n" + instruction
            )
        if hard:
            user += (
                "\n\n上一版被确定性硬拦截打回。下面是机械规则查出的问题，"
                "必须逐条改掉（这不是评委建议，不能反驳）：\n" + feedback
                + "\n\n先满足用户原话；硬拦截条目必须清零，否则这一轮不会进评委。"
            )
        else:
            user += (
                "\n\n上一版没过。下面是评委的软建议（参考为主，可反驳）：\n" + feedback
                + "\n\n先满足用户原话，再考虑评委意见；两者冲突时一律以用户原话为准 —— "
                "用户明令不要的，不许为了讨好评委请回来。"
            )
    text = api.providers.complete(
        item["model"],
        api._prompt("generate"),
        user,
        max_tokens=12000,  # 推理型模型（step-5 这类）会把几千 token 花在思考上，6000 不够会返回空 content
        temperature=0.9,
        timeout=300,
    )
    draft = api.extract_json(text)
    if not draft:
        raise api.PipelineError("生成结果不是 JSON：%s" % text[:200])
    # split 允许数组或字符串：统一成「元素、元素」的短句，给润色当拆法参考。
    raw_split = draft.get("split") or []
    if isinstance(raw_split, str):
        raw_split = [raw_split]
    split = "、".join(str(x).strip() for x in raw_split if str(x).strip())
    # elements 现在是对象数组（part/method/reasoning），这里只要纯净的元素名。
    raw_elements = draft.get("elements") or []
    elements = []
    for item_el in raw_elements[:8]:
        if isinstance(item_el, dict):
            part = str(item_el.get("part") or "").strip()
        else:
            part = str(item_el).strip()
        if part:
            elements.append(part)
    return {
        "method": api._method_key(draft.get("method")),
        "split": split,
        "elements": elements,
        "derivation": draft.get("derivation") or [],
    }


def generate(api, item, feedback="", hard=False):
    """第一步：出草稿。feedback 只在重试那一轮有 —— 把评委的意见还给选手。

    用户原话同时是硬约束：草稿踩到「不要 X」黑名单就带着违规项重生成一次。
    只加一次生成调用，不动 run 的轮数上限。
    """
    bans = api.banned_terms(item.get("instruction"))
    draft = api._generate_once(item, feedback, hard=hard)
    hits = api._draft_hits(bans, draft)
    if hits:
        note = (
            "你违反了用户禁令：%s。用户原话：%s\n"
            "这是硬约束：这些招／元素不许出现在拆法与推导里（只有末行顺带提一嘴才放行）。"
            % ("、".join(hits[:3]), str(item.get("instruction") or "").strip())
        )
        draft = api._generate_once(item, (feedback + "\n\n" + note) if feedback else note, hard=True)
        hits = api._draft_hits(bans, draft)
    draft["violations"] = hits
    return draft


def polish(api, item, draft):
    """第二步：单独开一次请求磨文字。磨坏了就退回草稿，别让润色拖垮整条链。"""
    draft_lines = api.normalize(draft["derivation"])
    if not draft_lines:
        raise api.PipelineError("草稿的 derivation 不是合规的 3–5 步：%s" % draft["derivation"])
    user = "%s\n\n拆法：%s\n\n待润色的 3–5 步：\n%s" % (
        api._brief(item),
        draft["split"] or draft["method"],
        "\n".join(draft_lines),
    )
    samples = api._exemplar_block(draft.get("method") or "")
    if samples:
        user += "\n\n【手感参考·只学说话方式，不许换联想】\n" + samples
    # 润色同样要吃红黑榜：黑榜里的「早破题 / 不硬拆 / 标签不贴歪」在润色阶段最容易被磨掉。
    style = api.style_block(draft.get("method") or "")
    if style:
        user += "\n\n" + style
    bans = api.banned_terms(item.get("instruction"))
    if bans:
        user += (
            "\n\n【用户禁令·润色只许收紧】用户原话：%s\n"
            "上面这些被用户否掉的招／元素，不许借润色回到主线，也不许为了顺口补一个新的。"
            % "、".join(ban["needle"] for ban in bans)
        )
    text = api.providers.complete(
        item["model"],
        api._prompt("polish"),
        user,
        max_tokens=12000,  # 润色同样要给推理留足余量：step-5 在 6000 时也会返回空 content
        temperature=0.4,
        timeout=240,
    )
    data = api.extract_json(text)
    polished = api.normalize((data or {}).get("derivation") or [])
    return api._ensure_phonetic_warning(item, draft.get("method"), polished or draft_lines)

"""评分协议、评委调用、通过条件与候选排序。"""

import taste_flow

PASS_LINE = 8  # judge.txt 的及格线：六维每一维都要 ≥8
COMPLIANCE_DIM = "诉求落实"  # 第 7 维：用户这一版指出的问题，这版到底处理了没有
COMPLIANCE_FLOOR = 6  # 两段式的第二段：诉求到位时，六维允许的最低分（真换了招的稿子常有一维 6–7）
JUDGE_DIMENSIONS = ("拆分成立", "方法选对", "谐音过关", "逻辑连贯", "文本充足", "像人说话")
# 维度改过名（链条闭合 → 逻辑连贯）：模型偶尔还回旧键，映射一下再记账，
# 免得旧键进了 scores、新键又被下面的 setdefault 记 0，把 min() 直接拽到 0。
JUDGE_ALIASES = {
    "链条闭合": "逻辑连贯",
    "链条完整": "逻辑连贯",
    "像不像人说话": "像人说话",
    "解决诉求": COMPLIANCE_DIM,
    "用户诉求": COMPLIANCE_DIM,
    "诉求落实度": COMPLIANCE_DIM,
    "诉求": COMPLIANCE_DIM,
}

# 五道二值排雷题（prompts/judge.txt）。全部 false 才算干净；有疑点按 true。
BINARY_FIELDS = (
    "has_unknown_part",
    "is_robot_tone",
    "is_logic_broken",
    "phonetic_drift_unwarned",
    "violates_user_intent",
)
BINARY_LABELS = {
    "has_unknown_part": "用了用户不认识的生僻词根/偏僻四级词",
    "is_robot_tone": "讲师报幕/词典腔/翻译腔/强行升华",
    "is_logic_broken": "逻辑断链/单字母算命/纯抽象画面",
    "phonetic_drift_unwarned": "谐音偏离音标或拼写拆法没提醒读音",
    "violates_user_intent": "没落实用户原话/把否掉的路线请回来",
}
# 二值结果到旧 scores 的兼容映射：只给记录页显示，不参与通过判定。
BINARY_TO_DIMS = {
    "has_unknown_part": ("拆分成立", "方法选对"),
    "is_robot_tone": ("像人说话",),
    "is_logic_broken": ("逻辑连贯", "文本充足"),
    "phonetic_drift_unwarned": ("谐音过关",),
    "violates_user_intent": (COMPLIANCE_DIM,),
}


class JudgeResult(tuple):
    """保持 judge() 原来的五元组解包，同时把二值排雷结果挂在 .binary 上。"""

    def __new__(cls, scores, issues, intent, same_method, history_repeat, binary=None):
        obj = super().__new__(cls, (scores, issues, intent, same_method, history_repeat))
        obj.binary = binary
        return obj


def _canonical_dim(api, key):
    """把评委返回的维度名归一：先查别名，再做一次近似匹配。

    模型偶尔把「像人说话」写成「像人谐话」；精确查表查不到就会被当成新键，
    真维再被 setdefault 记 0 —— 一张好稿可能因为一个错字撞上「无 0 分」红线被判死。
    """
    raw = str(key).strip()
    if raw in api.JUDGE_DIMENSIONS or raw == api.COMPLIANCE_DIM:
        return raw
    if raw in api.JUDGE_ALIASES:
        return api.JUDGE_ALIASES[raw]
    best, best_ratio = raw, 0.0
    for dim in api.JUDGE_DIMENSIONS + (api.COMPLIANCE_DIM,):
        ratio = api.difflib.SequenceMatcher(None, raw, dim).ratio()
        if ratio > best_ratio:
            best, best_ratio = dim, ratio
    return best if best_ratio >= 0.6 else raw


def _judge_flags(api, item, approach):
    """补判 intent / same_method / history_repeat；只问这三个字段，不重新打分。"""
    target = api.providers.judge_target(item["model"])
    user = "\n\n".join([
        api._judge_brief(item),
        "【新版成品 2–5 步】\n" + "\n".join(approach),
        "只输出 JSON：{\"intent\":\"change_method|explain|fix_detail|other\","
        "\"same_method\":true|false,\"history_repeat\":true|false}",
    ])
    text = api.providers.complete(
        target,
        api._prompt("judge"),
        user,
        max_tokens=512,
        temperature=0.0,
        timeout=120,
    )
    return api.extract_json(text) or {}


def judge_context(api, draft):
    method = api._method_key((draft or {}).get("method"))
    rows = ["本稿方法：" + method, api.style_block(method, judge=True)]
    try:
        samples = api.exemplars.retrieve(method, k_pos=1, k_neg=1)
        samples = taste_flow.refine(samples, data_dir=getattr(api, "DATA_DIR", None))
    except (OSError, ValueError):
        samples = {}
    for kind, label in (("pos", "点赞正例"), ("neg", "点踩反例")):
        for sample in (samples.get(kind) or [])[:1]:
            if sample.get("method") != method:
                continue
            text = " ".join(sample.get("derivation") or [])[:180]
            note = str(sample.get("note") or "")[:60]
            rows.append(f"{label}（仅校准尺度）：{text} {note}")
    return "\n".join(filter(None, rows))


def judge(api, item, draft, approach):
    """第三步：另一个脑子打分。只给它成品，不给前面「怎么想出来的」过程。

    返回 (scores, issues, intent, same_method, history_repeat)。scores 在用户提了意见时含
    「诉求落实」；用户没提意见时这一维不出现，免得把「没抱怨」误判成 0 分。
    """
    target = api.providers.judge_target(item["model"])
    rows = [api._judge_brief(item)]
    if not api._has_instruction(item):
        rows.append("【本轮没有用户意见：第 7 维「诉求落实」不判，也不要输出这个键】")
    rows.append("【新版成品 2–5 步】\n" + "\n".join(approach))
    rows.append(api.judge_context(draft))
    user = "\n\n".join(rows)
    user_short = user + chr(10) * 2 + "（输出从简：issues 最多 2 条，每条不超过 40 字。）"
    text = api.providers.complete(
        target,
        api._prompt("judge"),
        user,
        max_tokens=8192,  # 3000 时 issues 一长就把 JSON 截断，9 分的词会被误判成「不是 JSON」
        temperature=0.2,
        timeout=240,
    )
    data = api.extract_json(text)
    if not data:
        # 截断兜底：再要一次，但只让给两条短评，别让长评把预算吃光
        text = api.providers.complete(
            target,
            api._prompt("judge"),
            user_short,
            max_tokens=8192,
            temperature=0.2,
            timeout=240,
        )
        data = api.extract_json(text)
    if not data:
        raise api.PipelineError("评分结果不是 JSON：%s" % text[:200])
    history_present = bool(str(item.get("history") or "").strip())
    need_intent = api._has_instruction(item)

    def _flags(source):
        return {
            "intent": api._intent_field(source.get("intent")),
            "same_method": api._bool_field(source, "same_method"),
            "history_repeat": api._bool_field(source, "history_repeat"),
        }

    parsed = _flags(data)
    missing = []
    if need_intent and parsed["intent"] is None:
        missing.append("intent")
    if need_intent and parsed["same_method"] is None:
        missing.append("same_method")
    if history_present and parsed["history_repeat"] is None:
        missing.append("history_repeat")
    if missing:
        # 缺字段不当 false：有界补判一次；仍缺就延后（抛错→service 重试），绝不应用。
        patch = _flags(api._judge_flags(item, approach))
        for key in missing:
            if parsed[key] is None and patch[key] is not None:
                data[key] = patch[key]
        parsed = _flags(data)
        still_missing = []
        if need_intent and parsed["intent"] is None:
            still_missing.append("intent")
        if need_intent and parsed["same_method"] is None:
            still_missing.append("same_method")
        if history_present and parsed["history_repeat"] is None:
            still_missing.append("history_repeat")
        if still_missing:
            raise api.PipelineError("评委缺字段，已补判一次仍缺失：%s" % ",".join(still_missing))
    binary = {key: api._bool_field(data, key) for key in api.BINARY_FIELDS}
    binary_present = any(value is not None for value in binary.values())
    scores = {}
    raw_scores = data.get("scores")
    if isinstance(raw_scores, dict):
        for key, value in raw_scores.items():
            try:
                scores[api._canonical_dim(key)] = int(round(float(value)))
            except (TypeError, ValueError):
                continue
    if binary_present:
        # 新评委只回五道二值题；scores 缺失时按映射合成，旧记录页照常显示。
        for field, dims in api.BINARY_TO_DIMS.items():
            value = binary[field]
            score = 10 if value is False else (0 if value is True else 5)
            for dim in dims:
                scores.setdefault(dim, score)
    if not scores:
        raise api.PipelineError("评分没给出任何维度分数")
    # 漏项按 0 处理：只给一两个维度打分时，min() 会误判成「全过」
    for key in api.JUDGE_DIMENSIONS:
        scores.setdefault(key, 0)
    if api._has_instruction(item):
        # 用户提了意见却拿不到「诉求落实」→ 按 0，绝不当成「没意见所以不扣分」
        scores.setdefault(api.COMPLIANCE_DIM, 0)
    else:
        scores.pop(api.COMPLIANCE_DIM, None)
    return api.JudgeResult(
        scores,
        [str(x) for x in (data.get("issues") or [])][:6],
        api._intent(data.get("intent")),
        bool(api._bool_field(data, "same_method")),
        bool(api._bool_field(data, "history_repeat")),
        binary=binary if binary_present else None,
    )


# --------------------------------------------------------------- 黑榜判定（评委侧）
# 评委给完分之后再过一遍机械可判的黑榜条目：命中就按 black_rules[].penalty 硬扣分，
# block 级（reject_on 里那几个）直接打回，当轮不进交付。语义判断（高阶词倒教基础词）
# 交给评委二值题 has_unknown_part 与 rules_hard 的生僻零件表，这里不硬猜。


def _min_six(api, scores):
    return min(scores.get(dim, 0) for dim in api.JUDGE_DIMENSIONS)


def _passed(api, item, scores, intent=None, same_method=False, history_repeat=False, binary=None, hard_ok=True):
    """验收：新评委走五道二值排雷；老评委/旧测试回落到 scores。

    新判据（prompts/judge.txt）：
      rules_hard 0 命中 AND 五道二值全 false AND history_repeat=false。
      例外：没有用户原话时，violates_user_intent 的 None 表示“不适用”，
      可放行；其余四项仍须严格 false。
    旧 scores 只作兼容：没有 binary 字段时，仍按原来的两段式判。
    """
    if not hard_ok:
        return False
    if history_repeat:
        # 历史里用户明确否掉同一方法，新版还走这条线：换措辞不算解决。
        return False
    # 同方法硬闸必须看用户原话，不能只看评委 intent：
    # - 评委可能把「换个方法」回成 other，导致明确的换法请求被放行；
    # - 评委也可能把「不用换方法」回成 change_method，误伤合规的同方法稿。
    if api._has_instruction(item) and same_method and api._requires_change_method(item, intent):
        return False
    if binary is not None:
        # 新判据：五道排雷题必须全 false；缺字段/含糊一律不算干净。
        # 例外：用户没留原话时，第 5 题（是否违背用户原话）不适用。judge.txt
        # 明确不要求输出该键，judge() 也会把它解析成 None；此时 None 视为通过，
        # 但其余四项仍必须严格 false，且 violates_user_intent=true 依旧拦截。
        if len(binary) != len(api.BINARY_FIELDS):
            return False
        for key in api.BINARY_FIELDS:
            value = binary.get(key)
            if key == "violates_user_intent" and not api._has_instruction(item):
                if value is not False and value is not None:
                    return False
                continue
            if value is not False:
                return False
        return True
    low_six = api._min_six(scores)
    if not api._has_instruction(item):
        return low_six >= api.PASS_LINE
    compliance = scores.get(api.COMPLIANCE_DIM, 0)
    if low_six >= api.PASS_LINE and compliance >= api.PASS_LINE:
        return True
    # 第二段：真换了招／真补了说明，允许六维里有一项落到 6–7 分，但硬红线 0 分不算
    return compliance >= api.PASS_LINE and low_six >= api.COMPLIANCE_FLOOR and 0 not in (
        [scores.get(dim, 0) for dim in api.JUDGE_DIMENSIONS] + [compliance]
    )


def _rank(api, row):
    """兜底挑稿顺序：先看硬拦截是否干净，再看诉求分，最后看六维最低分。

    兼容旧调用：_rank 原来收的是 scores 字典；现在也接受带 scores 的历史行。
    """
    scores = row.get("scores") if isinstance(row, dict) else None
    if not isinstance(scores, dict):
        scores = row if isinstance(row, dict) else {}
    hard_ok = 1 if (not isinstance(row, dict) or row.get("hard_ok", True)) else 0
    return (hard_ok, scores.get(api.COMPLIANCE_DIM, 0), api._min_six(scores))

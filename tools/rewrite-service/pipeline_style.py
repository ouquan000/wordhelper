"""风格配置、红黑榜与评分扣分。"""

import re


def load_style_rules(api, path=None):
    """读风格规则；缺失/坏 JSON/结构不对一律返回 {}，由调用方回落到兜底常量。"""
    path = path or api.STYLE_RULES_PATH
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            data = api.json.load(fh)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def style_rules(api):
    """带缓存的风格规则；同进程内只读一次盘（测试可用 reload_style_rules 强刷）。"""
    path = api.STYLE_RULES_PATH
    if api._STYLE_CACHE["path"] != path or api._STYLE_CACHE["data"] is None:
        api._STYLE_CACHE["data"] = api.load_style_rules(path)
        api._STYLE_CACHE["path"] = path
    return api._STYLE_CACHE["data"]


def reload_style_rules(api, path=None):
    """换路径/改文件后重读；返回是否读到可用配置。"""

    if path:
        api.STYLE_RULES_PATH = path
    api._STYLE_CACHE["path"] = None
    api._STYLE_CACHE["data"] = None
    return bool(api.style_rules())


def style_active(api):
    """config/style_rules.json 是否真的被吃进来了（手机端/面板要能看出来）。"""
    return bool(api.style_rules())


def method_weights(api):
    """方法路由权重：sound 73.9% 实测优势，meaning/polysemy 只做保底。"""
    raw = api.style_rules().get("method_weights")
    if not isinstance(raw, dict):
        return dict(api.DEFAULT_METHOD_WEIGHTS)
    out = dict(api.DEFAULT_METHOD_WEIGHTS)
    for key, value in raw.items():
        try:
            out[str(key)] = float(value)
        except (TypeError, ValueError):
            continue
    return out


def method_weight(api, method):
    return api.method_weights().get(api._method_key(method) or str(method or ""), 0.0)


def method_penalty(api):
    """meaning / polysemy 当主线时，「方法选对」要扣多少分。"""
    raw = api.style_rules().get("method_penalty")
    if not isinstance(raw, dict):
        return dict(api.DEFAULT_METHOD_PENALTY)
    out = dict(api.DEFAULT_METHOD_PENALTY)
    for key, value in raw.items():
        try:
            out[str(key)] = int(value)
        except (TypeError, ValueError):
            continue
    return out


def black_rules(api):
    rows = api.style_rules().get("black_rules")
    return [row for row in rows if isinstance(row, dict)] if isinstance(rows, list) else []


def black_rule(api, rule_id):
    for row in api.black_rules():
        if row.get("id") == rule_id:
            return row
    return {}


def reject_on(api):
    rows = (api.style_rules().get("judge_rules") or {}).get("reject_on")
    return tuple(rows) if isinstance(rows, list) else api.DEFAULT_REJECT_ON


def _black_rule_block(api, limit=6):
    """黑榜铁律文本：给生成/润色侧的硬约束清单。"""
    rows = api.black_rules()
    if not rows:
        return ""
    header = (api.style_rules().get("few_shot") or {}).get("black_header") or "【黑榜铁律·命中即打回】"
    lines = [header]
    for row in rows[:limit]:
        lines.append("- %s：%s" % (row.get("name") or row.get("id"), row.get("detail") or ""))
        if row.get("fix"):
            lines.append("  改法：%s" % row.get("fix"))
    return "\n".join(lines)


def _red_sample_block(api, method="", limit=2):
    """红榜 few-shot：优先给同招的样板，不够再补其它招；只学手感，不许抄词。"""
    rows = api.style_rules().get("red_samples")
    if not isinstance(rows, list) or not rows:
        return ""
    key = api._method_key(method) or ""
    same = [row for row in rows if isinstance(row, dict) and key and row.get("method") == key]
    rest = [row for row in rows if isinstance(row, dict) and row not in same]
    picked = (same + rest)[:limit]
    if not picked:
        return ""
    header = (api.style_rules().get("few_shot") or {}).get("red_header") or "【红榜手感·照这个手感写，不许抄词】"
    out = [header]
    for row in picked:
        out.append("- %s（%s·%s）：%s" % (
            row.get("word") or "?",
            row.get("method") or "?",
            row.get("tag") or "",
            " ".join(row.get("derivation") or []),
        ))
        if row.get("why"):
            out.append("  为什么算红榜：%s" % row.get("why"))
    return "\n".join(out)


def style_block(api, method="", judge=False):
    """生成/润色/评委三侧共用的风格注入块；配置缺失时返回空串，行为与旧版一致。"""
    if not api.style_active():
        return ""
    few = api.style_rules().get("few_shot") or {}
    lines = []
    weights = api.method_weights()
    if api.judge:
        lines.append("【风格规则·黑榜（v1.5.3）】")
        for row in api.black_rules():
            lines.append("- %s（%s）：%s" % (
                row.get("name") or row.get("id"),
                "打回" if row.get("severity") == "block" else "扣分",
                row.get("detail") or "",
            ))
        if weights:
            lines.append(
                "方法权重实测："
                + "、".join("%s %.1f%%" % (k, v) for k, v in sorted(weights.items(), key=lambda kv: -kv[1]))
                + "；meaning / polysemy 只有一种情况算对：别的招确实做不出画面。"
            )
        return "\n".join(lines)
    red = api._red_sample_block(method or "", 2)
    if red:
        lines.append(red)
    if few.get("keep_magic"):
        lines.append("【手感底线】" + str(few.get("keep_magic")))
    if few.get("persona"):
        lines.append(str(few.get("persona")))
    black = api._black_rule_block()
    if black:
        lines.append(black)
    if weights:
        lines.append(
            "【方法权重实测·按这个比例选招】"
            + "、".join("%s %.1f%%" % (k, v) for k, v in sorted(weights.items(), key=lambda kv: -kv[1]))
            + "\nmeaning（含义联想）和 polysemy（熟词生义）是保底：只有 sound / root / shape / contrast "
            "都做不出画面时才允许，且画面必须和读音或拼写焊死。"
        )
    return "\n".join(lines)


_SOUND_EVIDENCE = re.compile(r"[ˈˌəɪʊæɑɒɔɜ]|谐音|读音|读作|念|听起来|耳朵里|音标")
_NUM_PREFIX_LINE = re.compile(r"^\s*[①②③④⑤⑥1-9][\s\.、,，)）]?")


def _first_lines(api, approach, count=2):
    """取前 count 行正文；顺手剥掉 ①②③ 这层序号，方便和慢动作开场词对齐。"""
    out = []
    for line in approach or []:
        text = api._NUM_PREFIX_LINE.sub("", str(line or "")).strip()
        if text:
            out.append(text)
        if len(out) >= count:
            break
    return out


def style_hits(api, item, draft, approach):
    """机械扫黑榜，返回 [{id, name, message}]。空列表＝没命中或没有风格配置。"""
    if not api.style_active():
        return []
    draft = draft or {}
    lines = [str(x) for x in (approach or []) if str(x).strip()]
    body = "\n".join(lines)
    method = api._method_key(draft.get("method"))
    hits = []

    def _add(rule_id, message, **extra):
        rule = api.black_rule(rule_id)
        row = {
            "id": rule_id,
            "name": rule.get("name") or rule_id,
            "message": message,
            "severity": rule.get("severity") or "warn",
        }
        row.update(extra)
        hits.append(row)

    # ①「前两段必须早破题」：①②两行还在摆架势就是慢动作铺垫/场景注水
    openers = tuple(getattr(api.rules_hard, "SLOW_OPENERS", ()) or ())
    for text in api._first_lines(lines, 2):
        opener = next((word for word in openers if word and text.startswith(word)), None)
        if opener:
            _add("slow_start", "黑榜①：开头「%s」是慢动作铺垫，前两段必须已经破题。" % opener)
            break

    # ②「禁生僻词根硬拆」：拆出来的零件用户根本不认识（hemi / skept / sui 这类）
    pool = " ".join([body] + [str(el) for el in (draft.get("elements") or [])])
    obscure = getattr(api.rules_hard, "_OBSCURE_RES", None) or ()
    found = set()
    for entry in obscure:
        # rules_hard 里登记的是 (part, 编译好的正则) 二元组，也兼容只给正则的写法。
        part, rx = (entry[0], entry[-1]) if isinstance(entry, tuple) else (None, entry)
        if not hasattr(rx, "finditer"):
            continue
        for match in rx.finditer(pool):
            found.add(str(part or match.group(0)).lower())
    found = sorted(found)
    if found:
        _add("root_overreach", "黑榜②：生僻零件「%s」没交代来历，用户不可能认识。" % "、".join(found))

    # ③「方法标签必须与内容一致」：写 sound 却没有读音/谐音锚点，是最响的一种贴歪
    if method == "sound" and not api._SOUND_EVIDENCE.search(body):
        _add("label_mismatch", "黑榜③：method 写 sound，但正文没有读音或谐音锚点，标签贴歪了。")

    # ④ meaning / polysemy 是保底不是首选：当主线就按 method_penalty 扣「方法选对」
    penalty = api.method_penalty()
    if method in penalty and penalty[method] > 0:
        _add("weak_method", "黑榜④：%s 是保底招（实测路由权重 %.1f%%），不该当主线。"
             % (method, api.method_weight(method)), method=method)
    return hits


def style_penalty_scores(api, hits, scores):
    """按 black_rules[].penalty 从对应维度硬扣，扣到 0 为止。"""
    out = dict(scores or {})
    for hit in hits or []:
        rule = api.black_rule(hit.get("id") or "")
        penalty = rule.get("penalty") or {}
        if not isinstance(penalty, dict):
            continue
        for dim, value in penalty.items():
            dim = api._canonical_dim(dim)
            if dim not in out:
                continue
            try:
                value = int(value)
            except (TypeError, ValueError):
                continue
            out[dim] = max(0, int(out[dim]) - value)
        if hit.get("id") == "weak_method":
            # 保底招的扣分按「方法权重」里登记的力度走，config 改权重就能改力度。
            method = (hit.get("method") or "").strip()
            extra = api.method_penalty().get(method, 0) if method else 0
            if extra:
                out["方法选对"] = max(0, int(out.get("方法选对", 0)) - int(extra))
    return out


def style_gate(api, item, draft, approach, scores):
    """评委之后的黑榜闸门：(扣过分, 命中列表, 是否打回)。没配置就原样放行。"""
    hits = api.style_hits(item, draft, approach)
    if not hits:
        return scores, [], False
    gated = api.style_penalty_scores(hits, scores)
    reject_ids = set(api.reject_on())
    rejected = any(hit.get("id") in reject_ids for hit in hits)
    return gated, hits, rejected

"""解析模型输出、规范化步骤和检查用户禁令。"""

import re


MAX_ATTEMPTS = 2  # 首次 + 重试一次（spec §4.2）
# judge.txt 要求先判意图与「是不是同一条思路」，生成侧据此决定重试时强调什么
INTENTS = ("change_method", "explain", "fix_detail", "other")

NUM = re.compile(r"^[\s①-⑳0-9０-９]{0,3}(?:[.、,，:：)\]]\s*)?")
ARROW = re.compile(r"\s*(?:-{1,}>|={1,2}>|→|⇒|—{1,}>)\s*")
ARROW_TEXT = " ——> "

CIRCLED = ("①", "②", "③", "④", "⑤")  # 2–5 步，最多 5 个号
MIN_STEPS, MAX_STEPS = 2, 5

# generate.txt 让模型吐英文键，它偶尔还是回中文名；这里收一下口，手机端按 key 出「联想 · 词根」标签。
METHOD_KEYS = {
    "sound": "sound", "谐音": "sound", "谐音联想": "sound",
    "meaning": "meaning", "含义": "meaning", "含义联想": "meaning",
    "shape": "shape", "字形": "shape", "字形联想": "shape",
    "root": "root", "词根": "root", "词根词缀": "root",
    "contrast": "contrast", "对比": "contrast", "对比辨析": "contrast",
    "polysemy": "polysemy", "熟词生义": "polysemy",
}

# 用户原话里的禁令：「不要 / 别 / 禁止 / 不能用 / 别再 + 元素」。
# 中文没有词边界，先把「分别、特别、区别、个性、类别」这类复合词里的「别」排掉。
BAN_TRIGGER = re.compile(
    r"(?:不要|别再|别用|不需要|禁止|不能用|不许|不可以|不想用|(?<![分特个区性类])别)"
    r"([^，,。；;！!？?\n]{1,30})"
)
BAN_STOPWORDS = frozenset((
    "方法", "这个", "那个", "这种", "那种", "这套", "那套", "拆法", "记法", "背法",
    "记忆法", "别的", "其他", "一个", "一次", "一句话", "提一嘴", "再说", "再用",
    "之类",
    "最后", "然后", "接着", "其实", "所以", "但是", "而且", "如果", "可以", "就行",
    "一句", "一嘴", "换个", "换一个", "换种", "让我", "说法",
    "了", "的", "再", "用", "我", "你", "他", "它", "就", "也", "都", "还", "又",
))


def _ban_noise(api, token):
    """句子被截断时会切出「提一」这种半截词；口头语也不是可执行的禁令，丢掉。"""
    if token in api.BAN_STOPWORDS:
        return True
    return any(len(word) > len(token) and word.startswith(token) for word in api.BAN_STOPWORDS)


# 反问请求使用（不是禁用）：「能不能用 X」「就 / 难道不能用 X 吗」。
# 「能不能」里的「能」是独立疑问助词（句首 / 标点 / 人称代词之后），
# 不把「功能 / 可能 + 不能用 X」这种名词尾巴的「能」误当反问。
_USAGE_REQ_BEFORE = frozenset("你您我咱他她它们，、；。！？：: \t\n")


def _is_usage_request(api, text, match_start, clause):
    """「不能用」触发时，判断这是请求使用 X 还是禁用 X。"""
    prefix = text[:match_start]
    if prefix.endswith("能"):
        before = text[match_start - 2] if match_start >= 2 else ""
        if not before or before in api._USAGE_REQ_BEFORE:
            return True
    if not clause.endswith(("吗", "呢")):
        return False
    return prefix.endswith("就") or prefix.endswith("难道")


# 用户口中的招 → 六招键；长的词先匹配（「词根词缀」先于「词缀」）
METHOD_WORDS = {
    "root": ("词根词缀", "词根", "词缀", "词源", "root"),
    "sound": ("谐音法", "谐音联想", "谐音", "sound"),
    "contrast": ("对比辨析", "对比法", "对比", "辨析", "contrast"),
    "shape": ("字形联想", "拆字母", "字形", "shape"),
    "meaning": ("含义联想", "meaning"),
    "polysemy": ("熟词生义", "生义", "polysemy"),
}

# 输出形状只由 prompts/generate.txt 定义（split 是数组、elements 是对象数组）。
# 早先这里还追加过一段「split 是字符串」的提醒，两套契约互相打架：模型按提示词输出数组，
# 解析后又 str(list) 成 "['ap', 'propri']" 喂给润色。现在只保留一个真源。


class PipelineError(Exception):
    """这个词这批救不回来了。"""


def extract_json(api, text):
    """模型爱把 JSON 包在 ```json 里或前后絮叨；抓第一个完整 JSON 对象。

    不能简单用 find("{") + rfind("}")：模型在对象后面补一句带花括号的解释时，
    rfind 会把尾巴也吞进去，明明开头是合法 JSON 却解析失败（canary 的 obscure
    就踩过这个边界）。JSONDecoder.raw_decode 只吃第一个完整对象，后面多什么
    都不影响；失败时再退回旧的全花括号切片，兼容被截断/嵌套说明的响应。
    """
    if not text:
        return None
    body = str(text).strip()
    fence = api.re.search(r"```(?:json)?\s*(.*?)```", body, api.re.S)
    if fence:
        body = fence.group(1).strip()
    pos = body.find("{")
    if pos >= 0:
        try:
            data, _ = api.json.JSONDecoder().raw_decode(body, pos)
            if isinstance(data, dict):
                return data
        except ValueError:
            pass
    start, end = body.find("{"), body.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = api.json.loads(body[start : end + 1])
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def normalize(api, derivation):
    """2–5 行归一：按实际行数重挂 ①②③④⑤，末行箭头统一成 ——>，行数越界直接判废。"""
    lines = []
    if isinstance(derivation, str):
        derivation = [derivation]
    for raw in derivation or []:
        text = str(raw or "").strip()
        if not text:
            continue
        text = api.ARROW.sub(api.ARROW_TEXT, text)
        text = api.re.sub(r"\s+", " ", text).strip()
        text = api.NUM.sub("", text).strip()
        if text:
            lines.append(text)
    if not (api.MIN_STEPS <= len(lines) <= api.MAX_STEPS):
        return None  # 不到 3 步串不成链，多于 5 步是把一句话拆碎了
    last = len(lines) - 1
    lines[last] = api.ARROW.sub(api.ARROW_TEXT, lines[last])
    # 末行没箭头时再看倒数第二行：模型常把「顺带一提」的旁注放在结论后面，
    # 旁注不是结论，不该让整轮 90 秒的生成白跑。
    if "——>" not in lines[last] and not (last >= 1 and "——>" in lines[last - 1]):
        return None  # 末步没落到中文词义，等于没闭环
    return [api.CIRCLED[i] + " " + text for i, text in enumerate(lines)]


def _method_key(api, value):
    """六招收成英文键（sound/meaning/shape/root/contrast/polysemy）；认不出来就原样带回去。"""
    text = str(value or "").strip()
    if not text:
        return ""
    lowered = text.lower()
    if lowered in api.METHOD_KEYS:
        return api.METHOD_KEYS[lowered]
    for name, key in api.METHOD_KEYS.items():
        if name in lowered:
            return key
    return text


_IPA_RE = re.compile(r"/[^/\n]{1,40}/")


def _ensure_phonetic_warning(api, item, method, lines):
    """拼写拆法必须给真读音：模型偶尔会漏，润色后在这里补一条，别让 judge 白判。"""
    if api._method_key(method) not in ("root", "shape"):
        return lines
    phonetic = str(item.get("phonetic") or "").strip()
    if not phonetic or not lines:
        return lines
    if any(api._IPA_RE.search(str(line)) for line in lines):
        return lines
    warning = "这个词真读 %s，上面的拆法只帮你看拼写，别按字母硬念。" % phonetic
    lines = [str(line) for line in lines]
    if len(lines) <= 4:
        lines = lines[:-1] + [warning] + lines[-1:]
    else:
        lines[-2] = lines[-2].rstrip() + " " + warning
    return api.normalize(lines) or lines


def banned_terms(api, instruction):
    """从用户原话里抽禁令：返回 [{"label": 原话里的词, "method": 六招键或 "", "needle": 文本}]。

    只认「不要 / 别 / 禁止 / 不能用 / 别再 + 元素」这种显式否定；
    「换个方法」是意图不是禁令，交给评委的 intent / 诉求落实处理。
    """
    text = str(instruction or "").strip()
    if not text:
        return []
    found = []
    seen = set()

    def add(label, method, needle):
        key = (method, needle or label)
        if not label or key in seen:
            return
        if not method and api._ban_noise(label):
            return
        seen.add(key)
        found.append({"label": label, "method": method, "needle": needle or label})

    for match in api.BAN_TRIGGER.finditer(text):
        clause = match.group(1).strip()
        if match.group(0).startswith("不能用") and api._is_usage_request(text, match.start(), clause):
            # 「就 / 难道 + 不能用 X 吗」是反问请求使用 X，不是禁用 X。
            continue
        clause = api.re.sub(r"^(?:再|用|使用|这种|这个|那种|那套|了|的)+", "", clause)
        rest = clause
        for method, words in sorted(api.METHOD_WORDS.items()):
            for word in sorted(words, key=len, reverse=True):
                if word in rest:
                    add(word, method, word)
                    rest = rest.replace(word, " ")
        for word in api.BAN_STOPWORDS:
            rest = rest.replace(word, " ")
        for token in api.re.split(r"[、\s/]+", rest):
            token = api.re.sub(r"[的了法吧啊呢！!]+$", "", token.strip())
            if len(token) < 2:
                continue
            method = ""
            for key, words in api.METHOD_WORDS.items():
                if token.lower() in words:
                    method = key
                    break
            add(token, method, token)
    return found


NEGATION_MARKERS = (
    "不", "别", "没", "勿", "禁止", "避免", "而非", "无需", "无须", "不用", "不要", "不再",
)
NEGATION_STOPS = "，。；：！？、\n\r\t—…"


def _negated(api, body, pos):
    """判断 pos 前的短窗口是不是否定语境（如「不像词根那样拆」「别用词根」）。

    只回看 6 个字符、遇标点即停：再长容易把上一句的「不」算进来。
    """
    window = body[max(0, pos - 6):pos]
    for stop in api.NEGATION_STOPS:
        cut = window.rfind(stop)
        if cut >= 0:
            window = window[cut + 1:]
    return any(marker in window for marker in api.NEGATION_MARKERS)


def _text_hits(api, bans, texts, allow_last_note=False):
    """在一段文本里找禁令命中；allow_last_note=True 时放行最后一行（用户常说「末行提一嘴就行」）。"""
    lines = list(texts)
    # 只有多行时才放行末行；单行文本不能整段跳过，否则禁令检查直接失效。
    if allow_last_note and len(lines) > 1:
        lines = lines[:-1]
    body = "\n".join(lines)
    if not body:
        return []
    lowered = body.lower()
    hits = []
    for ban in bans:
        if ban["method"]:
            words = api.METHOD_WORDS.get(ban["method"], (ban["needle"],))
        else:
            words = (ban["needle"],)
        # 否定式提及不算命中：好卡解释「这版不像词根那样拆」不该被自己的说明判违规。
        # 同一个词出现多处时，只要有一处不在否定语境里，就算命中。
        for word in words:
            needle = str(word).lower()
            if not needle:
                continue
            start = lowered.find(needle)
            while start >= 0:
                if not api._negated(lowered, start):
                    hits.append(ban["needle"])
                    break
                start = lowered.find(needle, start + 1)
    return hits


def _draft_hits(api, bans, draft):
    """扫草稿的 split / elements / derivation 有没有踩用户禁令。

    末行放行：用户原话里出现过「最后再提一嘴词根词缀就行」这种许可，
    把末行的顺带一提也判违规会把正确的稿子打回去。
    """
    if not bans:
        return []
    hits = []
    if draft.get("method"):
        for ban in bans:
            if ban["method"] and draft["method"] == ban["method"]:
                hits.append(ban["needle"])
    head = [str(draft.get("split") or "")] + [str(x) for x in (draft.get("elements") or [])]
    hits += api._text_hits(bans, head)
    lines = [str(x) for x in (draft.get("derivation") or [])]
    hits += api._text_hits(bans, lines, allow_last_note=True)
    out = []
    for hit in hits:
        if hit not in out:
            out.append(hit)
    return out

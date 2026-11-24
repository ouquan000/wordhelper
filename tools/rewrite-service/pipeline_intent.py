"""识别用户当前指令与换方法意图。"""

import re


# 否定必须修饰当前动作：「不要用词根了换个方法」仍然要换，「特别」也不是「别」。
_ZH_DIRECT_NEGATION = re.compile(
    r"(?:不(?:用|要|必|需要|(?:太|很)?想(?:要)?|希望|打算|准备|觉得需要|是)?"
    r"|没(?:有)?(?:必要|打算|想|准备)|无(?:需|须)|(?<![分特个区性类差级告鉴])别)"
    r"(?:\s*(?:再|重新|去|给我|现在|马上|每次都|总是))*\s*$"
)
_EN_DIRECT_NEGATION = re.compile(
    r"\b(?:do\s+not|don['’]t|should\s+not|shouldn['’]t|cannot|can['’]t|never|not|no)\s+"
    r"(?:really\s+|necessarily\s+)*"
    r"(?:(?:want|need|have|plan|intend)(?:\s+you)?\s+to\s+"
    r"|think(?:\s+that)?\s+it(?:['’]s|\s+is)\s+necessary\s+to\s+)?$"
)
# 反问式建议和双重否定不等于要求保留方法。
_ZH_CHANGE_PROPOSAL = re.compile(
    r"(?:要不要|是不是该|是不是|该不该|能不能|可不可以|为什么不|怎么不|何不|(?<![要不])要不"
    r"|(?<!能)不能不|不得不)(?:\s*(?:再|重新))?\s*$"
)
_INTENT_CLAUSE_END = re.compile(r"[，,。；;！!？?\n\r]")
_ZH_PAST_INTENT = re.compile(
    r"^\s*(?:我\s*)?(?:本来|原本|原先|原来|之前|先前)\s*(?:我\s*)?"
    r"(?:不(?:太|很)?\s*)?(?:想|希望|打算|准备)"
)
_ZH_CURRENT_INTENT = re.compile(r"\s*(?:(?:但(?:是)?|不过)\s*)?(?:现在|这次)")
_REPORTED_CHANGE_PREFIX = re.compile(
    r"(?:别人|有人|模型|评委|上次|之前)(?:曾经|刚才)?(?:说(?:了|过)?|提到|建议)"
    r"""\s*[:：]?\s*[「『“‘"']?\s*$"""
)


# 在分句前匹配成对引号，避免把引语内的逗号误当成用户改口。
# ASCII 单引号和弯引号不能把 don't / don’t 的撇号当成引语边界。
_INTENT_QUOTE = re.compile(
    r"""「[^」]*」|『[^』]*』|“[^”]*”|"[^"]*" """
    r"""|‘(?:[^’]|’(?=[a-z0-9]))*?’(?![a-z0-9])"""
    r"""|(?<![a-z0-9])'(?:[^']|'(?=[a-z0-9]))*?'(?![a-z0-9])""",
    re.IGNORECASE | re.VERBOSE,
)
_QUOTE_AS_DIRECT_REQUEST = re.compile(
    r"(?:^|[，,。.；;！!？?\n\r])\s*(?:(?:但|不过|现在|这次)\s*)*"
    r"(?:请(?:你)?|我(?:现在)?(?:要求你|要你|希望你)|please)\s*[:：]?\s*$",
    re.IGNORECASE,
)
_QUOTE_REQUEST_END = re.compile(r"^\s*(?:吧|就行|即可)?\s*(?:[，,。.；;！!？?\n\r]|$)")
# 连词后必须有另一个动作。只匹配「并/and」会把「并列的说法 / and similar phrases」当成指令。
_QUOTE_FOLLOWUP_REQUEST = re.compile(
    r"^\s*(?:(?:并且?|同时|然后)\s*(?:请(?:你)?\s*)?(?:先)?(?:讲|说|解释|说明|补充|保留|改写)"
    r"|and\s+(?:then\s+)?(?:please\s+)?(?:explain|clarify|fix|keep|rewrite)\b)",
    re.IGNORECASE,
)


def _intent_prose(api, text):
    """引语默认是被提及的内容；只有明确的命令式外框才采用其中的要求。"""
    def project(match):
        following = text[match.end():]
        if api._QUOTE_AS_DIRECT_REQUEST.search(text[:match.start()]) and (
            api._QUOTE_REQUEST_END.match(following) or api._QUOTE_FOLLOWUP_REQUEST.match(following)
        ):
            return match.group()[1:-1]
        # 保留占位，避免把原本隔着引语的两个词误连成一条指令。
        return "\ufffc"

    return api._INTENT_QUOTE.sub(project, text)


def _prefix_negates(api, prefix):
    return not api._ZH_CHANGE_PROPOSAL.search(prefix) and bool(
        api._ZH_DIRECT_NEGATION.search(prefix) or api._EN_DIRECT_NEGATION.search(prefix)
    )


def _negated_near(api, text, word):
    """True only if each occurrence is directly negated in its own clause."""
    seen = False
    for clause in api._INTENT_CLAUSE_END.split(text):
        for match in api.re.finditer(api.re.escape(word), clause):
            seen = True
            prefix = clause[:match.start()]
            if not api._prefix_negates(prefix):
                return False
    return seen


def _intent_words(api, text, words):
    return [word for word in words if word in text and not api._negated_near(text, word)]


_CHANGE_WORDS = (
    "换方法", "换个方法", "换一个方法", "换一种方法", "换个其他方法", "换个别的办法",
    "换招", "换思路", "换一种思路", "换个思路", "换种思路", "换种方法", "换套方法", "换背法", "换个背法",
    "改方法", "改一下方法", "改思路", "非换不可", "必须换",
    "change method", "change the method", "switch method", "try another method",
)
_CHANGE_PATTERN = re.compile("|".join(re.escape(word) for word in _CHANGE_WORDS))
_INTENT_WORDS = (
    ("explain", ("解释", "看不懂", "说明", "说清楚", "讲清楚", "explain")),
    ("fix_detail", ("具体", "某一步", "细节", "fix")),
)


def _change_directive(api, text):
    """换法、保留、未明确三态；提及和转述不计，用户改口以最后一句明确要求为准。"""
    directive = None
    clauses = api._INTENT_CLAUSE_END.split(api._intent_prose(str(text or "").lower()))
    for index, clause in enumerate(clauses):
        if api._ZH_PAST_INTENT.match(clause) and (past_change := api._CHANGE_PATTERN.search(clause)):
            # 「本来……现在……」中，过去的想法不覆盖评委对当前诉求的判断。
            current = api._ZH_CURRENT_INTENT.search(clause, past_change.end())
            if current:
                clause = clause[current.start():]
            elif index + 1 < len(clauses) and api._ZH_CURRENT_INTENT.match(clauses[index + 1]):
                continue
        for match in api._CHANGE_PATTERN.finditer(clause):
            prefix = clause[:match.start()]
            if not api._REPORTED_CHANGE_PREFIX.search(prefix):
                directive = not api._prefix_negates(prefix)
    return directive


def _explicit_change_request(api, text):
    return api._change_directive(text) is True


def _negated_change_request(api, text):
    return api._change_directive(text) is False


def _intent_field(api, value):
    """Strict parsing for judge output; unknown values trigger bounded rejudging."""
    text = str(value or "").strip().lower()
    if text in api.INTENTS:
        return text
    directive = api._change_directive(text)
    if text == "change" or directive is True:
        return "change_method"
    prose = api._intent_prose(text)
    for key, words in api._INTENT_WORDS:
        if api._intent_words(prose, words):
            return key
    return "other" if directive is False else None


def _intent(api, value):
    return api._intent_field(value) or "other"


def _requires_change_method(api, item, intent):
    directive = api._change_directive(item.get("instruction"))
    return intent == "change_method" if directive is None else directive


def _bool_field(data, key):
    """严格布尔：只认 true/false（及 0/1）；缺失或含糊返回 None。"""
    value = data.get(key)
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        text = value.strip().lower()
        if text == "true":
            return True
        if text == "false":
            return False
    return None

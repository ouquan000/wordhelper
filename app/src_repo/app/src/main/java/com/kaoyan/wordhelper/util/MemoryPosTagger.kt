package com.kaoyan.wordhelper.util

/**
 * 记忆思路正文被切出来的一段，带词性判定结果。
 *
 * @param text 这一段在原文里的样子：不改写、不删字。同一条物理行被切成多段时，
 *             按顺序把这些段的 [text] 拼起来与原文逐字一致。
 * @param pos 词性小标（"v." / "n." / "adj." …，写法取自 meaning 里真实出现的那一个），
 *            判不出来时为 null —— UI 按普通文字渲染，不贴标。
 * @param inlineSpans 正文里本来就写出来的显式 token（`v.` / `adj.` …）在 [text] 中的位置，供 UI 染色。
 */
data class TaggedLine(
    val text: String,
    val pos: String?,
    val inlineSpans: List<PosSpan> = emptyList()
)

/** 半开区间 [start, end)，相对 [TaggedLine.text]。 */
data class PosSpan(val start: Int, val end: Int)

/**
 * 卡背「记忆思路」正文的词性小标判定器（纯 Kotlin/JVM，不依赖 Android）。
 *
 * 输入正文 + 该词的 meaning，输出逐段的 [TaggedLine]。识别的三类信号：
 *
 * 1. 显式 token：`v.` `n.` `vt.` `vi.` `adj.` `adv.` `prep.` `conj.` `pron.` `num.` `art.` `int.` `aux.`
 *    （点号后允许空格、中文、括号；前后紧贴字母数字的不算，避免吃掉单词片段）
 * 2. 中文词性词：动词 / 名词 / 形容词 / 副词 / 介词 / 连词 / 代词 / 数词 / 冠词 / 感叹词 /
 *    及物动词 / 不及物动词（长的先匹配，「不及物动词」不会被「动词」抢先）
 * 3. 段落 / 收口结构：①②③④ 只是段落编号，本身不是信号；
 *    `→` 后面的中文义项串按 meaning 里义项到词性的索引逐项判定，全部可判才贴
 *
 * 判定策略（宁缺毋滥）：
 * - 一行只有一种词性写法 → 整行取它，不拆行（行高不变）
 * - 一行出现多种词性写法 → 只有每个信号都落在软边界（行首/空白/，；、：。（“）上才拆段，
 *   否则整行留白：宁可没有标，也不要标错
 * - [legalPositions] 之外的一律不贴：meaning 里没有的词性，正文说了也不认
 * - 判不准的收口义项串 / 对不上 meaning 的义项 → 整行留白，不猜
 * - `vt.`/`vi.` 在 meaning 只有 `v.` 时降级成 `v.`（同族）；反过来不降级，判不出就留白
 */
object MemoryPosTagger {

    private const val VERB = "v."
    private const val VERB_TRANSITIVE = "vt."
    private const val VERB_INTRANSITIVE = "vi."
    private const val SPACE = ' '

    /** 显式 token；点号前后都不许紧贴字母数字（中文、空格、括号、标点都算合法邻居）。 */
    private val EXPLICIT_TOKEN = Regex(
        """(?<!\w)(n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.(?!\w)""",
        RegexOption.IGNORE_CASE
    )

    /** 中文词性词 → 词性写法。顺序即优先级：「不及物动词」「及物动词」必须排在「动词」前面。 */
    private val CHINESE_POS: List<Pair<String, String>> = listOf(
        "不及物动词" to VERB_INTRANSITIVE,
        "及物动词" to VERB_TRANSITIVE,
        "动词" to VERB,
        "名词" to "n.",
        "形容词" to "adj.",
        "副词" to "adv.",
        "介词" to "prep.",
        "连词" to "conj.",
        "代词" to "pron.",
        "数词" to "num.",
        "冠词" to "art.",
        "感叹词" to "int."
    )

    private val CHINESE_POS_WORD = Regex(
        CHINESE_POS.joinToString("|") { Regex.escape(it.first) }
    )

    /** 收口箭头；正文里可能写成 `——>` / `-->` / `－>`，先统一成 `→` 再判定。 */
    private const val ARROW = '→'
    private val ARROW_VARIANTS = Regex("""(?:—+|－+|-+)>""")

    /** 信号前允许断行的软边界：行首、空白、常用标点、左引号/左括号。 */
    private val SOFT_BOUNDARIES = charArrayOf(
        SPACE, '\u3000', '，', '；', '、', '：', '。', '（', '(', '“', '「', '"'
    )

    /** 比对一个义项时要去掉的首尾字符。 */
    private val GLOSS_TRIM = charArrayOf(
        SPACE, '\u3000', '。', '，', '、', '；', '：', '！', '？', '!', '?',
        '“', '”', '‘', '’', '"', '\'', '（', '）', '(', ')', '…', '—'
    )

    /**
     * 逐行判定正文，返回按原文顺序排列的 [TaggedLine]。
     *
     * - 空行/纯空白行丢弃；行首尾空白去掉（与 UI 既有排版一致）
     * - 箭头写法归一成 `→`（同上），其余字符不动
     * - [meaning] 为空或解析不出词性时，所有 pos 均为 null
     */
    fun tag(content: String, meaning: String): List<TaggedLine> {
        if (content.isBlank()) return emptyList()
        val legal = legalPositions(meaning)
        val glosses = glossIndex(meaning)
        val result = ArrayList<TaggedLine>()
        for (rawLine in content.split("\n")) {
            val line = ARROW_VARIANTS.replace(rawLine, ARROW.toString()).trim()
            if (line.isEmpty()) continue
            result.addAll(tagLine(line, legal, glosses))
        }
        return result
    }

    /**
     * meaning 里真实出现的词性写法集合（小写、含末尾点），即「允许贴的标」。
     * 空 meaning / 无词性前缀 → 空集合（此时一个标都不会贴）。
     *
     * 注意用 [MeaningTextFormatter.parse] 而不是 displayLines：parse 不做去重，
     * extract 那种 `v. 摘录` + `n. 摘录` 跨词性共用义项时 v./n. 都会留下。
     */
    fun legalPositions(meaning: String): Set<String> =
        MeaningTextFormatter.parse(meaning)
            .mapNotNull { it.pos }
            .map { it.lowercase() }
            .toSet()

    /** 单行判定。 */
    private fun tagLine(
        line: String,
        legal: Set<String>,
        glosses: Map<String, Set<String>>
    ): List<TaggedLine> {
        val markers = collectMarkers(line, legal)
        if (markers.isEmpty()) return tagClosingGlosses(line, glosses)
        // 规则 A：整行只有一种词性写法 → 整行就是它，不拆行。
        if (markers.all { it.pos == markers.first().pos }) {
            return listOf(TaggedLine(line, markers.first().pos, spansWithin(markers, 0, line.length)))
        }
        // 规则 B：多种词性 → 每个信号都得落在软边界上才拆，否则整行留白。
        for (index in 1 until markers.size) {
            if (!isSoftBoundary(line, markers[index].start)) return listOf(TaggedLine(line, null))
        }
        val result = ArrayList<TaggedLine>(markers.size)
        for ((index, marker) in markers.withIndex()) {
            val start = if (index == 0) 0 else marker.start
            val end = if (index + 1 < markers.size) markers[index + 1].start else line.length
            result.add(TaggedLine(line.substring(start, end), marker.pos, spansWithin(markers, start, end)))
        }
        return result
    }

    /**
     * 规则 C：正文没有词性信号，但末尾有 `→ 义项串` 的收口结构。
     *
     * 按 `；` 把箭头后面切成义项，逐个查 meaning 的义项索引：
     * 全部义项都能唯一确定词性时，连续同词性的义项合并成一段；
     * 只要有一个义项查不到、或对应多个词性，整行留白。
     */
    private fun tagClosingGlosses(line: String, glosses: Map<String, Set<String>>): List<TaggedLine> {
        val arrow = line.lastIndexOf(ARROW)
        if (arrow < 0 || glosses.isEmpty()) return listOf(TaggedLine(line, null))
        val chunks = splitGlossChunks(line, arrow + 1)
        if (chunks.isEmpty()) return listOf(TaggedLine(line, null))
        val decided = ArrayList<Pair<IntRange, String>>()
        for (range in chunks) {
            val gloss = normalizeGloss(line.substring(range.first, range.last + 1))
            if (gloss.isEmpty()) {
                // 「；」后面什么都没有：并回上一段，别把分隔符丢了。
                if (decided.isEmpty()) return listOf(TaggedLine(line, null))
                val last = decided.removeAt(decided.size - 1)
                decided.add((last.first.first..range.last) to last.second)
                continue
            }
            val poses = glosses[gloss] ?: return listOf(TaggedLine(line, null))
            if (poses.size != 1) return listOf(TaggedLine(line, null))
            val pos = poses.first()
            val last = decided.lastOrNull()
            if (last != null && last.second == pos) {
                decided[decided.size - 1] = (last.first.first..range.last) to pos
            } else {
                decided.add(range to pos)
            }
        }
        if (decided.isEmpty()) return listOf(TaggedLine(line, null))
        if (decided.size == 1) return listOf(TaggedLine(line, decided.first().second))
        val result = ArrayList<TaggedLine>(decided.size)
        var cursor = 0
        for ((index, group) in decided.withIndex()) {
            val end = if (index + 1 < decided.size) decided[index + 1].first.first else line.length
            result.add(TaggedLine(line.substring(cursor, end), group.second))
            cursor = end
        }
        return result
    }

    /** 把箭头后面的义项串按 `；` 切成 [start, end) 区间，分隔符留在前一个义项末尾。 */
    private fun splitGlossChunks(line: String, from: Int): List<IntRange> {
        val chunks = ArrayList<IntRange>()
        var start = from
        for (index in from until line.length) {
            val ch = line[index]
            if (ch == '；' || ch == ';') {
                chunks.add(start until index + 1)
                start = index + 1
            }
        }
        if (start < line.length) chunks.add(start until line.length)
        return chunks
    }

    /** 显式 token + 中文词性词，按位置排序；已通过 meaning 合法性过滤。 */
    private fun collectMarkers(line: String, legal: Set<String>): List<Marker> {
        val markers = ArrayList<Marker>()
        for (match in EXPLICIT_TOKEN.findAll(line)) {
            // 正则的捕获组只到字母，词性写法要补回末尾的点才和 meaning 里的写法对得上。
            val pos = accept(match.groupValues[1] + ".", legal) ?: continue
            markers.add(Marker(match.range.first, match.range.last + 1, pos, explicit = true))
        }
        for (match in CHINESE_POS_WORD.findAll(line)) {
            val end = match.range.last + 1
            // 与显式 token 重叠的不重复记（两类信号字面不可能重叠，纯防御）。
            if (markers.any { match.range.first < it.end && it.start < end }) continue
            val written = CHINESE_POS.first { it.first == match.value }.second
            val pos = accept(written, legal) ?: continue
            markers.add(Marker(match.range.first, end, pos, explicit = false))
        }
        markers.sortBy { it.start }
        return markers
    }

    /**
     * 把候选词性落到 meaning 里真实存在的写法上；不合法返回 null（不贴标）。
     *
     * 唯一的放宽是及物/不及物与 v. 同族：正文写了「及物动词」而 meaning 只写 `v.` 时降级成 `v.`。
     * 反过来不成立 —— meaning 只写 `vt.` 时正文的「动词」判不出到底及物与否，按宁缺毋滥留白，
     * 绝不再造一个 meaning 里没有的 `v.` 标。
     */
    private fun accept(candidate: String, legal: Set<String>): String? {
        if (legal.isEmpty()) return null
        val pos = candidate.lowercase()
        if (pos in legal) return pos
        val isVerbFamily = pos == VERB_TRANSITIVE || pos == VERB_INTRANSITIVE
        return if (isVerbFamily && VERB in legal) VERB else null
    }

    private fun isSoftBoundary(line: String, at: Int): Boolean {
        if (at <= 0) return true
        val prev = line[at - 1]
        return prev.isWhitespace() || SOFT_BOUNDARIES.contains(prev)
    }

    /** 段内显式 token 的相对区间；中文词性词不染色（它前面就是段首的小标）。 */
    private fun spansWithin(markers: List<Marker>, start: Int, end: Int): List<PosSpan> {
        val spans = ArrayList<PosSpan>()
        for (marker in markers) {
            if (!marker.explicit) continue
            if (marker.start < start || marker.end > end) continue
            spans.add(PosSpan(marker.start - start, marker.end - start))
        }
        return spans
    }

    /** 义项 → 可能的词性写法（同一个义项可能跨词性共用，如 extract 的「摘录」）。 */
    private fun glossIndex(meaning: String): Map<String, Set<String>> {
        val index = LinkedHashMap<String, MutableSet<String>>()
        for (line in MeaningTextFormatter.parse(meaning)) {
            val pos = line.pos?.lowercase() ?: continue
            index.getOrPut(normalizeGloss(line.text)) { LinkedHashSet() }.add(pos)
        }
        return index
    }

    private fun normalizeGloss(text: String): String = text.trim { it in GLOSS_TRIM }

    /** 一行里的一个词性信号。[explicit] = 是正文写出来的拉丁 token（要染色）。 */
    private data class Marker(
        val start: Int,
        val end: Int,
        val pos: String,
        val explicit: Boolean
    )
}

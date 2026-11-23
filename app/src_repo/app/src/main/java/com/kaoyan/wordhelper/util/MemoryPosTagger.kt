package com.kaoyan.wordhelper.util

/**
 * 记忆思路正文里的一整行，外加这一行上「词性小标」的落点。
 *
 * 1.5.3 起 [MemoryPosTagger] **不再把一行拆成多条**（旧版那种把 `adj.` 小标骑在
 * 「③」编号脑门上、还把 `→` 后的义项拦腰劈成两行的做法已经废弃）：
 * 一行正文进、一行正文出，[text] 永远是这一行的完整原文（含 ①②③ 编号），
 * 词性信息全部放在 [badges] 里，由渲染层用 `InlineTextContent` 原地嵌进文本流。
 *
 * @param text   整行原文（已统一箭头、已 trim，内容一个字都不少）
 * @param pos    这一行整体归属的词性；一行里出现多种词性时为 null（不猜）
 * @param badges 行内小标落点，按 [PosMark.start] 升序
 */
data class TaggedLine(
    val text: String,
    val pos: String?,
    val badges: List<PosMark> = emptyList(),
)

/**
 * 一个行内词性小标在 [TaggedLine.text] 上的落点（半开区间 `start until end`）。
 *
 * - `start < end`：这段正文被小标**顶替**。命中的是显式 token（`v.` / `adj.`）或中文
 *   词性词（`动词` / `名词`），小标本身已经把词性说清楚了，留着文字是重复的。
 * - `start == end`：小标**插在这一点前面**，正文一个字都不删。用于 `→` 后的中文义项串
 *   按词性分组：`... → adj. 外来的；异国的； n. 外来人；外来物。`（括号里的 adj./n. 即小标）。
 *
 * @param pos 小标文字，带末尾的点（`v.` / `n.` / `adj.` …），与 [MeaningLine.pos] 同形
 */
data class PosMark(
    val start: Int,
    val end: Int,
    val pos: String,
)

/**
 * 记忆思路正文的词性识别器（纯 Kotlin/JVM，不依赖 Android）。
 *
 * 输入是卡背「记忆思路」正文 + 该词的 `meaning`，输出是**逐行的** [TaggedLine]。
 * 识别优先级：
 * 1. 正文里写了显式词性 token（`v.` / `vt.` / `n.` / `adj.` …）→ 就地顶替成小标；
 * 2. 正文里写了中文词性词（`动词` / `名词` / `形容词` …）→ 就地顶替成小标；
 * 3. 什么都没有、但行末用 `→` 收口一串义项（`→ 外来的；异国的；外来人`）→ 按 `；`
 *    把义项串切成 chunk，逐块到 `meaning` 里查它唯一对应的词性，同词性连续合并后
 *    在每个分组的第一块前面**插入**小标。
 *
 * 所有词性都必须先过 [accept]：`meaning` 里没写的词性一律不贴；义项串里只要有一块
 * 判不出唯一词性，整行就退回「无小标」——宁缺毋滥，绝不瞎猜。
 */
object MemoryPosTagger {

    private const val VERB = "v."
    private const val VERB_TRANSITIVE = "vt."
    private const val VERB_INTRANSITIVE = "vi."

    private const val SPACE = ' '
    private const val IDEOGRAPHIC_SPACE = '\u3000'

    /** 中文基本区：用于判定 `→` 后是否跟着有效中文义项/释义句。 */
    private val CJK_RANGE = '\u4e00'..'\u9fff'

    /** 正文里**显式写出来**的词性 token，如 `v.` / `adj.` / `vt.`（要求前后不是字母）。 */
    private val EXPLICIT_TOKEN = Regex(
        """(?<!\w)(n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.(?!\w)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 正文里**用中文写出来**的词性词，长的在前，避免「及物动词」被「动词」抢走。
     * 顺序即优先级，和 [EXPLICIT_TOKEN] 的候选一一对应。
     */
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
        "感叹词" to "int.",
    )

    private val CHINESE_POS_WORD = Regex(
        CHINESE_POS.joinToString("|") { Regex.escape(it.first) },
    )

    private const val ARROW = '\u2192'

    private val ENGLISH_GLOSS_PREFIX = Regex(
        """^(?:[A-Za-z]+[\s\u3000]+)+(?=[\u4e00-\u9fff])"""
    )

    /** 种子数据里出现过各种箭头写法（`-->`、`——>`、`－>`），统一成 [ARROW]。 */
    private val ARROW_VARIANTS = Regex("""(?:\u2014+|\uff0d+|-+)>""")

    /** `；` 切出来的义项 chunk 首尾可能要剥掉的字符。 */
    private val GLOSS_TRIM = charArrayOf(
        ' ', '\u3000', '，', '；', '、', '：', '。', '（', '）', '(', ')', '“', '”', '"', '\'',
    )

    /**
     * 逐行打标。空正文返回空列表；空行丢弃；箭头写法先统一。
     *
     * @param content 卡背「记忆思路」正文（可能含 ①②③ 编号与换行）
     * @param meaning 该词的 `meaning` 字段，既是词性白名单也是义项来源
     */
    fun tag(content: String, meaning: String): List<TaggedLine> {
        if (content.isBlank()) return emptyList()
        val legal = legalPositions(meaning)
        val glosses = glossIndex(meaning)

        val result = ArrayList<TaggedLine>()
        for (rawLine in content.split("\n")) {
            val line = ARROW_VARIANTS.replace(rawLine, ARROW.toString()).trim()
            if (line.isEmpty()) continue
            result.add(tagLine(line, legal, glosses))
        }
        return result
    }

    /** `meaning` 里出现过的全部词性（小写，带点）。 */
    fun legalPositions(meaning: String): Set<String> =
        // 用 parse 而不是 displayLines：displayLines 会把同词性的义项合并、去重，
        // extract 的「v. 摘录；提取」与「n. 摘录」共用义项时 n. 会被顺手删掉。
        MeaningTextFormatter.parse(meaning)
            .mapNotNull { it.pos }
            .map { it.lowercase() }
            .toSet()

    /** 单行打标：显式 token / 中文词性词优先，其次 `→` 收口义项串，都没有就留白。 */
    private fun tagLine(
        line: String,
        legal: Set<String>,
        glosses: Map<String, Set<String>>,
    ): TaggedLine {
        val markers = collectMarkers(line, legal)
        if (markers.isEmpty()) return tagClosingGlosses(line, glosses, legal)

        val badges = markers.map { marker -> PosMark(marker.start, marker.end, marker.pos) }
        val distinct = markers.map { it.pos }.distinct()
        // 一整行只有一种词性写法时，这一行整体就归它；混排时整体词性留 null 不猜。
        return TaggedLine(line, if (distinct.size == 1) distinct.first() else null, badges)
    }

    /**
     * 行内没有词性信号，但行末用 `→` 收口了一串义项：按分号/顿号/逗号切块，逐块到 [glosses]
     * 里查唯一词性，同词性连续合并成组，每组第一块前面插一个小标。
     *
     * 1.5.3v2 两条兜底：
     * - 防连坐清零：单个块查不到唯一词性时跳过这一块，其余块照贴；只有**一块都贴不出**
     *   才整行留白（不再因 AI 微调了某一个义项的措辞而整行丢标）。
     * - 单词性兜底：`meaning` 只含唯一词性、行末 `→` 后是有效中文义项/释义句（兼容种子版
     *   「词义就是徘徊、逗留」）时，直接在整个义项串前面嵌上这个唯一词性。
     */
    private fun tagClosingGlosses(
        line: String,
        glosses: Map<String, Set<String>>,
        legal: Set<String>,
    ): TaggedLine {
        if (glosses.isEmpty()) return TaggedLine(line, null)
        val arrow = line.lastIndexOf(ARROW)
        if (arrow < 0) return TaggedLine(line, null)

        val chunks = splitGlossChunks(line, arrow + 1)
        if (chunks.isEmpty()) return TaggedLine(line, null)

        val decided = ArrayList<IntRange>()
        val poses = ArrayList<String>()
        for (chunk in chunks) {
            val gloss = normalizeGloss(line.substring(chunk.first, chunk.last + 1))
            if (gloss.isEmpty()) continue
            val matches = glosses[gloss] ?: glosses[ENGLISH_GLOSS_PREFIX.replaceFirst(gloss, "")]
            val pos = matches?.singleOrNull()
            if (pos == null) {
                // 防连坐：这块查不到唯一词性就跳过，不影响其余块。
                continue
            }
            // 和上一块同词性就并进上一组，否则新开一组。
            if (poses.isNotEmpty() && poses.last() == pos) {
                decided[decided.lastIndex] = decided.last().first..chunk.last
            } else {
                decided.add(chunk)
                poses.add(pos)
            }
        }
        if (decided.isEmpty()) {
            // 单词性兜底：meaning 只有一种词性，→ 后是有效中文内容，直接嵌该词性。
            if (legal.size == 1) {
                val pos = legal.first()
                val after = line.substring(arrow + 1).trim()
                if (after.any { it in CJK_RANGE }) {
                    val at = skipSpaces(line, arrow + 1)
                    return TaggedLine(line, pos, listOf(PosMark(at, at, pos)))
                }
            }
            return TaggedLine(line, null)
        }

        val badges = ArrayList<PosMark>(decided.size)
        for (index in decided.indices) {
            val at = skipSpaces(line, decided[index].first)
            // start == end：插在这一点前面，义项串一个字都不删。
            badges.add(PosMark(at, at, poses[index]))
        }
        return TaggedLine(line, if (poses.size == 1) poses.first() else null, badges)
    }

    /** 把 `from` 之后的义项串按 `；` `;` `、` `，` `,` 切开，分隔符留在前一块末尾。 */
    private fun splitGlossChunks(line: String, from: Int): List<IntRange> {
        val chunks = ArrayList<IntRange>()
        var start = from
        var index = from
        while (index < line.length) {
            val ch = line[index]
            if (isGlossSeparator(ch)) {
                chunks.add(start until index + 1)
                start = index + 1
            }
            index++
        }
        if (start < line.length) chunks.add(start until line.length)
        return chunks
    }

    /** 义项分隔符：全角/半角分号、顿号、全角/半角逗号。 */
    private fun isGlossSeparator(ch: Char): Boolean =
        ch == '\uff1b' || ch == ';' || ch == '\u3001' || ch == '\uff0c' || ch == ','

    /** 收集行内所有词性信号（显式 token + 中文词性词），按出现位置升序。 */
    private fun collectMarkers(line: String, legal: Set<String>): List<Marker> {
        val markers = ArrayList<Marker>()

        for (match in EXPLICIT_TOKEN.findAll(line)) {
            // 捕获组只有字母，match.range 才是带点的那一段。
            val token = match.value.lowercase()
            val pos = accept(token, legal) ?: continue
            markers.add(Marker(match.range.first, match.range.last + 1, pos))
        }

        for (match in CHINESE_POS_WORD.findAll(line)) {
            val candidate = CHINESE_POS.firstOrNull { it.first == match.value }?.second ?: continue
            val pos = accept(candidate, legal) ?: continue
            markers.add(Marker(match.range.first, match.range.last + 1, pos))
        }

        markers.sortBy { it.start }
        return markers
    }

    /**
     * 词性是否可用：`meaning` 里没写就不能贴。
     * 唯一放宽：`meaning` 只写了大 `v.` 时，正文里的 `vt.` / `vi.` 也算 v.（反之不成立）。
     */
    private fun accept(candidate: String, legal: Set<String>): String? {
        if (legal.isEmpty()) return null
        if (candidate in legal) return candidate
        if (
            (candidate == VERB_TRANSITIVE || candidate == VERB_INTRANSITIVE) &&
            VERB in legal
        ) {
            return VERB
        }
        return null
    }

    /** 跳到下一个非空白字符（小标不要吸在空格后面）。 */
    private fun skipSpaces(line: String, from: Int): Int {
        var index = from
        while (index < line.length && (line[index] == SPACE || line[index] == IDEOGRAPHIC_SPACE)) {
            index++
        }
        return index
    }

    /** `meaning` → （义项文本 → 它出现过的词性集合）。同词性多义项各占一条。 */
    private fun glossIndex(meaning: String): Map<String, Set<String>> {
        val map = LinkedHashMap<String, MutableSet<String>>()
        for (line in MeaningTextFormatter.parse(meaning)) {
            val pos = line.pos?.lowercase() ?: continue
            // 义项文本里还可能残留顿号/逗号粘连（如 `逗留、徘徊`），再切一刀，
            // 让正文 `→ 逗留、徘徊；…` 里的每个小片都能命中。
            for (piece in splitGlossPieces(line.text)) {
                val gloss = normalizeGloss(piece)
                if (gloss.isEmpty()) continue
                map.getOrPut(gloss) { LinkedHashSet() }.add(pos)
            }
        }
        return map
    }

    /** 把一段义项文本按分号/顿号/逗号切细（与 [splitGlossChunks] 同套分隔符）。 */
    private fun splitGlossPieces(text: String): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (isGlossSeparator(text[index])) {
                pieces.add(text.substring(start, index))
                start = index + 1
            }
            index++
        }
        pieces.add(text.substring(start))
        return pieces
    }

    /** 义项比对用的归一化：剥掉首尾标点/空白，只留实词。 */
    private fun normalizeGloss(text: String): String = text.trim { it in GLOSS_TRIM }

    /** 行内一个词性信号：`start until end` 是被它覆盖的原文片段。 */
    private data class Marker(val start: Int, val end: Int, val pos: String)
}

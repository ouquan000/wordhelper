package com.kaoyan.wordhelper.util

/**
 * 卡片释义的一行渲染结果。
 *
 * @param pos 词性前缀（含末尾的点，如 "v."），无词性的文本为 null
 * @param text 该词性下的义项，已用「；」连接
 */
data class MeaningLine(val pos: String?, val text: String)

/**
 * `meaning` 字段的纯文本排版器（纯 Kotlin/JVM，不依赖 Android）。
 *
 * 种子数据里 `meaning` 的形态是「块之间用 \n 分隔，块内用 ；分隔，每段可能自带词性前缀」，
 * 例如 `v. 创造；v. 创建；v. 引起；任命；建造；引起`：
 * - 段自带词性前缀（n. / v. / vt. / vi. / adj. / adv. / prep. / conj. / pron. / num. / art. / int. / aux.）
 * - 段没有前缀时，继承同一块内上一个出现的词性
 * - 不同词性永不合并（extract 的 `v. 摘录` 与 `n. 摘录` 都要保留）
 *
 * 三个入口的分工：
 * - [parse]     原样解析，一段一个 [MeaningLine]，不合并、不去重
 * - [displayLines] 合并同一块内同词性的段、同词性内完全相同的义项只留第一次，跨块不合并
 * - [displayText]  把 [displayLines] 拼成多行文本，每行 `pos. text` 或 `text`
 */
object MeaningTextFormatter {

    /** 支持的词性前缀，长的在前，避免 "v." 抢走 "vt." 的开头。 */
    private val POS_TAGS = listOf(
        "vt.", "vi.", "v.", "n.", "adj.", "adv.",
        "prep.", "conj.", "pron.", "num.", "art.", "int.", "aux."
    )

    private val POS_PREFIX = Regex(
        "^(" + POS_TAGS.joinToString("|") { Regex.escape(it) } + ")[ \\t]*"
    )

    /** 全角/半角分号都算段分隔符。 */
    private val SEGMENT_SEPARATOR = Regex("[；;]")

    private const val LINE_SEPARATOR = "\n"
    private const val GLOSS_JOINER = "；"
    private const val POS_TEXT_SEPARATOR = " "

    /**
     * 原样解析：每个非空段一项，词性取段首前缀，没前缀则继承同一块内的上一个词性。
     * 空段（含只有分隔符的块）直接丢弃。
     */
    fun parse(meaning: String): List<MeaningLine> {
        val result = ArrayList<MeaningLine>()
        for (block in splitBlocks(meaning)) {
            result.addAll(block)
        }
        return result
    }

    /**
     * 展示用：相同词性的段合并成一行、词性前缀只留一次，
     * 同词性内完全相同的义项只留第一次出现的那一个（顺序不变）。
     * 1.5.3v2：相同词性跨换行块也合并（脏数据里 `n.` 义项常被拆到多个 \n 块，
     * 合并后一行一个词性，不再出现两行 `n.`）。
     */
    fun displayLines(meaning: String): List<MeaningLine> {
        val result = ArrayList<MeaningLine>()
        val glossesByPos = LinkedHashMap<String?, LinkedHashSet<String>>()
        for (block in splitBlocks(meaning)) {
            for (item in block) {
                glossesByPos.getOrPut(item.pos) { LinkedHashSet() }.add(item.text)
            }
        }
        for ((pos, glosses) in glossesByPos) {
            if (glosses.isEmpty()) continue
            result.add(MeaningLine(pos, glosses.joinToString(GLOSS_JOINER)))
        }
        return result
    }

    /** 每行 `pos. text`（pos 自带点），无词性时就是 `text`，行间用 \n 连接。 */
    fun displayText(meaning: String): String =
        displayLines(meaning).joinToString(LINE_SEPARATOR) { line ->
            val pos = line.pos
            if (pos == null) line.text else pos + POS_TEXT_SEPARATOR + line.text
        }

    /** 按块切分，每块内再按分号切段并解析词性（块内继承）。 */
    private fun splitBlocks(meaning: String): List<List<MeaningLine>> {
        if (meaning.isBlank()) return emptyList()
        val blocks = ArrayList<List<MeaningLine>>()
        for (rawLine in meaning.split(LINE_SEPARATOR)) {
            var currentPos: String? = null
            val items = ArrayList<MeaningLine>()
            for (rawSegment in rawLine.split(SEGMENT_SEPARATOR)) {
                val segment = rawSegment.trim()
                if (segment.isEmpty()) continue
                val prefix = POS_PREFIX.find(segment)
                val pos: String?
                val text: String
                if (prefix != null) {
                    pos = prefix.groupValues[1]
                    text = segment.substring(prefix.range.last + 1).trim()
                    currentPos = pos
                } else {
                    pos = currentPos
                    text = segment
                }
                if (text.isEmpty()) continue
                items.add(MeaningLine(pos, text))
            }
            if (items.isNotEmpty()) blocks.add(items)
        }
        return blocks
    }
}

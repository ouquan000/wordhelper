package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.model.AIContentType

object AIContentFormatter {

    private const val MEMORY_AID_FALLBACK_ARROW = "——> 见释义"
    private const val MEMORY_AID_RECALL_LINE = "遮住英文，用上面的抓手反推这个词。"

    private val MEMORY_AID_HEADING_REGEX =
        Regex("""【\s*(?:词根词缀|联想记忆|复习提示|方法|拆分)\s*】""")
    private val MEMORY_AID_LABEL_REGEX =
        Regex("""^(?:方法|拆分|词根|词缀|谐音|联想|记忆|复习提示|提示|招式|思路)\s*[:：]\s*""")
    private val MEMORY_AID_ARROW_REGEX = Regex("""——>|-->|→""")
    private val MEMORY_AID_BOUNDARIES = listOf(
        Regex("""(?<=[。；！？!?])\s*"""),
        Regex("""(?<=[，,、])\s*""")
    )

    fun normalize(type: AIContentType, raw: String): String {
        val content = raw.trim()
        if (content.isBlank()) return content
        return when (type) {
            AIContentType.EXAMPLE -> normalizeExample(content)
            AIContentType.MEMORY_AID -> normalizeMemoryAid(content)
            AIContentType.SENTENCE -> normalizeSentence(content)
            AIContentType.WORD_TRANSLATION -> normalizeWordTranslation(content)
        }
    }

    private fun normalizeExample(content: String): String {
        if (content.contains("【例句1】") && content.contains("【翻译1】")) {
            return content
        }
        val lines = collectCleanLines(content)
        val englishLines = lines.filter { it.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' } }
        val chineseLines = lines.filter { it.any { ch -> ch in '\u4e00'..'\u9fff' } }
        val e1 = englishLines.getOrNull(0) ?: "（未提取到标准例句，请重试）"
        val c1 = chineseLines.getOrNull(0) ?: "（未提取到标准翻译，请重试）"
        val e2 = englishLines.getOrNull(1) ?: "（未提取到第二条例句，请重试）"
        val c2 = chineseLines.getOrNull(1) ?: "（未提取到第二条翻译，请重试）"
        return """
            【例句1】
            $e1
            【翻译1】
            $c1
            【例句2】
            $e2
            【翻译2】
            $c2
        """.trimIndent()
    }

    private fun normalizeMemoryAid(content: String): String {
        // 旧缓存里的三段标题只当分隔符，正文全部保留；新格式没有标题，原样走同一套清洗。
        val body = content
            .replace("\r\n", "\n")
            .replace(MEMORY_AID_HEADING_REGEX, "\n")

        var lines = body.lineSequence()
            .map { line -> cleanMemoryAidLine(line) }
            .filter { it.isNotBlank() }
            .toMutableList()

        if (lines.isEmpty()) return MEMORY_AID_FALLBACK_ARROW

        if (lines.size < 3) {
            lines = expandMemoryAidLines(lines).toMutableList()
        }

        val arrowIndex = lines.indexOfLast { MEMORY_AID_ARROW_REGEX.containsMatchIn(it) }
        when {
            arrowIndex >= 0 && arrowIndex != lines.lastIndex -> {
                val arrowLine = lines.removeAt(arrowIndex)
                lines.add(arrowLine)
            }

            arrowIndex < 0 -> lines.add(MEMORY_AID_FALLBACK_ARROW)
        }

        // 内容太短时补一句中性复述动作，不编造释义。
        if (lines.size < 3) {
            lines.add(lines.lastIndex, MEMORY_AID_RECALL_LINE)
        }

        // 卡背只放 3–5 行：多出来的中段砍掉，末行释义始终保留。
        if (lines.size > 5) {
            val tail = lines.removeAt(lines.lastIndex)
            lines = lines.take(4).toMutableList()
            lines.add(tail)
        }

        return lines.joinToString("\n")
    }

    private fun cleanMemoryAidLine(line: String): String {
        return line.trim()
            .removePrefix("-")
            .removePrefix("*")
            .replace(Regex("""^\d+[).、]\s*"""), "")
            .replace(MEMORY_AID_LABEL_REGEX, "")
            .trim()
    }

    private fun expandMemoryAidLines(lines: List<String>): List<String> {
        var expanded = lines
        for (boundary in MEMORY_AID_BOUNDARIES) {
            if (expanded.size >= 3) break
            val next = expanded.flatMap { line ->
                line.split(boundary)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            if (next.size > expanded.size) expanded = next
        }
        return expanded
    }

    private fun normalizeSentence(content: String): String {
        if (content.contains("## 句子主干") &&
            content.contains("## 语法成分标注") &&
            content.contains("## 中文翻译")
        ) {
            return content
        }

        val normalized = content.replace("\r\n", "\n")
        val blocks = normalized
            .split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val mainClause = blocks.firstOrNull() ?: "未识别到句子主干，请重试。"
        val grammar = when {
            blocks.size >= 3 -> blocks.subList(1, blocks.lastIndex).joinToString("\n\n")
            blocks.size == 2 -> blocks[1]
            else -> normalized
        }.ifBlank { "未识别到语法成分标注，请重试。" }
        val translation = normalized
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.any { ch -> ch in '\u4e00'..'\u9fff' } }
            ?.takeIf { it.isNotBlank() }
            ?: "未识别到中文翻译，请重试。"

        return """
            ## 句子主干
            $mainClause
            ## 语法成分标注
            $grammar
            ## 中文翻译
            $translation
        """.trimIndent()
    }

    private fun normalizeWordTranslation(content: String): String {
        if (content.contains("【中文翻译】") && content.contains("【词性】")) {
            return content
        }
        val lines = collectCleanLines(content)
        val translation = lines.firstOrNull { line -> line.any { it in '\u4e00'..'\u9fff' } }
            ?: lines.firstOrNull()
            ?: "（未提取到中文翻译，请重试）"
        val partOfSpeech = lines.firstOrNull { line ->
            val lower = line.lowercase()
            lower.contains("n.") ||
                lower.contains("v.") ||
                lower.contains("adj.") ||
                lower.contains("adv.") ||
                lower.contains("prep.") ||
                lower.contains("pron.") ||
                lower.contains("词性")
        } ?: "未标注"
        return """
            【中文翻译】
            $translation
            【词性】
            $partOfSpeech
        """.trimIndent()
    }

    private fun collectCleanLines(content: String): List<String> {
        return content
            .replace("\r\n", "\n")
            .lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("-")
                    .removePrefix("*")
                    .replace(Regex("""^\d+[).]\s*"""), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .toList()
    }
}

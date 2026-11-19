package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.model.AIContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIContentFormatterTest {

    @Test
    fun normalizeExample_wrapsIntoFixedTemplate() {
        val raw = "Example one.\n示例一翻译。\nExample two.\n示例二翻译。"
        val normalized = AIContentFormatter.normalize(AIContentType.EXAMPLE, raw)
        assertTrue(normalized.contains("【例句1】"))
        assertTrue(normalized.contains("【翻译2】"))
    }

    @Test
    fun normalizeMemoryAid_stripsLegacyThreeSectionTemplate() {
        val raw = """
            【词根词缀】
            mini = 小
            【联想记忆】
            diminish 就是往里削，越削越小。
            【复习提示】
            记住 mini 的小。
            ——> 减少，削弱
        """.trimIndent()

        val normalized = AIContentFormatter.normalize(AIContentType.MEMORY_AID, raw)
        assertFalse(normalized.contains("【"))
        assertFalse(normalized.contains("词根词缀"))
        assertFalse(normalized.contains("复习提示"))
        assertTrue(normalized.contains("mini = 小"))
        assertTrue(normalized.contains("diminish 就是往里削"))
        assertTrue(normalized.endsWith("——> 减少，削弱"))
        assertTrue(normalized.lines().size in 3..5)
    }

    @Test
    fun normalizeMemoryAid_appendsFallbackArrowOnlyWhenMissing() {
        val raw = "cat 的 ca 像张开的猫嘴\n看见 ca+t 就是一只猫"
        val normalized = AIContentFormatter.normalize(AIContentType.MEMORY_AID, raw)

        assertTrue(normalized.contains("cat 的 ca 像张开的猫嘴"))
        assertTrue(normalized.lines().last().endsWith("——> 见释义"))
        assertTrue(normalized.lines().size in 3..5)
    }

    @Test
    fun normalizeMemoryAid_keepsNormalThreeToFiveLinesIntact() {
        val raw = """
            cat 的 ca 像张开的猫嘴
            t 是翘起的尾巴
            看见 ca+t 就是一只猫
            ——> 猫
        """.trimIndent()

        val normalized = AIContentFormatter.normalize(AIContentType.MEMORY_AID, raw)
        assertEquals(raw, normalized)
        assertFalse(normalized.contains("见释义"))
    }

    @Test
    fun normalizeMemoryAid_cleansLabelsAndCapsAtFiveLines() {
        val raw = """
            方法：把 ca 想成张开的猫嘴
            拆分：t 是翘起的尾巴
            联想：两者一拼就是一只猫
            多看两遍
            遮住英文能反推出猫
            复习提示：睡前再过一遍
            ——> 猫
        """.trimIndent()

        val normalized = AIContentFormatter.normalize(AIContentType.MEMORY_AID, raw)
        val lines = normalized.lines()

        assertFalse(normalized.contains("方法："))
        assertFalse(normalized.contains("拆分："))
        assertFalse(normalized.contains("复习提示"))
        assertTrue(lines.size in 3..5)
        assertTrue(lines.last().endsWith("——> 猫"))
    }

    @Test
    fun normalizeMemoryAid_movesArrowLineToTheEnd() {
        val raw = """
            ca 像张开的猫嘴
            ——> 猫
            t 是翘起的尾巴
        """.trimIndent()

        val normalized = AIContentFormatter.normalize(AIContentType.MEMORY_AID, raw)
        val lines = normalized.lines()

        assertTrue(lines.last().endsWith("——> 猫"))
        assertEquals(3, lines.size)
    }

    @Test
    fun normalizeSentence_wrapsIntoThreeSections() {
        val raw = "主句是 committee approved it.\nbecause 从句解释原因。\n中文：尽管实验昂贵，委员会仍批准。"
        val normalized = AIContentFormatter.normalize(AIContentType.SENTENCE, raw)
        assertTrue(normalized.contains("## 句子主干"))
        assertTrue(normalized.contains("## 语法成分标注"))
        assertTrue(normalized.contains("## 中文翻译"))
    }

    @Test
    fun normalizeWordTranslation_wrapsIntoFixedTemplate() {
        val raw = "遗弃；放弃\nv."
        val normalized = AIContentFormatter.normalize(AIContentType.WORD_TRANSLATION, raw)
        assertTrue(normalized.contains("【中文翻译】"))
        assertTrue(normalized.contains("【词性】"))
    }
}

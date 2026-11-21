package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.model.AIContentType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {

    @Test
    fun buildExamplePrompt_containsWordAndFormattingRules() {
        val prompt = PromptTemplates.build(AIContentType.EXAMPLE, "abandon")
        assertTrue(prompt.contains("abandon"))
        assertTrue(prompt.contains("2 个考研阅读风格英文例句"))
        assertTrue(prompt.contains("只输出上述模板"))
    }

    @Test
    fun buildMemoryAidPrompt_usesSmartRoommatePersonaAndMethodLadder() {
        val prompt = PromptTemplates.build(AIContentType.MEMORY_AID, "diminish")
        assertTrue(prompt.contains("diminish"))
        assertTrue(prompt.contains("聪明室友"))
        assertTrue(prompt.contains("初高中"))
        assertTrue(prompt.contains("——>"))
        assertTrue(prompt.contains("禁报幕"))
        assertTrue(prompt.contains("T0"))
        assertTrue(prompt.contains("T5"))
        // 旧三段模板和硬压字数必须退场。
        assertFalse(prompt.contains("【词根词缀】"))
        assertFalse(prompt.contains("总字数不超过"))
        assertFalse(prompt.contains("120"))
    }

    @Test
    fun buildMemoryAidPrompt_forbidsObscureRootsAndTranslationese() {
        val prompt = PromptTemplates.build(AIContentType.MEMORY_AID, "obscure")
        assertTrue(prompt.contains("polein"))
        assertTrue(prompt.contains("生僻希腊"))
        assertTrue(prompt.contains("体现"))
        assertTrue(prompt.contains("对于…而言"))
    }

    @Test
    fun buildSentencePrompt_containsStructuredSections() {
        val prompt = PromptTemplates.build(AIContentType.SENTENCE, "This is a long sentence.")
        assertTrue(prompt.contains("句子主干"))
        assertTrue(prompt.contains("语法成分标注"))
        assertTrue(prompt.contains("中文翻译"))
    }

    @Test
    fun buildWordTranslationPrompt_containsWordAndSections() {
        val prompt = PromptTemplates.build(AIContentType.WORD_TRANSLATION, "abandon")
        assertTrue(prompt.contains("abandon"))
        assertTrue(prompt.contains("中文翻译"))
        assertTrue(prompt.contains("词性"))
    }

    @Test
    fun systemInstruction_enforcesStrictOutput() {
        val instruction = PromptTemplates.systemInstruction(AIContentType.EXAMPLE)
        assertTrue(instruction.contains("严格"))
        assertTrue(instruction.contains("模板"))
    }

    @Test
    fun systemInstruction_memoryAid_matchesSmartRoommateVoice() {
        val instruction = PromptTemplates.systemInstruction(AIContentType.MEMORY_AID)
        assertTrue(instruction.contains("聪明室友"))
        assertTrue(instruction.contains("初高中"))
        assertTrue(instruction.contains("禁报幕"))
        assertTrue(instruction.contains("——>"))
        assertFalse(instruction.contains("英语单词记忆教练"))
    }
}

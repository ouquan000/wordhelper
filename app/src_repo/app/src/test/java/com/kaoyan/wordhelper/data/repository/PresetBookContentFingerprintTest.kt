package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.model.PresetContentRow
import com.kaoyan.wordhelper.data.model.WordDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetBookContentFingerprintTest {

    private fun wordDraft(word: String, meaning: String = "释义") = WordDraft(word = word, meaning = meaning)

    private fun row(wordKey: String, meaning: String = "释义") =
        PresetContentRow(wordKey = wordKey, meaning = meaning)

    @Test
    fun `same content in different order matches`() {
        val drafts = listOf(wordDraft("apple"), wordDraft("banana"), wordDraft("cherry"))
        val rows = listOf(row("cherry"), row("apple"), row("banana"))

        assertTrue(PresetBookContentFingerprint.matches(drafts, rows))
    }

    @Test
    fun `changed meaning with identical word count does not match`() {
        val drafts = listOf(wordDraft("apple", "苹果"), wordDraft("banana", "香蕉"))
        val rows = listOf(row("apple", "苹果"), row("banana", "旧释义"))

        assertFalse(PresetBookContentFingerprint.matches(drafts, rows))
        assertNotEquals(
            PresetBookContentFingerprint.fromDrafts(drafts),
            PresetBookContentFingerprint.fromRows(rows)
        )
    }

    @Test
    fun `added or removed word does not match`() {
        val drafts = listOf(wordDraft("apple"), wordDraft("banana"))

        assertFalse(PresetBookContentFingerprint.matches(drafts, listOf(row("apple"))))
        assertFalse(PresetBookContentFingerprint.matches(drafts, listOf(row("apple"), row("banana"), row("cherry"))))
    }

    @Test
    fun `duplicate draft keys keep the first occurrence`() {
        val drafts = listOf(wordDraft("apple", "苹果"), wordDraft("Apple", "后来覆盖的释义"))

        assertTrue(PresetBookContentFingerprint.matches(drafts, listOf(row("apple", "苹果"))))
        assertFalse(PresetBookContentFingerprint.matches(drafts, listOf(row("apple", "后来覆盖的释义"))))
    }

    @Test
    fun `word keys are normalized for case and surrounding spaces`() {
        val drafts = listOf(wordDraft("  Apple  "))

        assertTrue(PresetBookContentFingerprint.matches(drafts, listOf(row("apple"))))
        assertEquals(
            PresetBookContentFingerprint.fromDrafts(drafts),
            PresetBookContentFingerprint.fromRows(listOf(row("APPLE")))
        )
    }

    @Test
    fun `empty content matches empty content only`() {
        assertTrue(PresetBookContentFingerprint.matches(emptyList(), emptyList()))
        assertFalse(PresetBookContentFingerprint.matches(emptyList(), listOf(row("apple"))))
    }

    @Test
    fun `blank draft words are ignored`() {
        val drafts = listOf(wordDraft("   "), wordDraft("apple"))

        assertTrue(PresetBookContentFingerprint.matches(drafts, listOf(row("apple"))))
    }

    @Test
    fun `changing one body column changes the fingerprint`() {
        val drafts = listOf(WordDraft(word = "apple", meaning = "苹果", example = "an apple"))
        val rows = listOf(PresetContentRow(wordKey = "apple", meaning = "苹果", example = "a pear"))

        assertFalse(PresetBookContentFingerprint.matches(drafts, rows))
    }
}

package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.WordMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词卡热更安全闸门的纯 JVM 单测。
 *
 * 重点不是「能更新」，而是「什么情况下绝不能更新」：
 *  - 书名被改 → 拒绝，避免悄悄建一本新书；
 *  - 哈希格式不对 → 拒绝，避免跳过完整性校验；
 *  - 用户手改 / 撤销过 / 未复核的助记 → 保留。
 */
class WordbookSyncPolicyTest {

    @Test
    fun `book name must match exactly`() {
        assertTrue(WordbookSyncPolicy.isExpectedBook("六级记忆卡"))
        assertTrue(WordbookSyncPolicy.isExpectedBook("  六级记忆卡  "))
        assertFalse(WordbookSyncPolicy.isExpectedBook("六级记忆卡（新）"))
        assertFalse(WordbookSyncPolicy.isExpectedBook(""))
        assertFalse(WordbookSyncPolicy.isExpectedBook(null))
    }

    @Test
    fun `sha256 must be 64 lowercase hex chars`() {
        assertTrue(WordbookSyncPolicy.isSha256Hex("a".repeat(64)))
        assertTrue(WordbookSyncPolicy.isSha256Hex("0123456789abcdef".repeat(4)))
        assertFalse(WordbookSyncPolicy.isSha256Hex("A".repeat(64)))
        assertFalse(WordbookSyncPolicy.isSha256Hex("a".repeat(63)))
        assertFalse(WordbookSyncPolicy.isSha256Hex("a".repeat(65)))
        assertFalse(WordbookSyncPolicy.isSha256Hex("g".repeat(64)))
        assertFalse(WordbookSyncPolicy.isSha256Hex(null))
    }

    @Test
    fun `pristine seed memory can be replaced`() {
        assertTrue(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_SEED,
                rev = 0,
                prevApproach = null,
                prevMethod = "",
                unverified = false
            )
        )
    }

    @Test
    fun `imported memory without edits can be replaced`() {
        assertTrue(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_IMPORT,
                rev = 0,
                prevApproach = "",
                prevMethod = null,
                unverified = false
            )
        )
    }

    @Test
    fun `user edited memory is always protected`() {
        assertFalse(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_USER,
                rev = 0,
                prevApproach = null,
                prevMethod = null,
                unverified = false
            )
        )
        // 即使用户改完又被标成 seed（异常数据），只要 source=user 就不动。
        assertFalse(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_USER,
                rev = 0,
                prevApproach = "",
                prevMethod = "",
                unverified = false
            )
        )
    }

    @Test
    fun `rewritten or undone memory keeps its history`() {
        assertFalse(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_SEED,
                rev = 3,
                prevApproach = null,
                prevMethod = null,
                unverified = false
            )
        )
        assertFalse(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_SEED,
                rev = 0,
                prevApproach = "上一版思路",
                prevMethod = null,
                unverified = false
            )
        )
        assertFalse(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_SEED,
                rev = 0,
                prevApproach = null,
                prevMethod = "sound",
                unverified = false
            )
        )
    }

    @Test
    fun `unverified memory is not overwritten silently`() {
        assertFalse(
            WordbookSyncPolicy.canReplaceFromOta(
                source = WordMemory.SOURCE_SEED,
                rev = 0,
                prevApproach = null,
                prevMethod = null,
                unverified = true
            )
        )
    }

    @Test
    fun `entity overload follows the same rules`() {
        val fresh = WordMemory(wordId = 1L)
        assertTrue(WordbookSyncPolicy.canReplaceFromOta(fresh))

        val userEdited = WordMemory(wordId = 2L, source = WordMemory.SOURCE_USER)
        assertFalse(WordbookSyncPolicy.canReplaceFromOta(userEdited))

        val undone = WordMemory(wordId = 3L, prevApproach = "旧思路")
        assertFalse(WordbookSyncPolicy.canReplaceFromOta(undone))

        val unverified = WordMemory(wordId = 4L, unverified = true)
        assertFalse(WordbookSyncPolicy.canReplaceFromOta(unverified))
    }
}

package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.WordMemory

/**
 * 词卡热更的纯规则（不碰 Android / Room，方便 JVM 单测）。
 *
 * 这些规则是数据安全闸门：宁可拒绝一次更新，也不能把用户手改过的助记冲掉。
 */
object WordbookSyncPolicy {

    const val BOOK_NAME = "六级记忆卡"

    private val SHA_256_HEX = Regex("^[0-9a-f]{64}$")

    /** 发布清单 / 词卡里的书名必须严格等于「六级记忆卡」，禁止改名建新书。 */
    fun isExpectedBook(book: String?): Boolean = book?.trim() == BOOK_NAME

    /** 只接受 64 位小写十六进制 SHA-256（调用方先 lowercase 再传）。 */
    fun isSha256Hex(value: String?): Boolean = value != null && SHA_256_HEX.matches(value)

    /**
     * 只允许覆盖「从没被人动过」的助记：
     * 用户手改（source=user）、改过又撤销（rev!=0 / prev 非空）、被标 unverified 的，全部保留。
     */
    fun canReplaceFromOta(
        source: String,
        rev: Int,
        prevApproach: String?,
        prevMethod: String?,
        unverified: Boolean
    ): Boolean = source != WordMemory.SOURCE_USER &&
        rev == 0 &&
        prevApproach.isNullOrBlank() &&
        prevMethod.isNullOrBlank() &&
        !unverified

    fun canReplaceFromOta(memory: WordMemory): Boolean = canReplaceFromOta(
        source = memory.source,
        rev = memory.rev,
        prevApproach = memory.prevApproach,
        prevMethod = memory.prevMethod,
        unverified = memory.unverified
    )
}

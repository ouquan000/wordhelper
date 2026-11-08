package com.kaoyan.wordhelper.data.entity

data class Word(
    val id: Long = 0,
    val word: String,
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = "",
    val phrases: String = "",
    val synonyms: String = "",
    val relWords: String = "",
    val bookId: Long,
    /** 记忆思路，多行；来自 tb_word_memory，缺省为空。 */
    val approach: String = "",
    /** 常用搭配，多行；来自 tb_word_memory，缺省为空。 */
    val collocations: String = "",
    /** 记忆方法标签（六脉神剑之一），空串 = 未标注；来自 tb_word_memory。 */
    val method: String = ""
)

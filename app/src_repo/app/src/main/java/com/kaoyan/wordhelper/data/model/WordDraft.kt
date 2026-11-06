package com.kaoyan.wordhelper.data.model

data class WordDraft(
    val word: String,
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = "",
    val phrases: String = "",
    val synonyms: String = "",
    val relWords: String = "",
    /** 记忆思路，多行文本（①②③各一行）。 */
    val approach: String = "",
    /** 常用搭配，多行文本。 */
    val collocations: String = ""
)

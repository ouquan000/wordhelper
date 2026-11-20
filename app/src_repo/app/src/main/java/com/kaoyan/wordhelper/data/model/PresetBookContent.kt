package com.kaoyan.wordhelper.data.model

/**
 * 预设书正文行的投影（只用于内容指纹比较，不是实体表）。
 *
 * 只包含 upsertDraftsForBook 会整行 REPLACE 的正文列；
 * phonetic / approach / collocations 不参与指纹，避免用户改过的记忆思路
 * 让「内容是否需要刷新」永远判成不一致。
 */
data class PresetContentRow(
    val wordKey: String = "",
    val meaning: String = "",
    val example: String = "",
    val phrases: String = "",
    val synonyms: String = "",
    val relWords: String = ""
)

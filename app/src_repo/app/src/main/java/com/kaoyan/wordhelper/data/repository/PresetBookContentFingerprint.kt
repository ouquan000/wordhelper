package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.model.PresetContentRow
import com.kaoyan.wordhelper.data.model.WordDraft
import java.security.MessageDigest

/**
 * 预设书内容指纹：只覆盖 upsertDraftsForBook 会整行 REPLACE 的正文列
 * （词义 / 例句 / 短语 / 近义 / 相关词）。
 *
 * 为什么需要它：旧逻辑只比较 `COUNT(*) == drafts.size`，词数不变、内容换过之后
 * 就永远不再刷新，用户看到的还是旧释义。指纹按规范化后的 word_key 排序、
 * 长度前缀拼接再 SHA-256，语义与顺序无关。
 */
object PresetBookContentFingerprint {

    fun fromDrafts(drafts: List<WordDraft>): String {
        val rows = LinkedHashMap<String, PresetContentRow>(drafts.size)
        drafts.forEach { draft ->
            val key = normalizeKey(draft.word)
            if (key.isBlank() || rows.containsKey(key)) return@forEach
            rows[key] = PresetContentRow(
                wordKey = key,
                meaning = draft.meaning,
                example = draft.example,
                phrases = draft.phrases,
                synonyms = draft.synonyms,
                relWords = draft.relWords
            )
        }
        return digest(rows.values)
    }

    fun fromRows(rows: List<PresetContentRow>): String {
        val byKey = LinkedHashMap<String, PresetContentRow>(rows.size)
        rows.forEach { row ->
            val key = normalizeKey(row.wordKey)
            if (key.isBlank() || byKey.containsKey(key)) return@forEach
            byKey[key] = row.copy(wordKey = key)
        }
        return digest(byKey.values)
    }

    /** true 表示库里的正文和种子完全一致，可以跳过刷新。 */
    fun matches(drafts: List<WordDraft>, rows: List<PresetContentRow>): Boolean {
        return fromDrafts(drafts) == fromRows(rows)
    }

    private fun normalizeKey(raw: String): String = raw.trim().lowercase()

    private fun digest(rows: Collection<PresetContentRow>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sorted = rows.sortedBy { it.wordKey }
        md.update(intBytes(sorted.size))
        sorted.forEach { row ->
            updateField(md, row.wordKey)
            updateField(md, row.meaning)
            updateField(md, row.example)
            updateField(md, row.phrases)
            updateField(md, row.synonyms)
            updateField(md, row.relWords)
        }
        return md.digest().joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            if (value < 0x10) "0${value.toString(16)}" else value.toString(16)
        }
    }

    /** 长度前缀，避免「ab|c」和「a|bc」拼出同一个字节流。 */
    private fun updateField(md: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        md.update(intBytes(bytes.size))
        md.update(bytes)
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}

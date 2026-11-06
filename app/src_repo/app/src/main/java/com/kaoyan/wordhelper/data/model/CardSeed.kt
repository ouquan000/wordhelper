package com.kaoyan.wordhelper.data.model

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 内置种子词库（cet6_cards.json）的读取与转换。
 *
 * 种子文件随 APK 打包在 assets 里；如果在下面两个位置放了同名文件，
 * 会优先用它们，方便 adb push 换内容而不必重装：
 *   1. /sdcard/Android/data/<包名>/files/cet6_cards.json
 *   2. /data/data/<包名>/files/cet6_cards.json
 * 换内容时把 version 加 1，App 启动会自动重导（进度不重置）。
 */
data class CardSeedPayload(
    val version: Int = 0,
    val book: String = "",
    val count: Int = 0,
    val words: List<CardSeedWord> = emptyList()
)

data class CardSeedWord(
    val word: String = "",
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = "",
    val approach: List<String> = emptyList(),
    val collocations: List<String> = emptyList()
)

object CardSeedLoader {
    const val ASSET_NAME = "cet6_cards.json"
    const val DEFAULT_BOOK_NAME = "六级记忆卡"

    private val gson = Gson()

    /** 读种子文件；没有或解析失败返回 null。 */
    fun load(context: Context): CardSeedPayload? {
        externalCandidates(context).firstOrNull { it.isFile && it.length() > 0L }?.let { file ->
            parse { file.readText() }?.takeIf { it.words.isNotEmpty() }?.let { return it }
        }
        return parse {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }?.takeIf { it.words.isNotEmpty() }
    }

    fun toDrafts(seed: CardSeedPayload): List<WordDraft> {
        val drafts = ArrayList<WordDraft>(seed.words.size)
        seed.words.forEach { word ->
            val text = word.word.trim()
            if (text.isBlank()) return@forEach
            drafts.add(
                WordDraft(
                    word = text,
                    phonetic = word.phonetic.trim(),
                    meaning = word.meaning.trim(),
                    example = word.example.trim(),
                    approach = joinLines(word.approach),
                    collocations = joinLines(word.collocations)
                )
            )
        }
        return drafts
    }

    private fun joinLines(lines: List<String>): String {
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun externalCandidates(context: Context): List<File> {
        val files = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { files.add(File(it, ASSET_NAME)) }
        files.add(File(context.filesDir, ASSET_NAME))
        return files
    }

    private fun parse(block: () -> String): CardSeedPayload? {
        return try {
            gson.fromJson(block(), CardSeedPayload::class.java)
        } catch (t: Throwable) {
            null
        }
    }
}

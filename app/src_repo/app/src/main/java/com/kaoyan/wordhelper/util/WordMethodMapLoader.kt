package com.kaoyan.wordhelper.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 词 → 记忆方法标签映射（assets/word_method_map.json）。
 *
 * 数据来自离线 by-word 语料：每个词在生成时就带了 method（六招之一），
 * 之前没有落库，所以老卡片卡背上没有标签。这里只在升级后回填一次。
 * 文件约 94 KB，5228 条，读一次就够。
 */
object WordMethodMapLoader {

    private const val ASSET_NAME = "word_method_map.json"

    private val type = object : TypeToken<Map<String, String>>() {}.type

    fun load(context: Context): Map<String, String> {
        return runCatching {
            context.assets.open(ASSET_NAME).use { input ->
                val json = input.reader(Charsets.UTF_8).readText()
                val parsed: Map<String, String>? = Gson().fromJson(json, type)
                parsed ?: emptyMap()
            }
        }.getOrDefault(emptyMap())
    }
}

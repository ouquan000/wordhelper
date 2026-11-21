package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.Book

/**
 * 预设书保留策略（纯函数，便于单测）。
 *
 * 目录里已下架的预设书、同名重复的预设书都只软停用（total_count 打成隐藏哨兵），
 * 绝不物理删除——tb_progress 按 book_id 记录学习进度，删书行就等于永久丢进度，
 * 而这次 P0 明确要求「不删除任何用户进度」且「不改 DB version」。
 */
internal object PresetBookPlanner {

    /**
     * 需要隐藏的预设书：下架的 + 同名重复里 id 较大的那些。
     * 已经隐藏的书不会再返回（避免每次启动重复处理）。
     */
    fun booksToHide(books: List<Book>, presetNames: Set<String>): List<Book> {
        val visiblePresets = books.filter { it.type == Book.TYPE_PRESET && !it.isHidden }
        val stale = visiblePresets.filter { it.name !in presetNames }
        val duplicates = visiblePresets
            .filter { it.name in presetNames }
            .groupBy { it.name }
            .values
            .flatMap { sameNameBooks -> sameNameBooks.sortedBy { it.id }.drop(1) }
        return stale + duplicates
    }
}

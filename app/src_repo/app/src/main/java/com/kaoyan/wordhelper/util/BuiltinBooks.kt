package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.entity.Book

/**
 * 两本内置大词库的「隐藏」规则。
 *
 * 它们不是用户导入的书，是 WordbookFullLoader 每次启动从 assets 重新导入的，
 * 所以从数据库里删掉会复活——只能靠名字过滤。隐藏期间不导入、不显示，
 * 数据库里的旧记录保留，开关打开就回来。
 */
object BuiltinBooks {

    /** 与 WordbookFullLoader 里的 BuiltinWordbookSource 名字严格一致。 */
    val FULL_BOOK_NAMES = setOf("完整词库", "完整词库（精选）")

    fun isBuiltinFull(book: Book): Boolean = book.name.trim() in FULL_BOOK_NAMES
}

/** hide=true 时把两本内置大词库滤掉；其他书原样返回。 */
fun List<Book>.withoutBuiltinFull(hide: Boolean): List<Book> =
    if (!hide) this else filterNot { BuiltinBooks.isBuiltinFull(it) }

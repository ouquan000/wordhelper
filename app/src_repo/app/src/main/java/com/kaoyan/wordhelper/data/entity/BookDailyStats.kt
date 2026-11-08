package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 按书拆开的每日学习量（DB 17）。
 *
 * 老表 tb_daily_stats / tb_study_log 都是「一天一行、不分书」，所以学习统计切到
 * 「当前词书」时画不出趋势。这张表从升级当天起按书记账，只增不改，不参与打卡/连续天数
 * （那些仍用全局口径，避免历史断层把连续记录清零）。
 *
 * date 用学习日字符串（04:00 边界，与 [com.kaoyan.wordhelper.util.DateUtils] 一致）。
 */
@Entity(
    tableName = "tb_book_daily_stats",
    primaryKeys = ["date", "book_id"]
)
data class BookDailyStats(
    val date: String,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "new_count")
    val newCount: Int = 0,
    @ColumnInfo(name = "review_count")
    val reviewCount: Int = 0,
    @ColumnInfo(name = "spell_count")
    val spellCount: Int = 0
)

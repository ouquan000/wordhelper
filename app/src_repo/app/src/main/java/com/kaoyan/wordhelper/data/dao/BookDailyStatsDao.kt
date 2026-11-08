package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.BookDailyStats

@Dao
interface BookDailyStatsDao {

    /**
     * 累加某本书某一天的量。返回 0 表示这一天这本书还没有行，调用方再插一行。
     * 分成先 UPDATE 再 INSERT，是因为 minSdk 26 的 SQLite 没有 UPSERT 语法。
     */
    @Query(
        """UPDATE tb_book_daily_stats
           SET new_count = new_count + :newCount,
               review_count = review_count + :reviewCount,
               spell_count = spell_count + :spellCount
           WHERE date = :date AND book_id = :bookId"""
    )
    suspend fun addExisting(date: String, bookId: Long, newCount: Int, reviewCount: Int, spellCount: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: BookDailyStats)

    @Query(
        """SELECT * FROM tb_book_daily_stats
           WHERE book_id = :bookId AND date >= :fromDate
           ORDER BY date ASC"""
    )
    suspend fun getRange(bookId: Long, fromDate: String): List<BookDailyStats>
}

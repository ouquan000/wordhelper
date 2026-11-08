package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.MethodCount
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Query("SELECT * FROM tb_progress WHERE word_id = :wordId AND book_id = :bookId")
    suspend fun getProgress(wordId: Long, bookId: Long): Progress?

    @Query(
        """SELECT * FROM tb_progress
           WHERE word_id = :wordId
           ORDER BY review_count DESC, next_review_time DESC, id DESC
           LIMIT 1"""
    )
    suspend fun getGlobalProgress(wordId: Long): Progress?

    @Query("SELECT * FROM tb_progress WHERE word_id IN (:wordIds)")
    suspend fun getProgressByWordIds(wordIds: List<Long>): List<Progress>

    @Query("SELECT word_id FROM tb_progress WHERE book_id = :bookId AND word_id IN (:wordIds)")
    suspend fun getProgressWordIdsByBookAndWordIds(bookId: Long, wordIds: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM tb_progress WHERE book_id = :bookId AND status >= 1")
    fun getLearnedCount(bookId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM tb_progress WHERE book_id = :bookId AND status = 2")
    fun getMasteredCount(bookId: Long): Flow<Int>

    // ---- 按书统计聚合（「当前词书」视图）----
    // 下面三条都只返回单行标量。这些词书常有 5000+ 词，
    // 绝不能换成把整本 Progress 实体读进内存再数的写法（那是掉帧源）。

    /** 按书待复习数，口径与 DateUtils.isDue 一致：未掌握且已到期。 */
    @Query(
        """SELECT COUNT(*) FROM tb_progress
           WHERE book_id = :bookId
             AND status != 2
             AND next_review_time > 0
             AND next_review_time <= :now"""
    )
    fun getBookDueCount(bookId: Long, now: Long): Flow<Int>

    /** 按书正确率，沿用 getGlobalAccuracy 的公式，只多一条 book_id 约束；无复习记录时返回 null。 */
    @Query(
        """SELECT AVG(
            CASE WHEN review_count > 0
                THEN CAST(review_count - spell_wrong_count AS REAL) / review_count
                ELSE 0.5
            END
        ) FROM tb_progress WHERE book_id = :bookId AND review_count > 0"""
    )
    fun getBookAccuracy(bookId: Long): Flow<Float?>

    /** 按书「今日接触词数」：last_review_time 落在学习日区间内，同一个词当天多次复习只算一次。 */
    @Query(
        """SELECT COUNT(*) FROM tb_progress
           WHERE book_id = :bookId
             AND last_review_time >= :startTime
             AND last_review_time < :endTime"""
    )
    fun getBookTodayTouchedCount(bookId: Long, startTime: Long, endTime: Long): Flow<Int>

    // ---- 按记忆方法聚合（「按 tag 统计」）----
    // 只看已经学过的词（status >= 1），按 method 分组计数；COUNT(DISTINCT word_id)
    // 是为了同一个词在几本书里都有进度时不重复算。空串 = 还没落到方法标签。

    @Query(
        """SELECT IFNULL(m.method, '') AS method, COUNT(DISTINCT p.word_id) AS count
           FROM tb_progress p
           LEFT JOIN tb_word_memory m ON m.word_id = p.word_id
           WHERE p.status >= 1
           GROUP BY IFNULL(m.method, '')"""
    )
    fun getGlobalMethodCounts(): Flow<List<MethodCount>>

    @Query(
        """SELECT IFNULL(m.method, '') AS method, COUNT(DISTINCT p.word_id) AS count
           FROM tb_progress p
           LEFT JOIN tb_word_memory m ON m.word_id = p.word_id
           WHERE p.status >= 1 AND p.book_id = :bookId
           GROUP BY IFNULL(m.method, '')"""
    )
    fun getBookMethodCounts(bookId: Long): Flow<List<MethodCount>>

    @Query(
        """SELECT * FROM tb_progress WHERE book_id = :bookId 
           AND status != 2 AND next_review_time > 0 AND next_review_time <= :now 
           ORDER BY next_review_time ASC"""
    )
    suspend fun getDueWords(bookId: Long, now: Long = System.currentTimeMillis()): List<Progress>

    @Query("SELECT * FROM tb_progress WHERE book_id = :bookId")
    suspend fun getProgressByBook(bookId: Long): List<Progress>

    @Query("SELECT * FROM tb_progress WHERE book_id = :bookId")
    fun getProgressByBookFlow(bookId: Long): Flow<List<Progress>>

    @Query(
        """SELECT * FROM tb_progress
           WHERE status != 2 AND next_review_time > 0
           ORDER BY next_review_time ASC"""
    )
    suspend fun getAllPendingProgress(): List<Progress>

    @Query(
        """SELECT * FROM tb_progress
           WHERE status != 2
             AND next_review_time >= :startTime
             AND next_review_time < :endTime
           ORDER BY next_review_time ASC
           LIMIT :limit"""
    )
    suspend fun getWordsForDate(startTime: Long, endTime: Long, limit: Int): List<Progress>

    @Query("UPDATE tb_progress SET next_review_time = :time WHERE id = :id")
    suspend fun updateNextReviewTime(id: Long, time: Long)

    @Query(
        """
        UPDATE tb_progress
        SET status = :learningStatus
        WHERE status = :masteredStatus
          AND (
            interval_days < :minIntervalDays
            OR review_count < :minReviewCount
            OR ease_factor < :minEaseFactor
          )
        """
    )
    suspend fun downgradeInvalidMasteredStatus(
        masteredStatus: Int,
        learningStatus: Int,
        minIntervalDays: Int,
        minReviewCount: Int,
        minEaseFactor: Float
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: Progress): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(progresses: List<Progress>): List<Long>

    @Update
    suspend fun update(progress: Progress)

    @Query("DELETE FROM tb_progress WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("DELETE FROM tb_progress WHERE word_id = :wordId")
    suspend fun deleteByWordId(wordId: Long)

    @Query("SELECT AVG(avg_response_time_ms) FROM tb_progress WHERE avg_response_time_ms > 0")
    suspend fun getAverageResponseTime(): Float?

    @Query(
        """SELECT AVG(
            CASE WHEN review_count > 0
                THEN CAST(review_count - spell_wrong_count AS REAL) / review_count
                ELSE 0.5
            END
        ) FROM tb_progress WHERE review_count > 0"""
    )
    suspend fun getGlobalAccuracy(): Float?

    @Query(
        """SELECT avg_response_time_ms FROM tb_progress
           WHERE avg_response_time_ms > 0
           ORDER BY id DESC LIMIT :limit"""
    )
    suspend fun getRecentResponseTimes(limit: Int = 100): List<Float>
}

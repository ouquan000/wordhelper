package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import kotlinx.coroutines.flow.Flow

@Dao
interface RewriteFeedbackDao {

    /** 返回 -1：同一本书、同一版思路、同一个态度已经表过态（唯一索引挡下）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: RewriteFeedback): Long

    /** 还没发到电脑端的反馈（断网攒下来的），按时间正序补发。 */
    @Query("SELECT * FROM tb_feedback WHERE synced = 0 ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int): List<RewriteFeedback>

    @Query("UPDATE tb_feedback SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM tb_feedback WHERE word_id = :wordId AND book_id = :bookId ORDER BY created_at DESC")
    fun observeForWord(wordId: Long, bookId: Long): Flow<List<RewriteFeedback>>

    /** 当前展示的那一版思路上的态度；null = 这一版还没评价过（换版后可以重新评）。 */
    @Query(
        """SELECT rating FROM tb_feedback
           WHERE word_id = :wordId AND book_id = :bookId AND approach_rev = :approachRev
           ORDER BY created_at DESC, id DESC LIMIT 1"""
    )
    fun observeRatingForRevision(wordId: Long, bookId: Long, approachRev: Int): Flow<String?>

    @Query(
        """SELECT * FROM tb_feedback
           WHERE word_id = :wordId AND book_id = :bookId AND rating = :rating AND approach_rev = :approachRev
           LIMIT 1"""
    )
    suspend fun find(wordId: Long, bookId: Long, rating: String, approachRev: Int): RewriteFeedback?

    /**
     * 1.5.2 点赞++：同一版思路只保留一行，连击累加到 count。
     * 累加后把 synced 重新置 0，下一轮 push 会把最新累计数补发给电脑端。
     */
    @Query(
        """UPDATE tb_feedback
           SET count = count + :delta, synced = 0, created_at = :now
           WHERE id = :id"""
    )
    suspend fun addCount(id: Long, delta: Int, now: Long): Int

    /** 当前这一版思路的累计赞数（给「👍 5」显示用）。 */
    @Query(
        """SELECT COALESCE(SUM(count), 0) FROM tb_feedback
           WHERE word_id = :wordId AND book_id = :bookId
             AND rating = 'up' AND approach_rev = :approachRev"""
    )
    fun observeLikeCount(wordId: Long, bookId: Long, approachRev: Int): Flow<Int>
}

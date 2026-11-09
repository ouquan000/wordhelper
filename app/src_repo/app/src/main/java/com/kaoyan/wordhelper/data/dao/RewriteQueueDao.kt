package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface RewriteQueueDao {

    @Query("SELECT * FROM tb_rewrite_queue WHERE word_id = :wordId LIMIT 1")
    suspend fun getByWordId(wordId: Long): RewriteQueueItem?

    @Query("SELECT * FROM tb_rewrite_queue WHERE word_id = :wordId LIMIT 1")
    fun observeByWordId(wordId: Long): Flow<RewriteQueueItem?>

    /** 还没成功投递给电脑的标记（断网时攒下来的）。 */
    @Query("SELECT * FROM tb_rewrite_queue WHERE status = 'pending' ORDER BY updated_at ASC")
    suspend fun getPending(): List<RewriteQueueItem>

    /** 还没结束的标记数，用来在「我的」页提示还有几条在跑。 */
    @Query("SELECT COUNT(*) FROM tb_rewrite_queue WHERE status IN ('pending','queued')")
    fun observeOpenCount(): Flow<Int>

    /**
     * 1.4.6.1：当前这本书还有几条重写「没结清」。
     *
     * 不只是 pending/queued：DB20 起，已经 done 但还欠一次插回的（冷启动自动同步
     * 可能先于学习页恢复把行写成 done）也算，否则完成页会抢在补插之前出现。
     */
    @Query(
        """SELECT COUNT(*) FROM tb_rewrite_queue
           WHERE book_id = :bookId
             AND (status IN ('pending','queued')
                  OR (needs_reinsert = 1 AND status = 'done'))"""
    )
    fun observeOpenCountForBook(bookId: Long): Flow<Int>

    /**
     * 1.4.6.1 P0（DB20）：这条词被用户点过「跳过」，但还没插回当天队列。
     * 不看 status：done 也要能补插，这才是冷启动竞态的真正兜底。
     */
    @Query(
        """SELECT * FROM tb_rewrite_queue
           WHERE needs_reinsert = 1 AND book_id = :bookId
           ORDER BY updated_at ASC"""
    )
    suspend fun getNeedsReinsertForBook(bookId: Long): List<RewriteQueueItem>

    @Query(
        """UPDATE tb_rewrite_queue SET needs_reinsert = 1
           WHERE word_id = :wordId AND book_id = :bookId"""
    )
    suspend fun markNeedsReinsert(wordId: Long, bookId: Long)

    @Query("UPDATE tb_rewrite_queue SET needs_reinsert = 0 WHERE word_id = :wordId")
    suspend fun clearNeedsReinsert(wordId: Long)

    /** 已投递但太久没回音的标记，重新挂回待发（电脑端数据被清掉时靠它自愈）。 */
    @Query("SELECT * FROM tb_rewrite_queue WHERE status = 'queued' AND updated_at < :before ORDER BY updated_at ASC")
    suspend fun getStaleQueued(before: Long): List<RewriteQueueItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RewriteQueueItem): Long

    @Query(
        """UPDATE tb_rewrite_queue
           SET status = :status, error = :error, updated_at = :now
           WHERE word_id = :wordId"""
    )
    suspend fun updateStatus(wordId: Long, status: String, error: String?, now: Long)

    @Query("DELETE FROM tb_rewrite_queue WHERE word_id = :wordId")
    suspend fun delete(wordId: Long)
}

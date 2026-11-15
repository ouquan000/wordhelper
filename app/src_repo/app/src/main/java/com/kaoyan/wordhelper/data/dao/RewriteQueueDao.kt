package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface RewriteQueueDao {

    @Query("SELECT * FROM tb_rewrite_queue WHERE word_id = :wordId AND book_id = :bookId")
    suspend fun getForBook(wordId: Long, bookId: Long): RewriteQueueItem?

    @Query("SELECT * FROM tb_rewrite_queue WHERE word_id = :wordId")
    suspend fun getAllForWord(wordId: Long): List<RewriteQueueItem>

    @Query(
        """SELECT * FROM tb_rewrite_queue
           WHERE word_id = :wordId AND (book_id = :bookId OR book_id = 0)
           ORDER BY book_id DESC LIMIT 1"""
    )
    suspend fun getVisibleForBook(wordId: Long, bookId: Long): RewriteQueueItem?

    @Query(
        """SELECT * FROM tb_rewrite_queue
           WHERE word_id = :wordId AND (book_id = :bookId OR book_id = 0)
           ORDER BY book_id DESC LIMIT 1"""
    )
    fun observeForBook(wordId: Long, bookId: Long): Flow<RewriteQueueItem?>

    /** 还要投递或等待回包的任务。 */
    @Query("SELECT * FROM tb_rewrite_queue WHERE status IN ('pending','queued') ORDER BY updated_at ASC")
    suspend fun getOpen(): List<RewriteQueueItem>

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
     * book_id=0 是 16_17 迁移留下的全局行；不一起读出来，它就不会进恢复集，
     * 普通队列会把这个词的旧稿放回来，用户一答就清掉欠卡（见 W2 报告）。
     */
    @Query(
        """SELECT * FROM tb_rewrite_queue
           WHERE needs_reinsert = 1 AND
             (book_id = :bookId OR (book_id = 0 AND NOT EXISTS
                (SELECT 1 FROM tb_rewrite_queue AS scoped
                 WHERE scoped.word_id = tb_rewrite_queue.word_id AND scoped.book_id = :bookId)))
           ORDER BY updated_at ASC"""
    )
    suspend fun getNeedsReinsertForBook(bookId: Long): List<RewriteQueueItem>

    /**
     * 1.4.7.2 W2：当前书里「已跳过、还没被用户实际作答」的重写卡。
     *
     * needs_reinsert=1 从这一版起只表示「这张重写卡还欠一次作答」：
     * 生成完成、插回队列、退出都不清，只有用户真的点了三键/太简单/拼写成功才清。
     * 因此这个集合可以直接当欠卡用，按 createdAt 过滤当天就能挡「今日背词完成」。
     */
    @Query(
        """SELECT * FROM tb_rewrite_queue
           WHERE needs_reinsert = 1 AND
             (book_id = :bookId OR (book_id = 0 AND NOT EXISTS
                (SELECT 1 FROM tb_rewrite_queue AS scoped
                 WHERE scoped.word_id = tb_rewrite_queue.word_id AND scoped.book_id = :bookId)))
           ORDER BY created_at ASC, word_id ASC"""
    )
    fun observeNeedsReinsertForBook(bookId: Long): Flow<List<RewriteQueueItem>>

    @Query(
        """UPDATE tb_rewrite_queue SET needs_reinsert = 1
           WHERE id = (SELECT id FROM tb_rewrite_queue
                       WHERE word_id = :wordId AND (book_id = :bookId OR book_id = 0)
                       ORDER BY book_id DESC LIMIT 1)"""
    )
    suspend fun markNeedsReinsert(wordId: Long, bookId: Long): Int

    /**
     * 1.4.7.2 W2：按书结清欠卡。
     *
     * 优先当前书；只有本书没有独立任务时，才作答旧版的全局行。
     */
    @Query(
        """UPDATE tb_rewrite_queue SET needs_reinsert = 0
           WHERE id = (SELECT id FROM tb_rewrite_queue
                       WHERE word_id = :wordId AND (book_id = :bookId OR book_id = 0)
                       ORDER BY book_id DESC LIMIT 1)"""
    )
    suspend fun clearNeedsReinsert(wordId: Long, bookId: Long)

    /**
     * 1.4.7.2 W2：运维/后端停止重写时收尾，把还没插回的词全部结清。
     *
     * 学习页不再有「停止重写」入口（用户口径：不允许停止绕过欠卡），只有仓库层
     * stopAllRewrites / 测试会走到这里。
     */
    @Query("UPDATE tb_rewrite_queue SET needs_reinsert = 0")
    suspend fun clearAllNeedsReinsert()

    /** 旧服务的终态失败不等于合格；有学习欠卡的任务等冷却后再投递。 */
    @Query(
        """SELECT * FROM tb_rewrite_queue WHERE status = 'failed' AND needs_reinsert = 1
           AND updated_at < :before AND COALESCE(error, '') != '用户停止'
           ORDER BY updated_at ASC"""
    )
    suspend fun getFailedDebtsBefore(before: Long): List<RewriteQueueItem>

    @Query(
        """UPDATE tb_rewrite_queue SET status = 'pending', error = NULL, request_id = :requestId,
                  updated_at = :now WHERE id = :id AND status = 'failed' AND needs_reinsert = 1"""
    )
    suspend fun retryFailedDebt(id: Long, requestId: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RewriteQueueItem): Long

    @Query(
        """UPDATE tb_rewrite_queue
           SET status = :status, error = :error, updated_at = :now
           WHERE word_id = :wordId AND book_id = :bookId"""
    )
    suspend fun updateStatus(wordId: Long, bookId: Long, status: String, error: String?, now: Long)

    @Query("DELETE FROM tb_rewrite_queue WHERE word_id = :wordId AND book_id = :bookId")
    suspend fun delete(wordId: Long, bookId: Long)

    /** 删书时清掉该书的队列行，避免遗留孤儿把同词在别的书里永久挡住。 */
    @Query("DELETE FROM tb_rewrite_queue WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)
}

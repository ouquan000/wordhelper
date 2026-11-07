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

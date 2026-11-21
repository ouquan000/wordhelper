package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.SyncOp
import com.kaoyan.wordhelper.data.entity.SyncState

@Dao
interface SyncDao {

    @Query("SELECT * FROM tb_sync_op ORDER BY created_at ASC, op_id ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOp>

    @Query("SELECT COUNT(*) FROM tb_sync_op")
    suspend fun pendingCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(op: SyncOp)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueAll(ops: List<SyncOp>)

    @Query("DELETE FROM tb_sync_op WHERE op_id IN (:opIds)")
    suspend fun deleteByOpIds(opIds: List<String>)

    @Query("SELECT * FROM tb_sync_state WHERE id = 1 LIMIT 1")
    suspend fun state(): SyncState?

    /**
     * 首次同步用：把本地已学过的进度一次捞出来（只读，不改表）。
     * 升级到 1.4.4 之前背的几百个词没有对应的 op 记录，靠这条把历史补进队列，
     * 否则「第一次点同步只备份从今往后」——用户换机就丢老进度。
     */
    @Query(
        """
        SELECT p.word_id AS wordId, p.book_id AS bookId, p.status AS status,
               p.repetitions AS repetitions, p.interval_days AS intervalDays,
               p.next_review_time AS nextReviewTime, p.ease_factor AS easeFactor,
               p.review_count AS reviewCount, p.last_review_time AS lastReviewTime,
               p.fsrsStability AS fsrsStability, p.fsrsDifficulty AS fsrsDifficulty,
               p.fsrsLapses AS fsrsLapses, p.fsrsState AS fsrsState
        FROM tb_progress p
        WHERE p.status >= 1
        """
    )
    suspend fun allLearnedProgress(): List<ProgressForSync>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: SyncState)
}

/** [SyncDao.allLearnedProgress] 的投影：只取同步契约需要的字段。 */
data class ProgressForSync(
    val wordId: Long,
    val bookId: Long,
    val status: Int,
    val repetitions: Int,
    val intervalDays: Int,
    val nextReviewTime: Long,
    val easeFactor: Float,
    val reviewCount: Int,
    val lastReviewTime: Long,
    val fsrsStability: Float = 0f,
    val fsrsDifficulty: Float = 0f,
    val fsrsLapses: Int = 0,
    val fsrsState: Int = 0
)

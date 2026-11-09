package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.RewriteSkippedResult

@Dao
interface RewriteSkippedDao {

    /** 同一个 seq 再跳过只刷新 reason/attempts/last_try_at，不会堆行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RewriteSkippedResult)

    /**
     * 待补拉的跳过行。attempts 到顶的不再返回（它们留在表里当证据，不删也不无限重试）。
     */
    @Query("SELECT * FROM tb_rewrite_skipped WHERE attempts < :maxAttempts ORDER BY seq ASC LIMIT :limit")
    suspend fun getPending(limit: Int, maxAttempts: Int): List<RewriteSkippedResult>

    @Query("SELECT COUNT(*) FROM tb_rewrite_skipped")
    suspend fun count(): Int

    @Query("DELETE FROM tb_rewrite_skipped WHERE seq = :seq")
    suspend fun delete(seq: Long)
}

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: SyncState)
}

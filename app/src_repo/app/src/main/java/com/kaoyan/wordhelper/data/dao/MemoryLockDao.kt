package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.MemoryLock
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryLockDao {

    @Query("SELECT * FROM tb_memory_lock WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    suspend fun get(wordId: Long, bookId: Long): MemoryLock?

    @Query("SELECT EXISTS(SELECT 1 FROM tb_memory_lock WHERE word_id = :wordId AND book_id = :bookId AND locked = 1)")
    suspend fun isLocked(wordId: Long, bookId: Long): Boolean

    @Query("SELECT * FROM tb_memory_lock WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    fun observe(wordId: Long, bookId: Long): Flow<MemoryLock?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lock: MemoryLock): Long

    @Query("DELETE FROM tb_memory_lock WHERE word_id = :wordId AND book_id = :bookId")
    suspend fun delete(wordId: Long, bookId: Long)
}

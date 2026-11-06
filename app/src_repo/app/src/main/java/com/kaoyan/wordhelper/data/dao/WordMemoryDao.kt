package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.WordMemory

@Dao
interface WordMemoryDao {

    @Query("SELECT * FROM tb_word_memory WHERE word_id = :wordId LIMIT 1")
    suspend fun getByWordId(wordId: Long): WordMemory?

    @Query("SELECT * FROM tb_word_memory WHERE word_id IN (:wordIds)")
    suspend fun getByWordIds(wordIds: List<Long>): List<WordMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WordMemory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WordMemory)
}

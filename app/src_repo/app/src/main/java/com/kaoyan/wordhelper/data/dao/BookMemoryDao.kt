package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.BookMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface BookMemoryDao {

    @Query("SELECT * FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    suspend fun getForBook(wordId: Long, bookId: Long): BookMemory?

    @Query("SELECT approach FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    fun observeApproachForBook(wordId: Long, bookId: Long): Flow<String?>

    @Query("SELECT method FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    fun observeMethodForBook(wordId: Long, bookId: Long): Flow<String?>

    @Query("SELECT method FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    suspend fun methodForBook(wordId: Long, bookId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BookMemory)

    @Query("DELETE FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId")
    suspend fun delete(wordId: Long, bookId: Long)
}

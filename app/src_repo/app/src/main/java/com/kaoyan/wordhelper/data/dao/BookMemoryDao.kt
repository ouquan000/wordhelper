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

    /** 这本书当前思路的版本号；反馈要绑在用户真正看到的那一版上。 */
    @Query("SELECT rev FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    fun observeRevForBook(wordId: Long, bookId: Long): Flow<Int?>

    @Query("SELECT method FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId LIMIT 1")
    suspend fun methodForBook(wordId: Long, bookId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BookMemory)

    @Query("DELETE FROM tb_book_memory WHERE word_id = :wordId AND book_id = :bookId")
    suspend fun delete(wordId: Long, bookId: Long)

    /**
     * 1.4.6（P0）：按书那份同样会停在默认的 "root"（种子重导不带 method）。
     * 只改种子来源且仍等于 fromMethod 的行；用户重写出来的（source=user）不碰。
     */
    @Query(
        """UPDATE tb_book_memory SET method = :method, updated_at = :updatedAt
           WHERE word_id = :wordId AND IFNULL(method, '') = :fromMethod AND source != :userSource"""
    )
    suspend fun refreshMethod(
        wordId: Long,
        fromMethod: String,
        method: String,
        updatedAt: Long,
        userSource: String
    ): Int

    @Query(
        """SELECT DISTINCT word_id FROM tb_book_memory
           WHERE IFNULL(method, '') = :fromMethod AND source != :userSource"""
    )
    suspend fun getWordIdsByMethod(fromMethod: String, userSource: String): List<Long>
}

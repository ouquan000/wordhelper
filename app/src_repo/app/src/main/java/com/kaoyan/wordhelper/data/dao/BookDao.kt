package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kaoyan.wordhelper.data.entity.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    /** 用户可见的书：total_count < 0 是软停用的预设书，不展示。 */
    @Query("SELECT * FROM tb_book WHERE total_count >= 0 ORDER BY type ASC, id ASC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM tb_book WHERE total_count >= 0 ORDER BY type ASC, id ASC")
    suspend fun getAllBooksList(): List<Book>

    /**
     * 连软停用的书一起取。只有 ensurePresetBooks 复原预设书时该用，
     * 业务列表一律走 [getAllBooks] / [getAllBooksList]，避免隐藏书漏出来。
     */
    @Query("SELECT * FROM tb_book ORDER BY type ASC, id ASC")
    suspend fun getAllBooksIncludingHidden(): List<Book>

    @Query("SELECT * FROM tb_book WHERE is_active = 1 AND total_count >= 0 LIMIT 1")
    suspend fun getActiveBook(): Book?

    @Query("SELECT * FROM tb_book WHERE is_active = 1 AND total_count >= 0 LIMIT 1")
    fun getActiveBookFlow(): Flow<Book?>

    @Query("SELECT * FROM tb_book WHERE type = 2 LIMIT 1")
    suspend fun getNewWordsBook(): Book?

    @Query("SELECT * FROM tb_book WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): Book?

    @Query("SELECT * FROM tb_book WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByNameAndType(name: String, type: Int): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE tb_book SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE tb_book SET is_active = 1 WHERE id = :bookId")
    suspend fun activate(bookId: Long)

    /** 隐藏中的书不改 total_count，避免一次计数刷新把它重新变成可见。 */
    @Query("UPDATE tb_book SET total_count = :count WHERE id = :bookId AND total_count >= 0")
    suspend fun updateTotalCount(bookId: Long, count: Int)

    /**
     * 软停用预设书：只把 is_active 归零、total_count 打成隐藏哨兵。
     * 不删 tb_book 行、不删 tb_book_word_content、更不碰 tb_progress——
     * 学习进度全部按 book_id 保存，删书就等于永久丢进度。
     */
    @Query("UPDATE tb_book SET is_active = 0, total_count = -1 WHERE id = :bookId")
    suspend fun markPresetHidden(bookId: Long)

    /** 预设重新上架：原地复活，total_count 先按现有内容回填。 */
    @Query(
        """UPDATE tb_book
           SET total_count = (SELECT COUNT(*) FROM tb_book_word_content WHERE book_id = :bookId)
           WHERE id = :bookId"""
    )
    suspend fun unhidePreset(bookId: Long)
}

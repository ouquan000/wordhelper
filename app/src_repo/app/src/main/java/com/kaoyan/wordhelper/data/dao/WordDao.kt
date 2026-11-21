package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.BookWordContent
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.entity.WordEntity
import com.kaoyan.wordhelper.data.model.PresetContentRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {

    @Query(
        """SELECT w.id AS id,
                  w.word AS word,
                  w.phonetic AS phonetic,
                  c.meaning AS meaning,
                  c.example AS example,
                  c.phrases AS phrases,
                  c.synonyms AS synonyms,
                  c.rel_words AS relWords,
                  COALESCE(NULLIF(bm.approach, ''), m.approach, '') AS approach,
                  COALESCE(NULLIF(bm.collocations, ''), m.collocations, '') AS collocations,
                  COALESCE(NULLIF(bm.method, ''), m.method, '') AS method,
                  c.book_id AS bookId
           FROM tb_book_word_content c
           INNER JOIN tb_word w ON c.word_id = w.id
           LEFT JOIN tb_word_memory m ON m.word_id = w.id
           LEFT JOIN tb_book_memory bm ON bm.word_id = w.id AND bm.book_id = c.book_id
           WHERE c.book_id = :bookId
           ORDER BY w.id ASC"""
    )
    fun getWordsByBook(bookId: Long): Flow<List<Word>>

    @Query(
        """SELECT w.id AS id,
                  w.word AS word,
                  w.phonetic AS phonetic,
                  c.meaning AS meaning,
                  c.example AS example,
                  c.phrases AS phrases,
                  c.synonyms AS synonyms,
                  c.rel_words AS relWords,
                  COALESCE(NULLIF(bm.approach, ''), m.approach, '') AS approach,
                  COALESCE(NULLIF(bm.collocations, ''), m.collocations, '') AS collocations,
                  COALESCE(NULLIF(bm.method, ''), m.method, '') AS method,
                  c.book_id AS bookId
           FROM tb_book_word_content c
           INNER JOIN tb_word w ON c.word_id = w.id
           LEFT JOIN tb_word_memory m ON m.word_id = w.id
           LEFT JOIN tb_book_memory bm ON bm.word_id = w.id AND bm.book_id = c.book_id
           WHERE c.book_id = :bookId
           ORDER BY w.id ASC"""
    )
    suspend fun getWordsByBookList(bookId: Long): List<Word>

    @Query(
        """SELECT w.id AS id,
                  w.word AS word,
                  w.phonetic AS phonetic,
                  IFNULL(c.meaning, '') AS meaning,
                  IFNULL(c.example, '') AS example,
                  IFNULL(c.phrases, '') AS phrases,
                  IFNULL(c.synonyms, '') AS synonyms,
                  IFNULL(c.rel_words, '') AS relWords,
                  IFNULL(m.approach, '') AS approach,
                  IFNULL(m.collocations, '') AS collocations,
                  IFNULL(m.method, '') AS method,
                  IFNULL(c.book_id, 0) AS bookId
           FROM tb_word w
           LEFT JOIN tb_word_memory m ON m.word_id = w.id
           LEFT JOIN tb_book_word_content c ON c.id = (
               SELECT id
               FROM tb_book_word_content
               WHERE word_id = w.id
               ORDER BY book_id ASC
               LIMIT 1
           )
           WHERE w.id = :wordId
           LIMIT 1"""
    )
    suspend fun getWordById(wordId: Long): Word?

    @Query(
        """SELECT w.id AS id,
                  w.word AS word,
                  w.phonetic AS phonetic,
                  CASE
                      WHEN :bookId > 0 THEN IFNULL(c_current.meaning, '')
                      ELSE IFNULL(c_any.meaning, '')
                  END AS meaning,
                   CASE
                       WHEN :bookId > 0 THEN IFNULL(c_current.example, '')
                       ELSE IFNULL(c_any.example, '')
                   END AS example,
                   CASE
                       WHEN :bookId > 0 THEN IFNULL(c_current.phrases, '')
                       ELSE IFNULL(c_any.phrases, '')
                   END AS phrases,
                   CASE
                       WHEN :bookId > 0 THEN IFNULL(c_current.synonyms, '')
                       ELSE IFNULL(c_any.synonyms, '')
                   END AS synonyms,
                   CASE
                       WHEN :bookId > 0 THEN IFNULL(c_current.rel_words, '')
                       ELSE IFNULL(c_any.rel_words, '')
                   END AS relWords,
                   IFNULL(m.approach, '') AS approach,
                   IFNULL(m.collocations, '') AS collocations,
                   IFNULL(m.method, '') AS method,
                   CASE
                       WHEN :bookId > 0 THEN :bookId
                       ELSE IFNULL(c_any.book_id, 0)
                   END AS bookId
           FROM tb_word w
           LEFT JOIN tb_word_memory m ON m.word_id = w.id
           LEFT JOIN tb_book_word_content c_current
               ON c_current.word_id = w.id AND c_current.book_id = :bookId
           LEFT JOIN tb_book_word_content c_any ON c_any.id = (
               SELECT id
               FROM tb_book_word_content bc
               WHERE bc.word_id = w.id
               ORDER BY bc.book_id ASC
               LIMIT 1
           )
            WHERE (
                   w.word LIKE '%' || :query || '%'
                   OR (
                       CASE
                           WHEN :bookId > 0 THEN IFNULL(c_current.meaning, '')
                           ELSE IFNULL(c_any.meaning, '')
                       END
                   ) LIKE '%' || :query || '%'
                  )
              AND (:bookId <= 0 OR c_current.id IS NOT NULL)
            ORDER BY w.word ASC
            LIMIT 50"""
    )
    suspend fun searchWords(query: String, bookId: Long = 0): List<Word>

    @Query("SELECT COUNT(*) FROM tb_book_word_content WHERE book_id = :bookId")
    suspend fun getWordCount(bookId: Long): Int

    @Query(
        """SELECT w.word_key
           FROM tb_book_word_content c
           INNER JOIN tb_word w ON c.word_id = w.id
           WHERE c.book_id = :bookId"""
    )
    suspend fun getWordKeysByBook(bookId: Long): List<String>

    /**
     * 预设书内容指纹用的正文投影：只取 upsert 会覆盖的列，按词键对齐。
     * 词数不变但释义/例句改过的书，靠它才能识别出「需要刷新」。
     */
    @Query(
        """SELECT w.word_key AS wordKey,
                  IFNULL(c.meaning, '') AS meaning,
                  IFNULL(c.example, '') AS example,
                  IFNULL(c.phrases, '') AS phrases,
                  IFNULL(c.synonyms, '') AS synonyms,
                  IFNULL(c.rel_words, '') AS relWords
           FROM tb_book_word_content c
           INNER JOIN tb_word w ON c.word_id = w.id
           WHERE c.book_id = :bookId"""
    )
    suspend fun getBookContentForFingerprint(bookId: Long): List<PresetContentRow>

    @Query("SELECT DISTINCT book_id FROM tb_book_word_content WHERE word_id = :wordId")
    suspend fun getBookIdsByWordId(wordId: Long): List<Long>

    @Query("SELECT * FROM tb_word WHERE word_key = :wordKey LIMIT 1")
    suspend fun getWordEntityByKey(wordKey: String): WordEntity?

    @Query("SELECT * FROM tb_word WHERE word_key IN (:wordKeys)")
    suspend fun getWordEntitiesByKeys(wordKeys: List<String>): List<WordEntity>

    @Query("SELECT id FROM tb_word WHERE word_key = :wordKey LIMIT 1")
    suspend fun getWordIdByKey(wordKey: String): Long?

    /** 1.4.6：刷新 root 标签时按 id 反查词键，跟 word_method_map 对上。 */
    @Query("SELECT word_key FROM tb_word WHERE id = :wordId LIMIT 1")
    suspend fun getWordKeyById(wordId: Long): String?

    /** 词表是否已经导完（拉改写结果时，用来区分「查不到这个词」和「种子还没导完」）。 */
    @Query("SELECT COUNT(*) FROM tb_word")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWordEntity(word: WordEntity): Long

    @Query("UPDATE tb_word SET phonetic = :phonetic WHERE id = :wordId")
    suspend fun updatePhonetic(wordId: Long, phonetic: String)

    /**
     * 1.4.4：按词回填记忆方法标签。只填空值，不覆盖已经标注/重写过的（source=user 也不动）。
     * wordKeys 分批传，避免 SQLite 变量上限。
     */
    @Query(
        """UPDATE tb_word_memory SET method = :method
           WHERE IFNULL(method, '') = '' AND source != 'user'
             AND word_id IN (SELECT id FROM tb_word WHERE word_key IN (:wordKeys))"""
    )
    suspend fun backfillMethod(wordKeys: List<String>, method: String)

    @Query("SELECT COUNT(*) FROM tb_word_memory WHERE IFNULL(method, '') != ''")
    suspend fun countMarkedMethod(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookWordContent(content: BookWordContent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookWordContents(contents: List<BookWordContent>)

    /** 1.5.0 词卡热更：先看哪些词在这本书里已有内容行，已有就只改词义/例句，不冲掉搭配等附加字段。 */
    @Query("SELECT word_id FROM tb_book_word_content WHERE book_id = :bookId")
    suspend fun getContentWordIds(bookId: Long): List<Long>

    /** 1.5.0 词卡热更：只更新种子一定会带的两个字段，phrases/synonyms/relWords 原样保留。 */
    @Query(
        """UPDATE tb_book_word_content SET meaning = :meaning, example = :example
           WHERE word_id = :wordId AND book_id = :bookId"""
    )
    suspend fun updateMeaningExample(
        wordId: Long,
        bookId: Long,
        meaning: String,
        example: String
    ): Int

    @Query("DELETE FROM tb_book_word_content WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query(
        """DELETE FROM tb_word
           WHERE id NOT IN (SELECT DISTINCT word_id FROM tb_book_word_content)
             AND id NOT IN (SELECT DISTINCT word_id FROM tb_new_word_ref)"""
    )
    suspend fun deleteOrphanWords()

    @Query(
        """SELECT w.id AS id,
                  w.word AS word,
                  w.phonetic AS phonetic,
                  IFNULL(c.meaning, '') AS meaning,
                  IFNULL(c.example, '') AS example,
                  IFNULL(c.phrases, '') AS phrases,
                  IFNULL(c.synonyms, '') AS synonyms,
                  IFNULL(c.rel_words, '') AS relWords,
                  IFNULL(m.approach, '') AS approach,
                  IFNULL(m.collocations, '') AS collocations,
                  IFNULL(m.method, '') AS method,
                  IFNULL(c.book_id, 0) AS bookId
           FROM tb_new_word_ref n
           INNER JOIN tb_word w ON w.id = n.word_id
           LEFT JOIN tb_word_memory m ON m.word_id = w.id
           LEFT JOIN tb_book_word_content c ON c.id = (
               SELECT id
               FROM tb_book_word_content bc
               WHERE bc.word_id = w.id
               ORDER BY bc.book_id ASC
               LIMIT 1
           )
           ORDER BY n.add_time DESC"""
    )
    suspend fun getNewWordsList(): List<Word>

    @Query(
        """SELECT w.id AS id,
                  w.word AS word,
                  w.phonetic AS phonetic,
                  c.meaning AS meaning,
                  c.example AS example,
                  c.phrases AS phrases,
                  c.synonyms AS synonyms,
                  c.rel_words AS relWords,
                  COALESCE(NULLIF(bm.approach, ''), m.approach, '') AS approach,
                  COALESCE(NULLIF(bm.collocations, ''), m.collocations, '') AS collocations,
                  COALESCE(NULLIF(bm.method, ''), m.method, '') AS method,
                  c.book_id AS bookId
           FROM tb_book_word_content c
           INNER JOIN tb_word w ON c.word_id = w.id
           LEFT JOIN tb_word_memory m ON m.word_id = w.id
           LEFT JOIN tb_book_memory bm ON bm.word_id = w.id AND bm.book_id = c.book_id
           WHERE c.book_id = :bookId
             AND w.id NOT IN (
                 SELECT word_id
                 FROM tb_progress
                 WHERE book_id = :bookId AND status = 2
             )
           ORDER BY w.id ASC
           LIMIT 1"""
    )
    suspend fun getNextNewWord(bookId: Long): Word?
}

package com.kaoyan.wordhelper.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaoyan.wordhelper.data.entity.WordMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface WordMemoryDao {

    @Query("SELECT * FROM tb_word_memory WHERE word_id = :wordId LIMIT 1")
    suspend fun getByWordId(wordId: Long): WordMemory?

    @Query("SELECT * FROM tb_word_memory WHERE word_id IN (:wordIds)")
    suspend fun getByWordIds(wordIds: List<Long>): List<WordMemory>

    /**
     * 卡背直接盯库里的记忆思路：电脑端结果一旦落库，界面就换文，
     * 不再依赖「同步调用正好在词对象上执行过」这种时序。
     */
    @Query("SELECT approach FROM tb_word_memory WHERE word_id = :wordId LIMIT 1")
    fun observeApproach(wordId: Long): Flow<String?>

    /** 方法标签也盯库：重写落地后胶囊要立刻换成新那一招，不能等下一次翻面。 */
    @Query("SELECT method FROM tb_word_memory WHERE word_id = :wordId LIMIT 1")
    fun observeMethod(wordId: Long): Flow<String?>

    /** 全局那份思路的版本号；这本书没有自己的版本时，反馈绑它。 */
    @Query("SELECT rev FROM tb_word_memory WHERE word_id = :wordId LIMIT 1")
    fun observeRev(wordId: Long): Flow<Int?>

    /** 被用户重写过的词，种子重新导入时必须跳过，否则会把重写冲掉。 */
    @Query("SELECT word_id FROM tb_word_memory WHERE source = 'user'")
    suspend fun getUserSourcedWordIds(): List<Long>

    /**
     * 1.4.6：种子重导只更新正文，不碰 method/source/rev/prev_approach。
     * 已存在的行必须走 UPDATE，不能整行 REPLACE，否则标签和撤销都会被冲掉。
     */
    @Query(
        """UPDATE tb_word_memory
           SET approach = :approach, collocations = :collocations, updated_at = :updatedAt
           WHERE word_id = :wordId"""
    )
    suspend fun updateContent(
        wordId: Long,
        approach: String,
        collocations: String,
        updatedAt: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WordMemory>)

    /**
     * 1.4.6（P0）：种子重导不会带 method（WordDraft 没有这个字段），老行就永远停在默认的 "root"。
     * 这里把「种子来源、且还是 fromMethod」的行改成真正的方法标签；source=user 一律不碰。
     * 改完后 method 不再等于 fromMethod，重复调用天然幂等。
     */
    @Query(
        """UPDATE tb_word_memory SET method = :method, updated_at = :updatedAt
           WHERE word_id = :wordId AND IFNULL(method, '') = :fromMethod AND source != :userSource"""
    )
    suspend fun refreshMethod(
        wordId: Long,
        fromMethod: String,
        method: String,
        updatedAt: Long,
        userSource: String
    ): Int

    /** 还在用 fromMethod（默认 "root"）的种子词 id，用来把刷新范围收敛到真正需要改的那几个。 */
    @Query(
        """SELECT word_id FROM tb_word_memory
           WHERE IFNULL(method, '') = :fromMethod AND source != :userSource"""
    )
    suspend fun getWordIdsByMethod(fromMethod: String, userSource: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WordMemory)
}

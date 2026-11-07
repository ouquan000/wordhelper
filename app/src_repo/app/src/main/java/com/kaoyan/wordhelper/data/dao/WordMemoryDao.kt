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

    /** 被用户重写过的词，种子重新导入时必须跳过，否则会把重写冲掉。 */
    @Query("SELECT word_id FROM tb_word_memory WHERE source = 'user'")
    suspend fun getUserSourcedWordIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WordMemory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WordMemory)
}

package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 每个单词的记忆思路与常用搭配。
 *
 * 独立于 tb_book_word_content：内容表在导入时是 REPLACE 写入的，
 * 把记忆思路放进去会被一次重新导入冲掉；这里以 word_id 为主键，
 * 与词绑定，而不是与某本书绑定。
 */
@Entity(
    tableName = "tb_word_memory",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class WordMemory(
    @PrimaryKey
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /** 记忆思路，多行文本，一行一步。 */
    val approach: String = "",
    /** 常用搭配，多行文本，一行一条。 */
    val collocations: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
    /** 来源：seed = 内置种子；user = 用户重写过。种子重新导入时跳过 user，避免冲掉重写。 */
    val source: String = SOURCE_SEED,
    /** 重写版本号；0 = 从没重写过。 */
    val rev: Int = 0,
    /** 上一版思路，够撤销一次；撤销后清空。 */
    @ColumnInfo(name = "prev_approach")
    val prevApproach: String? = null
) {
    companion object {
        const val SOURCE_SEED = "seed"
        const val SOURCE_USER = "user"
    }
}

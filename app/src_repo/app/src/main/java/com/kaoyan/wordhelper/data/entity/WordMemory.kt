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
    val updatedAt: Long = 0
)

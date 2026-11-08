package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 按书保存的记忆思路（DB 17）。
 *
 * 从 1.4.5 起，重写结果落在「词 + 书」上，而不是只落在词上：
 * 同一个词在《测试·20词》里被重写，不该把《六级记忆卡》的卡背一起改掉。
 *
 * 读取优先级：本表有非空 approach → 用它；没有 → 回落到 [WordMemory]（全局种子内容）。
 * 这样老数据不用回填，新书第一次打开也天然有内容。
 */
@Entity(
    tableName = "tb_book_memory",
    primaryKeys = ["word_id", "book_id"],
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["book_id"])]
)
data class BookMemory(
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val approach: String = "",
    val collocations: String = "",
    /** 记忆方法标签，取值同 [WordMemory.method]。 */
    val method: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
    val source: String = WordMemory.SOURCE_SEED,
    val rev: Int = 0,
    @ColumnInfo(name = "prev_approach")
    val prevApproach: String? = null
)

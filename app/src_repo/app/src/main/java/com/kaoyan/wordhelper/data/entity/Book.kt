package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tb_book")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: Int,
    @ColumnInfo(name = "total_count")
    val totalCount: Int = 0,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false
) {
    /**
     * 非持久列：预设书被软停用（下架/重名）时 total_count 写 [HIDDEN_TOTAL_COUNT]。
     *
     * 为什么不加字段：加字段必须升 DB version，而这次 P0 明确要求不动 schema；
     * total_count 是唯一不会被业务当真值使用的整型列，负数哨兵不会和真实词数冲突。
     */
    val isHidden: Boolean get() = totalCount < 0

    companion object {
        const val TYPE_PRESET = 0
        const val TYPE_IMPORTED = 1
        const val TYPE_NEW_WORDS = 2

        /** total_count 的隐藏哨兵：书本行、词条与 tb_progress 全部保留，只从列表里消失。 */
        const val HIDDEN_TOTAL_COUNT = -1
    }
}

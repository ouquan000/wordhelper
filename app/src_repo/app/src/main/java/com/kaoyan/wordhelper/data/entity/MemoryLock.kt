package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 1.5.3：用户手动锁定「免洗金牌」。
 *
 * 锁是按词 × 书存的：同一词在另一本书里仍是独立的一份助记。
 * 锁只保护自动链路（闲时洗词、批量重写、云端词卡热更），
 * 不阻止用户自己再次点「重写」或手动编辑。
 */
@Entity(
    tableName = "tb_memory_lock",
    indices = [Index(value = ["word_id", "book_id"], unique = true)]
)
data class MemoryLock(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "locked", defaultValue = "1")
    val locked: Boolean = true,
    @ColumnInfo(name = "note", defaultValue = "''")
    val note: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

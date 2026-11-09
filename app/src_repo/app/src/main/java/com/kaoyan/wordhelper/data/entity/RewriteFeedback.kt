package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 卡背思路的「有帮助 / 没帮助」反馈（DB 19，1.4.6.1）。
 *
 * 只用来做语料回流与人工复盘：👎 不自动重写、不阻塞 UI，电脑端连不上就先攒在本地，
 * 等下次 [com.kaoyan.wordhelper.data.repository.RewriteRepository.sync] 再补发。
 *
 * 评价必须绑在「用户当时看到的那一版思路」上：同一段文字重复点不重复记，
 * 但换过思路（rev 变了）之后可以重新评价一次。所以唯一索引是
 * (word_id, book_id, rating, approach_rev)，而不是只看词和书。
 */
@Entity(
    tableName = "tb_feedback",
    indices = [Index(value = ["word_id", "book_id", "rating", "approach_rev"], unique = true)]
)
data class RewriteFeedback(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /** 在「哪本书」里点的；0 = 老数据/全局那份。 */
    @ColumnInfo(name = "book_id")
    val bookId: Long = 0,
    /** 冗余存一份单词，补发时不必再查词表。 */
    val word: String,
    /** 见 [RewriteFeedbackRating]。 */
    val rating: String,
    /** 当时看到的思路，出问题时能直接照着复现。 */
    val approach: String = "",
    val method: String = "",
    val model: String = "",
    /** 用户写的那句「哪里有问题」，空 = 整段重写。 */
    val instruction: String = "",
    /**
     * 这一版思路的版本号，和 tb_book_memory.rev / tb_word_memory.rev 对齐。
     * 0 = 还没重写过的种子思路，或老数据。
     */
    @ColumnInfo(name = "approach_rev", defaultValue = "0")
    val approachRev: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** 0 = 还没发到电脑端，sync 时补发；1 = 已确认收到。 */
    val synced: Boolean = false
)

/** 反馈口径。好评只记一次；差评也只记一次，但不会触发任何自动重写。 */
object RewriteFeedbackRating {
    const val UP = "up"
    const val DOWN = "down"

    fun isValid(value: String): Boolean = value == UP || value == DOWN
}

package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 手机上的一条「重写记忆思路」标记（1.4.3）。
 *
 * 手机只负责打标记与上传，真正的生成/润色/评分在电脑端跑。
 * 按 word_id 唯一：同一个词再点一次 = 覆盖旧的指令与模型，不会堆多条。
 */
@Entity(
    tableName = "tb_rewrite_queue",
    indices = [Index(value = ["word_id"], unique = true)]
)
data class RewriteQueueItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /** 冗余存一份单词，电脑端换机恢复时不必再查词表。 */
    val word: String,
    /** 用户写的「哪里有问题」；空 = 整段重写。 */
    val instruction: String = "",
    /** 选中的模型 id，见 RewriteModel。 */
    val model: String = "",
    /** 见 [RewriteStatus]。 */
    val status: String = RewriteStatus.PENDING,
    val error: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

/** 标记状态。done 保留一行，用来显示「上次重写时间 + 撤销」。 */
object RewriteStatus {
    /** 已入本地队列，还没成功发给电脑。 */
    const val PENDING = "pending"

    /** 电脑已收到，等待/正在处理。 */
    const val QUEUED = "queued"

    /** 电脑已重生并写回本地。 */
    const val DONE = "done"

    /** 电脑处理失败（评分不过 / 网络）。 */
    const val FAILED = "failed"
}

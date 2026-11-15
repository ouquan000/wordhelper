package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 手机上的一条「重写记忆思路」标记（1.4.3）。
 *
 * 手机只负责打标记与上传，真正的生成/润色/评分在电脑端跑。
 * 同书同词唯一：重复重写更新指令，另一本书有独立的任务与作答状态。
 */
@Entity(
    tableName = "tb_rewrite_queue",
    indices = [Index(value = ["word_id", "book_id"], unique = true)]
)
data class RewriteQueueItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /**
     * 在「哪本书」里点的重写（1.4.5 / DB17）。
     *
     * 卡背思路从这一版起按书存：同一个词在两本书里被重写，只改自己那本。
     * 老队列没有这个字段（迁移填 0），0 表示沿用旧行为、写全局那份。
     */
    @ColumnInfo(name = "book_id")
    val bookId: Long = 0,
    /** 冗余存一份单词，电脑端换机恢复时不必再查词表。 */
    val word: String,
    /** 用户写的「哪里有问题」；空 = 整段重写。 */
    val instruction: String = "",
    /** 选中的模型 id，见 RewriteModel。 */
    val model: String = "",
    /** 见 [RewriteStatus]。 */
    val status: String = RewriteStatus.PENDING,
    val error: String? = null,
    /**
     * 1.4.7.2 W2：这张重写卡还欠用户一次「实际作答」。
     *
     * 置位：用户点「跳过，先背下一个」（[markNeedsReinsert]）。
     * 清零：用户对这张卡真的作答（三键 / 太简单 / 拼写成功），或词表里已经没有这个词。
     *
     * 关键口径：**生成完成、合格、插回队列、退出学习页都不等于作答完成**，
     * 所以 1.4.7.2 起插回时不再清这个标记（1.4.6 的「插回即结清」是完成页提前出现的根因）。
     * 进程重启靠它把「跳过但没背」的意图恢复回来，也是当天完成判定的唯一依据。
     */
    @ColumnInfo(name = "needs_reinsert", defaultValue = "0")
    val needsReinsert: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** 空串仅用于升级前的请求，继续使用旧 clientId 认领已在途的结果。 */
    @ColumnInfo(name = "request_id", defaultValue = "''")
    val requestId: String = ""
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

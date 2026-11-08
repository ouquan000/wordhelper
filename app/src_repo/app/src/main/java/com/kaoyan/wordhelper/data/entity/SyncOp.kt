package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待发出的同步操作（本地队列）。
 *
 * 设计照抄墨墨背单词的 op_log：一条 op = 一次用户动作 = 一行结果态，
 * op_id 由客户端生成并落库，重发时靠它去重（服务端 INSERT OR IGNORE）。
 * payload 存的是「结果态」而不是「意图」——我们的 SM-2 在手机上算，
 * 服务端不需要重放算法。
 */
@Entity(tableName = "tb_sync_op")
data class SyncOp(
    @PrimaryKey
    @ColumnInfo(name = "op_id")
    val opId: String,
    /** 结果态 JSON，例如 {"t":"progress","wid":1,"bid":2,...} */
    val payload: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

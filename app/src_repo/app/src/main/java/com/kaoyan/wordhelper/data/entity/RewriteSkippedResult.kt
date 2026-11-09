package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步时没吃进去的电脑端结果（DB 18，1.4.6）。
 *
 * 1.4.5 的 [com.kaoyan.wordhelper.data.repository.RewriteRepository.sync] 只要游标往前走，
 * 某一行 apply 失败就等于永久丢掉：词表还没导完、队列行被撤销、同一结果重放……都会走到那条路。
 * 这张表按 seq 把失败行原样存下来（payload 是服务端返回的那一条 JSON），
 * 下次同步先把它们重试一遍，重试成功就删行，超过 [MAX_ATTEMPTS] 才认作废。
 *
 * 不存时间线的另一个原因：游标一旦后退会被服务端当成「重放整段历史」，所以只能本地补拉。
 */
@Entity(tableName = "tb_rewrite_skipped")
data class RewriteSkippedResult(
    /** 电脑端结果的全局序号，天然唯一，直接当主键（重复跳过只更新 attempts）。 */
    @PrimaryKey
    val seq: Long,
    val word: String = "",
    /** 原始结果行 JSON，重试时反序列化回 RewriteResultDto。 */
    val payload: String,
    /** 跳过的原因，出问题时方便在日志/DB 里看。 */
    val reason: String = "",
    val attempts: Int = 0,
    @ColumnInfo(name = "last_try_at")
    val lastTryAt: Long = 0
) {
    companion object {
        /** 重试这么多次还吃不下，就认作废（大多是队列行已被撤销/覆盖，本来就该丢）。 */
        const val MAX_ATTEMPTS = 5
    }
}

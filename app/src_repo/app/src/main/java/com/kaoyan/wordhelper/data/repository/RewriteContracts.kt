package com.kaoyan.wordhelper.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 电脑端 `/rewrite/status` 里当前词的一条进度。
 *
 * 服务端目前只给 `state`（pending/running/done/failed），`stage` 是预留字段，
 * 有就用它精确映射阶段，没有就由 ViewModel 按 state 推断。
 */
data class RewriteStatusSnapshot(
    val word: String,
    val state: String,
    val stage: String?,
    val error: String?,
    /** 电脑端那条记录的更新时间，用来区分「这次的结果」和上一次的历史。 */
    val updatedAt: String? = null
)

/** 一次同步的结果，给 ViewModel 决定要不要刷新当前卡片。 */
data class RewriteSyncResult(
    val uploaded: Int = 0,
    /** 这次真正写进本地库（或标记为失败）的单词 id。 */
    val touchedWordIds: List<Long> = emptyList(),
    val failed: Int = 0,
    val error: String? = null
)

internal fun newRewriteRequestId(wordId: Long, bookId: Long): String =
    "w$wordId-b$bookId-${UUID.randomUUID()}"

internal fun rewriteIsoTime(millis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(millis))
}

internal object RewriteProtocol {
    const val TAG = "RewriteRepository"
    const val STATUS_FAILED = "failed"
    const val POST_ATTEMPTS = 2
    const val PROGRESS_PATH = "/rewrite/status"
    const val STATE_MISSING = "missing"
    const val SEED_READY_MIN_WORDS = 1000
    const val FEEDBACK_BATCH = 50
    const val REASON_SEPARATOR = "|"
    const val SKIPPED_RETRY_LIMIT = 20
    const val NO_ADDRESS = "先去「我的」页填电脑地址"
    const val UNREACHABLE = "电脑没连上"
}

internal fun skipLockedRewrite(source: String?, isLocked: Boolean): Boolean =
    source == "worker" && isLocked

@file:JvmName("RewriteRepositoryKt")
@file:JvmMultifileClass

package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.util.DateUtils
import java.util.Locale

/** 升级前已投递的任务继续使用原 clientId。 */
internal fun rewriteClientId(item: RewriteQueueItem): String =
    item.requestId.ifBlank { "w${item.wordId}-${item.createdAt}" }

/** 老服务按词头合并任务：同词一次只投递一书，其他书的独立任务留在本地。 */
internal fun selectRewriteUploads(
    pending: List<RewriteQueueItem>,
    queued: List<RewriteQueueItem>,
    staleBefore: Long
): List<RewriteQueueItem> {
    fun key(item: RewriteQueueItem) = item.word.trim().lowercase(Locale.ROOT)
    val remoteWords = queued.mapTo(hashSetOf(), ::key)
    return (queued.filter { it.updatedAt < staleBefore } +
        pending.filter { key(it) !in remoteWords }).distinctBy(::key)
}

/** 无 clientId 的旧结果只认唯一全局旧任务，不靠词头猜词书。 */
internal fun matchRewriteResultMark(
    marks: List<RewriteQueueItem>,
    clientId: String?
): RewriteQueueItem? {
    val incoming = clientId?.takeIf { it.isNotBlank() }
    return if (incoming != null) {
        marks.singleOrNull { rewriteClientId(it) == incoming }
    } else {
        marks.singleOrNull()?.takeIf { it.requestId.isBlank() && it.bookId == 0L }
    }
}

/** 同书重写保留欠卡和原学习日；新指令用新的请求键。 */
internal fun buildRewriteRequest(
    existing: RewriteQueueItem?,
    wordId: Long,
    bookId: Long,
    word: String,
    model: String,
    instruction: String,
    now: Long,
    newRequestId: String,
    priority: Int = 0
): RewriteQueueItem {
    val prior = existing?.takeIf { it.wordId == wordId && it.bookId == bookId }
    val trimmed = instruction.trim()
    if (prior != null && prior.status == RewriteStatus.PENDING &&
        prior.instruction == trimmed && prior.model == model &&
        DateUtils.currentLearningDate(prior.createdAt) == DateUtils.currentLearningDate(now)
    ) {
        // 同一天同指令重复入队：保留原行，并保留更高优先级（数值更小者）。
        return prior.copy(updatedAt = now, priority = minOf(prior.priority, priority))
    }
    return RewriteQueueItem(
        id = prior?.id ?: 0,
        wordId = wordId,
        bookId = bookId,
        word = word,
        instruction = trimmed,
        model = model,
        needsReinsert = prior?.needsReinsert == true,
        createdAt = prior?.takeIf { it.needsReinsert }?.createdAt ?: now,
        updatedAt = now,
        requestId = newRequestId,
        priority = priority
    )
}

internal object RewriteReplayPolicy {
    private const val LEGACY_REPLAY_INTERVAL_MS = 60_000L

    fun shouldRewind(
        alreadyRewound: Boolean,
        streamId: String?,
        since: Long,
        nextSince: Long?,
        emptyResults: Boolean,
        now: Long,
        lastReplay: Long,
        previousRewoundSeq: Long
    ): Boolean = !alreadyRewound && streamId.isNullOrBlank() &&
        since > 0 && nextSince == since && emptyResults &&
        now - lastReplay >= LEGACY_REPLAY_INTERVAL_MS && since != previousRewoundSeq
}

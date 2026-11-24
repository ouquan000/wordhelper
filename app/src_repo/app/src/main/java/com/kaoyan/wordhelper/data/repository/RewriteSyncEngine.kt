package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.MemoryLockDao
import com.kaoyan.wordhelper.data.dao.RewriteFeedbackDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteSkippedDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.entity.RewriteSkippedResult
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

private const val MAX_PAGES = 20
private const val CAP_REWRITE_SCOPED = "rewrite_scoped"
private const val STALE_QUEUED_MILLIS = 30 * 60 * 1000L
private const val FAILED_DEBT_RETRY_MILLIS = 60_000L

internal class RewriteSyncEngine(
    private val settingsRepository: SettingsRepository,
    private val wordDao: WordDao,
    private val queueDao: RewriteQueueDao,
    private val feedbackDao: RewriteFeedbackDao,
    private val memoryLockDao: MemoryLockDao,
    private val skippedDao: RewriteSkippedDao,
    private val syncMutex: Mutex,
    private val transport: RewriteTransport,
    private val uploads: RewriteMarkUpload,
    private val merger: RewriteResultMerger,
    private val feedback: RewriteFeedbackStore,
    private val gson: Gson
) {
    private var lastLegacyResultReplay = 0L

    /**
     * 补发未发出的标记 + 增量拉结果。
     * 任何一步失败都只返回 error，不抛异常，调用方静默处理。
     */
    suspend fun sync(): RewriteSyncResult {
        return syncMutex.withLock {
            val address = transport.hasAddress()
            if (!address) return@withLock RewriteSyncResult(error = RewriteProtocol.NO_ADDRESS)
            // 先问一句「你是谁、会什么」：老服务端没有 /health（回 404）就是「电脑端版本过旧」，
            // 这一句比等后面某个接口 404 更早、更准。探测本身失败不阻塞主流程。
            transport.probeHealth()
            var uploaded = 0
            val touched = ArrayList<Long>()
            var failed = 0

            val retryAt = System.currentTimeMillis()
            queueDao.getFailedDebtsBefore(retryAt - FAILED_DEBT_RETRY_MILLIS).forEach { item ->
                queueDao.retryFailedDebt(item.id, newRewriteRequestId(item.wordId, item.bookId), retryAt)
            }
            // 电脑端仍按词头合并任务；等前一书有结果再传下一书，避免远端覆盖。
            // 半小时无回音时优先重投原请求，不能让候补任务抢先覆盖它。
            val staleBefore = System.currentTimeMillis() - STALE_QUEUED_MILLIS
            val open = queueDao.getOpen()
            val pending = if (ServerHealth.supports(transport.serverHealth, CAP_REWRITE_SCOPED) == true) {
                open.filter { it.status == RewriteStatus.PENDING || it.updatedAt < staleBefore }
            } else {
                selectRewriteUploads(
                    open.filter { it.status == RewriteStatus.PENDING },
                    open.filter { it.status == RewriteStatus.QUEUED },
                    staleBefore
                )
            }
            if (pending.isNotEmpty()) {
                val result = uploads.upload(pending)
                val accepted = result.accepted
                if (accepted == null) {
                    // 1.4.7：不再一律说「电脑没连上」—— 404 要说「电脑端版本过旧」，401 要说口令。
                    return@withLock RewriteSyncResult(error = result.error ?: RewriteProtocol.UNREACHABLE)
                }
                val now = System.currentTimeMillis()
                pending.forEach { item ->
                    if (accepted.contains(rewriteClientId(item))) {
                        queueDao.updateStatus(item.wordId, item.bookId, RewriteStatus.QUEUED, null, now)
                        uploaded += 1
                    }
                }
            }

            // 1.4.6：攒下来的 👍/👎 顺手补发；它坏了不该把整轮判死。
            // 1.4.7：但也不能静默 —— 404（电脑端没有 /rewrite/feedback）之类会带一句话进本次结果，
            // 状态页据此显示「电脑端版本过旧」，而不是每次同步都悄悄丢一批反馈。
            var feedbackIssue: String? = null
            runCatching { feedback.pushFeedback(feedbackDao.getUnsynced(RewriteProtocol.FEEDBACK_BATCH)) }
                .getOrNull()?.let { feedbackIssue = it }

            // 1.5.3v2 C5：离线点的「免洗金牌」补发。锁先落本地 synced=0，这里逐条推给电脑端，
            // 成功才置 synced=1；失败留给下一次 sync 再试，绝不丢锁、也不阻塞主流程。
            runCatching {
                memoryLockDao.unsynced().forEach { lock ->
                    val ok = runCatching { feedback.setMemoryLock(lock.wordId, lock.bookId, lock.locked) }
                        .getOrDefault(false)
                    if (ok) {
                        memoryLockDao.markSynced(lock.id)
                    }
                }
            }

            var cursor = settingsRepository.rewriteResultCursor()
            // 服务端在“写结果”和“抬 highWater”之间存在一瞬窗口：游标可能已经停在
            // 最新 seq，但对应结果还没落盘，下一次 since=最新 seq 就再也拉不到它。
            // 本地还有未结清的排队行时，第一页故意回看 1 条，补上这个边界。
            val replayFrom = if (queueDao.getOpen().isNotEmpty() && cursor.seq > 0) {
                cursor.seq - 1
            } else {
                cursor.seq
            }
            var firstPage = true
            var seqRewound = false
            var skippedChecked = false
            val legacyRewoundSeq = settingsRepository.rewriteLegacyRewoundSeq()
            val skippedAttempts = HashMap<Long, Int>()
            repeat(MAX_PAGES) {
                val since = if (firstPage) replayFrom else cursor.seq
                firstPage = false
                val outcome = transport.send("/rewrite/results?since=$since&limit=50")
                if (outcome.kind != RetryPolicy.Kind.OK) {
                    // 404 → 版本过旧；429 → 限流（带剩余秒数）；连不上 → 电脑没连上。
                    return@withLock RewriteSyncResult(
                        uploaded, touched, failed, outcome.message ?: RewriteProtocol.UNREACHABLE
                    )
                }
                val body = outcome.body
                if (body.isNullOrBlank()) {
                    return@withLock RewriteSyncResult(uploaded, touched, failed, "电脑返回的结果是空的")
                }
                val page = runCatching { gson.fromJson(body, ResultsPage::class.java) }.getOrNull()
                    ?: return@withLock RewriteSyncResult(uploaded, touched, failed, "电脑返回的结果读不懂")
                val binding = cursor.bind(page.streamId, page.highWater, page.nextSince)
                val now = System.currentTimeMillis()
                val legacyReplay = RewriteReplayPolicy.shouldRewind(
                    seqRewound, page.streamId, since, page.nextSince, page.results.isNullOrEmpty(),
                    now, lastLegacyResultReplay, legacyRewoundSeq
                )
                if (binding.rewind && seqRewound) {
                    return@withLock RewriteSyncResult(uploaded, touched, failed, "重写结果流正在切换，请稍后重试")
                }
                if (binding.replaceSkipped) {
                    // 旧流的 seq 可与新结果重号；只清补拉缓存，待发任务与用户卡片不动。
                    skippedDao.clearForReplacedStream()
                    skippedAttempts.clear()
                    skippedChecked = false
                }
                val nextCursor = if (legacyReplay) binding.cursor.copy(seq = 0L) else binding.cursor
                if (nextCursor != cursor) {
                    cursor = nextCursor
                    settingsRepository.updateRewriteResultCursor(cursor)
                }
                if (binding.rewind || legacyReplay) {
                    if (legacyReplay) {
                        lastLegacyResultReplay = now
                        settingsRepository.updateRewriteLegacyRewoundSeq(since)
                    }
                    seqRewound = true
                    return@repeat
                }
                if (!skippedChecked) {
                    // 先核对结果流，再补上轮未认领的行。
                    merger.retrySkipped()
                    skippedDao.getPending(RewriteProtocol.SKIPPED_RETRY_LIMIT, RewriteSkippedResult.MAX_ATTEMPTS).forEach {
                        skippedAttempts[it.seq] = it.attempts
                    }
                    skippedChecked = true
                }
                // 刚装好/刚清数据时，种子还没导完，词表里查不到这个词 —— 这条结果先别丢：
                // 不推进 since，下次同步还会再拉一遍。否则「换机恢复」会静默地一条都恢复不了。
                // 词表已经导完还查不到，说明这个词确实不在库里（删词/换词表/脏数据），
                // 那就跳过它继续往后走，不能让它把后面所有结果永远堵住。
                var unknownWord = false
                val skipped = ArrayList<Pair<RewriteResultDto, String>>()
                page.results.orEmpty().forEach { dto ->
                    val wordId = merger.applyResult(dto)
                    if (wordId != null) {
                        touched.add(wordId)
                        if (dto.status == RewriteProtocol.STATUS_FAILED) failed += 1
                    } else {
                        val key = dto.word?.trim()?.lowercase(Locale.ROOT).orEmpty()
                        if (key.isNotEmpty() && wordDao.getWordIdByKey(key) == null) unknownWord = true
                        // 1.4.6：跳过不等于丢掉 —— 记住这一行，下次同步先补它。
                        if (dto.seq != null) skipped.add(dto to merger.skipReasonOf(dto))
                    }
                }
                if (unknownWord && wordDao.countAll() < RewriteProtocol.SEED_READY_MIN_WORDS) {
                    // 种子还没导完：游标不推进，整页下次重来。这里不能落「跳过」，
                    // 否则同一行每拉一遍就烧一次 attempts，种子一到就被当成废行。
                    return@withLock RewriteSyncResult(uploaded, touched, failed, null)
                }
                skipped.forEach { (dto, reason) ->
                    val seq = dto.seq ?: return@forEach
                    val attempts = (skippedAttempts[seq] ?: 0) + 1
                    skippedAttempts[seq] = attempts
                    skippedDao.upsert(
                        RewriteSkippedResult(
                            seq = seq,
                            word = dto.word?.trim().orEmpty(),
                            payload = gson.toJson(dto),
                            reason = reason,
                            attempts = attempts,
                            lastTryAt = System.currentTimeMillis()
                        )
                    )
                }
                val next = page.nextSince ?: since
                if (next > since) {
                    cursor = cursor.copy(seq = next)
                    settingsRepository.updateRewriteResultCursor(cursor)
                }
                if (page.more != true) {
                    return@withLock RewriteSyncResult(uploaded, touched, failed, feedbackIssue)
                }
            }
            RewriteSyncResult(uploaded, touched, failed, feedbackIssue)
        }
    }

    private data class ResultsPage(
        val results: List<RewriteResultDto>?,
        val nextSince: Long?,
        val more: Boolean?,
        val streamId: String?,
        val highWater: Long?
    )
}

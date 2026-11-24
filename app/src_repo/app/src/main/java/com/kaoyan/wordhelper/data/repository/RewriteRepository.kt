@file:JvmName("RewriteRepositoryKt")
@file:JvmMultifileClass

package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.MemoryLockDao
import com.kaoyan.wordhelper.data.dao.RewriteFeedbackDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueBreakdown
import com.kaoyan.wordhelper.data.dao.RewriteSkippedDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.ApproachReview
import com.kaoyan.wordhelper.data.model.RewriteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 「重写记忆思路」的本地队列与电脑端同步（1.4.3）。
 *
 * 手机永不直接调模型：
 *   打标记 → 落 tb_rewrite_queue → 尝试 POST /rewrite/marks
 *   冷启动/回前台 → 补发未发出队列 + GET /rewrite/results?since=lastSeq → 写 tb_word_memory
 *
 * 地址复用「卡背结构地址」的 host:port，只把路径换成 /rewrite 下的接口。
 */
class RewriteRepository(
    settingsRepository: SettingsRepository,
    wordDao: WordDao,
    wordMemoryDao: WordMemoryDao,
    /** 1.4.5 起卡背思路按书存，读的时候先看这本书有没有自己的版本。 */
    bookMemoryDao: BookMemoryDao,
    queueDao: RewriteQueueDao,
    /** 1.4.6：卡背 👍/👎 先存本地，再（尽力）推给电脑端。 */
    feedbackDao: RewriteFeedbackDao,
    /** 1.4.6：同步时因认领不到/词还没导进来而跳过的结果，落在这里等补拉，不能静默丢。 */
    skippedDao: RewriteSkippedDao,
    /** 1.5.3：免洗金牌本地锁；1.5.3v2 起同步时补发 synced=0 的锁。 */
    memoryLockDao: MemoryLockDao,
    /** 只为把书名带上去：记录页按书筛，可书只在手机库里。 */
    bookDao: BookDao
) {
    private val gson = Gson()
    private val syncMutex = Mutex()
    private val transport = RewriteTransport(RewriteEndpointResolver(settingsRepository))
    private val memory = RewriteMemoryReader(bookMemoryDao, wordMemoryDao)
    private val feedback = RewriteFeedbackStore(feedbackDao, transport, gson, RewriteFeedbackEncoder::encode)
    private val status = RewriteStatusReader(queueDao, transport, gson)
    private val syncEngine = RewriteSyncEngine(
        settingsRepository, wordDao, queueDao, feedbackDao, memoryLockDao, skippedDao,
        syncMutex, transport,
        RewriteMarkUpload(wordDao, wordMemoryDao, bookMemoryDao, bookDao, transport, gson),
        RewriteResultMerger(wordDao, wordMemoryDao, bookMemoryDao, queueDao, memoryLockDao, skippedDao, gson),
        feedback, gson
    )
    private val queue = RewriteQueueStore(queueDao, wordMemoryDao, bookMemoryDao, syncMutex, syncEngine, transport, gson)
    private val signals = RewriteMemorySignals(settingsRepository, queueDao, queue, transport, gson)
    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 1.4.6.1：网络恢复后补一次同步。只跟系统回调走，不轮询；
        // App 被杀回调就没了 —— 不要求后台发送，下次打开时的启动同步会补。
        autoSyncScope.launch {
            settingsRepository.connectedNetworkFlow
                .distinctUntilChanged()
                // 第一个值是订阅时读到的「当前状态」：启动同步已经会做，别重复发一次。
                .drop(1)
                .filterNotNull()
                .collect {
                    runCatching { sync() }
                }
        }
    }

    fun observeMark(wordId: Long, bookId: Long): Flow<RewriteQueueItem?> =
        queue.observeMark(wordId, bookId)

    fun observeOpenCount(): Flow<Int> =
        queue.observeOpenCount()

    fun observeQueueBreakdown(): Flow<RewriteQueueBreakdown> =
        queue.observeQueueBreakdown()

    fun observeOpenCountForBook(bookId: Long): Flow<Int> =
        queue.observeOpenCountForBook(bookId)

    suspend fun needsReinsertForBook(bookId: Long): List<RewriteQueueItem> =
        queue.needsReinsertForBook(bookId)

    fun observeNeedsReinsertForBook(bookId: Long): Flow<List<RewriteQueueItem>> =
        queue.observeNeedsReinsertForBook(bookId)

    suspend fun markOf(wordId: Long, bookId: Long): RewriteQueueItem? =
        queue.markOf(wordId, bookId)

    suspend fun markNeedsReinsert(wordId: Long, bookId: Long): Boolean =
        queue.markNeedsReinsert(wordId, bookId)

    suspend fun clearNeedsReinsert(wordId: Long, bookId: Long) =
        queue.clearNeedsReinsert(wordId, bookId)

    suspend fun hasAddress(): Boolean =
        transport.hasAddress()

    suspend fun approachOf(wordId: Long, bookId: Long = 0L): String? =
        memory.approachOf(wordId, bookId)

    fun observeApproach(wordId: Long, bookId: Long = 0L): Flow<String?> =
        memory.observeApproach(wordId, bookId)

    fun observeApproachRev(wordId: Long, bookId: Long = 0L): Flow<Int> =
        memory.observeApproachRev(wordId, bookId)

    suspend fun approachRevOf(wordId: Long, bookId: Long = 0L): Int =
        memory.approachRevOf(wordId, bookId)

    fun observeMethod(wordId: Long, bookId: Long = 0L): Flow<String?> =
        memory.observeMethod(wordId, bookId)

    fun observePrevApproach(wordId: Long, bookId: Long = 0L): Flow<String?> =
        memory.observePrevApproach(wordId, bookId)

    fun observeApproachReview(wordId: Long, bookId: Long = 0L): Flow<ApproachReview> =
        memory.observeApproachReview(wordId, bookId)

    suspend fun methodOf(wordId: Long, bookId: Long = 0L): String =
        memory.methodOf(wordId, bookId)

    suspend fun requestIdOf(wordId: Long, bookId: Long): String? =
        queue.requestIdOf(wordId, bookId)

    suspend fun enqueueAndSync(
        wordId: Long,
        word: String,
        model: String,
        instruction: String,
        bookId: Long = 0L,
        priority: Int = 0
    ): Boolean =
        queue.enqueueAndSync(wordId, word, model, instruction, bookId, priority)

    suspend fun reportMemorySignal(
        wordId: Long,
        bookId: Long,
        word: String,
        method: String,
        event: String,
        stability: Float,
        difficulty: Float,
        lapses: Int
    ) =
        signals.reportMemorySignal(wordId, bookId, word, method, event, stability, difficulty, lapses)

    suspend fun maybeAutoRewriteAfterLapse(word: Word, book: Book, progress: Progress) =
        signals.maybeAutoRewriteAfterLapse(word, book, progress)

    suspend fun cancel(wordId: Long, bookId: Long = 0L): Boolean =
        queue.cancel(wordId, bookId)

    suspend fun stopRewrite(word: String? = null): Boolean =
        queue.stopRewrite(word)

    suspend fun stopAllRewrites(): Boolean =
        queue.stopAllRewrites()

    suspend fun undo(wordId: Long, bookId: Long = 0L): String? =
        queue.undo(wordId, bookId)

    suspend fun sync(): RewriteSyncResult =
        syncEngine.sync()

    fun observeFeedback(wordId: Long, bookId: Long): Flow<List<RewriteFeedback>> =
        feedback.observeFeedback(wordId, bookId)

    suspend fun setMemoryLock(wordId: Long, bookId: Long, locked: Boolean): Boolean =
        feedback.setMemoryLock(wordId, bookId, locked)

    fun observeLikeCount(wordId: Long, bookId: Long, approachRev: Int): Flow<Int> =
        feedback.observeLikeCount(wordId, bookId, approachRev)

    fun observeFeedbackRating(wordId: Long, bookId: Long, approachRev: Int): Flow<String?> =
        feedback.observeFeedbackRating(wordId, bookId, approachRev)

    suspend fun submitFeedback(
        wordId: Long,
        rating: String,
        word: String,
        approach: String,
        method: String,
        model: String,
        instruction: String,
        bookId: Long = 0L,
        approachRev: Int = 0,
        /** 1.5.2 点赞++：本次合并进来的连击次数（1..50）。 */
        count: Int = 1,
        /** 1.5.3：点踩原因（谐音不准/词根太生僻…），可空。 */
        reasons: List<String> = emptyList(),
        /** 1.5.3：点踩吐槽，可空。 */
        comment: String = ""
    ): Boolean =
        feedback.submitFeedback(wordId, rating, word, approach, method, model, instruction, bookId, approachRev, count, reasons, comment)

    suspend fun models(): List<RewriteModel> =
        status.models()

    suspend fun statusOf(mark: RewriteQueueItem): RewriteStatusSnapshot? =
        status.statusOf(mark)

    fun consumeAuthFailure(): Boolean =
        transport.consumeAuthFailure()

    /**
     * 一条反馈。clientId 由（词、书、评价）拼出来 —— 服务端据此幂等去重，
     * 手机端断了网重推同一批也不会在 data/feedback.jsonl 里堆重复行。
     */
    internal data class FeedbackDto(
        val clientId: String,
        val wordId: Long,
        val bookId: Long,
        val word: String,
        val rating: String,
        val approach: String,
        val method: String,
        val model: String,
        val instruction: String,
        /** 这一版思路的版本号；电脑端只做记录，不参与生成。 */
        val approachRev: Int = 0,
        /** 1.5.2：连击合并后的次数；旧服务端会忽略未知字段，不影响兼容。 */
        val count: Int = 1,
        /** 1.5.3：结构化点踩原因；旧服务端会忽略。 */
        val reasons: List<String> = emptyList(),
        /** 1.5.3：点踩吐槽；旧服务端会忽略。 */
        val comment: String = "",
        val createdAt: String?
    )

    companion object {
        const val STATE_MISSING = RewriteProtocol.STATE_MISSING
        const val NO_ADDRESS = RewriteProtocol.NO_ADDRESS
        const val UNREACHABLE = RewriteProtocol.UNREACHABLE

        fun shouldSkipWorkerResult(source: String?, isLocked: Boolean): Boolean =
            skipLockedRewrite(source, isLocked)
    }
}

private object RewriteFeedbackEncoder {
    fun encode(gson: Gson, items: List<RewriteFeedback>): String {
        val payload = FeedbackPayload(
            items = items.map { item ->
                RewriteRepository.FeedbackDto(
                    clientId = feedbackClientId(item.wordId, item.bookId, item.rating, item.approachRev),
                    wordId = item.wordId,
                    bookId = item.bookId,
                    word = item.word,
                    rating = item.rating,
                    approach = item.approach,
                    method = item.method,
                    model = item.model,
                    instruction = item.instruction,
                    approachRev = item.approachRev,
                    count = item.count,
                    reasons = item.reasons.split(RewriteProtocol.REASON_SEPARATOR).filter { it.isNotBlank() },
                    comment = item.comment,
                    createdAt = rewriteIsoTime(item.createdAt)
                )
            }
        )
        return gson.toJson(payload)
    }

    /** 反馈的幂等键：同一本书、同一个词、同一版思路、同一个态度只产生一行。 */
    private fun feedbackClientId(
        wordId: Long,
        bookId: Long,
        rating: String,
        approachRev: Int
    ): String = "f$wordId-$bookId-$rating-r$approachRev"

    private data class FeedbackPayload(val items: List<RewriteRepository.FeedbackDto>)
}

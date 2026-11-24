package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.RewriteFeedbackDao
import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import kotlinx.coroutines.flow.Flow

private const val CAP_FEEDBACK = "feedback"
/** 1.5.3：单次连击合并的上限，和后端 1..50 的校验对齐。 */
private const val MAX_FEEDBACK_BURST = FeedbackBurstPolicy.MAX_COUNT

/** 1.5.3：点踩原因最多几条，避免脏输入撑爆反馈行。 */
private const val MAX_FEEDBACK_REASONS = 8
/** 反馈正文最多带这么多字（思路可能很长，电脑端只需要够判断就行）。 */
private const val MAX_FEEDBACK_CHARS = 2000

internal class RewriteFeedbackStore(
    private val feedbackDao: RewriteFeedbackDao,
    private val transport: RewriteTransport,
    private val gson: Gson,
    private val encodeFeedback: (Gson, List<RewriteFeedback>) -> String
) {
    /** 卡片上的 👍/👎（1.4.6）：当前词在这本书里的反馈，用来把按钮显示成已选状态。 */
    fun observeFeedback(wordId: Long, bookId: Long): Flow<List<RewriteFeedback>> =
        feedbackDao.observeForWord(wordId, bookId)

    /**
     * 1.5.3：把「免洗金牌」同步给电脑端，作为闲时洗词的绝对保护。
     * 本地锁定表先落库；这里只是尽力同步，失败不阻塞用户。
     */
    suspend fun setMemoryLock(wordId: Long, bookId: Long, locked: Boolean): Boolean {
        val payload = gson.toJson(
            mapOf(
                "wordId" to wordId,
                "bookId" to bookId,
                "locked" to locked
            )
        )
        val outcome = transport.send("/rewrite/lock", payload, attempts = 2, rotate = true)
        return outcome.kind == RetryPolicy.Kind.OK
    }

    /** 1.5.2：当前这一版思路的累计赞数，用来显示「👍 5」。 */
    fun observeLikeCount(wordId: Long, bookId: Long, approachRev: Int): Flow<Int> =
        feedbackDao.observeLikeCount(wordId, bookId, approachRev.coerceAtLeast(0))

    /** 只取「当前这一版思路」上的态度；换版后返回 null，允许重新评价。 */
    fun observeFeedbackRating(wordId: Long, bookId: Long, approachRev: Int): Flow<String?> =
        feedbackDao.observeRatingForRevision(wordId, bookId, approachRev)

    /**
     * 记一条 👍/👎。先落本地（离线也不丢），再尽力推一次；推不出去的留给 sync 补发。
     * 同一条（词/书/评价/版本）重复点不会重复入库，也不会重复打扰电脑端。
     * 永远返回 true（除非 rating 非法）—— 反馈是旁路，不能因为网络把 UI 卡住。
     */
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
        comment: String
    ): Boolean {
        if (!RewriteFeedbackRating.isValid(rating)) return false
        val safeRev = approachRev.coerceAtLeast(0)
        val safeCount = count.coerceIn(1, MAX_FEEDBACK_BURST)
        val now = System.currentTimeMillis()
        val safeReasons = reasons.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_FEEDBACK_REASONS)
        val reasonsText = safeReasons.joinToString(RewriteProtocol.REASON_SEPARATOR).take(MAX_FEEDBACK_CHARS)
        val commentText = comment.trim().take(MAX_FEEDBACK_CHARS)

        // 👍 是累加：同一版思路只保留一行，连击加进 count。
        // 👎 1.5.3v2 起同样累加连踩次数（1..50，只记差评不重写），重复点不重复入库。
        if (rating == RewriteFeedbackRating.UP) {
            val existing = feedbackDao.find(wordId, bookId, rating, safeRev)
            if (existing != null) {
                // 累计值封顶 50，避免历史累计超过后端单条 1..50 的校验。
                val nextCount = (existing.count + safeCount).coerceAtMost(MAX_FEEDBACK_BURST)
                val added = nextCount - existing.count
                if (added > 0) {
                    feedbackDao.addCount(existing.id, added, now)
                    runCatching { pushFeedback(feedbackDao.getUnsynced(RewriteProtocol.FEEDBACK_BATCH)) }
                }
                return true
            }
        } else {
            val existing = feedbackDao.find(wordId, bookId, rating, safeRev)
            if (existing != null) {
                val nextCount = (existing.count + safeCount).coerceAtMost(MAX_FEEDBACK_BURST)
                val added = nextCount - existing.count
                if (added > 0) {
                    feedbackDao.addCount(existing.id, added, now)
                }
                feedbackDao.updateReasons(existing.id, reasonsText, commentText, now)
                runCatching { pushFeedback(feedbackDao.getUnsynced(RewriteProtocol.FEEDBACK_BATCH)) }
                return true
            }
        }

        val inserted = feedbackDao.insert(
            RewriteFeedback(
                wordId = wordId,
                bookId = bookId,
                word = word,
                rating = rating,
                approach = approach.take(MAX_FEEDBACK_CHARS),
                method = method,
                model = model,
                instruction = instruction.take(MAX_FEEDBACK_CHARS),
                approachRev = safeRev,
                createdAt = now,
                count = safeCount,
                reasons = reasonsText,
                comment = commentText
            )
        )
        // -1 = 唯一索引挡下了（之前已经表过态），不用再发一次。
        if (inserted <= 0L) return true
        runCatching { pushFeedback(feedbackDao.getUnsynced(RewriteProtocol.FEEDBACK_BATCH)) }
        return true
    }

    /**
     * 把没发出去的反馈推给电脑端。
     *
     * 返回 null = 成功（或本来就没东西要发）；非 null = 给用户看的一句失败原因。
     * 服务端按 clientId 幂等去重，所以整批重推是安全的；但 429 之后不会再硬怼，
     * 而是等冷却窗口（见 [rateLimitedUntil]），这期间同一批留在本地等下一轮。
     */
    suspend fun pushFeedback(items: List<RewriteFeedback>): String? {
        if (items.isEmpty()) return null
        // /health 明说「这台电脑没有 feedback 能力」：请求干脆不发（1.4.6 的 186 次 404 就是这么来的）。
        if (ServerHealth.supports(transport.serverHealth, CAP_FEEDBACK) == false) {
            val message = RetryPolicy.missingCapabilityMessage(CAP_FEEDBACK)
            transport.publish(SyncPhase.SERVER_TOO_OLD, null, message)
            return message
        }

        val outcome = transport.send(
            path = "/rewrite/feedback",
            payload = encodeFeedback(gson, items),
            attempts = RewriteProtocol.POST_ATTEMPTS,
            respectRateLimit = true
        )
        if (outcome.kind != RetryPolicy.Kind.OK) return outcome.message ?: RewriteProtocol.UNREACHABLE
        val response = runCatching { gson.fromJson(outcome.body, FeedbackPage::class.java) }
            .getOrNull() ?: return "电脑返回的回执读不懂，反馈先留在本地"
        if (response.ok != true) return "电脑没接受这批判注，反馈先留在本地"
        feedbackDao.markSynced(items.map { it.id })
        return null
    }

    private data class FeedbackPage(
        val ok: Boolean? = null,
        val accepted: List<String>? = null
    )
}

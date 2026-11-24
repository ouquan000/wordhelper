package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.model.WaitingRewriteRestore
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.WordRepository
import com.kaoyan.wordhelper.util.DateUtils
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val REWRITE_PROGRESS_POLL_INTERVAL_MS = 4_000L

private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

internal class LearningQueueCoordinator(
    private val viewModelScope: CoroutineScope,
    private val repository: WordRepository,
    private val rewriteRepository: RewriteRepository,
    private val session: LearningSessionState,
    private val gate: LearningSubmissionGate,
    private val study: LearningStudyFlows,
    private val rewrite: LearningRewriteFlows
) {
    private val waitingRewriteJobs = mutableMapOf<Long, Job>()
    // 同一 word 的 observer 回调与冷启动补插可能同时到达；只允许一条路径真正插回/清标记。
    private val reinsertingWordIds = mutableSetOf<Long>()
    private var waitingRewritePollJob: Job? = null

    suspend fun loadQueueForBook(
        book: Book?,
        newWordsLimit: Int,
        shuffleNewWords: Boolean,
        plannedModeEnabled: Boolean,
        presentAgain: Boolean = false
    ) {
        if (book == null) {
            session.immediateRetryQueue.clear()
            session.sessionSkippedWordIds.clear()
            clearWaitingRewrites()
            session.queueBookId = null
            session.pendingFailedWordId = null
            session.answeredInSession = 0
            session._dueReviewCount.value = 0
            session.updateQueue(emptyList())
            return
        }
        val bookChanged = session.queueBookId != book.id
        if (bookChanged) {
            session.immediateRetryQueue.clear()
            session.sessionSkippedWordIds.clear()
            clearWaitingRewrites()
            session.queueBookId = book.id
            session.pendingFailedWordId = null
            session.answeredInSession = 0
        }
        val queueSnapshot = withContext(Dispatchers.IO) {
            repository.getStudyQueueSnapshot(
                book = book,
                newWordLimit = newWordsLimit,
                shuffleNewWords = shuffleNewWords,
                plannedNewWordIds = resolvePlannedNewWordIds(book, plannedModeEnabled),
                plannedModeEnabled = plannedModeEnabled
            )
        }
        session._dueReviewCount.value = queueSnapshot.dueCount
        // W2：先把 DB 里「已合格、还没作答」的重写卡登记成稳定插回位（含冷启动补插），
        // 再一次性排进队列。这样每次作答后重建队列，插回位置都一样，不会被重新随机。
        if (!restoreWaitingRewrites(book.id)) {
            // 读库失败时不能按“没有欠卡”重建队列：还没合格的旧稿会漏回普通队列。
            // 同书重载保留当前卡（避免作答后总数归零被误判成完成），换书则清空旧书队列。
            gate._message.value = "重写记录暂时读不出来，先不换卡；请稍后重试"
            if (bookChanged) session.updateQueue(emptyList())
            return
        }
        session.rebuildQueue(queueSnapshot.queue, { study.algorithmV4Enabled.value }, presentAgain || bookChanged)
    }

    /**
     * 1.4.7.2 W2：按 needs_reinsert 恢复「跳过后还没作答」的重写卡。
     *
     * 关键点是以 `needs_reinsert` 为唯一“还没作答”的依据，不看 lastReviewTime：
     * - pending/queued：补注册 observer，结果回来时走 onWaitingRewriteReady；
     * - done：已经合格，登记成稳定插回位，由 loadQueueForBook 一次性排进队列；
     * - failed：仍然欠作答；等同步冷却后重新投递，不以旧稿替代。
     *
     * 恢复书内所有欠作答的行（包括昨天没背完的）：跨天照原 SM-2/正常派发。
     * 昨天的稿今天合格就正常插回；今天还没合格前它只在后台等待，不放进普通队列
     * （否则用户会拿旧稿作答，等于绕过“合格才插回”），也不挡今天的完成页——
     * 因为“今日完成”只按发起学习日过滤，昨天的欠卡不计入今天。重复 load 由
     * plan(rows, tracked) 去重。
     *
     * 读库失败（欠卡列表或其中任一词）返回 false：把「不知道有没有
     * 欠卡」当成「没有欠卡」，会让普通队列把还没合格的旧稿放回来，用户一作答就
     * 清掉 needs_reinsert。读词失败时只跳过该词也不行：它没进恢复集，就不会被
     * 普通队列过滤，旧稿同样会漏出来。
     */
    private suspend fun restoreWaitingRewrites(bookId: Long): Boolean {
        // 失败时交给调用方保留现有队列并提示重试；不能静默按空列表继续重建。
        val rows = runCatching { rewriteRepository.needsReinsertForBook(bookId) }.getOrNull()
            ?: return false
        val createdAtByWordId = rows.associate { it.wordId to it.createdAt }
        val tracked = HashSet<Long>(
            session.waitingRewriteWords.size + session.restoredWaitingRewriteWords.size + waitingRewriteJobs.size
        )
        tracked.addAll(session.waitingRewriteWords.keys)
        tracked.addAll(session.restoredWaitingRewriteWords.keys)
        tracked.addAll(waitingRewriteJobs.keys)
        val plan = WaitingRewriteRestore.plan(rows, tracked)

        plan.watchWordIds.forEach { wordId ->
            val wordResult = runCatching { repository.getWordById(wordId) }
            // 读词失败 ≠ 词已删除：整次恢复中止、保留标记。不能只跳过这个词——
            // 它没进 restoredWaitingRewriteWords，就不会被普通队列过滤，旧稿仍会漏出来。
            if (wordResult.isFailure) return false
            val word = wordResult.getOrNull() ?: return@forEach
            session.restoredWaitingRewriteWords[wordId] = word
            observeWaitingRewrite(word, bookId)
        }

        plan.reinsertWordIds.forEach { wordId ->
            if (wordId in reinsertingWordIds) return@forEach
            val wordResult = runCatching { repository.getWordById(wordId) }
            if (wordResult.isFailure) return false
            val word = wordResult.getOrNull()
            if (word == null) {
                // 词表里已经没有这个词：别再让这行每次加载都重试。
                runCatching { rewriteRepository.clearNeedsReinsert(wordId, bookId) }
                session.forgetPendingReinsert(wordId)
                return@forEach
            }
            // 只要行还带 needs_reinsert=1，就说明用户没有真正作答过（作答路径会清标记）。
            // 不能再用 lastReviewTime 判断：拼写失败也会刷新它，会把未作答误判成已作答。
            session.rememberPendingReinsert(wordId, word, createdAtByWordId[wordId] ?: 0L)
            session.restoredWaitingRewriteWords.remove(wordId)
        }

        if (session.waitingRewriteWords.isNotEmpty() || session.restoredWaitingRewriteWords.isNotEmpty()) {
            ensureWaitingRewritePoll()
        }
        return true
    }

    private fun resolvePlannedNewWordIds(book: Book, plannedModeEnabled: Boolean): List<Long> {
        if (!plannedModeEnabled) return emptyList()
        if (book.type == Book.TYPE_NEW_WORDS) return emptyList()
        val plan = study.todayNewWordsPlan.value
        val learningDate = DateUtils.currentLearningDate().format(DATE_FORMATTER)
        if (plan.learningDate != learningDate) return emptyList()
        if (plan.bookId != book.id) return emptyList()
        return plan.wordIds
    }

    /** 盯住一个等待词的标记；DONE 就插回队列，FAILED 只提示（sync 里会自动重试）。 */
    fun observeWaitingRewrite(word: Word, bookId: Long) {
        waitingRewriteJobs.remove(word.id)?.cancel()
        waitingRewriteJobs[word.id] = viewModelScope.launch {
            rewriteRepository.observeMark(word.id, bookId)
                .map { it?.status }
                .distinctUntilChanged()
                .collect { status ->
                    if (study.activeBook.value?.id != bookId) return@collect
                    when (status) {
                        RewriteStatus.DONE -> onWaitingRewriteReady(word.id, bookId)
                        RewriteStatus.FAILED -> Unit // 留在等待队列；同步会在冷却后重投旧终态任务。
                        null -> {
                            // 队列行被撤销/删除/取消：三个等待集合都要收口，
                            // 否则「预览后取消」会让等待计数在本会话里永远消不掉。
                            session.waitingRewriteWords.remove(word.id)
                            session.restoredWaitingRewriteWords.remove(word.id)
                            session.pendingReinsertWords.remove(word.id)
                            session.pendingReinsertSlots.remove(word.id)
                            waitingRewriteJobs.remove(word.id)?.cancel()
                            if (session.waitingRewriteWords.isEmpty() &&
                                session.restoredWaitingRewriteWords.isEmpty() &&
                                session.pendingReinsertWords.isEmpty()
                            ) {
                                waitingRewritePollJob?.cancel()
                                waitingRewritePollJob = null
                            }
                        }
                    }
                }
        }
        ensureWaitingRewritePoll()
    }

    /** 等待期间每 4 秒补拉一次；有人翻卡背/回前台时也会同步，互不冲突。 */
    private fun ensureWaitingRewritePoll() {
        if (waitingRewritePollJob?.isActive == true) return
        waitingRewritePollJob = viewModelScope.launch {
            while (isActive && (session.waitingRewriteWords.isNotEmpty() || session.restoredWaitingRewriteWords.isNotEmpty())) {
                delay(REWRITE_PROGRESS_POLL_INTERVAL_MS)
                runCatching { syncRewriteNow() }
            }
            waitingRewritePollJob = null
        }
    }

    /**
     * 1.4.7.2 W2：生成好了 = 可以插回，**不等于用户背完**。
     *
     * 登记成稳定插回位后立刻重排一次队列（合格稿马上出现，落在当前卡之后第 2/3 张
     * 普通卡之后），但不清 needs_reinsert —— 只有用户对这张卡真的作答才结清。
     * observer 回调与冷启动 done 行补插可能同时到达；reinsertingWordIds 保证只走一条路径。
     */
    private suspend fun onWaitingRewriteReady(wordId: Long, bookId: Long) {
        if (!reinsertingWordIds.add(wordId)) return
        val fromSessionWait = session.waitingRewriteWords.remove(wordId)
        val word = fromSessionWait
            ?: session.restoredWaitingRewriteWords.remove(wordId)
            ?: session.pendingReinsertWords[wordId]
        if (word == null) {
            reinsertingWordIds.remove(wordId)
            return
        }
        // 先只摘掉登记，等挂起的查询/插队做完再 cancel —— 当前 collector 自己的 job
        // 若在这里就 cancel，下面的 getProgress 会立刻抛 CancellationException，词就插不回去。
        val collectorJob = waitingRewriteJobs.remove(wordId)
        session.sessionSkippedWordIds.remove(wordId)
        session.refreshWaitingCount()

        try {
            // 作答路径会清 needs_reinsert；它还是 true 才说明这张重写卡没被真正作答。
            // 不能用 lastReviewTime：拼写失败也会刷新它，会把未作答误判成已作答。
            val markResult = runCatching { rewriteRepository.markOf(wordId, bookId) }
            if (study.activeBook.value?.id != bookId) return
            val mark = markResult.getOrNull()
            if (markResult.isFailure || (mark?.needsReinsert == true && mark.status != RewriteStatus.DONE)) {
                // 读不到当前书的结果、或新的重写已替代合格稿，都要继续等。
                session.restoredWaitingRewriteWords[wordId] = word
                session.refreshWaitingCount()
                viewModelScope.launch {
                    delay(REWRITE_PROGRESS_POLL_INTERVAL_MS)
                    if (study.activeBook.value?.id == bookId && wordId in session.restoredWaitingRewriteWords) {
                        observeWaitingRewrite(word, bookId)
                    }
                }
                return
            }
            if (mark == null || !mark.needsReinsert) {
                // 行已不存在（取消/删除）或用户真的作答过：这张卡不再欠，结清后退出等待。
                if (mark != null) {
                    runCatching { rewriteRepository.clearNeedsReinsert(wordId, mark.bookId) }
                }
                session.forgetPendingReinsert(wordId)
                return
            }
            session.rememberPendingReinsert(wordId, word, mark.createdAt)
            session.updateQueue(session.applyPendingReinsertPlacements(session.activeQueue))
        } finally {
            reinsertingWordIds.remove(wordId)
            collectorJob?.cancel()
            if (session.waitingRewriteWords.isEmpty() && session.restoredWaitingRewriteWords.isEmpty()) {
                waitingRewritePollJob?.cancel()
                waitingRewritePollJob = null
            }
        }
    }

    fun clearWaitingRewrites() {
        waitingRewriteJobs.values.forEach { it.cancel() }
        waitingRewriteJobs.clear()
        session.waitingRewriteWords.clear()
        session.restoredWaitingRewriteWords.clear()
        // 换书/停止后不再按旧书的槽位插回；DB 里的 needs_reinsert 留着，下次进这本书记得再恢复。
        session.clearPendingReinsert()
        waitingRewritePollJob?.cancel()
        waitingRewritePollJob = null
        session.refreshWaitingCount()
    }

    suspend fun syncRewriteNow() {
        rewriteRepository.sync()
        // 不管这次有没有拉到新结果，都拿库里的最新思路刷一下当前卡片：
        // 结果可能在上一轮同步里就落库了，但卡片还停着旧文本。
        val current = session._currentWord.value
        if (current != null) {
            val bookId = study.activeBook.value?.id ?: 0L
            val fresh = rewriteRepository.approachOf(current.id, bookId)
            // 方法标签跟着思路一起换：重写结果里带了 method，别让卡背还挂着旧的那一招。
            val freshMethod = rewriteRepository.methodOf(current.id, bookId)
                .ifBlank { current.method }
            val approachChanged = fresh != null && fresh != current.approach
            val methodChanged = freshMethod != current.method
            if (approachChanged || methodChanged) {
                session.publishWord(current.copy(approach = fresh ?: current.approach, method = freshMethod))
                rewrite._rewriteUpdatedTick.value += 1
            }
        } else if (study.waitingRewriteCount.value > 0) {
            // 1.5.2v2：队列背完时当前卡为 null，等待卡上的「现在刷新」只刷新当前卡，
            // 结果即使已经落库也不会插回队列。这里主动重建一次队列，让 needs_reinsert
            // 的 DONE 行按正常位置插回来（冷启动同样走这条）。
            study.activeBook.value?.let { book ->
                loadQueueForBook(
                    book = book,
                    newWordsLimit = study.newWordsLimit.value,
                    shuffleNewWords = study.shuffleNewWordsEnabled.value,
                    plannedModeEnabled = study.plannedNewWordsEnabled.value
                )
            }
        }
        if (rewrite._rewriteModels.value === RewriteModels.DEFAULT) {
            rewrite._rewriteModels.value = rewriteRepository.models()
        }
    }
}

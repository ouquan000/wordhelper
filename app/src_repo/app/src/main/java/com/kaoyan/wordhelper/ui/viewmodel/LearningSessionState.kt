package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.data.model.WaitingReinsertPlacement
import com.kaoyan.wordhelper.util.ImmediateRetryQueuePlanner
import com.kaoyan.wordhelper.util.RetryRandomSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class LearningAiState(
    val isEnabled: Boolean = false,
    val isConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val activeType: AIContentType? = null,
    val content: String = "",
    val error: String? = null
) {
    val isAvailable: Boolean
        get() = isEnabled && isConfigured
}

/**
 * 心流进度条节奏。
 *
 * tier：0=迟缓（当前卡停留 >22s），1=正常，2=快一点，3=更快，4=最快/燃烧。
 * elapsedMs：当前卡停留时长；lastAnswerDurationMs：最近一次作答耗时。
 */
data class LearningPace(
    val tier: Int = 1,
    val elapsedMs: Long = 0L,
    val lastAnswerDurationMs: Long = 0L,
    /** 连续心流热力 0..1：作答瞬间接近 1，随后按约 4.2s 线性冷却，不随切卡瞬间归零。 */
    val heat: Float = 0f
)

private fun paceTierForAnswerDuration(durationMs: Long): Int = when {
    durationMs <= 0L -> 1
    durationMs <= 8_000L -> 4
    durationMs <= 10_000L -> 3
    durationMs <= 13_000L -> 2
    else -> 1
}

/** 作答耗时 → 连续热力：6s 内 1，8s≈0.8，10s≈0.55，13s≈0.2，16s 后 0。 */
private fun paceHeatForAnswerDuration(durationMs: Long): Float = when {
    durationMs <= 0L -> 0f
    durationMs <= 6_000L -> 1f
    durationMs <= 8_000L -> 1f - (durationMs - 6_000L) / 2_000f * 0.20f
    durationMs <= 10_000L -> 0.80f - (durationMs - 8_000L) / 2_000f * 0.25f
    durationMs <= 13_000L -> 0.55f - (durationMs - 10_000L) / 3_000f * 0.35f
    durationMs <= 16_000L -> 0.20f - (durationMs - 13_000L) / 3_000f * 0.20f
    else -> 0f
}

private const val PACE_HEAT_COOLDOWN_MS = 4_200L

internal data class LearningCardPresentation(val word: Word?, val generation: Long)

internal class LearningSessionState {
    val immediateRetryQueue = ArrayDeque<Word>()
    val sessionSkippedWordIds = LinkedHashSet<Long>()
    // 1.4.6 H：跳过重写后等结果的词。它们不删、不免背，只暂时离开当前队列；
    // 电脑端一生成好就按随机位置插回来（见 onWaitingRewriteReady）。
    val waitingRewriteWords = LinkedHashMap<Long, Word>()
    // 1.4.6.1 P0：进程重启后从 DB 补回来的等待词。只用于补注册监听和结果到达时插回，
    // 不能混进 waitingRewriteWords —— updateQueue 会把它算进剩余进度，造成重复计数。
    val restoredWaitingRewriteWords = LinkedHashMap<Long, Word>()
    // 1.4.7.2 W2：已合格、已排进队列、但用户还没作答的重写卡。
    // 记忆里保存稳定槽位（当前卡之后第 2/3 张普通卡之后）：每次重建队列都按同一位置重排，
    // 作答（清 needs_reinsert）时才移除，所以不会「插一次、下一次作答又被冲回原位」。
    val pendingReinsertWords = LinkedHashMap<Long, Word>()

    val pendingReinsertSlots = LinkedHashMap<Long, Int>()
    // 这次 loadQueueForBook 是否由“当前卡刚被作答”触发：是则重写稿要前移一格，
    // 不是（普通刷新/回前台）则保持原位。
    private var currentConsumed = false

    val _waitingRewriteCount = MutableStateFlow(0)
    var activeQueue: List<Word> = emptyList()
    var queueBookId: Long? = null
    var pendingFailedWordId: Long? = null
    var answeredInSession = 0
    private var currentWordPresentedAtMs = 0L
    private val _learningPace = MutableStateFlow(LearningPace())
    val learningPace: StateFlow<LearningPace> = _learningPace.asStateFlow()
    private var paceBaseTier = 1

    /** 最近一次作答记录的热力基准与记录时刻；ticker 按 4.2s 线性冷却。 */
    private var paceBaseHeat = 0f
    private var paceHeatRecordedAtMs = 0L
    private var recognitionAutoPronounceEventCounter = 0L
    internal var retryQueueRandomSource: RetryRandomSource = RetryRandomSource.Default

    val _dueReviewCount = MutableStateFlow(0)
    val dueReviewCount: StateFlow<Int> = _dueReviewCount.asStateFlow()
    val _aiState = MutableStateFlow(LearningAiState())
    val aiState: StateFlow<LearningAiState> = _aiState.asStateFlow()
    val _cachedExampleContent = MutableStateFlow("")
    val cachedExampleContent: StateFlow<String> = _cachedExampleContent.asStateFlow()
    val _cachedMemoryAidContent = MutableStateFlow("")
    val cachedMemoryAidContent: StateFlow<String> = _cachedMemoryAidContent.asStateFlow()
    val _memoryAidSuggestionWordId = MutableStateFlow<Long?>(null)
    val memoryAidSuggestionWordId: StateFlow<Long?> = _memoryAidSuggestionWordId.asStateFlow()
    private val _recognitionAutoPronounceEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recognitionAutoPronounceEvents: SharedFlow<Long> = _recognitionAutoPronounceEvents.asSharedFlow()

    val _currentWord = MutableStateFlow<Word?>(null)
    val currentWord: StateFlow<Word?> = _currentWord.asStateFlow()
    private val _cardPresentation = MutableStateFlow(LearningCardPresentation(null, 0L))
    internal val cardPresentation: StateFlow<LearningCardPresentation> = _cardPresentation.asStateFlow()

    fun publishWord(word: Word?, presentAgain: Boolean = false) {
        val previous = _cardPresentation.value
        val generation = LearningSessionRules.generation(previous.generation, previous.word?.id, word?.id, presentAgain)
        _currentWord.value = word
        _cardPresentation.value = LearningCardPresentation(word, generation)
    }

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    fun updateQueue(list: List<Word>, presentAgain: Boolean = false) {
        activeQueue = list
        val oldWordId = _currentWord.value?.id
        // 等待重写的词也算今天的进度，否则跳过后总进度会凭空变少。
        val remaining = list.size + waitingRewriteWords.size
        val total = answeredInSession + remaining
        _totalCount.value = total
        _currentIndex.value = LearningSessionRules.currentIndex(answeredInSession, remaining, total)
        val nextWord = list.firstOrNull()
        publishWord(nextWord, presentAgain)
        currentWordPresentedAtMs = if (nextWord != null) System.currentTimeMillis() else 0L
        if (oldWordId != nextWord?.id) {
            // 切新卡/答完立即回到 Tier1 基础节奏；热力保留冷却尾迹，不瞬间归零。
            val carriedHeat = currentPaceHeat(System.currentTimeMillis())
            paceBaseTier = 1
            _learningPace.value = LearningPace(
                tier = 1,
                elapsedMs = 0L,
                lastAnswerDurationMs = _learningPace.value.lastAnswerDurationMs,
                heat = carriedHeat
            )
        }
        if (oldWordId != nextWord?.id) {
            _cachedExampleContent.value = ""
            _cachedMemoryAidContent.value = ""
            _aiState.value = _aiState.value.copy(
                isLoading = false,
                activeType = null,
                content = "",
                error = null
            )
        }
    }

    fun markAnswered() {
        answeredInSession += 1
        currentConsumed = true
    }

    fun emitRecognitionAutoPronounceEventIfNeeded(previousWordId: Long) {
        val nextWordId = _currentWord.value?.id ?: return
        if (nextWordId == previousWordId) return
        recognitionAutoPronounceEventCounter += 1
        _recognitionAutoPronounceEvents.tryEmit(recognitionAutoPronounceEventCounter)
    }

    fun buildCurrentResponseTimeMs(now: Long = System.currentTimeMillis()): Long {
        val startAt = currentWordPresentedAtMs
        if (startAt <= 0L) return 0L
        return (now - startAt).coerceAtLeast(0L)
    }

    /** 当前热力：从最近一次作答基准线性冷却，4.2s 后归零。 */
    private fun currentPaceHeat(nowMs: Long): Float {
        if (paceBaseHeat <= 0f) return 0f
        val cooledMs = (nowMs - paceHeatRecordedAtMs).coerceAtLeast(0L)
        return (paceBaseHeat * (1f - cooledMs / PACE_HEAT_COOLDOWN_MS.toFloat())).coerceIn(0f, 1f)
    }

    /** ticker 用：当前卡停留 >22s 强制 Tier0，否则沿用最近一次作答耗时对应的基础档位。 */
    fun refreshLearningPace() {
        val now = System.currentTimeMillis()
        val elapsed = if (currentWordPresentedAtMs > 0L) {
            (now - currentWordPresentedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val tier = when {
            _currentWord.value == null -> 1
            elapsed > 22_000L -> 0
            else -> paceBaseTier.coerceIn(1, 4)
        }
        val heat = currentPaceHeat(now)
        val current = _learningPace.value
        if (current.tier != tier || current.elapsedMs != elapsed || current.heat != heat) {
            _learningPace.value = current.copy(tier = tier, elapsedMs = elapsed, heat = heat)
        }
    }

    /** 作答后立即把节奏切到本次耗时对应的档位；切卡时 updateQueue 会再重置回 Tier1，热力保留冷却尾迹。 */
    fun recordAnswerPace(durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val elapsed = if (currentWordPresentedAtMs > 0L) {
            (now - currentWordPresentedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        paceBaseTier = paceTierForAnswerDuration(safeDuration)
        paceBaseHeat = paceHeatForAnswerDuration(safeDuration)
        paceHeatRecordedAtMs = now
        _learningPace.value = LearningPace(
            tier = if (elapsed > 22_000L) 0 else paceBaseTier,
            elapsedMs = elapsed,
            lastAnswerDurationMs = safeDuration,
            heat = paceBaseHeat
        )
    }

    /**
     * 登记一张「已合格、还没作答」的重写卡：槽位只算一次（wordId + 发起时间），
     * 之后每次重建队列都按同一槽位重排，不会重新随机。
     */
    fun rememberPendingReinsert(wordId: Long, word: Word, createdAt: Long) {
        pendingReinsertWords[wordId] = word
        if (!pendingReinsertSlots.containsKey(wordId)) {
            pendingReinsertSlots[wordId] = ImmediateRetryQueuePlanner.waitingInsertSlot(
                ImmediateRetryQueuePlanner.waitingRewriteSeed(wordId, createdAt)
            )
        }
    }

    /** 用户真的作答（或标记已结清）后，这张卡不再参与插回。 */
    fun forgetPendingReinsert(wordId: Long) {
        pendingReinsertWords.remove(wordId)
        pendingReinsertSlots.remove(wordId)
    }

    fun clearPendingReinsert() {
        pendingReinsertWords.clear()
        pendingReinsertSlots.clear()
    }

    /**
     * 把已合格的重写卡按稳定槽位排进队列：
     * 当前卡（下标 0）不动，稿子落在之后第 2/3 张普通卡之后；普通卡不够就接队尾；
     * 队列里已有的同名卡先摘掉再插，绝不重复。重排只要输入相同就结果相同。
     *
     * 本次会话里被「滑进生词本」跳过的卡继续留在 DB 欠卡里，但今天不再硬塞回队列
     * （和普通卡的 session skip 一致）；重开 App 后仍会恢复出来，欠卡不会被绕过。
     */
    fun applyPendingReinsertPlacements(queue: List<Word>): List<Word> {
        if (pendingReinsertWords.isEmpty() || pendingReinsertSlots.isEmpty()) return queue
        val pending = pendingReinsertSlots.entries.mapIndexedNotNull { index, (wordId, slot) ->
            if (wordId in sessionSkippedWordIds) {
                null
            } else {
                WaitingReinsertPlacement.Pending(
                    wordId = wordId,
                    slot = slot,
                    order = index.toLong()
                )
            }
        }
        if (pending.isEmpty()) return queue
        val byId = LinkedHashMap<Long, Word>(queue.size + pendingReinsertWords.size)
        queue.forEach { byId[it.id] = it }
        pendingReinsertWords.forEach { (wordId, word) -> byId.putIfAbsent(wordId, word) }
        return WaitingReinsertPlacement.apply(queue.map { it.id }, pending)
            .mapNotNull { byId[it] }
    }

    fun refreshWaitingCount() {
        _waitingRewriteCount.value = waitingRewriteWords.size
    }

    fun removeSessionSkippedWord(wordId: Long) {
        sessionSkippedWordIds.remove(wordId)
    }

    fun enqueueImmediateRetry(word: Word) {
        removeFromImmediateQueue(word.id)
        immediateRetryQueue.addLast(word)
    }

    fun removeFromImmediateQueue(wordId: Long) {
        if (immediateRetryQueue.isEmpty()) return
        val retained = immediateRetryQueue.filterNot { it.id == wordId }
        immediateRetryQueue.clear()
        immediateRetryQueue.addAll(retained)
    }

    private fun mergeWithImmediateRetryQueue(baseQueue: List<Word>, v4Enabled: () -> Boolean): List<Word> {
        if (immediateRetryQueue.isEmpty()) return baseQueue
        val immediateIds = immediateRetryQueue.map { it.id }.toSet()
        val merged = baseQueue
            .filterNot { it.id in immediateIds }
            .toMutableList()
        val policy = if (v4Enabled()) {
            ImmediateRetryQueuePlanner.V4Policy
        } else {
            ImmediateRetryQueuePlanner.LegacyPolicy
        }

        immediateRetryQueue.forEach { retryWord ->
            val insertIndex = ImmediateRetryQueuePlanner.resolveInsertIndex(
                queueSize = merged.size,
                policy = policy,
                randomSource = retryQueueRandomSource
            )
            merged.add(insertIndex, retryWord)
        }
        return merged
    }

    fun rebuildQueue(baseQueue: List<Word>, v4Enabled: () -> Boolean, presentAgain: Boolean) {
        val consumed = currentConsumed
        currentConsumed = false
        val previousPendingIndex = activeQueue.withIndex()
            .associate { (index, word) -> word.id to index }
        val mergedQueue = mergeWithImmediateRetryQueue(baseQueue, v4Enabled)
            .distinctBy { it.id }
            .filterNot { it.id in sessionSkippedWordIds }
            // W2：还没合格的欠卡不能从普通队列漏回来——否则重开 App 后
            // 用户会看到旧稿并被当成正常卡作答，等于绕过「合格才插回」。
            .filterNot {
                it.id in waitingRewriteWords ||
                    it.id in restoredWaitingRewriteWords
            }
        // W2：已合格的重写稿不能按 DB 快照的到期顺序重建。跳过重写不改进度，
        // DB 快照会把它排在最前，一重建就变成当前卡。这里按会话里“上一轮它前面还有几张卡”
        // 保留位置；当前卡刚被作答时前移一格，刷新则原样。
        val withoutPending = mergedQueue.filterNot { it.id in pendingReinsertWords }
        var placed = withoutPending
        pendingReinsertWords.forEach { (wordId, word) ->
            val oldIndex = previousPendingIndex[wordId] ?: return@forEach
            val insertAt = LearningSessionRules.restoredPosition(oldIndex, consumed, placed.size)
            placed = placed.toMutableList().apply { add(insertAt, word) }
        }
        updateQueue(applyPendingReinsertPlacements(placed), presentAgain)
    }
}

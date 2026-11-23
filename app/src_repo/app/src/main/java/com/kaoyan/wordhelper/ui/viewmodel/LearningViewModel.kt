package com.kaoyan.wordhelper.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.MemoryLock
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.data.model.ApproachReview
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.util.withoutBuiltinFull
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.data.model.PronunciationSource
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.data.model.WaitingReinsertPlacement
import com.kaoyan.wordhelper.data.model.WaitingRewriteRestore
import com.kaoyan.wordhelper.data.model.feedbackRatingForRevision
import com.kaoyan.wordhelper.data.repository.AddToNewWordsSource
import com.kaoyan.wordhelper.data.repository.CheckInResult
import com.kaoyan.wordhelper.data.repository.CardbackAuth
import com.kaoyan.wordhelper.data.repository.FeedbackBurstPolicy
import com.kaoyan.wordhelper.data.repository.RewriteMarkPolicy
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.TodayNewWordsPlan
import com.kaoyan.wordhelper.ml.core.AdaptiveResponseThreshold
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.ml.training.OnlineTrainer
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.ImmediateRetryQueuePlanner
import com.kaoyan.wordhelper.util.RetryRandomSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

data class LearningCheckInUiState(
    val showCompletionPanel: Boolean = false,
    val checkedInToday: Boolean = false,
    val streakDays: Int = 0,
    val weekCheckInDays: Int = 0,
    val todayStudyCount: Int = 0,
    val todayCheckInTimeText: String = ""
)

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

/** 1.4.7v7-bugfix：「跳过」的欠卡没落进库时的用户可见提示（不静默吞掉）。 */
private const val SKIP_PERSIST_FAILED = "跳过没生效：重写记录没存上，请稍后重试"

/** 点击到落库之间这条记录被撤销/换版：不换词，让用户刷新后重试。 */
private const val SKIP_MARK_CHANGED = "重写记录已变化，先刷新一下再试"
private const val SKIP_ALREADY_WAITING = "这张卡已经在等结果了"
private const val SUBMIT_BUSY = "正在处理上一步，稍等一下"

/** 结果已经合格：不做「跳过」，这张卡按既有插回规则留在队列里继续背。 */
private const val SKIP_RESULT_READY = "思路已更新，继续背当前词"

/**
 * 卡背重写进度（1.4.3.1）。
 *
 * [fraction] 为 null = 服务端没说清阶段，用不确定进度条；
 * [offline] = 这一轮没连上电脑，界面上提示「等电脑上线」。
 */
data class RewriteProgressUi(
    val fraction: Float?,
    val failed: Boolean = false,
    val offline: Boolean = false
)

private data class QueueLoadParams(
    val book: Book?,
    val newWordsLimit: Int,
    val shuffleNewWords: Boolean,
    val plannedModeEnabled: Boolean
)

internal data class LearningCardPresentation(val word: Word?, val generation: Long)

/**
 * 1.4.7v7-bugfix：当前这张卡能不能「跳过、先背下一个」。
 *
 * 判定只在这里写一次：暴露给界面的 [LearningViewModel.currentRewriteSkipAvailability]
 * 与命令 [LearningViewModel.skipCurrentWordForRewrite] 共用同一份，
 * 避免「按钮亮着、点下去却被 ViewModel 静默 return」这种按了没反应的状态。
 */
internal enum class SkipRewriteAvailability {
    /** 有本词本书记录、且结果还没合格：可以跳过。 */
    AVAILABLE,

    /** 没有任何重写记录（被撤销/取消/同步删掉了）：入口应消失。 */
    NO_MARK,

    /** 记录的词或书跟当前卡对不上（换词/换书后的旧按钮）：直接丢弃。 */
    OTHER_WORD,
    OTHER_BOOK,

    /** 结果已经合格：按既有插回规则处理，跳过没有意义，入口置灰。 */
    RESULT_READY
}

internal fun skipRewriteAvailability(
    wordId: Long?,
    bookId: Long?,
    mark: RewriteQueueItem?,
    activeRequestId: String? = null
): SkipRewriteAvailability {
    if (wordId == null || bookId == null) return SkipRewriteAvailability.OTHER_WORD
    if (mark == null) return SkipRewriteAvailability.NO_MARK
    if (mark.wordId != wordId) return SkipRewriteAvailability.OTHER_WORD
    // book_id = 0 是 16_17 迁移留下的全局行，同 observeMark 的口径，算作当前书。
    if (mark.bookId != 0L && mark.bookId != bookId) return SkipRewriteAvailability.OTHER_BOOK
    // 1.5.3：历史已结清的 DONE 行不能把「跳过」入口永久亮着。
    // null = 当前词没有本轮活跃重写；空串 = 本轮刚点下、requestId 还没落库。
    if (mark.status == RewriteStatus.DONE) {
        if (activeRequestId == null) {
            return SkipRewriteAvailability.NO_MARK
        }
        if (activeRequestId.isBlank()) {
            return SkipRewriteAvailability.AVAILABLE
        }
        return if (mark.requestId == activeRequestId) {
            SkipRewriteAvailability.RESULT_READY
        } else {
            SkipRewriteAvailability.AVAILABLE
        }
    }
    // 旧一轮的 DONE 在新一轮 requestId 已生成时不能挡住入口。
    if (activeRequestId != null && activeRequestId.isNotBlank() && mark.requestId != activeRequestId) {
        return SkipRewriteAvailability.AVAILABLE
    }
    return SkipRewriteAvailability.AVAILABLE
}

@OptIn(ExperimentalCoroutinesApi::class)
class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as KaoyanWordApp).repository
    private val database = (application as KaoyanWordApp).database
    private val settingsRepository = (application as KaoyanWordApp).settingsRepository
    private val aiConfigRepository = (application as KaoyanWordApp).aiConfigRepository
    private val aiRepository = (application as KaoyanWordApp).aiRepository
    private val cardBackRepository = (application as KaoyanWordApp).cardBackRepository
    private val rewriteRepository = (application as KaoyanWordApp).rewriteRepository

    /** 卡背结构清单（1.4.2）；默认就是 1.4.1 的样子。 */
    val cardBackConfig: StateFlow<CardBackConfig> = cardBackRepository.config
    private val mlScheduler: MLEnhancedScheduler = (application as KaoyanWordApp).mlScheduler
    private val mlOnlineTrainer: OnlineTrainer? = run {
        val app = application as KaoyanWordApp
        OnlineTrainer(app.mlPredictor, app.database.trainingSampleDao(), app.mlModelPersistence)
    }

    private val immediateRetryQueue = ArrayDeque<Word>()
    private val sessionSkippedWordIds = LinkedHashSet<Long>()
    // 1.4.6 H：跳过重写后等结果的词。它们不删、不免背，只暂时离开当前队列；
    // 电脑端一生成好就按随机位置插回来（见 onWaitingRewriteReady）。
    private val waitingRewriteWords = LinkedHashMap<Long, Word>()
    // 1.4.6.1 P0：进程重启后从 DB 补回来的等待词。只用于补注册监听和结果到达时插回，
    // 不能混进 waitingRewriteWords —— updateQueue 会把它算进剩余进度，造成重复计数。
    private val restoredWaitingRewriteWords = LinkedHashMap<Long, Word>()
    // 1.4.7.2 W2：已合格、已排进队列、但用户还没作答的重写卡。
    // 记忆里保存稳定槽位（当前卡之后第 2/3 张普通卡之后）：每次重建队列都按同一位置重排，
    // 作答（清 needs_reinsert）时才移除，所以不会「插一次、下一次作答又被冲回原位」。
    private val pendingReinsertWords = LinkedHashMap<Long, Word>()

    private val pendingReinsertSlots = LinkedHashMap<Long, Int>()
    // 这次 loadQueueForBook 是否由“当前卡刚被作答”触发：是则重写稿要前移一格，
    // 不是（普通刷新/回前台）则保持原位。
    private var currentConsumed = false
    private val waitingRewriteJobs = mutableMapOf<Long, Job>()
    // 同一 word 的 observer 回调与冷启动补插可能同时到达；只允许一条路径真正插回/清标记。
    private val reinsertingWordIds = mutableSetOf<Long>()
    private var waitingRewritePollJob: Job? = null
    private val _waitingRewriteCount = MutableStateFlow(0)
    private var activeQueue: List<Word> = emptyList()
    private var queueBookId: Long? = null
    private var pendingFailedWordId: Long? = null
    private var answeredInSession = 0
    private var currentWordPresentedAtMs = 0L
    private val _learningPace = MutableStateFlow(LearningPace())
    val learningPace: StateFlow<LearningPace> = _learningPace.asStateFlow()
    private var paceBaseTier = 1

    /** 最近一次作答记录的热力基准与记录时刻；ticker 按 4.2s 线性冷却。 */
    private var paceBaseHeat = 0f
    private var paceHeatRecordedAtMs = 0L
    private var recognitionAutoPronounceEventCounter = 0L
    internal var retryQueueRandomSource: RetryRandomSource = RetryRandomSource.Default
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _dueReviewCount = MutableStateFlow(0)
    val dueReviewCount: StateFlow<Int> = _dueReviewCount.asStateFlow()
    private val _aiState = MutableStateFlow(LearningAiState())
    val aiState: StateFlow<LearningAiState> = _aiState.asStateFlow()
    private val _cachedExampleContent = MutableStateFlow("")
    val cachedExampleContent: StateFlow<String> = _cachedExampleContent.asStateFlow()
    private val _cachedMemoryAidContent = MutableStateFlow("")
    val cachedMemoryAidContent: StateFlow<String> = _cachedMemoryAidContent.asStateFlow()
    private val _memoryAidSuggestionWordId = MutableStateFlow<Long?>(null)
    val memoryAidSuggestionWordId: StateFlow<Long?> = _memoryAidSuggestionWordId.asStateFlow()
    private val _recognitionAutoPronounceEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recognitionAutoPronounceEvents: SharedFlow<Long> = _recognitionAutoPronounceEvents.asSharedFlow()
    private val learningWeekRange = DateUtils.learningDateRange(days = 7)
    private val learningHeatmapRange = DateUtils.learningHeatmapRange()
    private val learningWeekStatsFlow = repository.getDailyStatsAggregated(learningWeekRange.first, learningWeekRange.second)
    private val learningHeatmapStatsFlow = repository.getDailyStatsAggregated(learningHeatmapRange.first, learningHeatmapRange.second)


    val books: StateFlow<List<Book>> = combine(
        repository.getAllBooks(),
        settingsRepository.showBuiltinFullBooks
    ) { list, showFull ->
        list.withoutBuiltinFull(hide = !showFull)
    }
        .map { list ->
            list.sortedWith(
                compareByDescending<Book> { it.type == Book.TYPE_NEW_WORDS }
                    .thenBy { it.type }
                    .thenBy { it.id }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeBook: StateFlow<Book?> = repository.getActiveBookFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 1.4.7.2 W2：今天发起、但用户还没实际作答的重写卡有几张。
     *
     * 只看 `needs_reinsert=1` 并按「发起重写的学习日」过滤（4 点换日）：
     * - 生成中、生成完成、合格、插回队列、退出学习页都不算作答，只有三键/太简单/拼写成功才清零；
     * - 昨天没背完的不挡今天（跨天照原 SM-2 派发），那行只是留在库里；
     * - 标记重写后照常背完的（没有 needs_reinsert）本来就不是欠卡。
     */
    /**
     * 4 点换日时 DB 不会发新行，必须自己给「今天」的判定一个重算脉冲；
     * 否则 App 跨过 4 点仍用昨天的日期过滤，完成页/欠卡数会一直停在旧状态。
     */
    private val learningDateTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    private val todayPendingRewriteCount: StateFlow<Int> = activeBook
        .flatMapLatest { book ->
            if (book == null) {
                flowOf(emptyList<RewriteQueueItem>())
            } else {
                rewriteRepository.observeNeedsReinsertForBook(book.id)
            }
        }
        .combine(learningDateTicker) { rows, _ -> rows }
        .map { rows -> countPendingRewritesForLearningDate(rows, DateUtils.currentLearningDate()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 等待卡上的条数：本地已跳过 + 数据库里今天还欠作答，取两者最大值，避免重复计数。
     * 只跳过没标记（不该发生）/ 只标记没跳过时也都能数到。
     */
    val waitingRewriteCount: StateFlow<Int> = combine(
        _waitingRewriteCount,
        todayPendingRewriteCount
    ) { local, pendingToday -> combinedWaitingRewriteCount(local, pendingToday) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _currentWord = MutableStateFlow<Word?>(null)
    val currentWord: StateFlow<Word?> = _currentWord.asStateFlow()
    private val _cardPresentation = MutableStateFlow(LearningCardPresentation(null, 0L))
    internal val cardPresentation: StateFlow<LearningCardPresentation> = _cardPresentation.asStateFlow()

    private fun publishWord(word: Word?, presentAgain: Boolean = false) {
        val previous = _cardPresentation.value
        val changed = previous.word?.id != word?.id || (presentAgain && word != null)
        val generation = if (changed) previous.generation + 1 else previous.generation
        _currentWord.value = word
        _cardPresentation.value = LearningCardPresentation(word, generation)
    }

    // ---- 重写记忆思路（1.4.3）----

    /** 下拉里的模型；电脑端下发，离线/没连上就用内置 5 个。 */
    private val _rewriteModels = MutableStateFlow(RewriteModels.DEFAULT)
    val rewriteModels: StateFlow<List<RewriteModel>> = _rewriteModels.asStateFlow()

    private val _rewriteModelId = MutableStateFlow(RewriteModels.DEFAULT_ID)
    val rewriteModelId: StateFlow<String> = _rewriteModelId.asStateFlow()

    /** 当前这张卡的重写标记；null = 还没标记过。 */
    val currentRewriteMark: StateFlow<RewriteQueueItem?> = combine(_currentWord, activeBook) { word, book ->
        word?.let { it.id to (book?.id ?: 0L) }
    }.flatMapLatest { key ->
        if (key == null) flowOf(null) else rewriteRepository.observeMark(key.first, key.second)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 1.5.2：本轮重写的 requestId（wordId -> requestId；空串=刚点下还没落库）。 */
    private val _activeRewriteRequestIds = MutableStateFlow<Map<Long, String>>(emptyMap())

    /**
     * 1.5.2v2：右侧「跳过」只认数据库里的当前行，不再依赖会话锁存。
     *
     * 旧实现要求 `_skipRewriteLatchedWordId == currentWord.id`；App 重启、进程被回收、
     * 从别的页面回来时这个锁存是空的，但队列行明明还在 queued，于是按钮状态和真实
     * 状态脱节，点了只会弹「已经在等结果」。现在只要当前词有 mark 就能推导：
     * 本轮未完成 -> AVAILABLE；本轮 DONE -> RESULT_READY（保留胶囊但置灰）。
     */
    internal val currentRewriteSkipAvailability: StateFlow<SkipRewriteAvailability> = combine(
        _currentWord,
        activeBook,
        currentRewriteMark,
        _activeRewriteRequestIds
    ) { word, book, mark, activeIds ->
        if (word == null) {
            SkipRewriteAvailability.NO_MARK
        } else {
            skipRewriteAvailability(word.id, book?.id, mark, activeIds[word.id])
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SkipRewriteAvailability.NO_MARK
    )

    /**
     * 当前词的记忆思路，直接来自数据库。
     * 卡背读它而不是读词对象快照：电脑端结果不管被哪一次同步落库，界面都会立刻换文。
     */
    val liveApproach: StateFlow<String?> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(null)
            else rewriteRepository.observeApproach(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 卡背方法标签，同样直接盯库；重写结果落库后胶囊立刻换，不等下一次翻面。 */
    val liveMethod: StateFlow<String?> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(null)
            else rewriteRepository.observeMethod(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 1.5.3v2：当前词有没有「上一版」可恢复（编辑弹窗里的恢复按钮据此亮灭）。 */
    val memoryEditCanRestore: StateFlow<Boolean> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(false)
            else rewriteRepository.observePrevApproach(word.id, bookId).map { !it.isNullOrBlank() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 1.4.7.1：当前这版思路的「未复核」标记（服务端 unverified + reason）。
     * 和 [liveApproach] 用同一套按书回退规则，角标永远跟着正文走；
     * EMPTY = 已复核 / 老数据 / 老服务端，卡背不显示角标。
     */
    val liveApproachReview: StateFlow<ApproachReview> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(ApproachReview.EMPTY)
            else rewriteRepository.observeApproachReview(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApproachReview.EMPTY)

    /**
     * 1.4.6.1：当前词在当前书里、**当前这一版思路**上的 👍/👎（null = 这一版还没表过态）。
     * 不依赖重写标记：普通卡也有自己的 rev（种子思路 = 0），换词/换书/换版都会自动重订阅。
     * 只拿来把按钮显示成已选状态；写入走 submitRewriteFeedback。
     */
    val currentRewriteFeedback: StateFlow<String?> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) {
                flowOf(null)
            } else {
                combine(
                    rewriteRepository.observeFeedback(word.id, bookId),
                    rewriteRepository.observeApproachRev(word.id, bookId)
                ) { rows, approachRev -> feedbackRatingForRevision(rows, approachRev) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 1.5.2 点赞++：当前这一版思路累计收到的 👍 次数。
     * 换词/换书/换版自动重订阅；连击未落库时由界面再叠加 [pendingLikeBurst]。
     */
    val currentRewriteLikeCount: StateFlow<Int> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) {
                flowOf(0)
            } else {
                combine(
                    rewriteRepository.observeFeedback(word.id, bookId),
                    rewriteRepository.observeApproachRev(word.id, bookId)
                ) { rows, rev ->
                    rows.filter { it.approachRev == rev && it.rating == RewriteFeedbackRating.UP }
                        .sumOf { it.count }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 1.5.3：当前这张卡的「免洗金牌」状态。
     * 锁定后闲时洗词、批量重写和词卡热更都不会覆盖这份助记。
     */
    val currentMemoryLocked: StateFlow<Boolean> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(false)
            else database.memoryLockDao().observe(word.id, bookId).map { it?.locked == true }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 连击还没写库的临时增量；只对同一张卡生效。 */
    data class PendingLikeUi(val wordId: Long = 0L, val delta: Int = 0)

    private val _pendingLikeUi = MutableStateFlow(PendingLikeUi())
    val pendingLikeBurst: StateFlow<PendingLikeUi> = _pendingLikeUi.asStateFlow()

    private data class PendingLikeBurst(
        val wordId: Long,
        val bookId: Long,
        val approachRev: Int,
        val word: String,
        val approach: String,
        val method: String,
        val model: String,
        val instruction: String,
        var delta: Int = 0
    )

    private var pendingLikeBurstData: PendingLikeBurst? = null
    private var pendingLikeJob: Job? = null

    /** 卡背上的进度条数据；null = 还没问过电脑端。 */
    private val _rewriteProgress = MutableStateFlow<RewriteProgressUi?>(null)
    val rewriteProgress: StateFlow<RewriteProgressUi?> = _rewriteProgress.asStateFlow()

    /** 同步把卡背文字换掉时 +1，界面据此闪 2 秒「已更新」。 */
    private val _rewriteUpdatedTick = MutableStateFlow(0)
    val rewriteUpdatedTick: StateFlow<Int> = _rewriteUpdatedTick.asStateFlow()

    /** 翻面同步的节流时间戳。 */
    private var lastBackFlipSyncAt = 0L

    /** 进度轮询的 job；null = 没在轮询。 */
    private var rewritePollJob: Job? = null

    /** 上一次轮询的是哪个词，用来在换词时把进度清零。 */
    private var polledWordId: Long? = null

    /** 这条标记到了终态还要不要拉一次结果；标记/换词时重新置上。 */
    private var pendingTerminalSync = true

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    val isNewWordsBook: StateFlow<Boolean> = activeBook
        .map { it?.type == Book.TYPE_NEW_WORDS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isInNewWords: StateFlow<Boolean> = repository.getNewWordIdsFlow()
        .combine(_currentWord) { ids, word -> word?.let { ids.contains(it.id) } ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentProgress: StateFlow<Progress?> = combine(activeBook, _currentWord) { book, word -> book to word }
        .mapLatest { (book, word) ->
            if (book == null || word == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val newWordsLimit: StateFlow<Int> = settingsRepository.settingsFlow
        .map { it.newWordsLimit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_NEW_WORDS_LIMIT)
    val algorithmV4Enabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.algorithmV4Enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val fsrsEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.fsrsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val desiredRetention: StateFlow<Float> = settingsRepository.settingsFlow
        .map { it.desiredRetention }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.90f)
    val reviewPressureReliefEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.reviewPressureReliefEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val reviewPressureDailyCap: StateFlow<Int> = settingsRepository.settingsFlow
        .map { it.reviewPressureDailyCap.coerceIn(10, 500) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 120)
    val pronunciationEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.pronunciationEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val pronunciationSource: StateFlow<PronunciationSource> = settingsRepository.settingsFlow
        .map { it.pronunciationSource }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PronunciationSource.FREE_DICTIONARY
        )
    val recognitionAutoPronounceEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.recognitionAutoPronounceEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val shuffleNewWordsEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.newWordsShuffleEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val plannedNewWordsEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.plannedNewWordsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val todayNewWordsPlan: StateFlow<TodayNewWordsPlan> = settingsRepository.todayNewWordsPlanFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayNewWordsPlan())
    val canRelieveReviewPressure: StateFlow<Boolean> = combine(
        activeBook,
        reviewPressureReliefEnabled,
        reviewPressureDailyCap,
        _dueReviewCount
    ) { book, enabled, cap, dueCount ->
        enabled &&
            book != null &&
            book.type != Book.TYPE_NEW_WORDS &&
            dueCount > cap
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val checkInState: StateFlow<LearningCheckInUiState> = combine(
        learningWeekStatsFlow,
        learningHeatmapStatsFlow,
        _currentWord,
        _totalCount,
        todayPendingRewriteCount
    ) { weekAggregates, heatmapAggregates, currentWord, totalCount, pendingRewriteCount ->
        buildLearningCheckInUiState(
            weekAggregates = weekAggregates,
            heatmapAggregates = heatmapAggregates,
            currentWord = currentWord,
            totalCount = totalCount,
            pendingRewriteCount = pendingRewriteCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearningCheckInUiState())

    private var cachedResponseThresholdMs: Long = RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS

    init {
        refreshAiAvailability()
        // 心流进度条节奏 ticker：500ms 更新当前卡停留时长；没有当前卡时保持 Tier1。
        viewModelScope.launch {
            while (isActive) {
                refreshLearningPace()
                delay(500)
            }
        }
        // 换词立刻把进度归零：上一条标记的 100% 不属于这张卡
        viewModelScope.launch {
            _currentWord.map { it?.id }.distinctUntilChanged().collect { wordId ->
                _rewriteProgress.value = null
                polledWordId = wordId
                pendingTerminalSync = true
            }
        }
        viewModelScope.launch {
            val savedModel = settingsRepository.rewriteModel()
            if (savedModel.isNotBlank()) _rewriteModelId.value = savedModel
            _rewriteModels.value = rewriteRepository.models()
        }
        // 加载ML响应时间阈值
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                if (settings.mlAdaptiveEnabled) {
                    val modelState = withContext(Dispatchers.IO) {
                        (getApplication<KaoyanWordApp>()).mlModelPersistence.getModelState()
                    }
                    cachedResponseThresholdMs = AdaptiveResponseThreshold.computeThreshold(
                        avgResponseTime = modelState?.avgResponseTime ?: 0f,
                        stdResponseTime = modelState?.stdResponseTime ?: 0f,
                        mlEnabled = true
                    )
                } else {
                    cachedResponseThresholdMs = RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS
                }
            }
        }
        viewModelScope.launch {
            combine(
                activeBook,
                newWordsLimit,
                shuffleNewWordsEnabled,
                todayNewWordsPlan,
                plannedNewWordsEnabled
            ) { book, limit, shuffle, _, plannedEnabled ->
                QueueLoadParams(
                    book = book,
                    newWordsLimit = limit,
                    shuffleNewWords = shuffle,
                    plannedModeEnabled = plannedEnabled
                )
            }.collect { params ->
                if (pendingFailedWordId != null) return@collect
                loadQueueForBook(
                    book = params.book,
                    newWordsLimit = params.newWordsLimit,
                    shuffleNewWords = params.shuffleNewWords,
                    plannedModeEnabled = params.plannedModeEnabled
                )
            }
        }
        viewModelScope.launch {
            repository.getNewWordIdsFlow().collect {
                if (pendingFailedWordId != null) return@collect
                val book = activeBook.value
                if (book?.type == Book.TYPE_NEW_WORDS) {
                    loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
                }
            }
        }
        viewModelScope.launch {
            activeBook
                .flatMapLatest { book ->
                    if (book == null) {
                        flowOf(0)
                    } else {
                        repository.getEarlyReviewCountFlow(book.id)
                    }
                }
                .collect {
                    if (pendingFailedWordId != null) return@collect
                    val book = activeBook.value
                    if (book != null) {
                        loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
                    }
                }
        }
    }

    // ---- 重写记忆思路（1.4.3）：只打标记，真正的生成在电脑端 ----

    fun selectRewriteModel(modelId: String) {
        if (modelId.isBlank()) return
        _rewriteModelId.value = modelId
        viewModelScope.launch { settingsRepository.updateRewriteModel(modelId) }
    }

    /** 点「重写思路」：落本地队列后立刻试发一次，发不出去就等下次同步补发。 */
    fun markRewrite(instruction: String) {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        val modelId = _rewriteModelId.value.ifBlank { RewriteModels.DEFAULT_ID }
        // 新一次标记：到终态时还要再拉一次结果
        pendingTerminalSync = true
        _rewriteProgress.value = null
        viewModelScope.launch {
            // 1.4.8v1：不再因为「没填电脑地址」提前 return。enqueueAndSync 内部是
            // 「先 queueDao.upsert 落本地，再 sync() 试发」；离线时 sync 失败但 PENDING
            // 行已经留下，跳过入口才有依据。否则没地址连 mark 都没有，点重写完全没反应。
            // 先确认还是同一张卡；跳过入口现在只由数据库里的 mark 推导，不再锁存。
            if (_currentWord.value?.id != word.id || (activeBook.value?.id ?: 0L) != bookId) return@launch
            // 1.5.2：新一轮重写开始，解除本 session 的「已跳过」过滤，允许同一词反复
            // 「重写 → 跳过 → 回队 → 再重写」无限轮次。
            sessionSkippedWordIds.remove(word.id)
            // 1.5.2：先记「本轮开始」，旧 DONE 行在这几毫秒里不能把跳过置灰。
            _activeRewriteRequestIds.update { it + (word.id to "") }
            // 1.5.3：不在点击时再弹「已标记/已送电脑」Toast。队列行本身会立刻
            // 变成 PENDING/QUEUED，RewriteBar 的单一状态行就是唯一反馈；只有
            // 真的离线排队或鉴权失败时才提示一句。
            val sent = rewriteRepository.enqueueAndSync(
                word.id,
                word.word,
                modelId,
                instruction,
                bookId = bookId
            )
            val roundRequestId = runCatching {
                rewriteRepository.requestIdOf(word.id, bookId)
            }.getOrNull().orEmpty()
            _activeRewriteRequestIds.update { it + (word.id to roundRequestId) }
            if (!sent) {
                // 没地址/电脑没开都只是「已记下」；只有确实带了口令、且这次请求
                // 真被 401 拦下时，才提示重粘配对行，别把离线误报成口令错。
                val authFailed = rewriteRepository.consumeAuthFailure()
                val hasToken = runCatching {
                    CardbackAuth.headerValue(settingsRepository.settingsFlow.first().cardBackToken) != null
                }.getOrDefault(false)
                _message.value = RewriteMarkPolicy.result(
                    sent = false,
                    authFailed = authFailed,
                    hasToken = hasToken,
                    authHint = CardbackAuth.HINT
                )
            }
        }
    }

    fun cancelRewrite() {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        viewModelScope.launch {
            rewriteRepository.cancel(word.id, bookId)
        }
    }

    fun undoRewrite() {
        val word = _currentWord.value ?: return
        viewModelScope.launch {
            val bookId = activeBook.value?.id ?: 0L
            val restored = rewriteRepository.undo(word.id, bookId)
            if (restored != null) {
                val restoredMethod = rewriteRepository.methodOf(word.id, bookId)
                publishWord(_currentWord.value?.copy(approach = restored, method = restoredMethod))
                _message.value = "已撤销回上一版"
            } else {
                _message.value = "没有可撤销的上一版了"
            }
        }
    }

    /**
     * 1.4.6：卡背上的 👍/👎。
     * 只记「这条思路有没有帮上」，👎 不会自动重写；同一条评价重复点不会重复入库。
     * 写入永远成功（离线也存本地），联网补发交给 RewriteRepository.sync。
     */
    fun submitRewriteFeedback(
        rating: String,
        instruction: String = "",
        count: Int = 1,
        reasons: List<String> = emptyList(),
        comment: String = ""
    ) {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        viewModelScope.launch {
            val accepted = runCatching {
                // 以点击这一刻库里的版本为准，别让 StateFlow 的旧值把评价挂到上一版。
                val approachRev = rewriteRepository.approachRevOf(word.id, bookId)
                rewriteRepository.submitFeedback(
                    wordId = word.id,
                    rating = rating,
                    word = word.word,
                    approach = liveApproach.value ?: word.approach,
                    method = liveMethod.value.orEmpty().ifBlank { word.method },
                    model = _rewriteModelId.value,
                    instruction = instruction.trim().take(200),
                    bookId = bookId,
                    approachRev = approachRev,
                    count = count,
                    reasons = reasons,
                    comment = comment
                )
            }.getOrDefault(false)
            if (accepted) {
                _message.value = if (rating == RewriteFeedbackRating.UP) {
                    "已记录：这条思路有帮助"
                } else {
                    "已记录：这条思路没帮上（不会自动重写）"
                }
            }
        }
    }

    /**
     * 1.5.2 点赞++：连点先在本地累加，停手 1.8 秒或切走卡片时合并成一条反馈。
     * 这样既不会每点一下发一次请求，也不会把连击丢掉。
     */
    fun tapRewriteFeedback(
        rating: String,
        instruction: String = "",
        reasons: List<String> = emptyList(),
        comment: String = ""
    ) {
        if (rating == RewriteFeedbackRating.DOWN) {
            flushPendingLikes()
            submitRewriteFeedback(rating, instruction, reasons = reasons, comment = comment)
            return
        }
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        viewModelScope.launch {
            val rev = rewriteRepository.approachRevOf(word.id, bookId)
            val current = pendingLikeBurstData
            if (current == null ||
                current.wordId != word.id ||
                current.bookId != bookId ||
                current.approachRev != rev
            ) {
                flushPendingLikesNow()
                pendingLikeBurstData = PendingLikeBurst(
                    wordId = word.id,
                    bookId = bookId,
                    approachRev = rev,
                    word = word.word,
                    approach = liveApproach.value ?: word.approach,
                    method = liveMethod.value.orEmpty().ifBlank { word.method },
                    model = _rewriteModelId.value,
                    instruction = instruction.trim().take(200)
                )
            }
            val burst = pendingLikeBurstData ?: return@launch
            burst.delta = FeedbackBurstPolicy.nextCount(burst.delta)
            _pendingLikeUi.value = PendingLikeUi(burst.wordId, burst.delta)
            pendingLikeJob?.cancel()
            pendingLikeJob = viewModelScope.launch {
                delay(LIKE_BURST_DEBOUNCE_MS)
                flushPendingLikesNow()
            }
        }
    }

    /** 切走当前卡/退出页面时把还没落库的连击补上。 */
    fun flushPendingLikes() {
        viewModelScope.launch { flushPendingLikesNow() }
    }

    private suspend fun flushPendingLikesNow() {
        val burst = pendingLikeBurstData ?: return
        pendingLikeBurstData = null
        _pendingLikeUi.value = PendingLikeUi()
        if (burst.delta <= 0) return
        runCatching {
            rewriteRepository.submitFeedback(
                wordId = burst.wordId,
                rating = RewriteFeedbackRating.UP,
                word = burst.word,
                approach = burst.approach,
                method = burst.method,
                model = burst.model,
                instruction = burst.instruction,
                bookId = burst.bookId,
                approachRev = burst.approachRev,
                count = burst.delta
            )
        }.onSuccess {
            _message.value = "已记录：这条思路有帮助 +${burst.delta}"
        }
    }

    /**
     * 1.5.3：用户手改当前卡背上的记忆思路。
     * 按书写入 tb_book_memory，并保留上一版供撤销；绝不碰 tb_progress。
     */
    fun saveMemoryEdit(text: String) {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            _message.value = "记忆思路不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                database.withTransaction {
                    val now = System.currentTimeMillis()
                    val existing = database.bookMemoryDao().getForBook(word.id, bookId)
                    val global = database.wordMemoryDao().getByWordId(word.id)
                    if (existing != null) {
                        database.bookMemoryDao().upsert(
                            existing.copy(
                                approach = cleaned,
                                prevApproach = existing.approach,
                                prevMethod = existing.method,
                                method = existing.method,
                                updatedAt = now,
                                source = WordMemory.SOURCE_USER,
                                rev = existing.rev + 1,
                                unverified = false,
                                unverifiedReason = null
                            )
                        )
                    } else {
                        database.bookMemoryDao().upsert(
                            BookMemory(
                                wordId = word.id,
                                bookId = bookId,
                                approach = cleaned,
                                collocations = global?.collocations.orEmpty(),
                                method = global?.method.orEmpty(),
                                updatedAt = now,
                                source = WordMemory.SOURCE_USER,
                                rev = (global?.rev ?: 0) + 1,
                                prevApproach = global?.approach,
                                prevMethod = global?.method,
                                unverified = false,
                                unverifiedReason = null
                            )
                        )
                    }
                }
            }.onSuccess {
                _message.value = "已保存手改思路"
            }.onFailure {
                _message.value = "保存失败：${it.message ?: "数据库写入异常"}"
            }
        }
    }

    /** 1.5.3v2：把当前手改思路回退到上一版（撤销胶囊的替代，放在编辑弹窗内）。 */
    fun restoreMemoryEdit() {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        if (bookId <= 0L) return
        viewModelScope.launch {
            runCatching {
                var restored = false
                database.withTransaction {
                    val existing = database.bookMemoryDao().getForBook(word.id, bookId) ?: return@withTransaction
                    val prev = existing.prevApproach?.trim().orEmpty()
                    if (prev.isBlank()) return@withTransaction
                    database.bookMemoryDao().upsert(
                        existing.copy(
                            approach = prev,
                            prevApproach = null,
                            prevMethod = null,
                            method = existing.prevMethod ?: existing.method,
                            updatedAt = System.currentTimeMillis(),
                            source = WordMemory.SOURCE_USER,
                            rev = existing.rev + 1,
                            unverified = false,
                            unverifiedReason = null
                        )
                    )
                    restored = true
                }
                restored
            }.onSuccess { restored ->
                _message.value = if (restored) "已恢复上一版" else "没有可恢复的上一版了"
            }.onFailure {
                _message.value = "恢复失败：${it.message ?: "数据库写入异常"}"
            }
        }
    }

    /** 1.5.3：免洗金牌开关。本地立即生效，电脑端后台尽力同步。 */
    fun toggleMemoryLock() {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        val locked = !currentMemoryLocked.value
        viewModelScope.launch {
            runCatching {
                database.memoryLockDao().upsert(
                    MemoryLock(
                        wordId = word.id,
                        bookId = bookId,
                        locked = locked,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onSuccess { lockId ->
                _message.value = if (locked) "已锁定：闲时洗词不会覆盖这份助记" else "已解锁"
                launch {
                    val synced = runCatching {
                        rewriteRepository.setMemoryLock(word.id, bookId, locked)
                    }.getOrDefault(false)
                    if (synced) {
                        // 1.5.3v2 C5：锁已推到电脑端，标记 synced=1，不再重复补发。
                        runCatching { database.memoryLockDao().markSynced(lockId) }
                    } else {
                        _message.value = if (locked) {
                            "已本地锁定，电脑端稍后自动同步"
                        } else {
                            "已本地解锁，电脑端稍后自动同步"
                        }
                    }
                }
            }.onFailure {
                _message.value = "锁定失败：${it.message ?: "数据库写入异常"}"
            }
        }
    }

    /**
     * 1.4.7.2 W2：清零所有待插回标记的「停止重写」**只留给后端/运维和测试**。
     *
     * 学习页已经移除了这个入口：停掉生成会让欠卡永远背不掉，用户也就永远看不到真实的
     * 「今日完成」。保留它是为了运维排查和契约测试，UI 不再调用。
     * 电脑确认停止后才结清本地队列；连不上就明确告诉调用方没停成。
     */
    fun stopAllRewrites() {
        val book = activeBook.value ?: return
        viewModelScope.launch {
            val stopped = rewriteRepository.stopAllRewrites()
            if (!stopped) {
                _message.value = "电脑没连上，停止没生效"
                return@launch
            }
            clearWaitingRewrites()
            sessionSkippedWordIds.clear()
            _message.value = "已停止重写，今天先背这些"
            loadQueueForBook(
                book,
                newWordsLimit.value,
                shuffleNewWordsEnabled.value,
                plannedNewWordsEnabled.value
            )
        }
    }

    /**
     * 1.4.6 H：跳过当前词，先背下一个。
     *
     * 只在「已点重写、结果还没回来」时可用；词不删、今天仍要背。1.4.7.2 W2 起，
     * 生成好后按稳定槽位插回当前卡之后第 2/3 张普通卡之后（见 onWaitingRewriteReady），
     * 且**只有用户真的作答**才清 needs_reinsert —— 跳过不等于背完。
     *
     * 1.4.7v7-bugfix：
     * - 判定与界面同源（[currentRewriteSkipAvailability]），不再出现「按钮亮着点了没反应」；
     * - 与三键/拼写共用 [launchSubmit] 的互斥闸，跳过写库期间不会被一次评分打断；
     * - 只有 `markNeedsReinsert` 与库内回读都确认后才换词，落库失败会明确提示而不是静默返回。
     */
    fun skipCurrentWordForRewrite() {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        when (skipRewriteAvailability(
            word.id,
            book.id,
            currentRewriteMark.value,
            _activeRewriteRequestIds.value[word.id]
        )) {
            SkipRewriteAvailability.AVAILABLE -> Unit
            SkipRewriteAvailability.RESULT_READY -> {
                // 结果已经合格：这张卡按既有插回规则处理，跳过没有意义也不允许，
                // 直接留在屏上继续背（不换词、不动进度）。
                _message.value = SKIP_RESULT_READY
                return
            }
            // 没有可跳过的欠卡（撤销/换词/换书后的旧入口）：按钮状态已收起或置灰，
            // 这里再拦一次，避免把还没背的词误移出队列。
            SkipRewriteAvailability.NO_MARK,
            SkipRewriteAvailability.OTHER_WORD,
            SkipRewriteAvailability.OTHER_BOOK -> return
        }
        // 与三键/拼写作答共用 launchSubmit 的互斥闸：跳过写库期间不会同时开始一次评分，
        // 评分在途时也不会把当前词从队列里拿走；重复点「跳过」同样被这道闸挡住。
        launchSubmit {
            // 先把「欠一次作答」落到当前这本书；落不下去就不换卡，并且要让人看见原因。
            val saved = runCatching { rewriteRepository.markNeedsReinsert(word.id, book.id) }
            if (saved.isFailure) {
                _message.value = SKIP_PERSIST_FAILED
                return@launchSubmit
            }
            if (!saved.getOrDefault(false)) {
                // 点下到落库之间行被撤销/删除：不重复入队，也不静默换卡。
                _message.value = SKIP_MARK_CHANGED
                return@launchSubmit
            }
            // 以库里的实时行复核标记真的落在这张卡上，确认后才允许移出当前队列。
            val confirmed = runCatching { rewriteRepository.markOf(word.id, book.id) }.getOrNull()
            if (confirmed == null) {
                // 行在这期间被撤销/删除：同样不静默换卡。
                _message.value = SKIP_MARK_CHANGED
                return@launchSubmit
            }
            if (!confirmed.needsReinsert) {
                // 刚才置的标记被别的路径清掉（例如已经作答/运维结清）：这次跳过作废，
                // 不换词、不重复入队；合格稿仍按既有插回规则由观察器处理。
                _message.value = if (confirmed.status == RewriteStatus.DONE) {
                    SKIP_RESULT_READY
                } else {
                    SKIP_MARK_CHANGED
                }
                return@launchSubmit
            }
            if (activeBook.value?.id != book.id || _currentWord.value?.id != word.id) {
                return@launchSubmit
            }
            // needs_reinsert 已是唯一「还欠一次作答」的依据：pending/queued/failed 都登记等待，
            // 合格后由 observeWaitingRewrite → onWaitingRewriteReady 按稳定槽位插回，
            // 不会重复插回（reinsertingWordIds 去重）。
            //
            // 点下到落库之间刚好变成 DONE 的竞争：这里**允许**这次已经获得资格的跳过成立
            // （needs_reinsert=1 + status=done），observeWaitingRewrite 下一帧就会把它登记成
            // 待插回，按「当前卡之后第 2/3 张普通卡之后」回到队列，不会丢卡也不会重插。
            // 不在这里 clearNeedsReinsert：那会让用户刚点过的跳过直接蒸发，且与「作答才结清」口径冲突。
            // 1.5.2v2：先把上一轮遗留的「待插回」状态清掉。
            // 旧实现里，DONE 的旧稿会留在 pendingReinsertWords，用户重新点重写再点跳过时，
            // 它仍按旧下标 0 插回，表现就是「跳过按了但卡不动」。
            forgetPendingReinsert(word.id)
            restoredWaitingRewriteWords.remove(word.id)
            val newlyWaiting = waitingRewriteWords.putIfAbsent(word.id, word) == null
            // 不论是不是已经在等待集合里，都要把这张卡从本次会话队列里过滤掉。
            sessionSkippedWordIds.add(word.id)
            if (newlyWaiting) {
                observeWaitingRewrite(word, book.id)
            }
            refreshWaitingCount()
            loadQueueForBook(
                book,
                newWordsLimit.value,
                shuffleNewWordsEnabled.value,
                plannedNewWordsEnabled.value
            )
            // 最后一道保险：loadQueueForBook 期间若有旧观察器把词插回队首，这里强制前移。
            // 只在当前卡仍是它时动手，避免误推用户已经切到的下一张。
            if (_currentWord.value?.id == word.id) {
                val remaining = activeQueue.filterNot { it.id == word.id }
                if (remaining.size != activeQueue.size) {
                    updateQueue(remaining)
                } else {
                    publishWord(remaining.firstOrNull())
                }
            }
            _message.value = if (newlyWaiting) {
                "已跳过，结果好了会自动插回来"
            } else {
                SKIP_ALREADY_WAITING
            }
        }
    }

    /** 回前台/进学习页：静默补发队列 + 增量拉结果，命中当前词就原地刷新卡背。 */
    fun syncRewrite() {
        viewModelScope.launch { syncRewriteNow() }
    }

    /** 翻到卡背顺手同步一次；8 秒内最多一次，来回翻面不打爆电脑。 */
    fun syncRewriteOnBackFlip() {
        val now = System.currentTimeMillis()
        if (now - lastBackFlipSyncAt < REWRITE_FLIP_SYNC_MIN_INTERVAL_MS) return
        lastBackFlipSyncAt = now
        syncRewrite()
    }

    private suspend fun syncRewriteNow() {
        rewriteRepository.sync()
        // 不管这次有没有拉到新结果，都拿库里的最新思路刷一下当前卡片：
        // 结果可能在上一轮同步里就落库了，但卡片还停着旧文本。
        val current = _currentWord.value
        if (current != null) {
            val bookId = activeBook.value?.id ?: 0L
            val fresh = rewriteRepository.approachOf(current.id, bookId)
            // 方法标签跟着思路一起换：重写结果里带了 method，别让卡背还挂着旧的那一招。
            val freshMethod = rewriteRepository.methodOf(current.id, bookId)
                .ifBlank { current.method }
            val approachChanged = fresh != null && fresh != current.approach
            val methodChanged = freshMethod != current.method
            if (approachChanged || methodChanged) {
                publishWord(current.copy(approach = fresh ?: current.approach, method = freshMethod))
                _rewriteUpdatedTick.value += 1
            }
        } else if (waitingRewriteCount.value > 0) {
            // 1.5.2v2：队列背完时当前卡为 null，等待卡上的「现在刷新」只刷新当前卡，
            // 结果即使已经落库也不会插回队列。这里主动重建一次队列，让 needs_reinsert
            // 的 DONE 行按正常位置插回来（冷启动同样走这条）。
            activeBook.value?.let { book ->
                loadQueueForBook(
                    book = book,
                    newWordsLimit = newWordsLimit.value,
                    shuffleNewWords = shuffleNewWordsEnabled.value,
                    plannedModeEnabled = plannedNewWordsEnabled.value
                )
            }
        }
        if (_rewriteModels.value === RewriteModels.DEFAULT) {
            _rewriteModels.value = rewriteRepository.models()
        }
    }

    /**
     * 卡背可见且标记未完成时调用：每 4 秒问一次电脑端进度。
     * 翻回正面、离开学习页、App 进后台由调用方 stop，避免后台空转耗电。
     */
    fun startRewriteProgressPolling() {
        if (rewritePollJob?.isActive == true) return
        val wordId = _currentWord.value?.id
        if (wordId != null && wordId != polledWordId) {
            // 换词了：上一个词的 100% 不带过来
            polledWordId = wordId
            _rewriteProgress.value = null
            pendingTerminalSync = true
        }
        rewritePollJob = viewModelScope.launch {
            while (isActive) {
                pollRewriteProgressOnce()
                delay(REWRITE_PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopRewriteProgressPolling() {
        rewritePollJob?.cancel()
        rewritePollJob = null
    }

    private suspend fun pollRewriteProgressOnce() {
        val word = _currentWord.value ?: return
        val bookId = activeBook.value?.id ?: 0L
        val mark = currentRewriteMark.value ?: return
        if (mark.wordId != word.id || (mark.bookId != bookId && mark.bookId != 0L)) return
        val snapshot = rewriteRepository.statusOf(mark)
        if (_currentWord.value?.id != word.id || (activeBook.value?.id ?: 0L) != bookId) return
        if (snapshot == null) {
            // 连不上电脑：保留上一次的百分比，只把「等电脑上线」点亮
            _rewriteProgress.value = RewriteProgressUi(
                fraction = _rewriteProgress.value?.fraction,
                failed = _rewriteProgress.value?.failed == true,
                offline = true
            )
            return
        }
        if (snapshot.state == RewriteRepository.STATE_MISSING) {
            _rewriteProgress.value = RewriteProgressUi(fraction = null)
            return
        }
        val state = snapshot.state.lowercase()
        val stage = snapshot.stage?.lowercase()
        val failed = state == STATE_FAILED || stage == STATE_FAILED
        val previous = _rewriteProgress.value
        val fraction = when {
            // failed 停在最后一次已知进度上，变红，不回退成 0
            failed -> previous?.fraction
            else -> fractionOfStage(stage) ?: fractionOfState(state) ?: previous?.fraction
        }
        _rewriteProgress.value = RewriteProgressUi(
            fraction = fraction,
            failed = failed,
            offline = false
        )
        val markStatus = currentRewriteMark.value?.status
        val markStillOpen = markStatus != RewriteStatus.DONE && markStatus != RewriteStatus.FAILED
        if ((state == "done" || failed) && (pendingTerminalSync || markStillOpen)) {
            // 终态立刻拉一次结果，把卡背文字 / 标记状态换成最终值。
            // pendingTerminalSync 只在「换了词」时重置，同一个词连续重写第二遍时它是 false，
            // 所以还得看当前标记是否仍未完成——否则第二遍会停在 100% 却不换文。
            syncRewriteNow()
            val after = rewriteRepository.markOf(word.id, bookId)?.status
            pendingTerminalSync = after != RewriteStatus.DONE && after != RewriteStatus.FAILED
            // 1.5.3v2 C1：本轮重写已 DONE 落地展示，清理该词的活跃 requestId，
            // 让 skipRewriteAvailability 落到 NO_MARK，右下角「跳过」彻底收回。
            if (after == RewriteStatus.DONE) {
                _activeRewriteRequestIds.update { it - word.id }
            }
        }
    }

    private fun fractionOfStage(stage: String?): Float? = when (stage) {
        "queued" -> 0.08f
        "generate" -> 0.45f
        "polish" -> 0.75f
        "judge" -> 0.92f
        "done" -> 1f
        else -> null
    }

    /** 老服务没有 stage：按 state 粗推断 —— pending 排队、running 已在生成。 */
    private fun fractionOfState(state: String): Float? = when (state) {
        "pending" -> 0.08f
        "running" -> 0.45f
        "done" -> 1f
        else -> null
    }

    fun switchBook(bookId: Long) {
        immediateRetryQueue.clear()
        sessionSkippedWordIds.clear()
        clearWaitingRewrites()
        queueBookId = null
        pendingFailedWordId = null
        answeredInSession = 0
        viewModelScope.launch {
            repository.switchBook(bookId)
        }
    }


    fun toggleNewWord() {
        val word = _currentWord.value ?: return
        viewModelScope.launch {
            val exists = repository.isInNewWords(word.id)
            if (exists) {
                repository.removeFromNewWords(word.id)
            } else {
                repository.addToNewWords(word.id, AddToNewWordsSource.BUTTON)
            }
        }
    }

    /**
     * v8：作答落库后的 FSRS 旁路 —— 记忆信号上报 + 遗忘自动换招。
     *
     * 单开 IO 协程：电脑不在线时最多等到请求超时，不会拖慢下一张卡的出现，
     * 也不会因为上报失败改变这次作答的结果。
     */
    private fun dispatchFsrsSignalsInBackground(word: Word, book: Book, rating: StudyRating) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dispatchFsrsSignals(word, book, rating) }
        }
    }

    private suspend fun dispatchFsrsSignals(word: Word, book: Book, rating: StudyRating) {
        val updated = repository.getProgress(word.id, book.id) ?: return
        val method = runCatching { rewriteRepository.methodOf(word.id, book.id) }.getOrDefault("")
        if (fsrsEnabled.value) {
            if (rating == StudyRating.AGAIN) {
                rewriteRepository.reportMemorySignal(
                    wordId = word.id,
                    bookId = book.id,
                    word = word.word,
                    method = method,
                    event = "lapsed",
                    stability = updated.fsrsStability,
                    difficulty = updated.fsrsDifficulty,
                    lapses = updated.fsrsLapses
                )
            } else if (updated.fsrsStability >= 14f) {
                rewriteRepository.reportMemorySignal(
                    wordId = word.id,
                    bookId = book.id,
                    word = word.word,
                    method = method,
                    event = "retained",
                    stability = updated.fsrsStability,
                    difficulty = updated.fsrsDifficulty,
                    lapses = updated.fsrsLapses
                )
            }
        }
        if (fsrsEnabled.value &&
            (updated.fsrsLapses >= 2 || updated.fsrsDifficulty >= 8.5f)
        ) {
            rewriteRepository.maybeAutoRewriteAfterLapse(word, book, updated)
        }
    }

    fun submitAnswer(rating: StudyRating) {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        val previousWordId = word.id
        val responseTimeMs = buildCurrentResponseTimeMs()
        recordAnswerPace(responseTimeMs)
        val effectiveRating = resolveRecognitionRating(rating, responseTimeMs)
        val currentSessionPosition = answeredInSession
        val currentSessionTotal = _totalCount.value.coerceAtLeast(1)
        launchSubmit {
            val currentMlEnabled = resolveMlEnabledForSubmission()
            pendingFailedWordId = null
            removeFromImmediateQueue(word.id)
            removeSessionSkippedWord(word.id)

            // ML训练：记录特征和结果
            if (currentMlEnabled && mlOnlineTrainer != null) {
                val progress = withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
                val features = mlScheduler.extractFeatures(
                    progress = progress,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                val isCorrect = effectiveRating != StudyRating.AGAIN
                withContext(Dispatchers.IO) {
                    mlOnlineTrainer.onReviewCompleted(word.id, features, isCorrect)
                }
            }

            withContext(Dispatchers.IO) {
                repository.applyStudyResult(
                    wordId = word.id,
                    bookId = book.id,
                    rating = effectiveRating,
                    algorithmV4Enabled = algorithmV4Enabled.value,
                    fsrsEnabled = fsrsEnabled.value,
                    desiredRetention = desiredRetention.value,
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal,
                    responseTimeMs = responseTimeMs
                )
                // 1.4.7.2 W2：用户真的答完这张重写卡，才清待插回标记（生成/合格/插回都不算）。
                // 只结清当前这本书：同一个词可能同时属于别的书，不能把别的书的欠卡一起清掉。
                rewriteRepository.clearNeedsReinsert(word.id, book.id)
            }
            dispatchFsrsSignalsInBackground(word, book, effectiveRating)
            forgetPendingReinsert(word.id)
            if (effectiveRating == StudyRating.AGAIN) {
                enqueueImmediateRetry(word)
                _memoryAidSuggestionWordId.value = word.id
            } else {
                _memoryAidSuggestionWordId.value = null
                markAnswered()
            }
            loadQueueForBook(
                book, newWordsLimit.value, shuffleNewWordsEnabled.value,
                plannedNewWordsEnabled.value, presentAgain = true
            )
            emitRecognitionAutoPronounceEventIfNeeded(previousWordId)
        }
    }

    fun submitAnswerAndRemoveFromNewWords(rating: StudyRating) {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        val previousWordId = word.id
        val responseTimeMs = buildCurrentResponseTimeMs()
        recordAnswerPace(responseTimeMs)
        val effectiveRating = resolveRecognitionRating(rating, responseTimeMs)
        val currentSessionPosition = answeredInSession
        val currentSessionTotal = _totalCount.value.coerceAtLeast(1)
        launchSubmit {
            val currentMlEnabled = resolveMlEnabledForSubmission()
            pendingFailedWordId = null
            _memoryAidSuggestionWordId.value = null
            removeFromImmediateQueue(word.id)
            removeSessionSkippedWord(word.id)

            if (currentMlEnabled && mlOnlineTrainer != null) {
                val progress = withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
                val features = mlScheduler.extractFeatures(
                    progress = progress,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                val isCorrect = effectiveRating != StudyRating.AGAIN
                withContext(Dispatchers.IO) {
                    mlOnlineTrainer.onReviewCompleted(word.id, features, isCorrect)
                }
            }

            withContext(Dispatchers.IO) {
                repository.removeFromNewWords(word.id)
                repository.applyStudyResult(
                    wordId = word.id,
                    bookId = book.id,
                    rating = effectiveRating,
                    algorithmV4Enabled = algorithmV4Enabled.value,
                    fsrsEnabled = fsrsEnabled.value,
                    desiredRetention = desiredRetention.value,
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal,
                    responseTimeMs = responseTimeMs
                )
                // 1.4.7.2 W2：用户真的答完这张重写卡，才清待插回标记（生成/合格/插回都不算）。
                rewriteRepository.clearNeedsReinsert(word.id, book.id)
            }
            dispatchFsrsSignalsInBackground(word, book, effectiveRating)
            forgetPendingReinsert(word.id)
            markAnswered()
            loadQueueForBook(
                book, newWordsLimit.value, shuffleNewWordsEnabled.value,
                plannedNewWordsEnabled.value, presentAgain = true
            )
            emitRecognitionAutoPronounceEventIfNeeded(previousWordId)
        }
    }

    fun submitSpellingOutcome(
        outcome: SpellingOutcome,
        attemptCount: Int,
        durationMillis: Long
    ) {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        val responseTimeMs = if (durationMillis > 0L) durationMillis else buildCurrentResponseTimeMs()
        recordAnswerPace(responseTimeMs)
        val currentSessionPosition = answeredInSession
        val currentSessionTotal = _totalCount.value.coerceAtLeast(1)
        val effectiveRating = when (outcome) {
            SpellingOutcome.PERFECT -> StudyRating.GOOD
            SpellingOutcome.HINTED, SpellingOutcome.RETRY_SUCCESS -> StudyRating.HARD
            SpellingOutcome.FAILED -> StudyRating.AGAIN
        }
        launchSubmit {
            val currentMlEnabled = resolveMlEnabledForSubmission()
            if (outcome == SpellingOutcome.FAILED) {
                pendingFailedWordId = word.id
                _memoryAidSuggestionWordId.value = word.id
            } else {
                pendingFailedWordId = null
                _memoryAidSuggestionWordId.value = null
            }
            removeFromImmediateQueue(word.id)
            removeSessionSkippedWord(word.id)

            // ML训练
            if (currentMlEnabled && mlOnlineTrainer != null) {
                val progress = withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
                val features = mlScheduler.extractFeatures(
                    progress = progress,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                val isCorrect = outcome != SpellingOutcome.FAILED
                withContext(Dispatchers.IO) {
                    mlOnlineTrainer.onReviewCompleted(word.id, features, isCorrect)
                }
            }

            withContext(Dispatchers.IO) {
                repository.applySpellingOutcome(
                    wordId = word.id,
                    bookId = book.id,
                    outcome = outcome,
                    attemptCount = attemptCount,
                    durationMillis = durationMillis,
                    algorithmV4Enabled = algorithmV4Enabled.value,
                    fsrsEnabled = fsrsEnabled.value,
                    desiredRetention = desiredRetention.value,
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                // 1.4.7.2 W2：拼写成功才算这张卡真的答完。
                // 失败还会原地重背（continueAfterSpellingFailure → 即时重背），欠卡先留着。
                if (outcome != SpellingOutcome.FAILED) {
                    rewriteRepository.clearNeedsReinsert(word.id, book.id)
                }
            }
            dispatchFsrsSignalsInBackground(word, book, effectiveRating)
            if (outcome == SpellingOutcome.FAILED) {
                return@launchSubmit
            }
            forgetPendingReinsert(word.id)
            pendingFailedWordId = null
            markAnswered()
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
        }
    }

    fun continueAfterSpellingFailure() {
        val currentWord = _currentWord.value ?: return
        val book = activeBook.value ?: return
        if (_isSubmitting.value) return
        if (pendingFailedWordId != currentWord.id) return
        pendingFailedWordId = null
        viewModelScope.launch {
            enqueueImmediateRetry(currentWord)
            markAnswered()
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
        }
    }

    fun refreshQueue() {
        val book = activeBook.value ?: return
        viewModelScope.launch {
            pendingFailedWordId = null
            // 手动刷新不能把正在等重写的词放回来，否则同一张卡会在队列里出现两次。
            val stillWaiting = waitingRewriteWords.keys.toSet() + restoredWaitingRewriteWords.keys
            sessionSkippedWordIds.clear()
            sessionSkippedWordIds.addAll(stillWaiting)
            answeredInSession = 0
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
        }
    }

    fun disperseReviewPressure() {
        val book = activeBook.value ?: return
        if (book.type == Book.TYPE_NEW_WORDS) return
        if (!reviewPressureReliefEnabled.value) return
        if (_isSubmitting.value) return
        val dailyCap = reviewPressureDailyCap.value.coerceAtLeast(1)
        launchSubmit {
            val movedCount = withContext(Dispatchers.IO) {
                repository.disperseReviewPressure(book.id, dailyCap)
            }
            _message.value = if (movedCount > 0) {
                "已分散 $movedCount 个待复习单词到后续天"
            } else {
                "当前待复习数量未超过上限"
            }
            pendingFailedWordId = null
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
        }
    }

    fun refreshAiAvailability() {
        viewModelScope.launch {
            val config = aiConfigRepository.getConfig()
            _aiState.value = _aiState.value.copy(
                isEnabled = config.enabled,
                isConfigured = config.isConfigured()
            )
        }
    }

    fun loadCachedAiForCurrentWord() {
        val word = _currentWord.value ?: run {
            _cachedExampleContent.value = ""
            _cachedMemoryAidContent.value = ""
            return
        }
        viewModelScope.launch {
            val cachedExample = withContext(Dispatchers.IO) {
                aiRepository.getCachedExample(word.id, word.word)
            }.orEmpty()
            val cachedMemoryAid = withContext(Dispatchers.IO) {
                aiRepository.getCachedMemoryAid(word.id, word.word)
            }.orEmpty()
            _cachedExampleContent.value = cachedExample
            _cachedMemoryAidContent.value = cachedMemoryAid
        }
    }

    fun requestAiExample(forceRefresh: Boolean = false) {
        val word = _currentWord.value ?: return
        viewModelScope.launch {
            val config = aiConfigRepository.getConfig()
            val enabled = config.enabled
            val configured = config.isConfigured()
            _aiState.value = _aiState.value.copy(
                isEnabled = enabled,
                isConfigured = configured
            )
            if (!enabled || !configured) {
                _aiState.value = _aiState.value.copy(
                    activeType = AIContentType.EXAMPLE,
                    error = "请先在 AI 实验室启用并配置 API Key",
                    content = "",
                    isLoading = false
                )
                return@launch
            }

            _aiState.value = _aiState.value.copy(
                activeType = AIContentType.EXAMPLE,
                isLoading = true,
                error = null
            )
            val result = withContext(Dispatchers.IO) {
                aiRepository.generateExample(
                    wordId = word.id,
                    word = word.word,
                    forceRefresh = forceRefresh
                )
            }
            result.fold(
                onSuccess = { content ->
                    _cachedExampleContent.value = content
                    _aiState.value = _aiState.value.copy(
                        activeType = AIContentType.EXAMPLE,
                        content = content,
                        error = null,
                        isLoading = false
                    )
                },
                onFailure = { throwable ->
                    _aiState.value = _aiState.value.copy(
                        activeType = AIContentType.EXAMPLE,
                        content = "",
                        error = throwable.message ?: "例句生成失败",
                        isLoading = false
                    )
                }
            )
        }
    }

    fun requestAiMemoryAid(forceRefresh: Boolean = false) {
        val word = _currentWord.value ?: return
        if (_memoryAidSuggestionWordId.value == word.id) {
            _memoryAidSuggestionWordId.value = null
        }
        viewModelScope.launch {
            val config = aiConfigRepository.getConfig()
            val enabled = config.enabled
            val configured = config.isConfigured()
            _aiState.value = _aiState.value.copy(
                isEnabled = enabled,
                isConfigured = configured
            )
            if (!enabled || !configured) {
                _aiState.value = _aiState.value.copy(
                    activeType = AIContentType.MEMORY_AID,
                    error = "请先在 AI 实验室启用并配置 API Key",
                    content = "",
                    isLoading = false
                )
                return@launch
            }

            _aiState.value = _aiState.value.copy(
                activeType = AIContentType.MEMORY_AID,
                isLoading = true,
                error = null
            )
            val result = withContext(Dispatchers.IO) {
                aiRepository.generateMemoryAid(
                    wordId = word.id,
                    word = word.word,
                    forceRefresh = forceRefresh
                )
            }
            result.fold(
                onSuccess = { content ->
                    _cachedMemoryAidContent.value = content
                    _aiState.value = _aiState.value.copy(
                        activeType = AIContentType.MEMORY_AID,
                        content = content,
                        error = null,
                        isLoading = false
                    )
                },
                onFailure = { throwable ->
                    _aiState.value = _aiState.value.copy(
                        activeType = AIContentType.MEMORY_AID,
                        content = "",
                        error = throwable.message ?: "助记生成失败",
                        isLoading = false
                    )
                }
            )
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun completeTodayCheckIn() {
        launchSubmit {
            // 打卡入口只有完成页这一张卡，但仓库层 checkInToday() 只认学习活动数，
            // 不认重写欠卡。这里补最小保护：今天还有没背完的重写卡就不许打卡。
            // （直接调仓库的测试/未来入口仍可绕过，见 W2 报告的收口建议。）
            if (todayPendingRewriteCount.value > 0) {
                _message.value = "还有重写卡没背完，背完再打卡"
                return@launchSubmit
            }
            when (withContext(Dispatchers.IO) { repository.checkInToday() }) {
                CheckInResult.SUCCESS -> _message.value = "打卡成功，明天继续保持"
                CheckInResult.ALREADY_CHECKED_IN -> _message.value = "今天已经打过卡了"
                CheckInResult.NOT_ELIGIBLE -> _message.value = "请先完成今日背词再打卡"
            }
        }
    }

    private suspend fun loadQueueForBook(
        book: Book?,
        newWordsLimit: Int,
        shuffleNewWords: Boolean,
        plannedModeEnabled: Boolean,
        presentAgain: Boolean = false
    ) {
        if (book == null) {
            immediateRetryQueue.clear()
            sessionSkippedWordIds.clear()
            clearWaitingRewrites()
            queueBookId = null
            pendingFailedWordId = null
            answeredInSession = 0
            _dueReviewCount.value = 0
            updateQueue(emptyList())
            return
        }
        val bookChanged = queueBookId != book.id
        if (bookChanged) {
            immediateRetryQueue.clear()
            sessionSkippedWordIds.clear()
            clearWaitingRewrites()
            queueBookId = book.id
            pendingFailedWordId = null
            answeredInSession = 0
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
        _dueReviewCount.value = queueSnapshot.dueCount
        // W2：先把 DB 里「已合格、还没作答」的重写卡登记成稳定插回位（含冷启动补插），
        // 再一次性排进队列。这样每次作答后重建队列，插回位置都一样，不会被重新随机。
        if (!restoreWaitingRewrites(book.id)) {
            // 读库失败时不能按“没有欠卡”重建队列：还没合格的旧稿会漏回普通队列。
            // 同书重载保留当前卡（避免作答后总数归零被误判成完成），换书则清空旧书队列。
            _message.value = "重写记录暂时读不出来，先不换卡；请稍后重试"
            if (bookChanged) updateQueue(emptyList())
            return
        }
        val consumed = currentConsumed
        currentConsumed = false
        val previousPendingIndex = activeQueue.withIndex()
            .associate { (index, word) -> word.id to index }
        val mergedQueue = mergeWithImmediateRetryQueue(queueSnapshot.queue)
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
            val target = if (consumed) (oldIndex - 1).coerceAtLeast(0) else oldIndex
            val insertAt = target.coerceIn(0, placed.size)
            placed = placed.toMutableList().apply { add(insertAt, word) }
        }
        updateQueue(applyPendingReinsertPlacements(placed), presentAgain || bookChanged)
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
            waitingRewriteWords.size + restoredWaitingRewriteWords.size + waitingRewriteJobs.size
        )
        tracked.addAll(waitingRewriteWords.keys)
        tracked.addAll(restoredWaitingRewriteWords.keys)
        tracked.addAll(waitingRewriteJobs.keys)
        val plan = WaitingRewriteRestore.plan(rows, tracked)

        plan.watchWordIds.forEach { wordId ->
            val wordResult = runCatching { repository.getWordById(wordId) }
            // 读词失败 ≠ 词已删除：整次恢复中止、保留标记。不能只跳过这个词——
            // 它没进 restoredWaitingRewriteWords，就不会被普通队列过滤，旧稿仍会漏出来。
            if (wordResult.isFailure) return false
            val word = wordResult.getOrNull() ?: return@forEach
            restoredWaitingRewriteWords[wordId] = word
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
                forgetPendingReinsert(wordId)
                return@forEach
            }
            // 只要行还带 needs_reinsert=1，就说明用户没有真正作答过（作答路径会清标记）。
            // 不能再用 lastReviewTime 判断：拼写失败也会刷新它，会把未作答误判成已作答。
            rememberPendingReinsert(wordId, word, createdAtByWordId[wordId] ?: 0L)
            restoredWaitingRewriteWords.remove(wordId)
        }

        if (waitingRewriteWords.isNotEmpty() || restoredWaitingRewriteWords.isNotEmpty()) {
            ensureWaitingRewritePoll()
        }
        return true
    }

    private fun resolvePlannedNewWordIds(book: Book, plannedModeEnabled: Boolean): List<Long> {
        if (!plannedModeEnabled) return emptyList()
        if (book.type == Book.TYPE_NEW_WORDS) return emptyList()
        val plan = todayNewWordsPlan.value
        val learningDate = DateUtils.currentLearningDate().format(DATE_FORMATTER)
        if (plan.learningDate != learningDate) return emptyList()
        if (plan.bookId != book.id) return emptyList()
        return plan.wordIds
    }

    private fun updateQueue(list: List<Word>, presentAgain: Boolean = false) {
        activeQueue = list
        val oldWordId = _currentWord.value?.id
        // 等待重写的词也算今天的进度，否则跳过后总进度会凭空变少。
        val remaining = list.size + waitingRewriteWords.size
        val total = answeredInSession + remaining
        _totalCount.value = total
        _currentIndex.value = when {
            total <= 0 -> 0
            remaining == 0 -> total
            else -> (answeredInSession + 1).coerceAtMost(total)
        }
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

    private fun markAnswered() {
        answeredInSession += 1
        currentConsumed = true
    }

    private fun emitRecognitionAutoPronounceEventIfNeeded(previousWordId: Long) {
        val nextWordId = _currentWord.value?.id ?: return
        if (nextWordId == previousWordId) return
        recognitionAutoPronounceEventCounter += 1
        _recognitionAutoPronounceEvents.tryEmit(recognitionAutoPronounceEventCounter)
    }

    private fun buildCurrentResponseTimeMs(now: Long = System.currentTimeMillis()): Long {
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
    private fun refreshLearningPace() {
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
    private fun recordAnswerPace(durationMs: Long) {
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

    private fun resolveRecognitionRating(
        rating: StudyRating,
        responseTimeMs: Long
    ): StudyRating {
        if (!algorithmV4Enabled.value) return rating
        if (responseTimeMs <= cachedResponseThresholdMs) return rating
        return when (rating) {
            StudyRating.GOOD -> StudyRating.HARD
            StudyRating.HARD -> StudyRating.AGAIN
            StudyRating.AGAIN -> StudyRating.AGAIN
        }
    }

    /** 盯住一个等待词的标记；DONE 就插回队列，FAILED 只提示（sync 里会自动重试）。 */
    private fun observeWaitingRewrite(word: Word, bookId: Long) {
        waitingRewriteJobs.remove(word.id)?.cancel()
        waitingRewriteJobs[word.id] = viewModelScope.launch {
            rewriteRepository.observeMark(word.id, bookId)
                .map { it?.status }
                .distinctUntilChanged()
                .collect { status ->
                    if (activeBook.value?.id != bookId) return@collect
                    when (status) {
                        RewriteStatus.DONE -> onWaitingRewriteReady(word.id, bookId)
                        RewriteStatus.FAILED -> Unit // 留在等待队列；同步会在冷却后重投旧终态任务。
                        null -> {
                            // 队列行被撤销/删除/取消：三个等待集合都要收口，
                            // 否则「预览后取消」会让等待计数在本会话里永远消不掉。
                            waitingRewriteWords.remove(word.id)
                            restoredWaitingRewriteWords.remove(word.id)
                            pendingReinsertWords.remove(word.id)
                            pendingReinsertSlots.remove(word.id)
                            waitingRewriteJobs.remove(word.id)?.cancel()
                            if (waitingRewriteWords.isEmpty() &&
                                restoredWaitingRewriteWords.isEmpty() &&
                                pendingReinsertWords.isEmpty()
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
            while (isActive && (waitingRewriteWords.isNotEmpty() || restoredWaitingRewriteWords.isNotEmpty())) {
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
        val fromSessionWait = waitingRewriteWords.remove(wordId)
        val word = fromSessionWait
            ?: restoredWaitingRewriteWords.remove(wordId)
            ?: pendingReinsertWords[wordId]
        if (word == null) {
            reinsertingWordIds.remove(wordId)
            return
        }
        // 先只摘掉登记，等挂起的查询/插队做完再 cancel —— 当前 collector 自己的 job
        // 若在这里就 cancel，下面的 getProgress 会立刻抛 CancellationException，词就插不回去。
        val collectorJob = waitingRewriteJobs.remove(wordId)
        sessionSkippedWordIds.remove(wordId)
        refreshWaitingCount()

        try {
            // 作答路径会清 needs_reinsert；它还是 true 才说明这张重写卡没被真正作答。
            // 不能用 lastReviewTime：拼写失败也会刷新它，会把未作答误判成已作答。
            val markResult = runCatching { rewriteRepository.markOf(wordId, bookId) }
            if (activeBook.value?.id != bookId) return
            val mark = markResult.getOrNull()
            if (markResult.isFailure || (mark?.needsReinsert == true && mark.status != RewriteStatus.DONE)) {
                // 读不到当前书的结果、或新的重写已替代合格稿，都要继续等。
                restoredWaitingRewriteWords[wordId] = word
                refreshWaitingCount()
                viewModelScope.launch {
                    delay(REWRITE_PROGRESS_POLL_INTERVAL_MS)
                    if (activeBook.value?.id == bookId && wordId in restoredWaitingRewriteWords) {
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
                forgetPendingReinsert(wordId)
                return
            }
            rememberPendingReinsert(wordId, word, mark.createdAt)
            updateQueue(applyPendingReinsertPlacements(activeQueue))
        } finally {
            reinsertingWordIds.remove(wordId)
            collectorJob?.cancel()
            if (waitingRewriteWords.isEmpty() && restoredWaitingRewriteWords.isEmpty()) {
                waitingRewritePollJob?.cancel()
                waitingRewritePollJob = null
            }
        }
    }

    /**
     * 登记一张「已合格、还没作答」的重写卡：槽位只算一次（wordId + 发起时间），
     * 之后每次重建队列都按同一槽位重排，不会重新随机。
     */
    private fun rememberPendingReinsert(wordId: Long, word: Word, createdAt: Long) {
        pendingReinsertWords[wordId] = word
        if (!pendingReinsertSlots.containsKey(wordId)) {
            pendingReinsertSlots[wordId] = ImmediateRetryQueuePlanner.waitingInsertSlot(
                ImmediateRetryQueuePlanner.waitingRewriteSeed(wordId, createdAt)
            )
        }
    }

    /** 用户真的作答（或标记已结清）后，这张卡不再参与插回。 */
    private fun forgetPendingReinsert(wordId: Long) {
        pendingReinsertWords.remove(wordId)
        pendingReinsertSlots.remove(wordId)
    }

    private fun clearPendingReinsert() {
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
    private fun applyPendingReinsertPlacements(queue: List<Word>): List<Word> {
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

    private fun refreshWaitingCount() {
        _waitingRewriteCount.value = waitingRewriteWords.size
    }

    private fun clearWaitingRewrites() {
        waitingRewriteJobs.values.forEach { it.cancel() }
        waitingRewriteJobs.clear()
        waitingRewriteWords.clear()
        restoredWaitingRewriteWords.clear()
        // 换书/停止后不再按旧书的槽位插回；DB 里的 needs_reinsert 留着，下次进这本书记得再恢复。
        clearPendingReinsert()
        waitingRewritePollJob?.cancel()
        waitingRewritePollJob = null
        refreshWaitingCount()
    }

    private fun skipWordInSession(wordId: Long) {
        sessionSkippedWordIds.add(wordId)
    }

    private fun removeSessionSkippedWord(wordId: Long) {
        sessionSkippedWordIds.remove(wordId)
    }

    private fun launchSubmit(block: suspend () -> Unit) {
        if (_isSubmitting.value) {
            // 1.5.2：重入不再静默丢弃，给一句轻提示。
            _message.value = SUBMIT_BUSY
            return
        }
        _isSubmitting.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    private suspend fun resolveMlEnabledForSubmission(): Boolean {
        return withContext(Dispatchers.IO) {
            val enabled = settingsRepository.settingsFlow.first().mlAdaptiveEnabled
            if (enabled) {
                getApplication<KaoyanWordApp>().ensureMLEngineInitialized()
            }
            enabled
        }
    }

    private fun enqueueImmediateRetry(word: Word) {
        removeFromImmediateQueue(word.id)
        immediateRetryQueue.addLast(word)
    }

    private fun removeFromImmediateQueue(wordId: Long) {
        if (immediateRetryQueue.isEmpty()) return
        val retained = immediateRetryQueue.filterNot { it.id == wordId }
        immediateRetryQueue.clear()
        immediateRetryQueue.addAll(retained)
    }

    private fun mergeWithImmediateRetryQueue(baseQueue: List<Word>): List<Word> {
        if (immediateRetryQueue.isEmpty()) return baseQueue
        val immediateIds = immediateRetryQueue.map { it.id }.toSet()
        val merged = baseQueue
            .filterNot { it.id in immediateIds }
            .toMutableList()
        val policy = if (algorithmV4Enabled.value) {
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

    companion object {
        private const val DEFAULT_NEW_WORDS_LIMIT = 20
        private const val RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS = 6_000L
        private const val REWRITE_FLIP_SYNC_MIN_INTERVAL_MS = 8_000L
        private const val REWRITE_PROGRESS_POLL_INTERVAL_MS = 4_000L
        private const val LIKE_BURST_DEBOUNCE_MS = FeedbackBurstPolicy.DEBOUNCE_MS
        private const val MAX_FEEDBACK_BURST = FeedbackBurstPolicy.MAX_COUNT
        private const val STATE_FAILED = "failed"
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

/**
 * 1.4.7.2 W2 完成判定。
 *
 * [pendingRewriteCount] = **今天发起、且用户还没实际作答**的重写卡数（needs_reinsert=1 且
 * 创建学习日 == 今天）。发送成功 / 生成完成 / 合格 / 已插回 / 退出学习页都不清这个标记，
 * 只有用户真的点了三键之一（或判定太简单、拼写作答）才清 —— 所以它空着才算今天背完。
 * 昨天没背完的重写卡不阻塞今天，仍留在库里等待正常派发。
 */
internal fun shouldShowCompletionCheckInPanel(
    currentWord: Word?,
    totalCount: Int,
    pendingRewriteCount: Int = 0
): Boolean {
    return currentWord == null && totalCount > 0 && pendingRewriteCount <= 0
}

/** 等待卡条数：本地等待集合与「今天仍未作答的重写卡」取最大值，既不漏也不重复计数。 */
internal fun combinedWaitingRewriteCount(localWaitingCount: Int, pendingTodayCount: Int): Int =
    maxOf(localWaitingCount.coerceAtLeast(0), pendingTodayCount.coerceAtLeast(0))

/**
 * 今天发起、且还没被用户实际作答的重写卡数。
 *
 * 「发起学习日」= [RewriteQueueItem.createdAt] 落在哪个学习日（4 点换日，见 DateUtils），
 * 不用 status —— pending/queued 只代表电脑端还在生成，failed 只代表这次没生成成功，
 * 三者都跟用户有没有背过无关；只有作答路径会清 needs_reinsert。
 */
internal fun countPendingRewritesForLearningDate(
    rows: List<RewriteQueueItem>,
    today: LocalDate = DateUtils.currentLearningDate()
): Int = rows.count { row ->
    row.needsReinsert &&
        DateUtils.currentLearningDate(row.createdAt) == today
}

internal fun buildLearningCheckInUiState(
    weekAggregates: List<DailyStatsAggregate>,
    heatmapAggregates: List<DailyStatsAggregate>,
    currentWord: Word?,
    totalCount: Int,
    pendingRewriteCount: Int = 0,
    today: LocalDate = DateUtils.currentLearningDate()
): LearningCheckInUiState {
    val todayAggregate = weekAggregates.firstOrNull { parseLearningAggregateDate(it.date) == today }
    val todayCheckedIn = (todayAggregate?.checkInCount ?: 0) > 0
    val weekCheckInDays = weekAggregates.count { it.checkInCount > 0 }
    val checkInDates = heatmapAggregates.filter { it.checkInCount > 0 }
        .mapNotNull { parseLearningAggregateDate(it.date) }
        .toSet()
    val streakDays = computeLearningCheckInStreak(today, checkInDates)
    val todayStudyCount = todayAggregate?.let {
        it.newWordsCount + it.reviewWordsCount + it.spellPracticeCount
    } ?: 0
    val todayCheckInTimeText = if (todayCheckedIn && (todayAggregate?.lastCheckInTime ?: 0L) > 0L) {
        DateUtils.formatDateTime(todayAggregate?.lastCheckInTime ?: 0L)
    } else {
        ""
    }
    return LearningCheckInUiState(
        showCompletionPanel = shouldShowCompletionCheckInPanel(
            currentWord = currentWord,
            totalCount = totalCount,
            pendingRewriteCount = pendingRewriteCount
        ),
        checkedInToday = todayCheckedIn,
        streakDays = streakDays,
        weekCheckInDays = weekCheckInDays,
        todayStudyCount = todayStudyCount,
        todayCheckInTimeText = todayCheckInTimeText
    )
}

private fun parseLearningAggregateDate(raw: String): LocalDate? {
    return runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}

private fun computeLearningCheckInStreak(today: LocalDate, checkInDates: Set<LocalDate>): Int {
    var streak = 0
    var cursor = today
    while (checkInDates.contains(cursor)) {
        streak += 1
        cursor = cursor.minusDays(1)
    }
    return streak
}

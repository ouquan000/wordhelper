package com.kaoyan.wordhelper.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.util.withoutBuiltinFull
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.data.model.PronunciationSource
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.data.repository.AddToNewWordsSource
import com.kaoyan.wordhelper.data.repository.CheckInResult
import com.kaoyan.wordhelper.data.repository.CardbackAuth
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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

data class SwipeSnackbarEvent(
    val id: Long,
    val message: String,
    val actionLabel: String? = null,
    val undoToken: Long? = null
)

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

private data class TooEasyUndoPayload(
    val wordId: Long,
    val snapshots: List<Progress>
)

private data class QueueLoadParams(
    val book: Book?,
    val newWordsLimit: Int,
    val shuffleNewWords: Boolean,
    val plannedModeEnabled: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as KaoyanWordApp).repository
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
    private val pendingTooEasyUndo = mutableMapOf<Long, TooEasyUndoPayload>()
    private var swipeSnackbarIdCounter = 0L
    private var undoTokenCounter = 0L
    private var queueBookId: Long? = null
    private var pendingFailedWordId: Long? = null
    private var answeredInSession = 0
    private var currentWordPresentedAtMs = 0L
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
    private val _swipeSnackbarEvent = MutableStateFlow<SwipeSnackbarEvent?>(null)
    val swipeSnackbarEvent: StateFlow<SwipeSnackbarEvent?> = _swipeSnackbarEvent.asStateFlow()
    private val _recognitionAutoPronounceEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recognitionAutoPronounceEvents: SharedFlow<Long> = _recognitionAutoPronounceEvents.asSharedFlow()
    private val learningWeekRange = DateUtils.learningDateRange(days = 7)
    private val learningHeatmapRange = DateUtils.learningHeatmapRange()
    private val learningWeekStatsFlow = repository.getDailyStatsAggregated(learningWeekRange.first, learningWeekRange.second)
    private val learningHeatmapStatsFlow = repository.getDailyStatsAggregated(learningHeatmapRange.first, learningHeatmapRange.second)

    val showSwipeGuide: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { !it.swipeGestureGuideShown }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    private val _currentWord = MutableStateFlow<Word?>(null)
    val currentWord: StateFlow<Word?> = _currentWord.asStateFlow()

    // ---- 重写记忆思路（1.4.3）----

    /** 下拉里的模型；电脑端下发，离线/没连上就用内置 5 个。 */
    private val _rewriteModels = MutableStateFlow(RewriteModels.DEFAULT)
    val rewriteModels: StateFlow<List<RewriteModel>> = _rewriteModels.asStateFlow()

    private val _rewriteModelId = MutableStateFlow(RewriteModels.DEFAULT_ID)
    val rewriteModelId: StateFlow<String> = _rewriteModelId.asStateFlow()

    /** 当前这张卡的重写标记；null = 还没标记过。 */
    val currentRewriteMark: StateFlow<RewriteQueueItem?> = _currentWord
        .flatMapLatest { word ->
            if (word == null) flowOf(null) else rewriteRepository.observeMark(word.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        _totalCount
    ) { weekAggregates, heatmapAggregates, currentWord, totalCount ->
        buildLearningCheckInUiState(
            weekAggregates = weekAggregates,
            heatmapAggregates = heatmapAggregates,
            currentWord = currentWord,
            totalCount = totalCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearningCheckInUiState())

    private var cachedResponseThresholdMs: Long = RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS

    init {
        refreshAiAvailability()
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
        val modelId = _rewriteModelId.value.ifBlank { RewriteModels.DEFAULT_ID }
        // 新一次标记：到终态时还要再拉一次结果
        pendingTerminalSync = true
        _rewriteProgress.value = null
        viewModelScope.launch {
            if (!rewriteRepository.hasAddress()) {
                _message.value = RewriteRepository.NO_ADDRESS
                return@launch
            }
            val sent = rewriteRepository.enqueueAndSync(
                word.id,
                word.word,
                modelId,
                instruction,
                bookId = activeBook.value?.id ?: 0L
            )
            if (!sent) {
                // 口令错和电脑没开是两件事：都说成「等电脑连上」，用户会一直干等。
                _message.value = if (rewriteRepository.consumeAuthFailure()) {
                    CardbackAuth.HINT
                } else {
                    "已记下，等电脑连上再发"
                }
            }
        }
    }

    fun cancelRewrite() {
        val word = _currentWord.value ?: return
        viewModelScope.launch { rewriteRepository.cancel(word.id) }
    }

    fun undoRewrite() {
        val word = _currentWord.value ?: return
        viewModelScope.launch {
            val bookId = activeBook.value?.id ?: 0L
            val restored = rewriteRepository.undo(word.id, bookId)
            if (restored != null) {
                val restoredMethod = rewriteRepository.methodOf(word.id, bookId)
                _currentWord.value = _currentWord.value?.copy(
                    approach = restored,
                    method = restoredMethod
                )
                _message.value = "已撤销回上一版"
            } else {
                _message.value = "没有可撤销的上一版了"
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
                _currentWord.value = current.copy(
                    approach = fresh ?: current.approach,
                    method = freshMethod
                )
                _rewriteUpdatedTick.value += 1
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
        val markCreatedAt = currentRewriteMark.value?.createdAt ?: 0L
        val snapshot = rewriteRepository.statusOf(word.word, sinceMillis = markCreatedAt)
        if (_currentWord.value?.id != word.id) return
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
            val after = rewriteRepository.observeMark(word.id).first()?.status
            pendingTerminalSync = after != RewriteStatus.DONE && after != RewriteStatus.FAILED
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
        pendingTooEasyUndo.clear()
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

    fun submitAnswer(rating: StudyRating) {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        val previousWordId = word.id
        val responseTimeMs = buildCurrentResponseTimeMs()
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
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal,
                    responseTimeMs = responseTimeMs
                )
            }
            if (effectiveRating == StudyRating.AGAIN) {
                enqueueImmediateRetry(word)
                _memoryAidSuggestionWordId.value = word.id
            } else {
                _memoryAidSuggestionWordId.value = null
                markAnswered()
            }
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
            emitRecognitionAutoPronounceEventIfNeeded(previousWordId)
        }
    }

    fun submitAnswerAndRemoveFromNewWords(rating: StudyRating) {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        val previousWordId = word.id
        val responseTimeMs = buildCurrentResponseTimeMs()
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
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal,
                    responseTimeMs = responseTimeMs
                )
            }
            markAnswered()
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
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
        val currentSessionPosition = answeredInSession
        val currentSessionTotal = _totalCount.value.coerceAtLeast(1)
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
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
            }
            if (outcome == SpellingOutcome.FAILED) {
                return@launchSubmit
            }
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

    fun onSwipeTooEasy() {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        launchSubmit {
            pendingFailedWordId = null
            _memoryAidSuggestionWordId.value = null
            removeFromImmediateQueue(word.id)
            removeSessionSkippedWord(word.id)
            val snapshots = withContext(Dispatchers.IO) {
                repository.getProgressSnapshotsForWord(word.id)
            }
            val marked = withContext(Dispatchers.IO) {
                repository.markWordAsTooEasy(word.id, book.id)
            }
            if (!marked) {
                emitSwipeSnackbar("标记失败，请重试")
                return@launchSubmit
            }
            markAnswered()
            val undoToken = registerTooEasyUndo(word.id, snapshots)
            emitSwipeSnackbar(
                message = "已标记为太简单，30天后复习",
                actionLabel = "撤销",
                undoToken = undoToken
            )
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
        }
    }

    fun onSwipeAddToNotebook() {
        val word = _currentWord.value ?: return
        val book = activeBook.value ?: return
        launchSubmit {
            pendingFailedWordId = null
            _memoryAidSuggestionWordId.value = null
            removeFromImmediateQueue(word.id)
            removeSessionSkippedWord(word.id)
            val inserted = withContext(Dispatchers.IO) {
                repository.addToNewWords(word.id, AddToNewWordsSource.GESTURE)
            }
            skipWordInSession(word.id)
            markAnswered()
            emitSwipeSnackbar(
                message = if (inserted) "已加入生词本" else "已在生词本中"
            )
            loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
        }
    }

    fun undoSwipeTooEasy(undoToken: Long) {
        val payload = pendingTooEasyUndo[undoToken] ?: return
        launchSubmit {
            withContext(Dispatchers.IO) {
                repository.restoreProgressSnapshots(
                    wordId = payload.wordId,
                    snapshots = payload.snapshots,
                    rollbackGestureEasy = true
                )
            }
            pendingTooEasyUndo.remove(undoToken)
            answeredInSession = (answeredInSession - 1).coerceAtLeast(0)
            val book = activeBook.value
            if (book != null) {
                loadQueueForBook(book, newWordsLimit.value, shuffleNewWordsEnabled.value, plannedNewWordsEnabled.value)
            }
            emitSwipeSnackbar("已撤销“太简单”")
        }
    }

    fun dismissSwipeUndo(undoToken: Long) {
        pendingTooEasyUndo.remove(undoToken)
    }

    fun consumeSwipeSnackbarEvent(eventId: Long) {
        if (_swipeSnackbarEvent.value?.id == eventId) {
            _swipeSnackbarEvent.value = null
        }
    }

    fun dismissSwipeGuide() {
        viewModelScope.launch {
            settingsRepository.updateSwipeGestureGuideShown(true)
        }
    }

    fun refreshQueue() {
        val book = activeBook.value ?: return
        viewModelScope.launch {
            pendingFailedWordId = null
            sessionSkippedWordIds.clear()
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
        plannedModeEnabled: Boolean
    ) {
        if (book == null) {
            immediateRetryQueue.clear()
            sessionSkippedWordIds.clear()
            pendingTooEasyUndo.clear()
            queueBookId = null
            pendingFailedWordId = null
            answeredInSession = 0
            _dueReviewCount.value = 0
            updateQueue(emptyList())
            return
        }
        if (queueBookId != book.id) {
            immediateRetryQueue.clear()
            sessionSkippedWordIds.clear()
            pendingTooEasyUndo.clear()
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
        val mergedQueue = mergeWithImmediateRetryQueue(queueSnapshot.queue)
            .filterNot { it.id in sessionSkippedWordIds }
        updateQueue(mergedQueue)
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

    private fun updateQueue(list: List<Word>) {
        val oldWordId = _currentWord.value?.id
        val remaining = list.size
        val total = answeredInSession + remaining
        _totalCount.value = total
        _currentIndex.value = when {
            total <= 0 -> 0
            remaining == 0 -> total
            else -> (answeredInSession + 1).coerceAtMost(total)
        }
        val nextWord = list.firstOrNull()
        _currentWord.value = nextWord
        currentWordPresentedAtMs = if (nextWord != null) System.currentTimeMillis() else 0L
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

    private fun skipWordInSession(wordId: Long) {
        sessionSkippedWordIds.add(wordId)
    }

    private fun removeSessionSkippedWord(wordId: Long) {
        sessionSkippedWordIds.remove(wordId)
    }

    private fun emitSwipeSnackbar(
        message: String,
        actionLabel: String? = null,
        undoToken: Long? = null
    ) {
        swipeSnackbarIdCounter += 1
        _swipeSnackbarEvent.value = SwipeSnackbarEvent(
            id = swipeSnackbarIdCounter,
            message = message,
            actionLabel = actionLabel,
            undoToken = undoToken
        )
    }

    private fun registerTooEasyUndo(wordId: Long, snapshots: List<Progress>): Long {
        undoTokenCounter += 1
        val token = undoTokenCounter
        pendingTooEasyUndo[token] = TooEasyUndoPayload(
            wordId = wordId,
            snapshots = snapshots
        )
        return token
    }

    private fun launchSubmit(block: suspend () -> Unit) {
        if (_isSubmitting.value) return
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
        private const val STATE_FAILED = "failed"
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

internal fun shouldShowCompletionCheckInPanel(currentWord: Word?, totalCount: Int): Boolean {
    return currentWord == null && totalCount > 0
}

internal fun buildLearningCheckInUiState(
    weekAggregates: List<DailyStatsAggregate>,
    heatmapAggregates: List<DailyStatsAggregate>,
    currentWord: Word?,
    totalCount: Int,
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
        showCompletionPanel = shouldShowCompletionCheckInPanel(currentWord, totalCount),
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

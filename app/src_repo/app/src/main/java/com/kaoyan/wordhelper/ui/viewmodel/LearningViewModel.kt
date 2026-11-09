package com.kaoyan.wordhelper.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Book
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
import com.kaoyan.wordhelper.data.model.WaitingRewriteRestore
import com.kaoyan.wordhelper.data.model.feedbackRatingForRevision
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
    /** 1.4.6：进度按书独立，撤销只能回滚当时那本书。 */
    val bookId: Long,
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
    // 1.4.6 H：跳过重写后等结果的词。它们不删、不免背，只暂时离开当前队列；
    // 电脑端一生成好就按随机位置插回来（见 onWaitingRewriteReady）。
    private val waitingRewriteWords = LinkedHashMap<Long, Word>()
    // 1.4.6.1 P0：进程重启后从 DB 补回来的等待词。只用于补注册监听和结果到达时插回，
    // 不能混进 waitingRewriteWords —— updateQueue 会把它算进剩余进度，造成重复计数。
    private val restoredWaitingRewriteWords = LinkedHashMap<Long, Word>()
    private val waitingRewriteJobs = mutableMapOf<Long, Job>()
    // 同一 word 的 observer 回调与冷启动补插可能同时到达；只允许一条路径真正插回/清标记。
    private val reinsertingWordIds = mutableSetOf<Long>()
    private var waitingRewritePollJob: Job? = null
    private val _waitingRewriteCount = MutableStateFlow(0)
    private var activeQueue: List<Word> = emptyList()
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

    /**
     * 1.4.6.1：当前书里还没回来的重写数，直接读 tb_rewrite_queue（pending/queued）。
     * 「今日背词完成」与等待卡都以它为准，不再只数用户点过跳过的词。
     */
    private val openRewriteCount: StateFlow<Int> = activeBook
        .flatMapLatest { book ->
            if (book == null) flowOf(0) else rewriteRepository.observeOpenCountForBook(book.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 等待卡上的条数：本地已跳过 + 数据库里已标记未回，取两者最大值，避免重复计数。
     * 只跳过没标记（不该发生）/ 只标记没跳过时也都能数到。
     */
    val waitingRewriteCount: StateFlow<Int> = combine(
        _waitingRewriteCount,
        openRewriteCount
    ) { local, openInDb -> combinedWaitingRewriteCount(local, openInDb) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

    /** 卡背方法标签，同样直接盯库；重写结果落库后胶囊立刻换，不等下一次翻面。 */
    val liveMethod: StateFlow<String?> = combine(activeBook, _currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(null)
            else rewriteRepository.observeMethod(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        _totalCount,
        openRewriteCount
    ) { weekAggregates, heatmapAggregates, currentWord, totalCount, openCount ->
        buildLearningCheckInUiState(
            weekAggregates = weekAggregates,
            heatmapAggregates = heatmapAggregates,
            currentWord = currentWord,
            totalCount = totalCount,
            openRewriteCount = openCount
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
                    "已记下，等电脑连上再发（电脑换过网络的话，地址会变，得重新粘那一行）"
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

    /**
     * 1.4.6：卡背上的 👍/👎。
     * 只记「这条思路有没有帮上」，👎 不会自动重写；同一条评价重复点不会重复入库。
     * 写入永远成功（离线也存本地），联网补发交给 RewriteRepository.sync。
     */
    fun submitRewriteFeedback(rating: String) {
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
                    instruction = "",
                    bookId = bookId,
                    approachRev = approachRev
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
     * 1.4.6 H：跳过当前词，先背下一个。
     * 只在「已点重写、结果还没回来」时可用；词不删、今天仍要背，生成好后由
     * onWaitingRewriteReady 随机插回当前词后 1–3 位。失败继续自动重试。
     */
    fun skipCurrentWordForRewrite() {
        val word = _currentWord.value ?: return
        val mark = currentRewriteMark.value ?: return
        if (mark.status == RewriteStatus.DONE) return
        if (!waitingRewriteWords.containsKey(word.id)) {
            waitingRewriteWords[word.id] = word
            sessionSkippedWordIds.add(word.id)
            observeWaitingRewrite(word)
        }
        refreshWaitingCount()
        val book = activeBook.value ?: return
        viewModelScope.launch {
            // DB20：先把「跳过、待插回」落库，再重载队列。这样即使进程马上被杀，
            // 或者在恢复之前自动同步把结果写成了 done，下次加载仍然能补插回来。
            runCatching { rewriteRepository.markNeedsReinsert(word.id, book.id) }
            loadQueueForBook(
                book,
                newWordsLimit.value,
                shuffleNewWordsEnabled.value,
                plannedNewWordsEnabled.value
            )
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
        clearWaitingRewrites()
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
                // 已经答完这个词：不需要再把它插回队列，清掉 DB20 的待插回标记。
                rewriteRepository.clearNeedsReinsert(word.id)
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
                // 已经答完这个词：不需要再把它插回队列，清掉 DB20 的待插回标记。
                rewriteRepository.clearNeedsReinsert(word.id)
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
                repository.getProgressSnapshotsForWord(word.id, book.id)
            }
            val marked = withContext(Dispatchers.IO) {
                repository.markWordAsTooEasy(word.id, book.id)
            }
            if (!marked) {
                emitSwipeSnackbar("标记失败，请重试")
                return@launchSubmit
            }
            markAnswered()
            val undoToken = registerTooEasyUndo(word.id, book.id, snapshots)
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
                    bookId = payload.bookId,
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
            clearWaitingRewrites()
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
            clearWaitingRewrites()
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
        // P0：进程重启后内存等待集合是空的，必须按 DB 补注册，否则结果 DONE 时词不回队列。
        restoreWaitingRewrites(book.id)
    }

    /**
     * 1.4.6.1 P0（DB20）：按 needs_reinsert 恢复「跳过后还没插回」的词。
     *
     * 关键点是不看 status：
     * - pending/queued：补注册 observer，结果回来时走 onWaitingRewriteReady；
     * - done：冷启动自动同步可能已经先落库，这里直接补插（否则查询永远为空）；
     * - failed：不插，留给下一次重写。
     * 重复 load 由 plan(rows, tracked) 去重；这些词不进 waitingRewriteWords，
     * 避免 updateQueue 重复计入剩余进度，等待条数由 DB 的 observeOpenCountForBook 兜底。
     */
    private suspend fun restoreWaitingRewrites(bookId: Long) {
        val rows = runCatching { rewriteRepository.needsReinsertForBook(bookId) }
            .getOrDefault(emptyList())
        val tracked = HashSet<Long>(
            waitingRewriteWords.size + restoredWaitingRewriteWords.size + waitingRewriteJobs.size
        )
        tracked.addAll(waitingRewriteWords.keys)
        tracked.addAll(restoredWaitingRewriteWords.keys)
        tracked.addAll(waitingRewriteJobs.keys)
        val plan = WaitingRewriteRestore.plan(rows, tracked)

        plan.watchWordIds.forEach { wordId ->
            val word = runCatching { repository.getWordById(wordId) }.getOrNull()
                ?: return@forEach
            restoredWaitingRewriteWords[wordId] = word
            observeWaitingRewrite(word)
        }

        plan.reinsertWordIds.forEach { wordId ->
            if (wordId in reinsertingWordIds) return@forEach
            val word = runCatching { repository.getWordById(wordId) }.getOrNull()
            if (word == null) {
                // 词表里已经没有这个词：别再让这行每次加载都重试。
                runCatching { rewriteRepository.clearNeedsReinsert(wordId) }
                return@forEach
            }
            restoredWaitingRewriteWords.putIfAbsent(wordId, word)
            onWaitingRewriteReady(wordId)
        }

        if (waitingRewriteWords.isNotEmpty() || restoredWaitingRewriteWords.isNotEmpty()) {
            ensureWaitingRewritePoll()
        }
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

    /** 盯住一个等待词的标记；DONE 就插回队列，FAILED 只提示（sync 里会自动重试）。 */
    private fun observeWaitingRewrite(word: Word) {
        waitingRewriteJobs.remove(word.id)?.cancel()
        waitingRewriteJobs[word.id] = viewModelScope.launch {
            rewriteRepository.observeMark(word.id)
                .map { it?.status }
                .distinctUntilChanged()
                .collect { status ->
                    when (status) {
                        RewriteStatus.DONE -> onWaitingRewriteReady(word.id)
                        RewriteStatus.FAILED -> _message.value = "「${word.word}」生成失败，正在自动重试"
                        null -> if (restoredWaitingRewriteWords.remove(word.id) != null) {
                            // 队列行被撤销/删除：别让补注册的词永远挂在等待集合里。
                            waitingRewriteJobs.remove(word.id)?.cancel()
                            if (waitingRewriteWords.isEmpty() && restoredWaitingRewriteWords.isEmpty()) {
                                waitingRewritePollJob?.cancel()
                                waitingRewritePollJob = null
                            }
                        }
                        else -> Unit
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
     * 生成好了：从等待集合拿掉，随机插到当前词后 1–3 位（词少就贴到队尾）。
     *
     * observer 回调与冷启动 done 行补插可能同时到达；reinsertingWordIds 保证同一
     * 个词只走一条路径。无论插没插，最后都清 needs_reinsert，避免明天重复插。
     */
    private suspend fun onWaitingRewriteReady(wordId: Long) {
        if (!reinsertingWordIds.add(wordId)) return
        val fromSessionWait = waitingRewriteWords.remove(wordId)
        val word = fromSessionWait ?: restoredWaitingRewriteWords.remove(wordId)
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
            val bookId = activeBook.value?.id ?: 0L
            val progress = if (bookId > 0L) {
                runCatching { repository.getProgress(wordId, bookId) }.getOrNull()
            } else {
                null
            }
            val answeredToday = progress?.lastReviewTime?.let { lastReview ->
                lastReview > 0L &&
                    DateUtils.currentLearningDate(lastReview) == DateUtils.currentLearningDate()
            } == true
            val shouldInsert = WaitingRewriteRestore.shouldInsertOnReady(
                wordId = wordId,
                currentWordId = _currentWord.value?.id,
                queuedWordIds = activeQueue.mapTo(HashSet()) { it.id },
                answeredToday = answeredToday
            )

            if (shouldInsert) {
                val base = activeQueue.filterNot { it.id == wordId }
                val insertIndex = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
                    queueSize = base.size,
                    randomSource = retryQueueRandomSource
                )
                val queue = base.toMutableList().apply { add(insertIndex, word) }
                updateQueue(queue)
            } else if (fromSessionWait != null) {
                // 词已经在队列里/今天已经答过：只需把它的等待名额从进度里扣掉。
                updateQueue(activeQueue)
            }
        } finally {
            runCatching { rewriteRepository.clearNeedsReinsert(wordId) }
            reinsertingWordIds.remove(wordId)
            collectorJob?.cancel()
            if (waitingRewriteWords.isEmpty() && restoredWaitingRewriteWords.isEmpty()) {
                waitingRewritePollJob?.cancel()
                waitingRewritePollJob = null
            }
        }
    }

    private fun refreshWaitingCount() {
        _waitingRewriteCount.value = waitingRewriteWords.size
    }

    private fun clearWaitingRewrites() {
        waitingRewriteJobs.values.forEach { it.cancel() }
        waitingRewriteJobs.clear()
        waitingRewriteWords.clear()
        restoredWaitingRewriteWords.clear()
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

    private fun registerTooEasyUndo(wordId: Long, bookId: Long, snapshots: List<Progress>): Long {
        undoTokenCounter += 1
        val token = undoTokenCounter
        pendingTooEasyUndo[token] = TooEasyUndoPayload(
            wordId = wordId,
            bookId = bookId,
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

internal fun shouldShowCompletionCheckInPanel(
    currentWord: Word?,
    totalCount: Int,
    openRewriteCount: Int = 0
): Boolean {
    // 词背完了还不够：当前书还有重写在排队/等结果时，先别宣布「今日背词完成」。
    return currentWord == null && totalCount > 0 && openRewriteCount <= 0
}

/** 等待卡条数：本地等待集合与库里未完成重写取最大值，既不漏「只标记没跳过」也不重复计数。 */
internal fun combinedWaitingRewriteCount(localWaitingCount: Int, openRewriteCount: Int): Int =
    maxOf(localWaitingCount.coerceAtLeast(0), openRewriteCount.coerceAtLeast(0))

internal fun buildLearningCheckInUiState(
    weekAggregates: List<DailyStatsAggregate>,
    heatmapAggregates: List<DailyStatsAggregate>,
    currentWord: Word?,
    totalCount: Int,
    openRewriteCount: Int = 0,
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
            openRewriteCount = openRewriteCount
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

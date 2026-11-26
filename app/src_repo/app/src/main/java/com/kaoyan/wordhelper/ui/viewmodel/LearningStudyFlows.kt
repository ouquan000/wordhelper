package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.model.PronunciationSource
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.SettingsRepository
import com.kaoyan.wordhelper.data.repository.TodayNewWordsPlan
import com.kaoyan.wordhelper.data.repository.WordRepository
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.withoutBuiltinFull
import com.kaoyan.wordhelper.data.repository.pressureDismissedDay
import com.kaoyan.wordhelper.data.repository.pressureVisible
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val DEFAULT_NEW_WORDS_LIMIT = 20

@OptIn(ExperimentalCoroutinesApi::class)
internal class LearningStudyFlows(
    private val viewModelScope: CoroutineScope,
    private val repository: WordRepository,
    private val settingsRepository: SettingsRepository,
    private val rewriteRepository: RewriteRepository,
    private val session: LearningSessionState
) {
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
            val zone = ZoneId.systemDefault()
            val today = DateUtils.currentLearningDate(zoneId = zone)
            emit(today)
            val boundary = today.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
            delay((boundary - System.currentTimeMillis()).coerceIn(1L, 60_000L))
        }
    }

    val todayPendingRewriteCount: StateFlow<Int> = activeBook
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
        session._waitingRewriteCount,
        todayPendingRewriteCount
    ) { local, pendingToday -> combinedWaitingRewriteCount(local, pendingToday) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isNewWordsBook: StateFlow<Boolean> = activeBook
        .map { it?.type == Book.TYPE_NEW_WORDS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isInNewWords: StateFlow<Boolean> = repository.getNewWordIdsFlow()
        .combine(session._currentWord) { ids, word -> word?.let { ids.contains(it.id) } ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentProgress: StateFlow<Progress?> = combine(activeBook, session._currentWord) { book, word -> book to word }
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
    val newWordsLimit: StateFlow<Int> = settingsRepository.settingsFlow
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
    val shuffleNewWordsEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.newWordsShuffleEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val plannedNewWordsEnabled: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.plannedNewWordsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val todayNewWordsPlan: StateFlow<TodayNewWordsPlan> = settingsRepository.todayNewWordsPlanFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayNewWordsPlan())
    val canRelieveReviewPressure: StateFlow<Boolean> = combine(
        activeBook,
        reviewPressureReliefEnabled,
        reviewPressureDailyCap,
        session._dueReviewCount
    ) { book, enabled, cap, dueCount ->
        LearningSessionRules.canRelievePressure(enabled, book != null && book.type != Book.TYPE_NEW_WORDS, cap, dueCount)
    }.combine(combine(learningDateTicker, settingsRepository.dataStore.pressureDismissedDay, ::pressureVisible)) { eligible, visible ->
        eligible && visible
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val checkInState: StateFlow<LearningCheckInUiState> = combine(
        learningWeekStatsFlow,
        learningHeatmapStatsFlow,
        session._currentWord,
        session._totalCount,
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
}

package com.kaoyan.wordhelper.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookDailyStats
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.data.model.AIPresets
import com.kaoyan.wordhelper.data.model.MethodCount
import com.kaoyan.wordhelper.data.repository.DarkMode
import com.kaoyan.wordhelper.data.repository.UserSettings
import com.kaoyan.wordhelper.util.DatabaseBackupManager
import com.kaoyan.wordhelper.util.DateUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 学习统计卡片的两种口径。 */
enum class StatsScope {
    /** 全局：tb_daily_stats 的事件次数，跨全部词书。 */
    GLOBAL,

    /** 当前词书：tb_progress 的去重词数，只算正在学的这一本。 */
    BOOK
}

/**
 * 「当前词书」视图的指标。
 * 与 ProfileStats 是两套口径（这里是「词」，全局是「次」），**不能相加、不能互相换算**。
 */
data class BookStats(
    val bookName: String = "",
    val hasBook: Boolean = false,
    val learnedCount: Int = 0,
    val masteredCount: Int = 0,
    val dueCount: Int = 0,
    /** 无复习记录时为 null，UI 显示「—」而不是 NaN。 */
    val accuracyPercent: Int? = null,
    val todayTouchedCount: Int = 0
)

data class ProfileStats(
    val streakDays: Int,
    val todayStudyCount: Int,
    val weekStudyCount: Int,
    val todaySpellCount: Int,
    val weekSpellCount: Int,
    val todayCheckedIn: Boolean,
    val todayManualCheckedIn: Boolean,
    val weekCheckInDays: Int,
    val todayCheckInTimeText: String,
    val weekLineData: List<LineChartEntry>,
    val heatmap: List<HeatmapCell>
)

data class AiLabSummary(
    val status: String = "未配置 API Key",
    val enabled: Boolean = false,
    val isConfigured: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as KaoyanWordApp).repository
    private val settingsRepository = (application as KaoyanWordApp).settingsRepository
    private val aiConfigRepository = (application as KaoyanWordApp).aiConfigRepository
    private val database = (application as KaoyanWordApp).database

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /** 还没被电脑处理完的重写标记数（1.4.3）。 */
    val rewritePendingCount: StateFlow<Int> =
        (application as KaoyanWordApp).rewriteRepository.observeOpenCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- 学习记录同步（1.4.4）：手机本地排队，用户点一下才和电脑对一次。
    private val syncRepository = (application as KaoyanWordApp).syncRepository

    private val _syncPending = MutableStateFlow(0)

    /** 还没发出去的进度条数；0 = 手机就是最新的。 */
    val syncPending: StateFlow<Int> = _syncPending.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    fun refreshSyncPending() {
        viewModelScope.launch {
            _syncPending.value = runCatching { syncRepository.pendingCount() }.getOrDefault(0)
        }
    }

    /** 手动同步一次：先拉后推再拉；冲突按最后复习时间判，不弹窗。 */
    fun syncLearningRecords() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val result = syncRepository.syncOnce()
            _syncMessage.value = result.getOrElse { it.message ?: "同步失败，稍后再试" }
            _syncPending.value = runCatching { syncRepository.pendingCount() }.getOrDefault(0)
            _syncing.value = false
        }
    }

    private val weekRange = DateUtils.learningDateRange(days = 7)

    private val heatmapRange = DateUtils.learningHeatmapRange()

    private val weekStatsFlow = repository.getDailyStatsAggregated(weekRange.first, weekRange.second)
    private val heatmapStatsFlow = repository.getDailyStatsAggregated(heatmapRange.first, heatmapRange.second)

    val stats: StateFlow<ProfileStats> = combine(weekStatsFlow, heatmapStatsFlow) { weekAggregates, heatmapAggregates ->
        buildStats(weekAggregates, heatmapAggregates)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyStats())

    /** 学习统计卡片的当前口径，切到「当前词书」时由 [bookStats] 提供数字。 */
    private val _statsScope = MutableStateFlow(StatsScope.GLOBAL)
    val statsScope: StateFlow<StatsScope> = _statsScope.asStateFlow()

    /** 「当前词书」= 正在学的那本；没有激活词书时退回第一本非「生词本」，再退回第一本。 */
    private val currentBookFlow: Flow<Book?> = combine(
        repository.getActiveBookFlow(),
        repository.getAllBooks()
    ) { active, books ->
        active ?: books.firstOrNull { it.type != Book.TYPE_NEW_WORDS } ?: books.firstOrNull()
    }

    /**
     * 「当前词书」视图的 5 个指标。
     * 全部走 DAO 的 COUNT/AVG 聚合（每本 5000+ 词），切勿换成把整本 Progress 实体化再数。
     */
    val bookStats: StateFlow<BookStats> = currentBookFlow.flatMapLatest { book ->
        if (book == null) {
            flowOf(BookStats())
        } else {
            val (dayStart, dayEnd) = learningDayRange()
            combine(
                repository.getLearnedCount(book.id),
                repository.getMasteredCount(book.id),
                repository.getBookDueCount(book.id),
                repository.getBookAccuracy(book.id),
                repository.getBookTodayTouchedCount(book.id, dayStart, dayEnd)
            ) { learned, mastered, due, accuracy, todayTouched ->
                BookStats(
                    bookName = book.name,
                    hasBook = true,
                    learnedCount = learned,
                    masteredCount = mastered,
                    dueCount = due,
                    accuracyPercent = accuracy?.let { percentOf(it) },
                    todayTouchedCount = todayTouched
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookStats())

    /**
     * 「按记忆方法」分布，跟着统计口径一起切：
     * 全局 = 已学词按 method 去重计数；当前词书 = 只数这一本。
     */
    val methodCounts: StateFlow<List<MethodCount>> = _statsScope.flatMapLatest { scope ->
        if (scope == StatsScope.GLOBAL) {
            repository.getGlobalMethodCounts()
        } else {
            currentBookFlow.flatMapLatest { book ->
                if (book == null) flowOf(emptyList()) else repository.getBookMethodCounts(book.id)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前词书的一周趋势（1.4.5）。
     * 1.4.5 之前没有按书记账，所以曲线从升级当天开始——界面要写「从今天起」，
     * 不然看起来像历史数据丢了。
     */
    val bookLineData: StateFlow<List<LineChartEntry>> = _statsScope.flatMapLatest { scope ->
        if (scope != StatsScope.BOOK) {
            flowOf(emptyList())
        } else {
            currentBookFlow.flatMapLatest { book ->
                if (book == null) flowOf(emptyList())
                else flow {
                    val rows = repository.getBookDailyStats(book.id, weekRange.first.toString())
                    emit(buildBookLineData(rows))
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setStatsScope(scope: StatsScope) {
        _statsScope.value = scope
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _aiLabSummary = MutableStateFlow(AiLabSummary())
    val aiLabSummary: StateFlow<AiLabSummary> = _aiLabSummary.asStateFlow()

    init {
        refreshAiSummary()
    }

    fun updateNewWordsLimit(limit: Int) {
        viewModelScope.launch {
            settingsRepository.updateNewWordsLimit(limit)
        }
    }

    fun updateFontScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.updateFontScale(scale)
        }
    }

    fun updateDarkMode(mode: DarkMode) {
        viewModelScope.launch {
            settingsRepository.updateDarkMode(mode)
        }
    }

    fun updateReviewPressureReliefEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateReviewPressureReliefEnabled(enabled)
        }
    }

    fun updateReviewPressureDailyCap(cap: Int) {
        viewModelScope.launch {
            settingsRepository.updateReviewPressureDailyCap(cap)
        }
    }

    fun updateAlgorithmV4Enabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAlgorithmV4Enabled(enabled)
            if (enabled) {
                repository.repairMasteredStatusForV4()
            }
        }
    }

    fun updateMlAdaptiveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateMlAdaptiveEnabled(enabled)
        }
    }

    fun updatePlannedNewWordsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updatePlannedNewWordsEnabled(enabled)
        }
    }

    /** 1.4.4：两本内置大词库默认隐藏，开回来才显示并重新导入。 */
    val showBuiltinFullBooks: StateFlow<Boolean> = settingsRepository.showBuiltinFullBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun updateShowBuiltinFullBooks(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowBuiltinFullBooks(show)
        }
    }

    /** 卡背结构地址（1.4.2）；留空就只用手机本地结构。 */
    fun updateCardBackUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.updateCardBackUrl(url)
        }
    }

    /** 电脑端口令（1.4.4 安全收口）；空 = 不带头，电脑本机访问才够用。 */
    fun updateCardBackToken(token: String) {
        viewModelScope.launch {
            settingsRepository.updateCardBackToken(token)
        }
    }

    fun exportDatabase(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val result = DatabaseBackupManager.exportDatabase(getApplication(), database, uri)
            _message.value = if (result.isSuccess) {
                "备份完成"
            } else {
                "备份失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }

    fun importDatabase(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val result = DatabaseBackupManager.importDatabase(getApplication(), database, uri)
            _message.value = if (result.isSuccess) {
                "恢复完成，请重启应用"
            } else {
                "恢复失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun refreshAiSummary() {
        viewModelScope.launch {
            val config = aiConfigRepository.getConfig()
            val hasApiKey = config.apiKey.isNotBlank()
            val providerName = AIPresets.inferProviderName(config.apiBaseUrl)
            val status = when {
                !hasApiKey -> "未配置 API Key"
                !config.enabled -> "已配置（未启用）"
                providerName == AIPresets.CUSTOM_NAME -> "已启用"
                else -> "$providerName 已启用"
            }
            _aiLabSummary.value = AiLabSummary(
                status = status,
                enabled = config.enabled,
                isConfigured = hasApiKey
            )
        }
    }

    private fun buildStats(
        weekAggregates: List<DailyStatsAggregate>,
        heatmapAggregates: List<DailyStatsAggregate>
    ): ProfileStats {
        val today = DateUtils.currentLearningDate()
        val weekLineData = buildLineData(weekRange.first, weekRange.second, weekAggregates)
        val todayAggregate = weekAggregates.firstOrNull { parseDate(it.date) == today }
        val todayStudyCount = todayAggregate?.let { activityCount(it) } ?: 0
        val todaySpellCount = todayAggregate?.spellPracticeCount ?: 0
        val weekStudyCount = weekAggregates.sumOf { activityCount(it) }
        val weekSpellCount = weekAggregates.sumOf { it.spellPracticeCount }
        val heatmap = buildHeatmap(heatmapRange.first, heatmapRange.second, heatmapAggregates)
        val checkInDates = heatmapAggregates.filter { isCheckedIn(it) }
            .mapNotNull { parseDate(it.date) }
            .toSet()
        val streakDays = computeStreak(today, checkInDates)
        val todayCheckedIn = todayAggregate?.let { isCheckedIn(it) } ?: false
        val todayManualCheckedIn = (todayAggregate?.checkInCount ?: 0) > 0
        val weekCheckInDays = weekAggregates.count { isCheckedIn(it) }
        val todayCheckInTimeText = if (todayManualCheckedIn && (todayAggregate?.lastCheckInTime ?: 0L) > 0L) {
            DateUtils.formatDateTime(todayAggregate?.lastCheckInTime ?: 0L)
        } else {
            ""
        }
        return ProfileStats(
            streakDays = streakDays,
            todayStudyCount = todayStudyCount,
            weekStudyCount = weekStudyCount,
            todaySpellCount = todaySpellCount,
            weekSpellCount = weekSpellCount,
            todayCheckedIn = todayCheckedIn,
            todayManualCheckedIn = todayManualCheckedIn,
            weekCheckInDays = weekCheckInDays,
            todayCheckInTimeText = todayCheckInTimeText,
            weekLineData = weekLineData,
            heatmap = heatmap
        )
    }

    private fun buildLineData(
        startDate: LocalDate,
        endDate: LocalDate,
        aggregates: List<DailyStatsAggregate>
    ): List<LineChartEntry> {
        val map = aggregates.mapNotNull { aggregate ->
            parseDate(aggregate.date)?.let { it to aggregate }
        }.toMap()
        val result = mutableListOf<LineChartEntry>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            val aggregate = map[cursor]
            result.add(
                LineChartEntry(
                    date = cursor,
                    newCount = aggregate?.newWordsCount ?: 0,
                    reviewCount = aggregate?.reviewWordsCount ?: 0
                )
            )
            cursor = cursor.plusDays(1)
        }
        return result
    }

    private fun buildBookLineData(rows: List<BookDailyStats>): List<LineChartEntry> {
        val map = rows.mapNotNull { row -> parseDate(row.date)?.let { it to row } }.toMap()
        val result = mutableListOf<LineChartEntry>()
        var cursor = weekRange.first
        while (!cursor.isAfter(weekRange.second)) {
            val row = map[cursor]
            result.add(
                LineChartEntry(
                    date = cursor,
                    newCount = row?.newCount ?: 0,
                    reviewCount = row?.reviewCount ?: 0
                )
            )
            cursor = cursor.plusDays(1)
        }
        return result
    }

    private fun buildHeatmap(
        startDate: LocalDate,
        endDate: LocalDate,
        aggregates: List<DailyStatsAggregate>
    ): List<HeatmapCell> {
        val map = aggregates.mapNotNull { aggregate ->
            parseDate(aggregate.date)?.let { it to aggregate }
        }.toMap()
        val maxCount = aggregates.maxOfOrNull { aggregate -> activityCount(aggregate) } ?: 0
        val displayEnd = endDate.with(DayOfWeek.SUNDAY)
        val result = mutableListOf<HeatmapCell>()
        var cursor = startDate
        while (!cursor.isAfter(displayEnd)) {
            val inRange = !cursor.isAfter(endDate)
            val aggregate = if (inRange) map[cursor] else null
            val count = aggregate?.let { activityCount(it) } ?: 0
            val level = if (inRange) computeLevel(count, maxCount) else 0
            result.add(
                HeatmapCell(
                    date = cursor,
                    count = count,
                    level = level,
                    inRange = inRange
                )
            )
            cursor = cursor.plusDays(1)
        }
        return result
    }

    private fun computeLevel(count: Int, maxCount: Int): Int {
        if (count <= 0 || maxCount <= 0) return 0
        val ratio = count.toFloat() / maxCount.toFloat()
        return when {
            ratio <= 0.25f -> 1
            ratio <= 0.5f -> 2
            ratio <= 0.75f -> 3
            else -> 4
        }
    }

    private fun computeStreak(today: LocalDate, studyDates: Set<LocalDate>): Int {
        var streak = 0
        var cursor = today
        while (studyDates.contains(cursor)) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun activityCount(aggregate: DailyStatsAggregate): Int {
        return aggregate.newWordsCount + aggregate.reviewWordsCount + aggregate.spellPracticeCount
    }

    private fun isCheckedIn(aggregate: DailyStatsAggregate): Boolean {
        return aggregate.checkInCount > 0
    }

    private fun parseDate(raw: String): LocalDate? {
        return runCatching { LocalDate.parse(raw, DATE_FORMATTER) }.getOrNull()
    }

    /** 0f..1f 的正确率 → 0..100 的整数百分比；先夹范围，避免异常数据出现负值或超过 100%。 */
    private fun percentOf(ratio: Float): Int {
        return (ratio.coerceIn(0f, 1f) * 100f).roundToInt()
    }

    /**
     * 当前学习日的毫秒区间 [起, 止)。
     * 学习日以 04:00 为界（与 DateUtils 的 DAY_REFRESH_HOUR 同一口径）；DateUtils 没有对外暴露
     * 这个换算，所以在这里补一个——改刷新小时时两处要一起改。
     */
    private fun learningDayRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = DateUtils.currentLearningDate(zoneId = zone)
            .atTime(LEARNING_DAY_REFRESH_HOUR, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        return start to (start + ONE_DAY_MILLIS)
    }

    private fun emptyStats(): ProfileStats {
        return ProfileStats(
            streakDays = 0,
            todayStudyCount = 0,
            weekStudyCount = 0,
            todaySpellCount = 0,
            weekSpellCount = 0,
            todayCheckedIn = false,
            todayManualCheckedIn = false,
            weekCheckInDays = 0,
            todayCheckInTimeText = "",
            weekLineData = emptyList(),
            heatmap = emptyList()
        )
    }

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

        /** 学习日刷新小时，必须与 DateUtils 的 DAY_REFRESH_HOUR 保持一致。 */
        private const val LEARNING_DAY_REFRESH_HOUR = 4

        private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

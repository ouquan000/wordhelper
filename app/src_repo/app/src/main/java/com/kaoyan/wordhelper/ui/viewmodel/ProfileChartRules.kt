package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.BookDailyStats
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.util.DateUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal object ProfileChartRules {
    private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

    /** 学习日刷新小时，必须与 DateUtils 的 DAY_REFRESH_HOUR 保持一致。 */
    private const val LEARNING_DAY_REFRESH_HOUR = 4

    private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000

    fun buildLineData(
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

    fun buildBookLineData(weekRange: Pair<LocalDate, LocalDate>, rows: List<BookDailyStats>): List<LineChartEntry> {
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

    fun buildHeatmap(
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
            val level = if (inRange) heatmapLevel(count, maxCount) else 0
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

    fun computeStreak(today: LocalDate, studyDates: Set<LocalDate>): Int {
        var streak = 0
        var cursor = today
        while (studyDates.contains(cursor)) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    fun activityCount(aggregate: DailyStatsAggregate): Int {
        return aggregate.newWordsCount + aggregate.reviewWordsCount + aggregate.spellPracticeCount
    }

    fun isCheckedIn(aggregate: DailyStatsAggregate): Boolean {
        return aggregate.checkInCount > 0
    }

    fun parseDate(raw: String): LocalDate? {
        return runCatching { LocalDate.parse(raw, DATE_FORMATTER) }.getOrNull()
    }

    /** 0f..1f 的正确率 → 0..100 的整数百分比；先夹范围，避免异常数据出现负值或超过 100%。 */
    fun percentOf(ratio: Float): Int {
        return (ratio.coerceIn(0f, 1f) * 100f).roundToInt()
    }

    /**
     * 当前学习日的毫秒区间 [起, 止)。
     * 学习日以 04:00 为界（与 DateUtils 的 DAY_REFRESH_HOUR 同一口径）；DateUtils 没有对外暴露
     * 这个换算，所以在这里补一个——改刷新小时时两处要一起改。
     */
    fun learningDayRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = DateUtils.currentLearningDate(zoneId = zone)
            .atTime(LEARNING_DAY_REFRESH_HOUR, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        return start to (start + ONE_DAY_MILLIS)
    }

    /**
     * 当前词书热力图的纯计算（1.4.6.1）。
     *
     * 一天的量 = 新词 + 复习 + 拼写，与全局热力图同口径；但 rows 只能是这本书的行，
     * 绝不能拿全局 tb_daily_stats 冒充 —— 1.4.5 之前没有按书记账，升级前的格子就是 0。
     *
     * 抽成顶层函数是为了单测：不用起 Room，也不用起 ViewModel。
     */
    fun bookHeatmap(
        startDate: LocalDate,
        endDate: LocalDate,
        rows: List<BookDailyStats>
    ): List<HeatmapCell> {
        val byDate = rows.mapNotNull { row ->
            runCatching { LocalDate.parse(row.date) }.getOrNull()
                ?.takeIf { !it.isBefore(startDate) && !it.isAfter(endDate) }
                ?.let { date -> date to row }
        }.toMap()
        // 分档只看窗口内的量：窗口外的历史行画不出来，不该把当前这张图压暗。
        val maxCount = byDate.values.maxOfOrNull { it.newCount + it.reviewCount + it.spellCount } ?: 0
        // 和全局热力图一致：多画到本周日，让周一~周日按列对齐；超出 endDate 的格子标记 inRange=false。
        val displayEnd = endDate.with(DayOfWeek.SUNDAY)
        val result = mutableListOf<HeatmapCell>()
        var cursor = startDate
        while (!cursor.isAfter(displayEnd)) {
            val inRange = !cursor.isAfter(endDate)
            val count = if (inRange) {
                byDate[cursor]?.let { row -> row.newCount + row.reviewCount + row.spellCount } ?: 0
            } else {
                0
            }
            result.add(
                HeatmapCell(
                    date = cursor,
                    count = count,
                    level = if (inRange) heatmapLevel(count, maxCount) else 0,
                    inRange = inRange
                )
            )
            cursor = cursor.plusDays(1)
        }
        return result
    }

    /** 与全局热力图同一分档：0 / 25% / 50% / 75% 四档。 */
    private fun heatmapLevel(count: Int, maxCount: Int): Int {
        if (count <= 0 || maxCount <= 0) return 0
        val ratio = count.toFloat() / maxCount.toFloat()
        return when {
            ratio <= 0.25f -> 1
            ratio <= 0.5f -> 2
            ratio <= 0.75f -> 3
            else -> 4
        }
    }
}

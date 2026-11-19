package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.ml.ui.MemoryHeatmapEntry
import com.kaoyan.wordhelper.util.FsrsScheduler
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private const val MILLIS_PER_DAY = 86_400_000f
private const val MIN_ELAPSED_DAYS = 0.01f
private const val HEATMAP_DAYS = 7
private const val HEATMAP_HOURS = 24

/**
 * FSRS-5 统计页指标（1.4.8）。
 *
 * 只统计真正复习过的行：reviewCount > 0 或 fsrsStability > 0。
 * 纯数据类，由 [buildFsrsInsights] 从 Progress 列表推导，便于单元测试。
 */
internal data class FsrsInsights(
    val hasData: Boolean = false,
    val averageRetrievability: Float = 0f,
    val averageStability: Float = 0f,
    val dueCount: Int = 0,
    val lapseCount: Int = 0,
    val nextIntervalDays: Int = 0,
    val heatmap: List<MemoryHeatmapEntry> = emptyList()
)

/**
 * 把当前词书的 Progress 行折算成统计页指标。
 *
 * - 平均 R：FsrsScheduler.retrievability(elapsedDays, stability)，elapsedDays = (now - lastReviewTime) / 1 天；
 *   lastReviewTime <= 0 时按 0.01 天兜底，避免除零把 R 拉到 0。
 * - 平均 S：fsrsStability 均值（只算已建立 FSRS 状态的行）。
 * - 今日到期：nextReviewTime <= 今天 23:59:59.999（按 [zoneId]）。
 * - 累计遗忘：fsrsLapses 求和。
 * - 热力图：按 lastReviewTime 的星期/小时分桶，效率 = 1 - 遗忘数 / max(复习数, 1)，空桶用平均 R 填充。
 */
internal fun buildFsrsInsights(
    rows: List<Progress>,
    now: Long,
    retention: Float,
    zoneId: ZoneId = ZoneId.systemDefault()
): FsrsInsights {
    val eligible = rows.filter { it.reviewCount > 0 || it.fsrsStability > 0f }
    if (eligible.isEmpty()) return FsrsInsights()

    val stabilityRows = eligible.filter { it.fsrsStability > 0f }
    val averageStability = if (stabilityRows.isEmpty()) {
        0f
    } else {
        stabilityRows.map { it.fsrsStability }.average().toFloat()
    }
    val averageRetrievability = if (stabilityRows.isEmpty()) {
        0f
    } else {
        stabilityRows.map { row ->
            FsrsScheduler.retrievability(
                elapsedDays = elapsedDays(row.lastReviewTime, now),
                stability = row.fsrsStability
            )
        }.average().toFloat()
    }

    val endOfToday = Instant.ofEpochMilli(now)
        .atZone(zoneId)
        .toLocalDate()
        .atTime(LocalTime.MAX)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
    val dueCount = eligible.count { it.nextReviewTime in 1..endOfToday }
    val lapseCount = eligible.sumOf { it.fsrsLapses }

    val reviewTotals = Array(HEATMAP_DAYS) { IntArray(HEATMAP_HOURS) }
    val lapseTotals = Array(HEATMAP_DAYS) { IntArray(HEATMAP_HOURS) }
    eligible.forEach { row ->
        if (row.lastReviewTime <= 0L) return@forEach
        val reviewedAt = Instant.ofEpochMilli(row.lastReviewTime).atZone(zoneId)
        val dayIndex = reviewedAt.dayOfWeek.value - 1
        val hour = reviewedAt.hour
        reviewTotals[dayIndex][hour] += row.reviewCount.coerceAtLeast(0)
        lapseTotals[dayIndex][hour] += row.fsrsLapses.coerceAtLeast(0)
    }

    val fallbackEfficiency = averageRetrievability.coerceIn(0f, 1f)
    val heatmap = buildList(HEATMAP_DAYS * HEATMAP_HOURS) {
        for (day in 0 until HEATMAP_DAYS) {
            for (hour in 0 until HEATMAP_HOURS) {
                val reviews = reviewTotals[day][hour]
                val efficiency = if (reviews > 0) {
                    1f - lapseTotals[day][hour].toFloat() / reviews.toFloat()
                } else {
                    fallbackEfficiency
                }
                add(
                    MemoryHeatmapEntry(
                        dayOfWeek = day,
                        hour = hour,
                        efficiency = efficiency.coerceIn(0f, 1f)
                    )
                )
            }
        }
    }

    val nextIntervalDays = if (averageStability > 0f) {
        FsrsScheduler.nextIntervalDays(averageStability, retention)
    } else {
        0
    }

    return FsrsInsights(
        hasData = true,
        averageRetrievability = averageRetrievability,
        averageStability = averageStability,
        dueCount = dueCount,
        lapseCount = lapseCount,
        nextIntervalDays = nextIntervalDays,
        heatmap = heatmap
    )
}

private fun elapsedDays(lastReviewTime: Long, now: Long): Float {
    if (lastReviewTime <= 0L) return MIN_ELAPSED_DAYS
    val days = (now - lastReviewTime) / MILLIS_PER_DAY
    return days.coerceAtLeast(MIN_ELAPSED_DAYS)
}

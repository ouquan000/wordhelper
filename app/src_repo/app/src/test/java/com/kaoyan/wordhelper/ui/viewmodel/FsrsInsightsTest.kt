package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.util.FsrsScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * FSRS 统计口径单测：空表、单行 elapsed/R、lapses 热力图、due 边界。
 */
class FsrsInsightsTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 5, 20, 12, 0)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `空表返回空指标`() {
        val insights = buildFsrsInsights(emptyList(), now, 0.90f, zoneId)

        assertFalse(insights.hasData)
        assertEquals(0f, insights.averageRetrievability, 0.0001f)
        assertEquals(0f, insights.averageStability, 0.0001f)
        assertEquals(0, insights.dueCount)
        assertEquals(0, insights.lapseCount)
        assertTrue(insights.heatmap.isEmpty())
    }

    @Test
    fun `未复习过的行不参与统计`() {
        val untouched = Progress(
            wordId = 1L,
            bookId = 1L,
            reviewCount = 0,
            fsrsStability = 0f,
            lastReviewTime = 0L
        )

        val insights = buildFsrsInsights(listOf(untouched), now, 0.90f, zoneId)

        assertFalse(insights.hasData)
        assertEquals(0, insights.dueCount)
    }

    @Test
    fun `单行按真实 elapsed 计算 R 与 S`() {
        val lastReviewTime = now - 10L * 86_400_000L
        val row = Progress(
            wordId = 1L,
            bookId = 1L,
            reviewCount = 3,
            fsrsStability = 10f,
            fsrsDifficulty = 5f,
            fsrsLapses = 0,
            lastReviewTime = lastReviewTime,
            nextReviewTime = 0L
        )

        val insights = buildFsrsInsights(listOf(row), now, 0.90f, zoneId)
        val expectedR = FsrsScheduler.retrievability(elapsedDays = 10f, stability = 10f)

        assertTrue(insights.hasData)
        assertEquals(10f, insights.averageStability, 0.0001f)
        assertEquals(expectedR, insights.averageRetrievability, 0.0001f)

        // 有复习记录的桶：效率 = 1 - 遗忘/复习（本例遗忘 0，故为 1）；空桶标 hasData=false，不着色。
        val reviewedAt = Instant.ofEpochMilli(lastReviewTime).atZone(zoneId)
        val bucketIndex = (reviewedAt.dayOfWeek.value - 1) * 24 + reviewedAt.hour
        val emptyIndex = if (bucketIndex == 0) 1 else 0
        assertEquals(7 * 24, insights.heatmap.size)
        assertEquals(1f, insights.heatmap[bucketIndex].efficiency, 0.0001f)
        assertTrue(insights.heatmap[bucketIndex].hasData)
        assertFalse(insights.heatmap[emptyIndex].hasData)
        assertEquals(0f, insights.heatmap[emptyIndex].efficiency, 0.0001f)
    }

    @Test
    fun `热力图按遗忘比例降低效率`() {
        val lastReviewTime = now - 86_400_000L
        val row = Progress(
            wordId = 2L,
            bookId = 1L,
            reviewCount = 4,
            fsrsStability = 5f,
            fsrsLapses = 1,
            lastReviewTime = lastReviewTime
        )

        val insights = buildFsrsInsights(listOf(row), now, 0.90f, zoneId)
        val reviewedAt = Instant.ofEpochMilli(lastReviewTime).atZone(zoneId)
        val bucketIndex = (reviewedAt.dayOfWeek.value - 1) * 24 + reviewedAt.hour

        assertEquals(1, insights.lapseCount)
        assertEquals(1f - 1f / 4f, insights.heatmap[bucketIndex].efficiency, 0.0001f)
    }

    @Test
    fun `今日到期边界含当天 23点59分59秒999`() {
        val endOfToday = Instant.ofEpochMilli(now)
            .atZone(zoneId)
            .toLocalDate()
            .atTime(LocalTime.MAX)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val base = Progress(wordId = 0L, bookId = 1L, reviewCount = 1, fsrsStability = 4f)

        val insights = buildFsrsInsights(
            listOf(
                base.copy(wordId = 1L, nextReviewTime = endOfToday),
                base.copy(wordId = 2L, nextReviewTime = endOfToday + 1L),
                base.copy(wordId = 3L, nextReviewTime = now - 86_400_000L)
            ),
            now,
            0.90f,
            zoneId
        )

        assertEquals(2, insights.dueCount)
        // 90% 留存率下，S=4 天的下次间隔应至少 1 天。
        assertTrue(insights.nextIntervalDays >= 1)
        assertEquals(
            FsrsScheduler.nextIntervalDays(4f, 0.90f),
            insights.nextIntervalDays
        )
    }
}

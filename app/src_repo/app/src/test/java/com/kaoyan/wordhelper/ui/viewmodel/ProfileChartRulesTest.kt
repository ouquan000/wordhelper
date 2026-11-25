package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.BookDailyStats
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileChartRulesTest {
    private val monday = LocalDate.of(2026, 8, 17)

    @Test
    fun streakRequiresTodayAndStopsAtTheFirstGap() {
        val days = setOf(monday, monday.minusDays(1), monday.minusDays(3))
        assertEquals(2, ProfileChartRules.computeStreak(monday, days))
        assertEquals(0, ProfileChartRules.computeStreak(monday.plusDays(1), days))
    }

    @Test
    fun accuracyClampsBeforeRounding() {
        assertEquals(0, ProfileChartRules.percentOf(-0.2f))
        assertEquals(13, ProfileChartRules.percentOf(0.125f))
        assertEquals(100, ProfileChartRules.percentOf(1.2f))
    }

    @Test
    fun bookTrendUsesLastDuplicateAndFillsEveryMissingDate() {
        val rows = listOf(
            BookDailyStats(monday.toString(), 1, newCount = 1),
            BookDailyStats("bad date", 1, newCount = 10),
            BookDailyStats(monday.toString(), 1, reviewCount = 3, spellCount = 9)
        )
        val result = ProfileChartRules.buildBookLineData(monday to monday.plusDays(1), rows)
        assertEquals(listOf(monday, monday.plusDays(1)), result.map { it.date })
        assertEquals(listOf(0, 0), result.map { it.newCount })
        assertEquals(listOf(3, 0), result.map { it.reviewCount })
    }

    @Test
    fun globalHeatmapIncludesSpellingAndPadsToSunday() {
        val row = dailyStats(newWords = 1, reviews = 2, spelling = 3)
        val cells = ProfileChartRules.buildHeatmap(monday, monday, listOf(row))
        assertEquals(7, cells.size)
        assertEquals(6, cells.first().count)
        assertEquals(4, cells.first().level)
        assertTrue(cells.first().inRange)
        assertTrue(cells.drop(1).all { !it.inRange && it.count == 0 && it.level == 0 })
    }

    @Test
    fun studyActivityAndManualCheckInAreIndependent() {
        val row = dailyStats(newWords = 2, reviews = 1, spelling = 3)
        assertEquals(6, ProfileChartRules.activityCount(row))
        assertFalse(ProfileChartRules.isCheckedIn(row))
        assertTrue(ProfileChartRules.isCheckedIn(row.copy(checkInCount = 1)))
    }

    private fun dailyStats(newWords: Int, reviews: Int, spelling: Int) = DailyStatsAggregate(
        date = monday.toString(),
        newWordsCount = newWords,
        reviewWordsCount = reviews,
        spellPracticeCount = spelling,
        durationMillis = 0,
        gestureEasyCount = 0,
        gestureNotebookCount = 0,
        fuzzyWordsCount = 0,
        recognizedWordsCount = 0,
        checkInCount = 0,
        lastCheckInTime = 0
    )
}

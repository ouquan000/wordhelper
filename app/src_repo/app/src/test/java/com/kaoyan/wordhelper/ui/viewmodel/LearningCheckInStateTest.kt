package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LearningCheckInStateTest {

    @Test
    fun showsCompletionPanelOnlyWhenSessionHasFinished() {
        assertTrue(shouldShowCompletionCheckInPanel(currentWord = null, totalCount = 8))
        assertFalse(shouldShowCompletionCheckInPanel(currentWord = Word(id = 1, word = "test", bookId = 1), totalCount = 8))
        assertFalse(shouldShowCompletionCheckInPanel(currentWord = null, totalCount = 0))
    }

    @Test
    fun completionPanelWaitsForOpenRewrites() {
        // 词都背完了，但当前书还有重写在排队/等结果：不能提前宣布「今日背词完成」。
        assertFalse(shouldShowCompletionCheckInPanel(currentWord = null, totalCount = 8, openRewriteCount = 2))
        assertTrue(shouldShowCompletionCheckInPanel(currentWord = null, totalCount = 8, openRewriteCount = 0))
    }

    @Test
    fun waitingCountUsesMaxOfLocalSkipAndDatabaseOpen() {
        // 只标记、没点跳过的词也要算进等待条数。
        assertEquals(3, combinedWaitingRewriteCount(localWaitingCount = 1, openRewriteCount = 3))
        // 同一批词两边都数到时不重复相加。
        assertEquals(2, combinedWaitingRewriteCount(localWaitingCount = 2, openRewriteCount = 2))
        // 本地集合还没来得及清（DONE 后差一帧）时以较大的为准，不会短暂显示 0。
        assertEquals(1, combinedWaitingRewriteCount(localWaitingCount = 1, openRewriteCount = 0))
        assertEquals(0, combinedWaitingRewriteCount(localWaitingCount = 0, openRewriteCount = 0))
    }

    @Test
    fun checkInStateUsesExplicitCheckInInsteadOfStudyOnly() {
        val today = LocalDate.of(2024, 1, 2)
        val weekStats = listOf(
            aggregate(date = "2024-01-02", newWordsCount = 20, checkInCount = 0),
            aggregate(date = "2024-01-01", newWordsCount = 15, checkInCount = 1)
        )

        val state = buildLearningCheckInUiState(
            weekAggregates = weekStats,
            heatmapAggregates = weekStats,
            currentWord = null,
            totalCount = 20,
            today = today
        )

        assertTrue(state.showCompletionPanel)
        assertFalse(state.checkedInToday)
        assertEquals(1, state.weekCheckInDays)
        assertEquals(0, state.streakDays)
        assertEquals(20, state.todayStudyCount)
    }

    @Test
    fun checkInStateBlocksCompletionWhileRewritesOpen() {
        val today = LocalDate.of(2024, 1, 2)
        val weekStats = listOf(aggregate(date = "2024-01-02", newWordsCount = 20, checkInCount = 0))

        val blocked = buildLearningCheckInUiState(
            weekAggregates = weekStats,
            heatmapAggregates = weekStats,
            currentWord = null,
            totalCount = 20,
            openRewriteCount = 1,
            today = today
        )
        assertFalse(blocked.showCompletionPanel)

        val cleared = buildLearningCheckInUiState(
            weekAggregates = weekStats,
            heatmapAggregates = weekStats,
            currentWord = null,
            totalCount = 20,
            openRewriteCount = 0,
            today = today
        )
        assertTrue(cleared.showCompletionPanel)
    }

    @Test
    fun checkInStateBuildsStreakFromManualCheckIns() {
        val today = LocalDate.of(2024, 1, 3)
        val weekStats = listOf(
            aggregate(date = "2024-01-03", newWordsCount = 10, checkInCount = 1, lastCheckInTime = 1_704_240_000_000),
            aggregate(date = "2024-01-02", newWordsCount = 12, checkInCount = 1),
            aggregate(date = "2024-01-01", newWordsCount = 9, checkInCount = 1)
        )

        val state = buildLearningCheckInUiState(
            weekAggregates = weekStats,
            heatmapAggregates = weekStats,
            currentWord = null,
            totalCount = 10,
            today = today
        )

        assertTrue(state.checkedInToday)
        assertEquals(3, state.streakDays)
        assertEquals(3, state.weekCheckInDays)
        assertTrue(state.todayCheckInTimeText.isNotBlank())
    }

    private fun aggregate(
        date: String,
        newWordsCount: Int = 0,
        reviewWordsCount: Int = 0,
        spellPracticeCount: Int = 0,
        checkInCount: Int = 0,
        lastCheckInTime: Long = 0L
    ) = DailyStatsAggregate(
        date = date,
        newWordsCount = newWordsCount,
        reviewWordsCount = reviewWordsCount,
        spellPracticeCount = spellPracticeCount,
        durationMillis = 0L,
        gestureEasyCount = 0,
        gestureNotebookCount = 0,
        fuzzyWordsCount = 0,
        recognizedWordsCount = 0,
        checkInCount = checkInCount,
        lastCheckInTime = lastCheckInTime
    )
}

package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LearningCheckInStateTest {

    @Test
    fun showsCompletionPanelOnlyWhenSessionHasFinished() {
        assertTrue(shouldShowCompletionCheckInPanel(currentWord = null, totalCount = 8))
        assertFalse(shouldShowCompletionCheckInPanel(currentWord = Word(id = 1, word = "test", bookId = 1), totalCount = 8))
        assertFalse(shouldShowCompletionCheckInPanel(currentWord = null, totalCount = 0))
    }

    @Test
    fun completionPanelWaitsForTodaysUnansweredRewrites() {
        // 词都背完了，但今天发起过的重写卡还没被实际背掉：不能提前宣布「今日背词完成」。
        assertFalse(
            shouldShowCompletionCheckInPanel(
                currentWord = null,
                totalCount = 8,
                pendingRewriteCount = 2
            )
        )
        assertTrue(
            shouldShowCompletionCheckInPanel(
                currentWord = null,
                totalCount = 8,
                pendingRewriteCount = 0
            )
        )
    }

    @Test
    fun waitingCountUsesMaxOfLocalSkipAndTodaysPendingRewrites() {
        // 本地还挂着等待词时不能显示 0；同一批词两边都数到时不重复相加。
        assertEquals(3, combinedWaitingRewriteCount(localWaitingCount = 1, pendingTodayCount = 3))
        assertEquals(2, combinedWaitingRewriteCount(localWaitingCount = 2, pendingTodayCount = 2))
        assertEquals(1, combinedWaitingRewriteCount(localWaitingCount = 1, pendingTodayCount = 0))
        assertEquals(0, combinedWaitingRewriteCount(localWaitingCount = 0, pendingTodayCount = 0))
    }

    @Test
    fun pendingRewriteCount_onlyCountsTodaysUnansweredCards() {
        val today = LocalDate.of(2024, 1, 2)
        val rows = listOf(
            rewriteRow(wordId = 1, needsReinsert = true, day = today),
            rewriteRow(wordId = 2, needsReinsert = true, day = today),
            // 昨天发起、昨天没背完：不挡今天（跨天照原 SM-2 派发）。
            rewriteRow(wordId = 3, needsReinsert = true, day = today.minusDays(1)),
            // 今天发起过，但用户已经实际作答（标记被清）：不是欠卡。
            rewriteRow(wordId = 4, needsReinsert = false, day = today)
        )

        assertEquals(2, countPendingRewritesForLearningDate(rows, today))
        assertEquals(0, countPendingRewritesForLearningDate(emptyList(), today))
    }

    @Test
    fun pendingRewriteCount_keepsFailedCardAsDebt() {
        // 生成失败但用户没背过：FAILED 行仍带 needs_reinsert=1，必须继续挡完成。
        // 恢复逻辑会把词接回普通队列；用户真的答完这张卡才清标记。
        val today = LocalDate.of(2024, 1, 2)
        val rows = listOf(
            rewriteRow(wordId = 1, needsReinsert = true, day = today, status = RewriteStatus.FAILED),
            rewriteRow(wordId = 2, needsReinsert = true, day = today)
        )

        assertEquals(2, countPendingRewritesForLearningDate(rows, today))
    }

    @Test
    fun pendingRewriteCount_usesFourAmLearningDayBoundary() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2024, 1, 2)
        // 次日凌晨 2 点仍算 1 月 2 日这个学习日（DateUtils 4 点换日）。
        val afterMidnight = today.plusDays(1).atTime(2, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(
            RewriteQueueItem(
                wordId = 1L,
                word = "after-midnight",
                needsReinsert = true,
                createdAt = afterMidnight,
                updatedAt = afterMidnight
            )
        )

        assertEquals(1, countPendingRewritesForLearningDate(rows, today))
        assertEquals(0, countPendingRewritesForLearningDate(rows, today.plusDays(1)))
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
            pendingRewriteCount = 1,
            today = today
        )
        assertFalse(blocked.showCompletionPanel)

        val cleared = buildLearningCheckInUiState(
            weekAggregates = weekStats,
            heatmapAggregates = weekStats,
            currentWord = null,
            totalCount = 20,
            pendingRewriteCount = 0,
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

    private fun rewriteRow(
        wordId: Long,
        needsReinsert: Boolean,
        day: LocalDate,
        status: String = RewriteStatus.PENDING
    ): RewriteQueueItem =
        RewriteQueueItem(
            wordId = wordId,
            word = "word-$wordId",
            status = status,
            needsReinsert = needsReinsert,
            // 用正午构造时间戳：离 4 点换日边界足够远，任何时区跑都稳定。
            createdAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            updatedAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

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

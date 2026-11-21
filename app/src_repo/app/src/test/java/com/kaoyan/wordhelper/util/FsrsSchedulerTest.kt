package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.StudyRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsSchedulerTest {

    @Test
    fun newCard_allFourGrades_haveReasonableStabilityAndDifficulty() {
        val now = 1_700_000_000_000L

        val again = FsrsScheduler.schedule(null, StudyRating.AGAIN, now = now)
        val hard = FsrsScheduler.schedule(null, StudyRating.HARD, now = now)
        val good = FsrsScheduler.schedule(null, StudyRating.GOOD, now = now)
        val easy = FsrsScheduler.schedule(null, StudyRating.GOOD, easy = true, now = now)

        // S0 = w[rating-1]：0.40255 / 1.18385 / 3.173 / 15.69105
        assertEquals(0.40255, again.stability.toDouble(), 0.0001)
        assertEquals(1.18385, hard.stability.toDouble(), 0.0001)
        assertEquals(3.173, good.stability.toDouble(), 0.0001)
        assertEquals(15.69105, easy.stability.toDouble(), 0.0001)

        // D0 单调：AGAIN 最难（约 7.19），EASY 最易（约 3.22），全部在 1..10。
        assertTrue(again.difficulty > hard.difficulty)
        assertTrue(hard.difficulty > good.difficulty)
        assertTrue(good.difficulty > easy.difficulty)
        listOf(again, hard, good, easy).forEach { result ->
            assertInRange(result.difficulty, 1f, 10f)
            assertTrue(result.intervalDays >= 1)
            assertEquals(FsrsScheduler.STATE_LEARNING, result.state)
            assertFalse(result.stability.isNaN())
            assertFalse(result.difficulty.isNaN())
        }

        // EASY 的间隔应最长；AGAIN/HARD 至少 1 天。
        assertTrue(easy.intervalDays > good.intervalDays)
        assertTrue(good.intervalDays > hard.intervalDays)
    }

    @Test
    fun consecutiveGoodReviews_stabilityIncreasesMonotonically() {
        val now = 1_700_000_000_000L
        var current: Progress? = null
        var previousStability = 0f

        repeat(5) { round ->
            val result = FsrsScheduler.schedule(current, StudyRating.GOOD, now = now)
            if (round > 0) {
                assertTrue(
                    "第 $round 轮 GOOD 后 S 应增长：$previousStability -> ${result.stability}",
                    result.stability > previousStability
                )
                assertEquals(FsrsScheduler.STATE_REVIEW, result.state)
            } else {
                // 第 0 轮 current=null，是新卡首评：先进 LEARNING，再转入 REVIEW。
                assertEquals(FsrsScheduler.STATE_LEARNING, result.state)
            }
            previousStability = result.stability
            current = progressFrom(result, now)
        }
    }

    @Test
    fun againAfterReview_incrementsLapses_dropsStabilityAndEntersRelearning() {
        val now = 1_700_000_000_000L
        val current = Progress(
            wordId = 1L,
            bookId = 1L,
            status = Progress.STATUS_LEARNING,
            repetitions = 5,
            intervalDays = 3,
            nextReviewTime = now + 3 * DAY_MILLIS,
            easeFactor = 2.5f,
            reviewCount = 5,
            lastReviewTime = now - 3 * DAY_MILLIS,
            fsrsStability = 20f,
            fsrsDifficulty = 5f,
            fsrsLapses = 2,
            fsrsState = FsrsScheduler.STATE_REVIEW
        )

        val result = FsrsScheduler.schedule(current, StudyRating.AGAIN, now = now)

        assertEquals(3, result.lapses)
        assertTrue("遗忘后 S 必须下降：20 -> ${result.stability}", result.stability < 20f)
        assertTrue(result.stability > 0f)
        assertEquals(FsrsScheduler.STATE_RELEARNING, result.state)
        assertEquals(Progress.STATUS_LEARNING, result.status)
        assertTrue(result.intervalDays >= 1)
    }

    @Test
    fun higherDesiredRetention_producesShorterInterval() {
        val now = 1_700_000_000_000L
        val current = Progress(
            wordId = 1L,
            bookId = 1L,
            status = Progress.STATUS_LEARNING,
            repetitions = 5,
            intervalDays = 10,
            nextReviewTime = now + 10 * DAY_MILLIS,
            easeFactor = 2.5f,
            reviewCount = 5,
            lastReviewTime = now - 10 * DAY_MILLIS,
            fsrsStability = 50f,
            fsrsDifficulty = 5f,
            fsrsState = FsrsScheduler.STATE_REVIEW
        )

        val aggressive = FsrsScheduler.schedule(
            current,
            StudyRating.GOOD,
            now = now,
            desiredRetention = 0.97f
        )
        val relaxed = FsrsScheduler.schedule(
            current,
            StudyRating.GOOD,
            now = now,
            desiredRetention = 0.80f
        )

        assertTrue(
            "0.97 的间隔应短于 0.80：${aggressive.intervalDays} vs ${relaxed.intervalDays}",
            aggressive.intervalDays < relaxed.intervalDays
        )
        assertEquals(Progress.STATUS_MASTERED, relaxed.status)
    }

    @Test
    fun boundaryValues_neverProduceNaNOrNegativeResults() {
        val now = 1_700_000_000_000L

        // 迁移旧行：S=0、D=0、state 却是 REVIEW —— 应按新卡兜底。
        val zeroStability = Progress(
            wordId = 1L,
            bookId = 1L,
            status = Progress.STATUS_LEARNING,
            repetitions = 3,
            intervalDays = 12,
            easeFactor = 2.5f,
            lastReviewTime = 0L,
            fsrsStability = 0f,
            fsrsDifficulty = 0f,
            fsrsState = FsrsScheduler.STATE_REVIEW
        )
        val fromZero = FsrsScheduler.schedule(zeroStability, StudyRating.GOOD, now = now)
        assertFiniteResult(fromZero)
        assertEquals(FsrsScheduler.STATE_LEARNING, fromZero.state)

        // elapsed = 0（同一时刻重复调度）
        val justReviewed = zeroStability.copy(
            fsrsStability = 10f,
            fsrsDifficulty = 5f,
            lastReviewTime = now
        )
        assertFiniteResult(FsrsScheduler.schedule(justReviewed, StudyRating.GOOD, now = now))

        // 极端 S / NaN / 非法 retention / 未来时间
        val extreme = justReviewed.copy(
            fsrsStability = Float.MAX_VALUE,
            fsrsDifficulty = Float.NaN,
            lastReviewTime = now + 30 * DAY_MILLIS
        )
        val extremeResult = FsrsScheduler.schedule(
            extreme,
            StudyRating.HARD,
            now = now,
            desiredRetention = 5f
        )
        assertFiniteResult(extremeResult)

        val nanRetention = FsrsScheduler.schedule(
            justReviewed,
            StudyRating.GOOD,
            easy = true,
            now = now,
            desiredRetention = Float.NaN
        )
        assertFiniteResult(nanRetention)

        // 纯函数边界
        val r = FsrsScheduler.retrievability(0f, 0f)
        assertTrue(r.isFinite())
        assertInRange(r, 0f, 1f)
        assertInRange(FsrsScheduler.retrievability(Float.NaN, Float.NaN), 0f, 1f)
        assertEquals(1, FsrsScheduler.nextIntervalDays(0f, 0.9f))
        assertEquals(1, FsrsScheduler.nextIntervalDays(Float.NaN, Float.NaN))
        assertEquals(36_500, FsrsScheduler.nextIntervalDays(Float.MAX_VALUE, 0.9f))
        assertInRange(
            FsrsScheduler.nextIntervalDays(10f, 0.999f).toFloat(),
            1f,
            36_500f
        )
    }

    private fun progressFrom(result: FsrsScheduleResult, now: Long): Progress {
        return Progress(
            wordId = 1L,
            bookId = 1L,
            status = result.status,
            repetitions = result.repetitions,
            intervalDays = result.intervalDays,
            nextReviewTime = result.nextReviewTime,
            easeFactor = 2.5f,
            reviewCount = result.repetitions,
            lastReviewTime = now - result.intervalDays.toLong() * DAY_MILLIS,
            fsrsStability = result.stability,
            fsrsDifficulty = result.difficulty,
            fsrsLapses = result.lapses,
            fsrsState = result.state
        )
    }

    private fun assertFiniteResult(result: FsrsScheduleResult) {
        assertTrue("stability 必须有限且为正：${result.stability}", result.stability.isFinite() && result.stability > 0f)
        assertTrue("difficulty 必须有限", result.difficulty.isFinite())
        assertInRange(result.difficulty, 1f, 10f)
        assertInRange(result.intervalDays.toFloat(), 1f, 36_500f)
        assertTrue(result.nextReviewTime > 0L)
        assertTrue(result.lapses >= 0)
        assertTrue(result.repetitions >= 0)
    }

    private fun assertInRange(value: Float, min: Float, max: Float) {
        assertTrue("$value 应落在 $min..$max", value >= min && value <= max)
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

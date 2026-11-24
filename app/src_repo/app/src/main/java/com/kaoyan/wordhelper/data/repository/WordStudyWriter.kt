package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.util.FsrsScheduler
import com.kaoyan.wordhelper.util.Sm2Scheduler

internal class WordStudyWriter(
    database: AppDatabase,
    private val stats: WordStudyStats,
    private val progressStore: WordProgressStore,
    private val commits: WordStudyCommit
) {
    private val progressDao = database.progressDao()
    private val earlyReviewDao = database.earlyReviewDao()

    suspend fun applyStudyResult(
        wordId: Long,
        bookId: Long,
        rating: StudyRating,
        fsrsEnabled: Boolean,
        desiredRetention: Float,
        algorithmV4Enabled: Boolean,
        mlScheduler: MLEnhancedScheduler?,
        mlEnabled: Boolean,
        sessionPosition: Int,
        sessionTotal: Int,
        responseTimeMs: Long
    ) {
        commits.write(wordId, bookId) {
            val existing = progressDao.getProgress(wordId, bookId)
            // v8：FSRS-5 主调度；关闭时整段回落到旧的 SM-2 + ML 分支。
            val fsrs = if (fsrsEnabled) {
                FsrsScheduler.schedule(
                    current = existing,
                    rating = rating,
                    easy = false,
                    desiredRetention = desiredRetention
                )
            } else {
                null
            }
            val schedule = Sm2Scheduler.schedule(
                current = existing,
                rating = rating,
                algorithmV4Enabled = algorithmV4Enabled
            )

            // ML微调（只服务旧分支；FSRS 分支不再叠加 ML）
            val mlAdjustment = if (fsrs == null) {
                mlScheduler?.adjust(
                    baseIntervalDays = schedule.intervalDays,
                    baseEaseFactor = schedule.easeFactor,
                    progress = existing,
                    sessionPosition = sessionPosition,
                    sessionTotal = sessionTotal,
                    mlEnabled = mlEnabled
                )
            } else {
                null
            }
            val finalIntervalDays = fsrs?.intervalDays
                ?: mlAdjustment?.adjustedIntervalDays
                ?: schedule.intervalDays
            val finalEaseFactor = when {
                // FSRS 不产出 ease；保留旧值只为兼容旧字段与旧阅读逻辑。
                fsrs != null -> existing?.easeFactor ?: 2.5f
                mlAdjustment != null && mlAdjustment.confidence > 0.1f -> mlAdjustment.adjustedEaseFactor
                else -> schedule.easeFactor
            }
            val finalNextReviewTime = when {
                fsrs != null -> fsrs.nextReviewTime
                finalIntervalDays != schedule.intervalDays && finalIntervalDays > 0 ->
                    Sm2Scheduler.nextReviewTimeByDays(finalIntervalDays)
                else -> schedule.nextReviewTime
            }

            val previousReviewCount = existing?.reviewCount ?: 0
            val qualifiesAsCompleted = rating.quality >= StudyRating.HARD.quality
            val reviewCount = previousReviewCount + if (qualifiesAsCompleted) 1 else 0
            val isNewLearningCompletion = qualifiesAsCompleted && previousReviewCount == 0
            val isReviewCompletion = qualifiesAsCompleted && previousReviewCount > 0

            // 更新ML相关字段
            val now = System.currentTimeMillis()
            val isCorrect = rating != StudyRating.AGAIN
            val newConsecutiveCorrect = if (isCorrect) {
                (existing?.consecutiveCorrect ?: 0) + 1
            } else {
                0
            }
            val oldAvgTime = existing?.avgResponseTimeMs ?: 0f
            val oldReviewCount = existing?.reviewCount ?: 0
            val newAvgResponseTime = if (responseTimeMs > 0L) {
                if (oldReviewCount > 0) {
                    (oldAvgTime * oldReviewCount + responseTimeMs) / (oldReviewCount + 1)
                } else {
                    responseTimeMs.toFloat()
                }
            } else {
                oldAvgTime
            }

            // 1.4.6：只写当前这本书。同一个词在别的书里是另一份独立进度，不跟着动。
            val progress = Progress(
                id = existing?.id ?: 0,
                wordId = wordId,
                bookId = bookId,
                status = fsrs?.status ?: schedule.status,
                repetitions = fsrs?.repetitions ?: schedule.repetitions,
                intervalDays = finalIntervalDays,
                nextReviewTime = finalNextReviewTime,
                easeFactor = finalEaseFactor,
                reviewCount = reviewCount,
                spellCorrectCount = existing?.spellCorrectCount ?: 0,
                spellWrongCount = existing?.spellWrongCount ?: 0,
                markedEasyCount = existing?.markedEasyCount ?: 0,
                lastEasyTime = existing?.lastEasyTime ?: 0L,
                consecutiveCorrect = newConsecutiveCorrect,
                avgResponseTimeMs = newAvgResponseTime,
                lastReviewTime = now,
                fsrsStability = fsrs?.stability ?: existing?.fsrsStability ?: 0f,
                fsrsDifficulty = fsrs?.difficulty ?: existing?.fsrsDifficulty ?: 0f,
                fsrsLapses = fsrs?.lapses ?: existing?.fsrsLapses ?: 0,
                fsrsState = fsrs?.state ?: existing?.fsrsState ?: FsrsScheduler.STATE_NEW
            )
            if (existing != null) {
                progressDao.update(progress)
            } else {
                progressDao.insert(progress)
            }
            earlyReviewDao.deleteByWord(wordId)
            if (qualifiesAsCompleted) {
                stats.recordStudyInternal()
            }
            stats.updateDailyStatsInternal(
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0,
                fuzzyWordsDelta = if (rating == StudyRating.HARD) 1 else 0,
                recognizedWordsDelta = if (rating == StudyRating.GOOD) 1 else 0
            )
            // 1.4.5：全局那份照旧，另外给「这本书」记一笔，学习统计切到词书时才有曲线。
            stats.updateBookDailyStatsInternal(
                bookId = bookId,
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0
            )
            progressStore.invalidateForecastCacheInternal()
        }
    }

    suspend fun applySpellingOutcome(
        wordId: Long,
        bookId: Long,
        outcome: SpellingOutcome,
        attemptCount: Int,
        durationMillis: Long,
        fsrsEnabled: Boolean,
        desiredRetention: Float,
        algorithmV4Enabled: Boolean,
        mlScheduler: MLEnhancedScheduler?,
        mlEnabled: Boolean,
        sessionPosition: Int,
        sessionTotal: Int
    ) {
        commits.write(wordId, bookId) {
            val existing = progressDao.getProgress(wordId, bookId)
            // v8：拼写结果折算成 FSRS rating；PERFECT 额外按 easy 处理。
            val fsrsRating = when (outcome) {
                SpellingOutcome.PERFECT -> StudyRating.GOOD
                SpellingOutcome.HINTED, SpellingOutcome.RETRY_SUCCESS -> StudyRating.HARD
                SpellingOutcome.FAILED -> StudyRating.AGAIN
            }
            val fsrs = if (fsrsEnabled) {
                FsrsScheduler.schedule(
                    current = existing,
                    rating = fsrsRating,
                    easy = outcome == SpellingOutcome.PERFECT,
                    desiredRetention = desiredRetention
                )
            } else {
                null
            }
            val schedule = Sm2Scheduler.scheduleSpelling(
                current = existing,
                outcome = outcome,
                algorithmV4Enabled = algorithmV4Enabled
            )

            // ML微调（只服务旧分支；FSRS 分支不再叠加 ML）
            val mlAdjustment = if (fsrs == null) {
                mlScheduler?.adjust(
                    baseIntervalDays = schedule.intervalDays,
                    baseEaseFactor = schedule.easeFactor,
                    progress = existing,
                    sessionPosition = sessionPosition,
                    sessionTotal = sessionTotal,
                    mlEnabled = mlEnabled
                )
            } else {
                null
            }
            val finalIntervalDays = fsrs?.intervalDays
                ?: mlAdjustment?.adjustedIntervalDays
                ?: schedule.intervalDays
            val finalEaseFactor = when {
                // FSRS 不产出 ease；保留旧值只为兼容旧字段与旧阅读逻辑。
                fsrs != null -> existing?.easeFactor ?: 2.5f
                mlAdjustment != null && mlAdjustment.confidence > 0.1f -> mlAdjustment.adjustedEaseFactor
                else -> schedule.easeFactor
            }
            val finalNextReviewTime = when {
                fsrs != null -> fsrs.nextReviewTime
                finalIntervalDays != schedule.intervalDays && finalIntervalDays > 0 ->
                    Sm2Scheduler.nextReviewTimeByDays(finalIntervalDays)
                else -> schedule.nextReviewTime
            }

            val previousReviewCount = existing?.reviewCount ?: 0
            val qualifiesAsCompleted = outcome.quality >= StudyRating.HARD.quality
            val reviewCount = previousReviewCount + if (qualifiesAsCompleted) 1 else 0
            val isNewLearningCompletion = qualifiesAsCompleted && previousReviewCount == 0
            val isReviewCompletion = qualifiesAsCompleted && previousReviewCount > 0
            val spellCorrectCount = (existing?.spellCorrectCount ?: 0) + outcome.spellCorrectDelta
            val spellWrongCount = (existing?.spellWrongCount ?: 0) + outcome.spellWrongDelta

            // 更新ML相关字段
            val now = System.currentTimeMillis()
            val isCorrect = outcome != SpellingOutcome.FAILED
            val newConsecutiveCorrect = if (isCorrect) {
                (existing?.consecutiveCorrect ?: 0) + 1
            } else {
                0
            }
            val responseTimeMs = durationMillis
            val oldAvgTime = existing?.avgResponseTimeMs ?: 0f
            val oldReviewCount = existing?.reviewCount ?: 0
            val newAvgResponseTime = if (responseTimeMs > 0L) {
                if (oldReviewCount > 0) {
                    (oldAvgTime * oldReviewCount + responseTimeMs) / (oldReviewCount + 1)
                } else {
                    responseTimeMs.toFloat()
                }
            } else {
                oldAvgTime
            }

            // 1.4.6：拼写结果同样只落当前这本书。
            val progress = Progress(
                id = existing?.id ?: 0,
                wordId = wordId,
                bookId = bookId,
                status = fsrs?.status ?: schedule.status,
                repetitions = fsrs?.repetitions ?: schedule.repetitions,
                intervalDays = finalIntervalDays,
                nextReviewTime = finalNextReviewTime,
                easeFactor = finalEaseFactor,
                reviewCount = reviewCount,
                spellCorrectCount = spellCorrectCount,
                spellWrongCount = spellWrongCount,
                markedEasyCount = existing?.markedEasyCount ?: 0,
                lastEasyTime = existing?.lastEasyTime ?: 0L,
                consecutiveCorrect = newConsecutiveCorrect,
                avgResponseTimeMs = newAvgResponseTime,
                lastReviewTime = now,
                fsrsStability = fsrs?.stability ?: existing?.fsrsStability ?: 0f,
                fsrsDifficulty = fsrs?.difficulty ?: existing?.fsrsDifficulty ?: 0f,
                fsrsLapses = fsrs?.lapses ?: existing?.fsrsLapses ?: 0,
                fsrsState = fsrs?.state ?: existing?.fsrsState ?: FsrsScheduler.STATE_NEW
            )
            if (existing != null) {
                progressDao.update(progress)
            } else {
                progressDao.insert(progress)
            }
            earlyReviewDao.deleteByWord(wordId)
            if (qualifiesAsCompleted) {
                stats.recordStudyInternal()
            }
            stats.updateDailyStatsInternal(
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0,
                spellPracticeDelta = attemptCount.coerceAtLeast(1),
                durationMillisDelta = durationMillis.coerceAtLeast(0L)
            )
            stats.updateBookDailyStatsInternal(
                bookId = bookId,
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0,
                spellPracticeDelta = attemptCount.coerceAtLeast(1)
            )
            progressStore.invalidateForecastCacheInternal()
        }
    }
}

/** 同一事务提交识别或拼写结果；只有提交成功才把最终状态写入同步队列。 */
internal class WordStudyCommit(
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val enqueue: suspend (Long, Long) -> Unit
) {
    suspend fun write(wordId: Long, bookId: Long, update: suspend () -> Unit) {
        transaction(update)
        enqueue(wordId, bookId)
    }
}

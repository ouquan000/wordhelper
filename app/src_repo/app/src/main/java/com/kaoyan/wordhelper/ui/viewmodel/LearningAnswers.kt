package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.SettingsRepository
import com.kaoyan.wordhelper.data.repository.WordRepository
import com.kaoyan.wordhelper.ml.core.AdaptiveResponseThreshold
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.ml.training.OnlineTrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS = 6_000L

internal class LearningAnswers(
    private val viewModelScope: CoroutineScope,
    private val app: KaoyanWordApp,
    private val session: LearningSessionState,
    private val study: LearningStudyFlows,
    private val queue: LearningQueueCoordinator,
    private val gate: LearningSubmissionGate,
    private val repository: WordRepository,
    private val rewriteRepository: RewriteRepository,
    private val settingsRepository: SettingsRepository,
    private val mlScheduler: MLEnhancedScheduler,
    private val mlOnlineTrainer: OnlineTrainer?
) {
    private var cachedResponseThresholdMs: Long = RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS

    fun observeResponseThreshold() {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                if (settings.mlAdaptiveEnabled) {
                    val modelState = withContext(Dispatchers.IO) {
                        app.mlModelPersistence.getModelState()
                    }
                    cachedResponseThresholdMs = AdaptiveResponseThreshold.computeThreshold(
                        avgResponseTime = modelState?.avgResponseTime ?: 0f,
                        stdResponseTime = modelState?.stdResponseTime ?: 0f,
                        mlEnabled = true
                    )
                } else {
                    cachedResponseThresholdMs = RESPONSE_TIME_DOWNGRADE_THRESHOLD_MS
                }
            }
        }
    }

    /**
     * v8：作答落库后的 FSRS 旁路 —— 记忆信号上报 + 遗忘自动换招。
     *
     * 单开 IO 协程：电脑不在线时最多等到请求超时，不会拖慢下一张卡的出现，
     * 也不会因为上报失败改变这次作答的结果。
     */
    private fun dispatchFsrsSignalsInBackground(word: Word, book: Book, rating: StudyRating) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dispatchFsrsSignals(word, book, rating) }
        }
    }

    private suspend fun dispatchFsrsSignals(word: Word, book: Book, rating: StudyRating) {
        val updated = repository.getProgress(word.id, book.id) ?: return
        val method = runCatching { rewriteRepository.methodOf(word.id, book.id) }.getOrDefault("")
        if (study.fsrsEnabled.value) {
            if (rating == StudyRating.AGAIN) {
                rewriteRepository.reportMemorySignal(
                    wordId = word.id,
                    bookId = book.id,
                    word = word.word,
                    method = method,
                    event = "lapsed",
                    stability = updated.fsrsStability,
                    difficulty = updated.fsrsDifficulty,
                    lapses = updated.fsrsLapses
                )
            } else if (updated.fsrsStability >= 14f) {
                rewriteRepository.reportMemorySignal(
                    wordId = word.id,
                    bookId = book.id,
                    word = word.word,
                    method = method,
                    event = "retained",
                    stability = updated.fsrsStability,
                    difficulty = updated.fsrsDifficulty,
                    lapses = updated.fsrsLapses
                )
            }
        }
        if (study.fsrsEnabled.value &&
            (updated.fsrsLapses >= 2 || updated.fsrsDifficulty >= 8.5f)
        ) {
            rewriteRepository.maybeAutoRewriteAfterLapse(word, book, updated)
        }
    }

    fun submitAnswer(rating: StudyRating) {
        val word = session._currentWord.value ?: return
        val book = study.activeBook.value ?: return
        val previousWordId = word.id
        val responseTimeMs = session.buildCurrentResponseTimeMs()
        session.recordAnswerPace(responseTimeMs)
        val effectiveRating = resolveRecognitionRating(rating, responseTimeMs)
        val currentSessionPosition = session.answeredInSession
        val currentSessionTotal = session._totalCount.value.coerceAtLeast(1)
        gate.launchSubmit {
            val currentMlEnabled = resolveMlEnabledForSubmission()
            session.pendingFailedWordId = null
            session.removeFromImmediateQueue(word.id)
            session.removeSessionSkippedWord(word.id)

            // ML训练：记录特征和结果
            if (currentMlEnabled && mlOnlineTrainer != null) {
                val progress = withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
                val features = mlScheduler.extractFeatures(
                    progress = progress,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                val isCorrect = effectiveRating != StudyRating.AGAIN
                withContext(Dispatchers.IO) {
                    mlOnlineTrainer.onReviewCompleted(word.id, features, isCorrect)
                }
            }

            withContext(Dispatchers.IO) {
                repository.applyStudyResult(
                    wordId = word.id,
                    bookId = book.id,
                    rating = effectiveRating,
                    algorithmV4Enabled = study.algorithmV4Enabled.value,
                    fsrsEnabled = study.fsrsEnabled.value,
                    desiredRetention = study.desiredRetention.value,
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal,
                    responseTimeMs = responseTimeMs
                )
                // 1.4.7.2 W2：用户真的答完这张重写卡，才清待插回标记（生成/合格/插回都不算）。
                // 只结清当前这本书：同一个词可能同时属于别的书，不能把别的书的欠卡一起清掉。
                rewriteRepository.clearNeedsReinsert(word.id, book.id)
            }
            dispatchFsrsSignalsInBackground(word, book, effectiveRating)
            session.forgetPendingReinsert(word.id)
            if (effectiveRating == StudyRating.AGAIN) {
                session.enqueueImmediateRetry(word)
                session._memoryAidSuggestionWordId.value = word.id
            } else {
                session._memoryAidSuggestionWordId.value = null
                session.markAnswered()
            }
            queue.loadQueueForBook(
                book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value,
                study.plannedNewWordsEnabled.value, presentAgain = true
            )
            session.emitRecognitionAutoPronounceEventIfNeeded(previousWordId)
        }
    }

    fun submitAnswerAndRemoveFromNewWords(rating: StudyRating) {
        val word = session._currentWord.value ?: return
        val book = study.activeBook.value ?: return
        val previousWordId = word.id
        val responseTimeMs = session.buildCurrentResponseTimeMs()
        session.recordAnswerPace(responseTimeMs)
        val effectiveRating = resolveRecognitionRating(rating, responseTimeMs)
        val currentSessionPosition = session.answeredInSession
        val currentSessionTotal = session._totalCount.value.coerceAtLeast(1)
        gate.launchSubmit {
            val currentMlEnabled = resolveMlEnabledForSubmission()
            session.pendingFailedWordId = null
            session._memoryAidSuggestionWordId.value = null
            session.removeFromImmediateQueue(word.id)
            session.removeSessionSkippedWord(word.id)

            if (currentMlEnabled && mlOnlineTrainer != null) {
                val progress = withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
                val features = mlScheduler.extractFeatures(
                    progress = progress,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                val isCorrect = effectiveRating != StudyRating.AGAIN
                withContext(Dispatchers.IO) {
                    mlOnlineTrainer.onReviewCompleted(word.id, features, isCorrect)
                }
            }

            withContext(Dispatchers.IO) {
                repository.removeFromNewWords(word.id)
                repository.applyStudyResult(
                    wordId = word.id,
                    bookId = book.id,
                    rating = effectiveRating,
                    algorithmV4Enabled = study.algorithmV4Enabled.value,
                    fsrsEnabled = study.fsrsEnabled.value,
                    desiredRetention = study.desiredRetention.value,
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal,
                    responseTimeMs = responseTimeMs
                )
                // 1.4.7.2 W2：用户真的答完这张重写卡，才清待插回标记（生成/合格/插回都不算）。
                rewriteRepository.clearNeedsReinsert(word.id, book.id)
            }
            dispatchFsrsSignalsInBackground(word, book, effectiveRating)
            session.forgetPendingReinsert(word.id)
            session.markAnswered()
            queue.loadQueueForBook(
                book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value,
                study.plannedNewWordsEnabled.value, presentAgain = true
            )
            session.emitRecognitionAutoPronounceEventIfNeeded(previousWordId)
        }
    }

    fun submitSpellingOutcome(
        outcome: SpellingOutcome,
        attemptCount: Int,
        durationMillis: Long
    ) {
        val word = session._currentWord.value ?: return
        val book = study.activeBook.value ?: return
        val responseTimeMs = if (durationMillis > 0L) durationMillis else session.buildCurrentResponseTimeMs()
        session.recordAnswerPace(responseTimeMs)
        val currentSessionPosition = session.answeredInSession
        val currentSessionTotal = session._totalCount.value.coerceAtLeast(1)
        val effectiveRating = when (outcome) {
            SpellingOutcome.PERFECT -> StudyRating.GOOD
            SpellingOutcome.HINTED, SpellingOutcome.RETRY_SUCCESS -> StudyRating.HARD
            SpellingOutcome.FAILED -> StudyRating.AGAIN
        }
        gate.launchSubmit {
            val currentMlEnabled = resolveMlEnabledForSubmission()
            if (outcome == SpellingOutcome.FAILED) {
                session.pendingFailedWordId = word.id
                session._memoryAidSuggestionWordId.value = word.id
            } else {
                session.pendingFailedWordId = null
                session._memoryAidSuggestionWordId.value = null
            }
            session.removeFromImmediateQueue(word.id)
            session.removeSessionSkippedWord(word.id)

            // ML训练
            if (currentMlEnabled && mlOnlineTrainer != null) {
                val progress = withContext(Dispatchers.IO) {
                    repository.getProgress(word.id, book.id)
                }
                val features = mlScheduler.extractFeatures(
                    progress = progress,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                val isCorrect = outcome != SpellingOutcome.FAILED
                withContext(Dispatchers.IO) {
                    mlOnlineTrainer.onReviewCompleted(word.id, features, isCorrect)
                }
            }

            withContext(Dispatchers.IO) {
                repository.applySpellingOutcome(
                    wordId = word.id,
                    bookId = book.id,
                    outcome = outcome,
                    attemptCount = attemptCount,
                    durationMillis = durationMillis,
                    algorithmV4Enabled = study.algorithmV4Enabled.value,
                    fsrsEnabled = study.fsrsEnabled.value,
                    desiredRetention = study.desiredRetention.value,
                    mlScheduler = if (currentMlEnabled) mlScheduler else null,
                    mlEnabled = currentMlEnabled,
                    sessionPosition = currentSessionPosition,
                    sessionTotal = currentSessionTotal
                )
                // 1.4.7.2 W2：拼写成功才算这张卡真的答完。
                // 失败还会原地重背（continueAfterSpellingFailure → 即时重背），欠卡先留着。
                if (outcome != SpellingOutcome.FAILED) {
                    rewriteRepository.clearNeedsReinsert(word.id, book.id)
                }
            }
            dispatchFsrsSignalsInBackground(word, book, effectiveRating)
            if (outcome == SpellingOutcome.FAILED) {
                return@launchSubmit
            }
            session.forgetPendingReinsert(word.id)
            session.pendingFailedWordId = null
            session.markAnswered()
            queue.loadQueueForBook(book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value, study.plannedNewWordsEnabled.value)
        }
    }

    fun continueAfterSpellingFailure() {
        val currentWord = session._currentWord.value ?: return
        val book = study.activeBook.value ?: return
        if (gate.isSubmitting.value) return
        if (session.pendingFailedWordId != currentWord.id) return
        session.pendingFailedWordId = null
        viewModelScope.launch {
            session.enqueueImmediateRetry(currentWord)
            session.markAnswered()
            queue.loadQueueForBook(book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value, study.plannedNewWordsEnabled.value)
        }
    }

    private fun resolveRecognitionRating(
        rating: StudyRating,
        responseTimeMs: Long
    ): StudyRating {
        return LearningSessionRules.recognitionRating(rating, study.algorithmV4Enabled.value, responseTimeMs, cachedResponseThresholdMs)
    }

    private suspend fun resolveMlEnabledForSubmission(): Boolean {
        return withContext(Dispatchers.IO) {
            val enabled = settingsRepository.settingsFlow.first().mlAdaptiveEnabled
            if (enabled) {
                app.ensureMLEngineInitialized()
            }
            enabled
        }
    }
}

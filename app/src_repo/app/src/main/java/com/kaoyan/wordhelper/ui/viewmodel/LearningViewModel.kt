package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.repository.dismissPressure
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.ApproachReview
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.PronunciationSource
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.ml.training.OnlineTrainer
import com.kaoyan.wordhelper.util.RetryRandomSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KaoyanWordApp
    private val repository = app.repository
    private val database = app.database
    private val settingsRepository = app.settingsRepository
    private val aiConfigRepository = app.aiConfigRepository
    private val aiRepository = app.aiRepository
    private val cardBackRepository = app.cardBackRepository
    private val rewriteRepository = app.rewriteRepository

    /** 卡背结构清单（1.4.2）；默认就是 1.4.1 的样子。 */
    val cardBackConfig: StateFlow<CardBackConfig> = cardBackRepository.config
    private val mlScheduler: MLEnhancedScheduler = app.mlScheduler
    private val mlOnlineTrainer: OnlineTrainer? = run {
        OnlineTrainer(app.mlPredictor, app.database.trainingSampleDao(), app.mlModelPersistence)
    }

    private val session = LearningSessionState()
    private val gate = LearningSubmissionGate(viewModelScope)
    private val study = LearningStudyFlows(viewModelScope, repository, settingsRepository, rewriteRepository, session)
    private val rewrite = LearningRewriteFlows(viewModelScope, rewriteRepository, database, session, study)
    private val queue = LearningQueueCoordinator(
        viewModelScope, repository, rewriteRepository, session, gate, study, rewrite
    )
    private val polling = LearningRewritePolling(viewModelScope, session, study, rewrite, queue, rewriteRepository)
    private val rewrites = LearningRewriteActions(
        viewModelScope, session, study, rewrite, queue, polling, gate, settingsRepository, rewriteRepository
    )
    private val feedback = LearningMemoryFeedback(
        viewModelScope, session, study, rewrite, gate, database, rewriteRepository, ::PendingLikeUi
    )
    private val answers = LearningAnswers(
        viewModelScope, app, session, study, queue, gate, repository, rewriteRepository, settingsRepository, mlScheduler, mlOnlineTrainer
    )
    private val ai = LearningAiCoordinator(viewModelScope, session, aiRepository, aiConfigRepository)
    private val actions = LearningSessionActions(viewModelScope, repository, session, study, queue, gate)

    /** 连击还没写库的临时增量；只对同一张卡生效。 */
    data class PendingLikeUi(val wordId: Long = 0L, val delta: Int = 0)

    val learningPace: StateFlow<LearningPace> = session.learningPace
    val isSubmitting: StateFlow<Boolean> = gate.isSubmitting
    val message: StateFlow<String?> = gate.message
    val dueReviewCount: StateFlow<Int> = session.dueReviewCount
    val aiState: StateFlow<LearningAiState> = session.aiState
    val cachedExampleContent: StateFlow<String> = session.cachedExampleContent
    val cachedMemoryAidContent: StateFlow<String> = session.cachedMemoryAidContent
    val memoryAidSuggestionWordId: StateFlow<Long?> = session.memoryAidSuggestionWordId
    val recognitionAutoPronounceEvents: SharedFlow<Long> = session.recognitionAutoPronounceEvents
    val books: StateFlow<List<Book>> = study.books
    val activeBook: StateFlow<Book?> = study.activeBook
    val waitingRewriteCount: StateFlow<Int> = study.waitingRewriteCount
    val currentWord: StateFlow<Word?> = session.currentWord
    internal val cardPresentation: StateFlow<LearningCardPresentation> = session.cardPresentation
    val rewriteModels: StateFlow<List<RewriteModel>> = rewrite.rewriteModels
    val rewriteModelId: StateFlow<String> = rewrite.rewriteModelId
    val currentRewriteMark: StateFlow<RewriteQueueItem?> = rewrite.currentRewriteMark
    internal val currentRewriteSkipAvailability: StateFlow<SkipRewriteAvailability> = rewrite.currentRewriteSkipAvailability
    val liveApproach: StateFlow<String?> = rewrite.liveApproach
    val liveMethod: StateFlow<String?> = rewrite.liveMethod
    val memoryEditCanRestore: StateFlow<Boolean> = rewrite.memoryEditCanRestore
    val liveApproachReview: StateFlow<ApproachReview> = rewrite.liveApproachReview
    val currentRewriteFeedback: StateFlow<String?> = rewrite.currentRewriteFeedback
    val currentRewriteLikeCount: StateFlow<Int> = rewrite.currentRewriteLikeCount
    val currentMemoryLocked: StateFlow<Boolean> = rewrite.currentMemoryLocked
    val pendingLikeBurst: StateFlow<PendingLikeUi> = feedback.pendingLikeBurst
    val rewriteProgress: StateFlow<RewriteProgressUi?> = rewrite.rewriteProgress
    val rewriteUpdatedTick: StateFlow<Int> = rewrite.rewriteUpdatedTick
    val currentIndex: StateFlow<Int> = session.currentIndex
    val totalCount: StateFlow<Int> = session.totalCount
    val isNewWordsBook: StateFlow<Boolean> = study.isNewWordsBook
    val isInNewWords: StateFlow<Boolean> = study.isInNewWords
    val currentProgress: StateFlow<Progress?> = study.currentProgress
    val algorithmV4Enabled: StateFlow<Boolean> = study.algorithmV4Enabled
    val fsrsEnabled: StateFlow<Boolean> = study.fsrsEnabled
    val desiredRetention: StateFlow<Float> = study.desiredRetention
    val reviewPressureReliefEnabled: StateFlow<Boolean> = study.reviewPressureReliefEnabled
    val reviewPressureDailyCap: StateFlow<Int> = study.reviewPressureDailyCap
    val pronunciationEnabled: StateFlow<Boolean> = study.pronunciationEnabled
    val pronunciationSource: StateFlow<PronunciationSource> = study.pronunciationSource
    val recognitionAutoPronounceEnabled: StateFlow<Boolean> = study.recognitionAutoPronounceEnabled
    val canRelieveReviewPressure: StateFlow<Boolean> = study.canRelieveReviewPressure
    val checkInState: StateFlow<LearningCheckInUiState> = study.checkInState

    internal var retryQueueRandomSource: RetryRandomSource
        get() = session.retryQueueRandomSource
        set(value) { session.retryQueueRandomSource = value }

    init {
        refreshAiAvailability()
        // 心流进度条节奏 ticker：500ms 更新当前卡停留时长；没有当前卡时保持 Tier1。
        viewModelScope.launch {
            while (isActive) {
                session.refreshLearningPace()
                delay(500)
            }
        }
        polling.observeCurrentWord()
        viewModelScope.launch {
            val savedModel = settingsRepository.rewriteModel()
            if (savedModel.isNotBlank()) rewrite._rewriteModelId.value = savedModel
            rewrite._rewriteModels.value = rewriteRepository.models()
        }
        answers.observeResponseThreshold()
        actions.observeQueue()
    }

    fun selectRewriteModel(modelId: String) = rewrites.selectRewriteModel(modelId)

    fun markRewrite(instruction: String) = rewrites.markRewrite(instruction)

    fun cancelRewrite() = rewrites.cancelRewrite()

    fun undoRewrite() = rewrites.undoRewrite()

    fun submitRewriteFeedback(
        rating: String,
        instruction: String = "",
        count: Int = 1,
        reasons: List<String> = emptyList(),
        comment: String = ""
    ) = feedback.submitRewriteFeedback(rating, instruction, count, reasons, comment)

    fun tapRewriteFeedback(
        rating: String,
        instruction: String = "",
        reasons: List<String> = emptyList(),
        comment: String = ""
    ) = feedback.tapRewriteFeedback(rating, instruction, reasons, comment)

    fun flushPendingLikes() = feedback.flushPendingLikes()

    fun saveMemoryEdit(text: String) = feedback.saveMemoryEdit(text)

    fun restoreMemoryEdit() = feedback.restoreMemoryEdit()

    fun toggleMemoryLock() = feedback.toggleMemoryLock()

    fun stopAllRewrites() = rewrites.stopAllRewrites()

    fun skipCurrentWordForRewrite() = rewrites.skipCurrentWordForRewrite()

    fun syncRewrite() = polling.syncRewrite()

    fun syncRewriteOnBackFlip() = polling.syncRewriteOnBackFlip()

    fun startRewriteProgressPolling() = polling.startRewriteProgressPolling()

    fun stopRewriteProgressPolling() = polling.stopRewriteProgressPolling()

    fun switchBook(bookId: Long) = actions.switchBook(bookId)

    fun toggleNewWord() = actions.toggleNewWord()

    fun submitAnswer(rating: StudyRating) = answers.submitAnswer(rating)

    fun submitAnswerAndRemoveFromNewWords(rating: StudyRating) = answers.submitAnswerAndRemoveFromNewWords(rating)

    fun submitSpellingOutcome(
        outcome: SpellingOutcome,
        attemptCount: Int,
        durationMillis: Long
    ) = answers.submitSpellingOutcome(outcome, attemptCount, durationMillis)

    fun continueAfterSpellingFailure() = answers.continueAfterSpellingFailure()

    fun refreshQueue() = actions.refreshQueue()

    fun dismissReviewPressure() { viewModelScope.launch { settingsRepository.dataStore.dismissPressure() } }

    fun disperseReviewPressure() = actions.disperseReviewPressure()

    fun refreshAiAvailability() = ai.refreshAiAvailability()

    fun loadCachedAiForCurrentWord() = ai.loadCachedAiForCurrentWord()

    fun requestAiExample(forceRefresh: Boolean = false) = ai.requestAiExample(forceRefresh)

    fun requestAiMemoryAid(forceRefresh: Boolean = false) = ai.requestAiMemoryAid(forceRefresh)

    fun clearMessage() = actions.clearMessage()

    fun completeTodayCheckIn() = actions.completeTodayCheckIn()
}

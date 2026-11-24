package com.kaoyan.wordhelper.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.CardMotionMode
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.ui.component.CardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.rememberCardLightTime
import com.kaoyan.wordhelper.ui.component.rememberCardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.rememberHoloLightState
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel
import com.kaoyan.wordhelper.ui.viewmodel.SkipRewriteAvailability
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.PronunciationPlayer
import com.kaoyan.wordhelper.util.Sm2Scheduler
import com.kaoyan.wordhelper.util.rememberPronunciationPlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(
    viewModel: LearningViewModel = viewModel(),
    initialMode: LearningMode = LearningMode.RECOGNITION,
    onOpenDrawer: () -> Unit = {},
    styleDrawerState: CardStyleDrawerState = rememberCardStyleDrawerState(),
    navDrawerOpen: Boolean = false,
    /**
     * 左抽屉展开时的景深缩放（AppNavigation 的 1f - 0.032f * progress 同一 lambda）。
     * 学习页由这里缩放内容层，而不是整根 Scaffold，好让 AmbientBackdrop 和抽屉的
     * 圆角背板始终铺满全屏，不会在四周露一条未缩放的根底色接缝。
     */
    navDrawerDepthScale: () -> Float = { 1f }
) {
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val cardBackConfig by viewModel.cardBackConfig.collectAsStateWithLifecycle()
    val presentedCard by viewModel.cardPresentation.collectAsStateWithLifecycle()
    val currentWord = presentedCard.word
    // 卡背正文直接订阅库里的思路：电脑端结果一落库就换文，不依赖词对象什么时候被刷新
    val liveApproach by viewModel.liveApproach.collectAsStateWithLifecycle()
    // 方法标签也订阅库：重写落地后胶囊立刻换，不等下一次翻面
    val liveMethod by viewModel.liveMethod.collectAsStateWithLifecycle()
    // 1.4.7.1：这版思路是不是「没复核」——同样盯库，角标跟着卡背正文一起换
    val liveApproachReview by viewModel.liveApproachReview.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val learningPace by viewModel.learningPace.collectAsStateWithLifecycle()
    val isInNewWords by viewModel.isInNewWords.collectAsStateWithLifecycle()
    val isNewWordsBook by viewModel.isNewWordsBook.collectAsStateWithLifecycle()
    val currentProgress by viewModel.currentProgress.collectAsStateWithLifecycle()
    val isAnswering by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val dueReviewCount by viewModel.dueReviewCount.collectAsStateWithLifecycle()
    val canRelieveReviewPressure by viewModel.canRelieveReviewPressure.collectAsStateWithLifecycle()
    val reviewPressureDailyCap by viewModel.reviewPressureDailyCap.collectAsStateWithLifecycle()
    val algorithmV4Enabled by viewModel.algorithmV4Enabled.collectAsStateWithLifecycle()
    val pronunciationEnabled by viewModel.pronunciationEnabled.collectAsStateWithLifecycle()
    val pronunciationSource by viewModel.pronunciationSource.collectAsStateWithLifecycle()
    val recognitionAutoPronounceEnabled by viewModel.recognitionAutoPronounceEnabled.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val cachedExampleContent by viewModel.cachedExampleContent.collectAsStateWithLifecycle()
    val cachedMemoryAidContent by viewModel.cachedMemoryAidContent.collectAsStateWithLifecycle()
    val memoryAidSuggestionWordId by viewModel.memoryAidSuggestionWordId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val checkInState by viewModel.checkInState.collectAsStateWithLifecycle()
    val waitingRewriteCount by viewModel.waitingRewriteCount.collectAsStateWithLifecycle()
    val rewriteMark by viewModel.currentRewriteMark.collectAsStateWithLifecycle()
    // 1.4.7v7-bugfix：显示/可用与 ViewModel 的点击判定同源（含「这次停留」锁存），
    // 不再由页面另拼一套 showSkip/skipEnabled 条件。
    val skipAvailability by viewModel.currentRewriteSkipAvailability.collectAsStateWithLifecycle()
    val rewriteModels by viewModel.rewriteModels.collectAsStateWithLifecycle()
    val rewriteModelId by viewModel.rewriteModelId.collectAsStateWithLifecycle()
    val rewriteProgress by viewModel.rewriteProgress.collectAsStateWithLifecycle()
    // 1.4.6：这条思路的 👍/👎 状态（null = 还没表过态）
    val rewriteFeedback by viewModel.currentRewriteFeedback.collectAsStateWithLifecycle()
    // 1.5.2：连击点赞的累计值（👍胶囊上的数字）
    val rewriteLikeCount by viewModel.currentRewriteLikeCount.collectAsStateWithLifecycle()
    val memoryLocked by viewModel.currentMemoryLocked.collectAsStateWithLifecycle()
    val memoryEditCanRestore by viewModel.memoryEditCanRestore.collectAsStateWithLifecycle()
    var showMemoryEditDialog by rememberSaveable { mutableStateOf(false) }
    var memoryDraft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(currentWord?.id) { viewModel.flushPendingLikes() }
    DisposableEffect(Unit) {
        onDispose { viewModel.flushPendingLikes() }
    }
    // 回到前台就补发断网时的标记、拉电脑端跑完的结果；没网也只是静默跳过。
    LifecycleResumeEffect(Unit) {
        viewModel.syncRewrite()
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    // 1.4.7：发音索引 5 MB 级，别在首帧组合时同步加载（冷启动会 ANR）；
    // 用到时再取，Application 里已经在后台预热。
    val appContext = remember(context) { context.applicationContext as KaoyanWordApp }
    val pronunciationPlayer: PronunciationPlayer = rememberPronunciationPlayer()
    val uiScope = rememberCoroutineScope()

    // 卡片材质三件套：单独订阅，避免整页跟着 settingsFlow 里其它字段一起重组。
    val cardUiPrefs by remember(appContext) {
        appContext.settingsRepository.settingsFlow
            .map { CardUiPrefs(it.cardStyleId, it.cardGlossHigh, it.cardMotionMode, it.ambientBackdropId) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = CardUiPrefs())
    val presentation = presentedCard.generation
    val cardStyle = if (cardUiPrefs.styleId == "random") {
        val index = rememberSaveable(presentation) {
            HoloCardStyle.entries.indices.random()
        }
        HoloCardStyle.entries[index]
    } else {
        HoloCardStyle.fromId(cardUiPrefs.styleId)
    }

    val systemAnimationsOn = rememberSystemAnimationsEnabled()
    val motionEnabled = cardUiPrefs.motionMode != CardMotionMode.STATIC && systemAnimationsOn
    val lightTime = rememberCardLightTime(motionEnabled)

    var showRemoveDialog by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showAiAssistantPage by rememberSaveable { mutableStateOf(false) }
    var showAiGuideDialog by remember { mutableStateOf(false) }
    var isCardFlipped by remember { mutableStateOf(false) }
    var pronouncingWordId by remember { mutableStateOf<Long?>(null) }
    var learningMode by rememberSaveable { mutableStateOf(initialMode) }
    var rewriteInstruction by remember(currentWord?.id, learningMode) { mutableStateOf<RewriteInstructionRequest?>(null) }
    val holoLight = rememberHoloLightState(
        motionEnabled && currentWord != null && learningMode == LearningMode.RECOGNITION && !showAiAssistantPage,
        lightTime
    )

    LaunchedEffect(presentation) {
        showReviewDialog = false
        showAiGuideDialog = false
        showAiAssistantPage = false
        isCardFlipped = false
    }

    LaunchedEffect(initialMode) {
        learningMode = initialMode
        if (initialMode != LearningMode.RECOGNITION) {
            isCardFlipped = false
        }
    }

    // 卡背可见 + 本词标记还没跑完，才每 4 秒问一次电脑端进度。
    // 翻回正面（key 变化）、离开学习页、App 进后台（ON_STOP）都会停掉轮询。
    val shouldPollRewrite = isCardFlipped && (
        rewriteMark?.status == RewriteStatus.PENDING || rewriteMark?.status == RewriteStatus.QUEUED
        )
    // W3：点过重写就锁存，生成完成也不收起；换词后 currentWord 变了才失效。
    // 1.4.7v7-bugfix：这份「本次停留」锁存已并入 ViewModel 的 currentRewriteSkipAvailability，
    // 页面不再单独推导 showSkip，避免两处条件漂移。
    LifecycleStartEffect(shouldPollRewrite) {
        if (shouldPollRewrite) {
            viewModel.startRewriteProgressPolling()
        }
        onStopOrDispose { viewModel.stopRewriteProgressPolling() }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAiAvailability()
    }

    LaunchedEffect(showAiAssistantPage, currentWord?.id) {
        if (showAiAssistantPage && currentWord != null) {
            viewModel.loadCachedAiForCurrentWord()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    fun handleAnswer(rating: StudyRating, removeFromNewWords: Boolean = false) {
        if (isAnswering) return
        if (removeFromNewWords) {
            viewModel.submitAnswerAndRemoveFromNewWords(rating)
        } else {
            viewModel.submitAnswer(rating)
        }
    }

    fun openAiEntry() {
        if (isAnswering) return
        if (aiState.isAvailable) {
            showAiAssistantPage = true
            showAiGuideDialog = false
        } else {
            showAiGuideDialog = true
            showAiAssistantPage = false
        }
    }

    fun playPronunciation(word: Word) {
        if (!pronunciationEnabled) return
        if (pronouncingWordId == word.id) return
        pronouncingWordId = word.id
        uiScope.launch {
            playLearningWordAudio(context, appContext, pronunciationPlayer, word) { pronunciationSource }
            pronouncingWordId = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.recognitionAutoPronounceEvents.collect {
            val word = viewModel.currentWord.value ?: return@collect
            if (learningMode != LearningMode.RECOGNITION) return@collect
            if (!pronunciationEnabled || !recognitionAutoPronounceEnabled) return@collect
            playPronunciation(word)
        }
    }

    if (showRemoveDialog) {
        RemoveLearningWordDialog(
            onConfirm = { handleAnswer(StudyRating.GOOD, removeFromNewWords = true) },
            onDismiss = { showRemoveDialog = false }
        )
    }
    if (showAiGuideDialog) {
        LearningAiGuideDialog { showAiGuideDialog = false }
    }
    if (showMemoryEditDialog) {
        LearningMemoryEditDialog(
            rewriteMark = rewriteMark,
            memoryDraft = memoryDraft,
            onDraftChange = { memoryDraft = it },
            memoryEditCanRestore = memoryEditCanRestore,
            onSave = { viewModel.saveMemoryEdit(memoryDraft) },
            onRestore = viewModel::restoreMemoryEdit,
            onDismiss = { showMemoryEditDialog = false }
        )
    }

    val nextReviewTime = currentProgress?.nextReviewTime ?: 0L
    val reviewTag = DateUtils.reviewTag(nextReviewTime)
    val reviewDescription = DateUtils.reviewDescription(nextReviewTime)
    val lastReviewTime = Sm2Scheduler.estimateLastReviewTime(currentProgress)
    val lastReviewText = lastReviewTime?.let { DateUtils.formatDateTime(it) } ?: "暂无"
    val nextReviewText = DateUtils.formatReviewDay(nextReviewTime).let { if (it == "无") "暂无" else it }

    if (showReviewDialog) {
        LearningReviewDialog(lastReviewText, nextReviewText, reviewDescription) { showReviewDialog = false }
    }

    if (showAiAssistantPage && currentWord != null && learningMode == LearningMode.RECOGNITION) {
        LearningAssistantContent(
            currentWord, aiState, cachedExampleContent, cachedMemoryAidContent,
            showMemoryAidSuggestion = memoryAidSuggestionWordId == currentWord.id,
            canGenerateExample = isCardFlipped,
            navDrawerDepthScale = navDrawerDepthScale,
            onBack = { showAiAssistantPage = false },
            viewModel = viewModel
        )
        return
    }

    LearningStudyFrame(
        styleDrawerState = styleDrawerState,
        navDrawerOpen = navDrawerOpen,
        navDrawerDepthScale = navDrawerDepthScale,
        cardUiPrefs = cardUiPrefs,
        cardStyle = cardStyle,
        systemAnimationsOn = systemAnimationsOn,
        lightTime = lightTime,
        onOpenDrawer = onOpenDrawer,
        uiScope = uiScope,
        appContext = appContext,
        currentIndex = currentIndex,
        totalCount = totalCount,
        learningPace = learningPace,
        motionEnabled = motionEnabled,
        overlay = {
            rewriteInstruction?.let { request ->
                InstructionInputBar(
                    placeholder = request.placeholder,
                    onSubmit = { text ->
                        rewriteInstruction = null
                        request.submit(text)
                    },
                    onDismissRequest = { rewriteInstruction = null },
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    ) {
        if (canRelieveReviewPressure) {
            ReviewPressureReliefBanner(
                dueReviewCount = dueReviewCount,
                dailyCap = reviewPressureDailyCap,
                onRelieve = viewModel::disperseReviewPressure,
                enabled = !isAnswering
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (currentWord == null) {
            LearningEmptyContent(waitingRewriteCount, checkInState, viewModel)
        } else {
            when (learningMode) {
                LearningMode.RECOGNITION -> {
                    val cardState = WordCardState(currentWord, isInNewWords)
                    LearningRecognitionLayout(
                        presentation = presentation,
                        motionEnabled = motionEnabled,
                        card = {
                            WordCard(
                                // 词对象可能是旧快照，卡背正文与方法标签都以库里的实时值为准
                                word = cardState.word.copy(
                                    approach = liveApproach ?: cardState.word.approach,
                                    method = liveMethod?.takeIf { it.isNotBlank() }
                                        ?: cardState.word.method
                                ),
                                isInNewWords = cardState.isInNewWords,
                                onToggleNewWords = viewModel::toggleNewWord,
                                reviewTag = reviewTag,
                                onReviewTagClick = { showReviewDialog = true },
                                aiAvailable = aiState.isAvailable,
                                aiSuggested = memoryAidSuggestionWordId == currentWord.id,
                                onAiEntryClick = ::openAiEntry,
                                onPronounceClick = { playPronunciation(cardState.word) },
                                showPronounceButton = pronunciationEnabled,
                                pronounceLoading = pronouncingWordId == cardState.word.id,
                                onFlipChanged = { flipped ->
                                    isCardFlipped = flipped
                                    // 翻到背面顺手同步一次；翻回正面不发请求
                                    if (flipped) viewModel.syncRewriteOnBackFlip()
                                },
                                starEnabled = !isAnswering,
                                cardBackConfig = cardBackConfig,
                                // 1.4.7.1：服务端标了 unverified 的那版思路，卡背挂个中性角标；
                                // 点角标展开原因。没这个字段（老服务端）就是 false，什么都不显示。
                                unverified = liveApproachReview.unverified,
                                unverifiedReason = liveApproachReview.reason,
                                memoryLocked = memoryLocked,
                                onEditMemory = {
                                    memoryDraft = liveApproach ?: cardState.word.approach
                                    showMemoryEditDialog = true
                                },
                                onToggleMemoryLock = viewModel::toggleMemoryLock,
                                backActions = { live ->
                                    LearningRewriteActions(
                                        viewModel = viewModel,
                                        wordId = cardState.word.id,
                                        mark = rewriteMark,
                                        models = rewriteModels,
                                        selectedModelId = rewriteModelId,
                                        onInstruction = { rewriteInstruction = it },
                                        feedback = rewriteFeedback,
                                        likeCount = rewriteLikeCount,
                                        enabled = live && !isAnswering,
                                        progress = rewriteProgress
                                    )
                                },
                                cardStyle = cardStyle,
                                glossHigh = cardUiPrefs.glossHigh,
                                motionMode = if (systemAnimationsOn) cardUiPrefs.motionMode else CardMotionMode.STATIC,
                                holoLight = holoLight,
                                modifier = Modifier.fillMaxHeight()
                            )
                        },
                        actions = {
                            RecognitionActionPanel(
                                isAnswering = isAnswering,
                                isNewWordsBook = isNewWordsBook,
                                resetKey = currentWord?.id,
                                onAgain = { handleAnswer(StudyRating.AGAIN) },
                                onHard = { handleAnswer(StudyRating.HARD) },
                                onGood = {
                                    if (isNewWordsBook) {
                                        showRemoveDialog = true
                                    } else {
                                        handleAnswer(StudyRating.GOOD)
                                    }
                                },
                                // 1.5.3v2 C1：重写完成后彻底收回「跳过」——DONE 落稳即隐藏，
                                // 不再靠 RESULT_READY 留一个灰色占位把三段胶囊挤窄。
                                showSkip = skipAvailability == SkipRewriteAvailability.AVAILABLE,
                                skipEnabled = skipAvailability == SkipRewriteAvailability.AVAILABLE,
                                onSkip = viewModel::skipCurrentWordForRewrite
                            )
                        }
                    )
                }
                LearningMode.SPELLING -> LearningSpellingContent(
                    currentWord, currentIndex, isAnswering, algorithmV4Enabled, aiState, viewModel
                )
            }
        }
    }
}

enum class LearningMode {
    RECOGNITION,
    SPELLING
}

private data class WordCardState(
    val word: Word,
    val isInNewWords: Boolean
)

internal fun String.adaptiveWordText(): String {
    val trimmed = trim()
    if (trimmed.length <= 12) return trimmed
    return trimmed.toCharArray().joinToString(separator = "\u200B")
}

package com.kaoyan.wordhelper.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.R
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.data.model.CardBackBlock
import com.kaoyan.wordhelper.data.model.CardBackBlockType
import com.kaoyan.wordhelper.data.model.CardBackColor
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.CardBackSize
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.ui.component.AIGeneratedBadge
import com.kaoyan.wordhelper.ui.component.AnimatedStarToggle
import com.kaoyan.wordhelper.ui.theme.AlertRed
import com.kaoyan.wordhelper.ui.theme.FuzzyYellow
import com.kaoyan.wordhelper.ui.theme.KnownGreen
import com.kaoyan.wordhelper.ui.viewmodel.LearningAiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningCheckInUiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel
import com.kaoyan.wordhelper.util.PronunciationPlayer
import com.kaoyan.wordhelper.util.rememberPronunciationPlayer
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.GestureHandler
import com.kaoyan.wordhelper.util.Sm2Scheduler
import com.kaoyan.wordhelper.util.SwipeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(
    viewModel: LearningViewModel = viewModel(),
    initialMode: LearningMode = LearningMode.RECOGNITION,
    onModeChange: (LearningMode) -> Unit = {}
) {
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val cardBackConfig by viewModel.cardBackConfig.collectAsStateWithLifecycle()
    val currentWord by viewModel.currentWord.collectAsStateWithLifecycle()
    // 卡背正文直接订阅库里的思路：电脑端结果一落库就换文，不依赖词对象什么时候被刷新
    val liveApproach by viewModel.liveApproach.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
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
    val swipeSnackbarEvent by viewModel.swipeSnackbarEvent.collectAsStateWithLifecycle()
    val showSwipeGuide by viewModel.showSwipeGuide.collectAsStateWithLifecycle()
    val checkInState by viewModel.checkInState.collectAsStateWithLifecycle()
    val rewriteMark by viewModel.currentRewriteMark.collectAsStateWithLifecycle()
    val rewriteModels by viewModel.rewriteModels.collectAsStateWithLifecycle()
    val rewriteModelId by viewModel.rewriteModelId.collectAsStateWithLifecycle()
    val rewriteProgress by viewModel.rewriteProgress.collectAsStateWithLifecycle()
    val rewriteUpdatedTick by viewModel.rewriteUpdatedTick.collectAsStateWithLifecycle()
    // 回到前台就补发断网时的标记、拉电脑端跑完的结果；没网也只是静默跳过。
    LifecycleResumeEffect(Unit) {
        viewModel.syncRewrite()
        onPauseOrDispose { }
    }
    var rewriteJustUpdated by remember { mutableStateOf(false) }
    LaunchedEffect(rewriteUpdatedTick) {
        if (rewriteUpdatedTick > 0) {
            rewriteJustUpdated = true
            delay(2_000)
            rewriteJustUpdated = false
        }
    }
    val context = LocalContext.current
    val pronunciationRepository = remember(context) {
        (context.applicationContext as KaoyanWordApp).pronunciationRepository
    }
    val pronunciationPlayer: PronunciationPlayer = rememberPronunciationPlayer()
    val uiScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showRemoveDialog by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showAiAssistantPage by rememberSaveable { mutableStateOf(false) }
    var showAiGuideDialog by remember { mutableStateOf(false) }
    var isCardFlipped by remember { mutableStateOf(false) }
    var lastRating by remember { mutableStateOf<StudyRating?>(null) }
    var pronouncingWordId by remember { mutableStateOf<Long?>(null) }
    var learningMode by rememberSaveable { mutableStateOf(initialMode) }
    var hideSwipeGuideInSession by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentWord?.id) {
        showReviewDialog = false
        showAiGuideDialog = false
        showAiAssistantPage = false
        isCardFlipped = false
    }

    LaunchedEffect(activeBook?.id) {
        lastRating = null
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

    LaunchedEffect(swipeSnackbarEvent?.id) {
        val event = swipeSnackbarEvent ?: return@LaunchedEffect
        val result = if (event.undoToken != null) {
            val dismissJob = launch {
                delay(3_000)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val snackbarResult = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Indefinite
            )
            dismissJob.cancel()
            snackbarResult
        } else {
            snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Short
            )
        }
        if (event.undoToken != null) {
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoSwipeTooEasy(event.undoToken)
            } else {
                viewModel.dismissSwipeUndo(event.undoToken)
            }
        }
        viewModel.consumeSwipeSnackbarEvent(event.id)
    }

    fun handleAnswer(rating: StudyRating, removeFromNewWords: Boolean = false) {
        if (isAnswering) return
        lastRating = rating
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
            val result = withContext(Dispatchers.IO) {
                pronunciationRepository.getPronunciationAudioUrl(
                    word = word.word,
                    preferredSource = pronunciationSource
                )
            }
            result.onSuccess { audioUrl ->
                pronunciationPlayer.play(
                    url = audioUrl,
                    onError = { errMsg ->
                        Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                    }
                )
            }.onFailure { throwable ->
                Toast.makeText(context, throwable.message ?: "单词发音失败", Toast.LENGTH_SHORT).show()
            }
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
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(text = "移出生词本") },
            text = { Text(text = "确认将该单词从生词本移出吗？") },
            confirmButton = {
                TextButton(onClick = {
                    handleAnswer(StudyRating.GOOD, removeFromNewWords = true)
                    showRemoveDialog = false
                }) {
                    Text(text = "移出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    if (showAiGuideDialog) {
        AlertDialog(
            onDismissRequest = { showAiGuideDialog = false },
            title = { Text(text = "AI 功能未配置") },
            text = { Text(text = "请先在“我的 - AI 实验室”配置 API Key 解锁智能助记。") },
            confirmButton = {
                TextButton(onClick = { showAiGuideDialog = false }) {
                    Text(text = "知道了", modifier = Modifier.testTag("learning_ai_guide_confirm"))
                }
            }
        )
    }

    if (
        showSwipeGuide &&
        !hideSwipeGuideInSession &&
        learningMode == LearningMode.RECOGNITION &&
        currentWord != null
    ) {
        SwipeGestureCoachMarkDialog(
            onDismiss = { hideSwipeGuideInSession = true },
            onNeverShowAgain = {
                hideSwipeGuideInSession = true
                viewModel.dismissSwipeGuide()
            }
        )
    }

    val nextReviewTime = currentProgress?.nextReviewTime ?: 0L
    val reviewTag = DateUtils.reviewTag(nextReviewTime)
    val reviewDescription = DateUtils.reviewDescription(nextReviewTime)
    val lastReviewTime = Sm2Scheduler.estimateLastReviewTime(currentProgress)
    val lastReviewText = lastReviewTime?.let { DateUtils.formatDateTime(it) } ?: "暂无"
    val nextReviewText = DateUtils.formatReviewDay(nextReviewTime).let { if (it == "无") "暂无" else it }

    if (showReviewDialog) {
        AlertDialog(
            onDismissRequest = { showReviewDialog = false },
            title = {
                Text(
                    text = "复习时间",
                    modifier = Modifier.testTag("review_dialog_title")
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "上次复习：$lastReviewText",
                        modifier = Modifier.testTag("review_last_review_time")
                    )
                    Text(
                        text = "下次复习：$nextReviewText",
                        modifier = Modifier.testTag("review_next_review_time")
                    )
                    Text(
                        text = "提示：$reviewDescription",
                        modifier = Modifier.testTag("review_hint")
                    )
                    Text(text = SM2_DESCRIPTION)
                }
            },
            confirmButton = {
                TextButton(onClick = { showReviewDialog = false }) {
                    Text(
                        text = "知道了",
                        modifier = Modifier.testTag("review_dialog_confirm")
                    )
                }
            }
        )
    }

    if (showAiAssistantPage && currentWord != null && learningMode == LearningMode.RECOGNITION) {
        RecognitionAiAssistantPage(
            word = currentWord!!,
            aiState = aiState,
            cachedExampleContent = cachedExampleContent,
            cachedMemoryAidContent = cachedMemoryAidContent,
            showMemoryAidSuggestion = memoryAidSuggestionWordId == currentWord!!.id,
            canGenerateExample = isCardFlipped,
            onBack = { showAiAssistantPage = false },
            onGenerateExample = { forceRefresh -> viewModel.requestAiExample(forceRefresh) },
            onGenerateMemoryAid = { forceRefresh -> viewModel.requestAiMemoryAid(forceRefresh) }
        )
        return
    }

    val pageBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
            MaterialTheme.colorScheme.background
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 1.4.3：卡背输入框唤起键盘时，整页让到键盘上方，
                // 否则「重写思路」按钮会被键盘盖住，点不到（edge-to-edge 默认不会让）。
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TabRow(
                        selectedTabIndex = learningMode.ordinal,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        divider = {},
                        indicator = {}
                    ) {
                        Tab(
                            selected = learningMode == LearningMode.RECOGNITION,
                            onClick = {
                                learningMode = LearningMode.RECOGNITION
                                onModeChange(learningMode)
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("learning_mode_recognition"),
                            text = {
                                Text(
                                    text = "认词模式",
                                    fontWeight = if (learningMode == LearningMode.RECOGNITION) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                            }
                        )
                        Tab(
                            selected = learningMode == LearningMode.SPELLING,
                            onClick = {
                                learningMode = LearningMode.SPELLING
                                onModeChange(learningMode)
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("learning_mode_spelling"),
                            text = {
                                Text(
                                    text = "拼写模式",
                                    fontWeight = if (learningMode == LearningMode.SPELLING) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (checkInState.showCompletionPanel) {
                        LearningCompletionCheckInCard(
                            state = checkInState,
                            onCheckIn = viewModel::completeTodayCheckIn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("learning_completion_checkin_card")
                        )
                    } else {
                        Text(text = "暂无单词", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                when (learningMode) {
                    LearningMode.RECOGNITION -> {
                        val cardState = WordCardState(currentWord!!, isInNewWords)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(RECOGNITION_SECTION_SPACING)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedContent(
                                    targetState = cardState,
                                    transitionSpec = { wordCardTransition(lastRating) },
                                    // 同词内的状态变化（如点星星改生词本）不该重建卡片，
                                    // 否则 remember 的翻面状态会被重置、还会多播一次切换动画。
                                    contentKey = { it.word.id },
                                    label = "wordCardTransition"
                                ) { state ->
                                    SwipeableWordCard(
                                        enabled = !isAnswering,
                                        onSwipeTooEasy = viewModel::onSwipeTooEasy,
                                        onSwipeAddToNotebook = viewModel::onSwipeAddToNotebook,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight()
                                            .testTag("learning_word_card")
                                    ) { swipeModifier ->
                                        WordCard(
                                            // 词对象可能是旧快照，卡背正文以库里的实时值为准
                                            word = liveApproach?.let { state.word.copy(approach = it) }
                                                ?: state.word,
                                            isInNewWords = state.isInNewWords,
                                            onToggleNewWords = viewModel::toggleNewWord,
                                            reviewTag = reviewTag,
                                            onReviewTagClick = { showReviewDialog = true },
                                            aiAvailable = aiState.isAvailable,
                                            aiSuggested = memoryAidSuggestionWordId == currentWord!!.id,
                                            onAiEntryClick = ::openAiEntry,
                                            onPronounceClick = { playPronunciation(state.word) },
                                            showPronounceButton = pronunciationEnabled,
                                            pronounceLoading = pronouncingWordId == state.word.id,
                                            onFlipChanged = { flipped ->
                                                isCardFlipped = flipped
                                                // 翻到背面顺手同步一次；翻回正面不发请求
                                                if (flipped) viewModel.syncRewriteOnBackFlip()
                                            },
                                            starEnabled = !isAnswering,
                                            cardBackConfig = cardBackConfig,
                                            // 1.4.3.1：重写条挪进卡背底部，只在背面出现
                                            backActions = {
                                                RewriteBar(
                                                    wordId = state.word.id,
                                                    mark = rewriteMark,
                                                    models = rewriteModels,
                                                    selectedModelId = rewriteModelId,
                                                    onSelectModel = viewModel::selectRewriteModel,
                                                    onMark = viewModel::markRewrite,
                                                    onCancel = viewModel::cancelRewrite,
                                                    onUndo = viewModel::undoRewrite,
                                                    enabled = !isAnswering,
                                                    progress = rewriteProgress,
                                                    justUpdated = rewriteJustUpdated
                                                )
                                            },
                                            modifier = swipeModifier.fillMaxHeight()
                                        )
                                    }
                                }
                            }
                            RecognitionActionPanel(
                                isAnswering = isAnswering,
                                isNewWordsBook = isNewWordsBook,
                                onAgain = { handleAnswer(StudyRating.AGAIN) },
                                onHard = { handleAnswer(StudyRating.HARD) },
                                onGood = {
                                    if (isNewWordsBook) {
                                        showRemoveDialog = true
                                    } else {
                                        handleAnswer(StudyRating.GOOD)
                                    }
                                }
                            )
                        }
                    }

                    LearningMode.SPELLING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SpellingScreen(
                                word = currentWord,
                                presentationKey = currentIndex,
                                isSubmitting = isAnswering,
                                algorithmV4Enabled = algorithmV4Enabled,
                                onSpellingResolved = viewModel::submitSpellingOutcome,
                                onContinueAfterFailure = viewModel::continueAfterSpellingFailure,
                                aiAssistAvailable = aiState.isAvailable,
                                aiAssistLoading = aiState.isLoading && aiState.activeType == AIContentType.MEMORY_AID,
                                aiAssistText = if (aiState.activeType == AIContentType.MEMORY_AID) {
                                    aiState.content.ifBlank { null }
                                } else {
                                    null
                                },
                                aiAssistError = if (aiState.activeType == AIContentType.MEMORY_AID) {
                                    aiState.error
                                } else {
                                    null
                                },
                                onRequestAiAssist = { forceRefresh ->
                                    viewModel.requestAiMemoryAid(forceRefresh)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
            }

            BottomLearningProgressBar(
                currentIndex = currentIndex,
                totalCount = totalCount,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

private const val SM2_DESCRIPTION =
    "SM-2 调度说明：不认识会在会话内重试，模糊保守推进，认识按记忆因子增长间隔。"
private val RECOGNITION_SECTION_SPACING = 10.dp
private const val SWIPE_TRIGGER_RATIO = 0.3f
private const val SWIPE_ACTIVE_ZONE_RATIO = 0.8f

@Composable
private fun LearningCompletionCheckInCard(
    state: LearningCheckInUiState,
    onCheckIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "今日背词完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = if (state.checkedInToday) {
                    if (state.todayCheckInTimeText.isNotBlank()) {
                        "今日已打卡 · ${state.todayCheckInTimeText}"
                    } else {
                        "今日已打卡"
                    }
                } else {
                    "今天的学习任务已经完成，点击完成今日打卡。"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("learning_completion_checkin_status")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CompletionMetric(label = "今日学习", value = state.todayStudyCount.toString(), unit = "词")
                CompletionMetric(label = "连续打卡", value = state.streakDays.toString(), unit = "天")
                CompletionMetric(label = "本周打卡", value = state.weekCheckInDays.toString(), unit = "天")
            }
            FilledTonalButton(
                onClick = onCheckIn,
                enabled = !state.checkedInToday,
                modifier = Modifier.testTag("learning_completion_checkin_button")
            ) {
                Text(text = if (state.checkedInToday) "今日已打卡" else "完成今日打卡")
            }
        }
    }
}

@Composable
private fun CompletionMetric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private enum class SwipeDirection {
    LEFT,
    RIGHT,
    NONE
}

@Composable
private fun SwipeableWordCard(
    enabled: Boolean,
    onSwipeTooEasy: () -> Unit,
    onSwipeAddToNotebook: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var offsetX by remember { mutableStateOf(0f) }
    var allowDrag by remember { mutableStateOf(false) }
    var thresholdHapticTriggered by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val threshold = GestureHandler.triggerThreshold(widthPx, SWIPE_TRIGGER_RATIO)
        val edgeReserved = GestureHandler.edgeReservedWidth(widthPx, SWIPE_ACTIVE_ZONE_RATIO)
        val swipeProgress = (abs(offsetX) / threshold).coerceIn(0f, 1f)

        SwipeBackgroundIndicator(
            offsetX = offsetX,
            progress = swipeProgress,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(enabled, widthPx) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            allowDrag = GestureHandler.isStartInActiveZone(
                                startX = startOffset.x,
                                widthPx = widthPx,
                                activeZoneRatio = SWIPE_ACTIVE_ZONE_RATIO
                            )
                            thresholdHapticTriggered = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!allowDrag) return@detectHorizontalDragGestures
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-widthPx, widthPx)
                            if (!thresholdHapticTriggered && abs(offsetX) >= threshold) {
                                thresholdHapticTriggered = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = {
                            val finalOffset = offsetX
                            allowDrag = false
                            thresholdHapticTriggered = false
                            when (GestureHandler.resolveSwipeAction(finalOffset, threshold)) {
                                SwipeAction.TOO_EASY -> {
                                    offsetX = 0f
                                    onSwipeTooEasy()
                                }

                                SwipeAction.ADD_TO_NOTEBOOK -> {
                                    offsetX = 0f
                                    onSwipeAddToNotebook()
                                }

                                SwipeAction.NONE -> {
                                    scope.launch {
                                        animate(finalOffset, 0f) { value, _ ->
                                            offsetX = value
                                        }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            allowDrag = false
                            thresholdHapticTriggered = false
                            val finalOffset = offsetX
                            scope.launch {
                                animate(finalOffset, 0f) { value, _ ->
                                    offsetX = value
                                }
                            }
                        }
                    )
                }
        ) {
            content(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SwipeBackgroundIndicator(
    offsetX: Float,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val direction = when {
        offsetX < 0f -> SwipeDirection.LEFT
        offsetX > 0f -> SwipeDirection.RIGHT
        else -> SwipeDirection.NONE
    }
    val backgroundColor = when (direction) {
        SwipeDirection.LEFT -> KnownGreen.copy(alpha = 0.08f + 0.22f * progress)
        SwipeDirection.RIGHT -> FuzzyYellow.copy(alpha = 0.10f + 0.24f * progress)
        SwipeDirection.NONE -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentAlignment = when (direction) {
                SwipeDirection.LEFT -> Alignment.CenterStart
                SwipeDirection.RIGHT -> Alignment.CenterEnd
                SwipeDirection.NONE -> Alignment.Center
            }
        ) {
            when (direction) {
                SwipeDirection.LEFT -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_swipe_left),
                            contentDescription = null,
                            tint = KnownGreen.copy(alpha = 0.4f + 0.6f * progress)
                        )
                        Text(
                            text = "太简单（30天）",
                            color = KnownGreen.copy(alpha = 0.5f + 0.5f * progress),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SwipeDirection.RIGHT -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_swipe_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f + 0.5f * progress)
                        )
                        Text(
                            text = "加入生词本",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f + 0.5f * progress),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SwipeDirection.NONE -> {
                    Text(
                        text = "左滑太简单 · 右滑加入生词本",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeGestureCoachMarkDialog(
    onDismiss: () -> Unit,
    onNeverShowAgain: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "手势快捷操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "左滑超过 30%：标记“太简单”，30 天后复习。")
                Text(text = "右滑超过 30%：快速加入生词本，并切换到下一词。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "知道了",
                    modifier = Modifier.testTag("learning_swipe_guide_confirm")
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onNeverShowAgain) {
                Text(
                    text = "不再提示",
                    modifier = Modifier.testTag("learning_swipe_guide_never_again")
                )
            }
        }
    )
}

@Composable
private fun BottomLearningProgressBar(
    currentIndex: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    val rawProgress = if (totalCount > 0) currentIndex.toFloat() / totalCount else 0f
    val progress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 260),
        label = "learningBottomProgress"
    )
    val progressText = if (totalCount > 0) "$currentIndex/$totalCount" else "0/0"

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "学习进度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        )
    }
}

@Composable
private fun RecognitionAiAssistantPage(
    word: Word,
    aiState: LearningAiState,
    cachedExampleContent: String,
    cachedMemoryAidContent: String,
    showMemoryAidSuggestion: Boolean,
    canGenerateExample: Boolean,
    onBack: () -> Unit,
    onGenerateExample: (Boolean) -> Unit,
    onGenerateMemoryAid: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var exampleContent by remember(word.id) { mutableStateOf(cachedExampleContent) }
    var memoryAidContent by remember(word.id) { mutableStateOf(cachedMemoryAidContent) }
    val scrollState = rememberScrollState()
    val displayWord = remember(word.word) { word.word.adaptiveWordText() }

    LaunchedEffect(cachedExampleContent) {
        if (cachedExampleContent.isNotBlank()) {
            exampleContent = cachedExampleContent
        }
    }

    LaunchedEffect(cachedMemoryAidContent) {
        if (cachedMemoryAidContent.isNotBlank()) {
            memoryAidContent = cachedMemoryAidContent
        }
    }

    LaunchedEffect(aiState.activeType, aiState.content) {
        when (aiState.activeType) {
            AIContentType.EXAMPLE -> if (aiState.content.isNotBlank()) exampleContent = aiState.content
            AIContentType.MEMORY_AID -> if (aiState.content.isNotBlank()) memoryAidContent = aiState.content
            AIContentType.SENTENCE, AIContentType.WORD_TRANSLATION, null -> Unit
        }
    }

    val isExampleLoading = aiState.activeType == AIContentType.EXAMPLE && aiState.isLoading
    val isMemoryAidLoading = aiState.activeType == AIContentType.MEMORY_AID && aiState.isLoading
    val exampleError = if (aiState.activeType == AIContentType.EXAMPLE) aiState.error else null
    val memoryAidError = if (aiState.activeType == AIContentType.MEMORY_AID) aiState.error else null
    val hasExampleContent = exampleContent.isNotBlank()
    val hasMemoryAidContent = memoryAidContent.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("learning_ai_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("learning_ai_page_back")) {
                Text(text = "返回认词")
            }
            Text(
                text = "AI 学习助手",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.size(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前单词",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = displayWord,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "例句和助记会在本页展示，生成内容仅供学习参考。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showMemoryAidSuggestion) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = AlertRed.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "刚才答错了，建议优先生成助记技巧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlertRed,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            if (!aiState.isAvailable) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                ) {
                    Text(
                        text = "AI 未启用或未配置，请先前往“我的 - AI 实验室”完成配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("learning_ai_page_unavailable")
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "例句生成",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!canGenerateExample) {
                        Text(
                            text = "请先翻卡查看释义后再生成例句。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isExampleLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("learning_ai_page_example_loading")
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(text = "正在生成例句...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!exampleError.isNullOrBlank()) {
                        Text(
                            text = exampleError,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed,
                            modifier = Modifier.testTag("learning_ai_page_example_error")
                        )
                    }
                    if (hasExampleContent) {
                        AIContentResultCard(
                            title = "AI 生成例句",
                            content = exampleContent,
                            modifier = Modifier.testTag("learning_ai_page_example_content")
                        )
                    }
                    OutlinedButton(
                        onClick = { onGenerateExample(hasExampleContent) },
                        enabled = aiState.isAvailable && !aiState.isLoading && canGenerateExample,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("learning_ai_page_example_generate")
                    ) {
                        Text(text = if (hasExampleContent) "重新生成例句" else "生成例句")
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "助记技巧",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isMemoryAidLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("learning_ai_page_memory_loading")
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(text = "正在生成助记...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!memoryAidError.isNullOrBlank()) {
                        Text(
                            text = memoryAidError,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed,
                            modifier = Modifier.testTag("learning_ai_page_memory_error")
                        )
                    }
                    if (hasMemoryAidContent) {
                        AIContentResultCard(
                            title = "AI 助记技巧",
                            content = memoryAidContent,
                            modifier = Modifier.testTag("learning_ai_page_memory_content")
                        )
                    }
                    OutlinedButton(
                        onClick = { onGenerateMemoryAid(hasMemoryAidContent) },
                        enabled = aiState.isAvailable && !aiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("learning_ai_page_memory_generate")
                    ) {
                        Text(text = if (hasMemoryAidContent) "重新生成助记" else "生成助记")
                    }
                }
            }
        }
    }
}

@Composable
fun LearningAiActionSheetContent(
    isAvailable: Boolean,
    showExampleAction: Boolean,
    showMemoryAidSuggestion: Boolean,
    hasExampleContent: Boolean,
    hasMemoryContent: Boolean,
    isLoading: Boolean,
    onGenerateExample: () -> Unit,
    onRegenerateExample: () -> Unit,
    onGenerateMemoryAid: () -> Unit,
    onRegenerateMemoryAid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("learning_ai_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "AI 助手", style = MaterialTheme.typography.titleLarge)
        if (!isAvailable) {
            Text(
                text = "AI 未启用或未配置，请先在“我的 - AI 实验室”完成设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        if (showExampleAction) {
            OutlinedButton(
                onClick = { if (hasExampleContent) onRegenerateExample() else onGenerateExample() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("learning_ai_action_example")
            ) {
                Text(text = if (hasExampleContent) "重新生成例句" else "生成例句")
            }
        } else {
            Text(
                text = "翻卡后可使用例句生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            onClick = { if (hasMemoryContent) onRegenerateMemoryAid() else onGenerateMemoryAid() },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("learning_ai_action_memory")
        ) {
            val text = when {
                showMemoryAidSuggestion && hasMemoryContent -> "重新生成助记（推荐）"
                showMemoryAidSuggestion -> "生成助记（推荐）"
                hasMemoryContent -> "重新生成助记"
                else -> "生成助记"
            }
            Text(text = text)
        }
    }
}

@Composable
fun LearningAiResultPanel(
    aiState: LearningAiState,
    showMemoryAidSuggestion: Boolean,
    onRetryExample: () -> Unit,
    onRetryMemoryAid: () -> Unit,
    onOpenAiEntry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isExampleContext = aiState.activeType == AIContentType.EXAMPLE
    val isMemoryAidContext = aiState.activeType == AIContentType.MEMORY_AID
    val shouldShow = showMemoryAidSuggestion ||
        ((isExampleContext || isMemoryAidContext) && (
            aiState.isLoading ||
                aiState.content.isNotBlank() ||
                !aiState.error.isNullOrBlank()
            ))
    if (!shouldShow) return

    val loadingText = when (aiState.activeType) {
        AIContentType.EXAMPLE -> "正在生成例句..."
        AIContentType.MEMORY_AID -> "正在生成助记..."
        AIContentType.SENTENCE -> "正在生成内容..."
        AIContentType.WORD_TRANSLATION -> "正在生成翻译..."
        null -> "正在请求 AI..."
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("learning_ai_panel"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showMemoryAidSuggestion && aiState.activeType == null && aiState.content.isBlank()) {
                Text(
                    text = "刚才答错了，点击灯泡生成 AI 助记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertRed,
                    modifier = Modifier.testTag("learning_ai_suggestion")
                )
            }

            if (aiState.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("learning_ai_loading")
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(text = loadingText, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!aiState.error.isNullOrBlank()) {
                Text(
                    text = aiState.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertRed,
                    modifier = Modifier.testTag("learning_ai_error")
                )
                if (aiState.isAvailable && aiState.activeType != null && !aiState.isLoading) {
                    OutlinedButton(
                        onClick = {
                            when (aiState.activeType) {
                                AIContentType.EXAMPLE -> onRetryExample()
                                AIContentType.MEMORY_AID -> onRetryMemoryAid()
                                AIContentType.SENTENCE -> onOpenAiEntry()
                                AIContentType.WORD_TRANSLATION -> onOpenAiEntry()
                            }
                        },
                        modifier = Modifier.testTag("learning_ai_retry")
                    ) {
                        Text(text = "重试")
                    }
                }
            }

            if (aiState.content.isNotBlank() && aiState.activeType != null) {
                val title = when (aiState.activeType) {
                    AIContentType.EXAMPLE -> "AI 生成例句"
                    AIContentType.MEMORY_AID -> "AI 助记技巧"
                    AIContentType.SENTENCE -> "AI 内容"
                    AIContentType.WORD_TRANSLATION -> "AI 中文翻译"
                }
                if (aiState.activeType == AIContentType.EXAMPLE || aiState.activeType == AIContentType.MEMORY_AID) {
                    AIContentResultCard(title = title, content = aiState.content)
                }
            }
        }
    }
}

@Composable
fun AIContentResultCard(
    title: String,
    content: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("learning_ai_content")
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "内容由 AI 生成，请甄别参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AIGeneratedBadge(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .testTag("learning_ai_badge")
        )
    }
}

@Composable
private fun ReviewPressureReliefBanner(
    dueReviewCount: Int,
    dailyCap: Int,
    onRelieve: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
        ),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "今日待复习 $dueReviewCount，超过上限 $dailyCap",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "点击后会把超出部分均匀顺延到后续天。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            OutlinedButton(onClick = onRelieve, enabled = enabled) {
                Text(text = "分散压力")
            }
        }
    }
}

@Composable
private fun RecognitionActionPanel(
    isAnswering: Boolean,
    isNewWordsBook: Boolean,
    onAgain: () -> Unit,
    onHard: () -> Unit,
    onGood: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
        ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onAgain,
                enabled = !isAnswering,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("learning_action_again"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AlertRed.copy(alpha = 0.18f),
                    contentColor = AlertRed
                )
            ) {
                Text(text = "不认识")
            }
            FilledTonalButton(
                onClick = onHard,
                enabled = !isAnswering,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("learning_action_hard"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FuzzyYellow.copy(alpha = 0.28f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(text = "模糊")
            }
            val goodText = if (isNewWordsBook) "认识并移出" else "认识"
            FilledTonalButton(
                onClick = onGood,
                enabled = !isAnswering,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("learning_action_good"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = KnownGreen.copy(alpha = 0.22f),
                    contentColor = KnownGreen
                )
            ) {
                Text(text = goodText, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun WordCard(
    word: Word,
    isInNewWords: Boolean,
    onToggleNewWords: () -> Unit,
    reviewTag: String,
    onReviewTagClick: () -> Unit,
    aiAvailable: Boolean,
    aiSuggested: Boolean,
    onAiEntryClick: () -> Unit,
    onPronounceClick: () -> Unit,
    showPronounceButton: Boolean,
    pronounceLoading: Boolean,
    onFlipChanged: (Boolean) -> Unit,
    starEnabled: Boolean,
    cardBackConfig: CardBackConfig,
    backActions: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFlipped by remember(word.id) { mutableStateOf(false) }
    val normalizedWordLength = word.word.trim().length
    val displayWord = remember(word.word) { word.word.adaptiveWordText() }
    val typography = MaterialTheme.typography
    val frontStyleCandidates = listOf(
        typography.displayLarge,
        typography.displayMedium,
        typography.headlineLarge,
        typography.headlineMedium,
        typography.titleLarge,
        typography.titleMedium,
        typography.titleSmall,
        typography.bodyLarge,
        typography.bodyMedium,
        typography.bodySmall,
        typography.labelLarge
    )
    val preferredFrontStyleIndex = when {
        normalizedWordLength <= 12 -> 0
        normalizedWordLength <= 16 -> 1
        normalizedWordLength <= 20 -> 2
        normalizedWordLength <= 24 -> 3
        normalizedWordLength <= 32 -> 4
        normalizedWordLength <= 40 -> 5
        normalizedWordLength <= 56 -> 6
        normalizedWordLength <= 72 -> 7
        normalizedWordLength <= 96 -> 8
        normalizedWordLength <= 128 -> 9
        else -> 10
    }
    val frontWordStyle = frontStyleCandidates[preferredFrontStyleIndex]
    val frontFallbackStyles = frontStyleCandidates.drop(preferredFrontStyleIndex + 1)
    val backWordStyle = when {
        normalizedWordLength <= 16 -> typography.titleLarge
        normalizedWordLength <= 24 -> typography.titleMedium
        normalizedWordLength <= 32 -> typography.titleSmall
        else -> typography.bodyLarge
    }
    val wordMaxLines = when {
        normalizedWordLength <= 20 -> 1
        normalizedWordLength <= 32 -> 2
        normalizedWordLength <= 48 -> 3
        normalizedWordLength <= 72 -> 4
        else -> 5
    }
    val backContentScrollState = rememberScrollState()
    LaunchedEffect(word.id) {
        onFlipChanged(false)
        backContentScrollState.scrollTo(0)
    }
    val density = LocalDensity.current
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "wordCardRotation"
    )
    val showFront = rotation <= 90f

    val frontBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f),
            MaterialTheme.colorScheme.surface
        )
    )
    val backBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
            MaterialTheme.colorScheme.surface
        )
    )

    val tapMaxDragPx = with(density) { 4.dp.toPx() }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.26f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .cardFlipTap(tapMaxDragPx) {
                    isFlipped = !isFlipped
                    onFlipChanged(isFlipped)
                }
                .background(if (showFront) frontBrush else backBrush)
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .heightIn(min = 260.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReviewTimeTag(text = reviewTag, onClick = onReviewTagClick)
                    if (showPronounceButton) {
                        OutlinedButton(
                            onClick = onPronounceClick,
                            enabled = !pronounceLoading,
                            modifier = Modifier.testTag("learning_pronounce_button")
                        ) {
                            if (pronounceLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(text = "发音")
                            }
                        }
                    }
                }
                AnimatedStarToggle(
                    checked = isInNewWords,
                    onCheckedChange = { onToggleNewWords() },
                    enabled = starEnabled,
                    modifier = Modifier.testTag("learning_star_toggle")
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            rotationY = rotation
                            cameraDistance = 12f * density.density
                        },
                    contentAlignment = if (showFront) Alignment.Center else Alignment.TopCenter
                ) {
                    if (showFront) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                        ) {
                            AdaptiveWordText(
                                text = displayWord,
                                preferredStyle = frontWordStyle,
                                fallbackStyles = frontFallbackStyles,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp),
                                textAlign = TextAlign.Center,
                                maxLines = wordMaxLines
                            )
                            if (word.phonetic.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        text = word.phonetic,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(
                                text = "轻触卡片查看释义与例句",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // 1.4.3.2：重写区随卡背内容一起滚，排在正文最后，不再固定贴卡片底边。
                        CardBackBlocks(
                            config = cardBackConfig,
                            word = word,
                            displayWord = displayWord,
                            backWordStyle = backWordStyle,
                            scrollState = backContentScrollState,
                            backActions = backActions
                        )
                    }
                }
                if (aiAvailable) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clickable(enabled = starEnabled, onClick = onAiEntryClick)
                            .testTag("learning_ai_bulb"),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                tint = if (aiSuggested) AlertRed else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (aiSuggested) "助记推荐" else "AI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            // 1.4.3.2：操作区已挪进卡背滚动内容（CardBackBlocks 的 backActions），
            // 这里不再固定贴底，避免正文被切走两百多像素。
        }
    }
}

/**
 * 卡背内容区（1.4.2）：块顺序 / 显隐 / 标题文案 / 颜色 / 字号全部来自 [CardBackConfig]。
 *
 * 红线：默认清单（CardBackDefaults.DEFAULT_JSON）渲染出来必须与 1.4.1 逐像素一致。
 * 所以每个块的字体、内边距、分隔线、圆角都照抄原实现；配置里的 color / size 只是
 * 「可选覆盖」，不填时就用该类型的原生样式。
 */
@Composable
private fun CardBackBlocks(
    config: CardBackConfig,
    word: Word,
    displayWord: String,
    backWordStyle: TextStyle,
    scrollState: androidx.compose.foundation.ScrollState,
    backActions: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .graphicsLayer { rotationY = 180f }
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(config.spacingDp.dp)
    ) {
        config.renderableBlocks.forEach { block ->
            CardBackBlockView(
                block = block,
                word = word,
                displayWord = displayWord,
                backWordStyle = backWordStyle
            )
        }
        // 1.4.3.2：重写区放在正文最后，跟着滚动；用分隔线把「内容」和「操作」分开。
        if (backActions != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉空白区的点击，免得点状态文字时把卡片翻回正面
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            ) {
                backActions()
            }
        }
    }
}

/**
 * 渲染单个块。它会向父 Column 吐**多个**兄弟节点（例如 MEANING 的标题 + 正文），
 * 这是有意的：1.4.1 里它们本来就是 spacedBy(12dp) 的兄弟，套一层嵌套 Column 会改间距。
 *
 * `type == null` 表示手机还不认识这个块，直接跳过；汇总提示由外层负责。
 */
@Composable
private fun CardBackBlockView(
    block: CardBackBlock,
    word: Word,
    displayWord: String,
    backWordStyle: TextStyle
) {
    val overrideColor = block.style.color.resolveCardBackColor()
    val bodyStyle = block.style.size
    when (block.type) {
        CardBackBlockType.TITLE -> Text(
            text = displayWord,
            style = backWordStyle,
            fontWeight = FontWeight.SemiBold,
            color = overrideColor ?: androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.fillMaxWidth()
        )

        CardBackBlockType.PHONETIC -> {
            // 音标与「这一招」标签同一行：标签回答的是「这条思路靠什么记」，不占版式层次。
            val methodLabel = methodLabelOf(word.method)
            if (word.phonetic.isNotBlank() || methodLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (word.phonetic.isNotBlank()) {
                        Text(
                            text = word.phonetic,
                            style = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle),
                            color = overrideColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (methodLabel != null) {
                        MemoryMethodChip(methodLabel)
                    }
                }
            }
        }

        CardBackBlockType.DIVIDER -> HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        )

        CardBackBlockType.MEANING -> if (word.meaning.isNotBlank()) {
            if (!block.resolvedTitle.isNullOrEmpty()) {
                Text(
                    text = block.resolvedTitle.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = overrideColor ?: MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = formatMeaningByPos(word.meaning),
                style = MaterialTheme.typography.bodyLarge.scaledBy(bodyStyle),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        CardBackBlockType.MEMORY -> WordMemorySection(
            title = block.resolvedTitle,
            content = word.approach,
            titleColor = overrideColor ?: MaterialTheme.colorScheme.primary,
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle)
        )

        CardBackBlockType.COLLOCATIONS -> WordMemorySection(
            title = block.resolvedTitle,
            content = word.collocations,
            titleColor = overrideColor ?: MaterialTheme.colorScheme.primary,
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle)
        )

        CardBackBlockType.EXAMPLE -> if (word.example.isNotBlank()) {
            val exampleTitle = block.resolvedTitle
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Text(
                    text = if (exampleTitle.isNullOrEmpty()) word.example
                    else "$exampleTitle\n${word.example}",
                    style = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle),
                    color = overrideColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        CardBackBlockType.PHRASES -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.phrases,
            titleColor = overrideColor ?: MaterialTheme.colorScheme.primary,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        CardBackBlockType.SYNONYMS -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.synonyms,
            titleColor = overrideColor ?: MaterialTheme.colorScheme.primary,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        CardBackBlockType.REL_WORDS -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.relWords,
            titleColor = overrideColor ?: MaterialTheme.colorScheme.primary,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        // 1.4.3.1：EF 系数因子不再展示（easeFactor 的计算与存储不变）
        CardBackBlockType.EF_STATS -> Unit

        null -> Unit
    }
}

/**
 * 语义色 → 具体颜色。
 *
 * `null` 和 `DEFAULT` 都表示「不覆盖」，走该块的原生标题色 —— 这样默认清单才能和
 * 1.4.1 一模一样。要强行压成正文色用 `MUTED`（onSurfaceVariant）。
 */
@Composable
private fun CardBackColor.resolveCardBackColor(): androidx.compose.ui.graphics.Color? =
    when (this) {
        CardBackColor.DEFAULT -> null
        CardBackColor.PRIMARY -> MaterialTheme.colorScheme.primary
        CardBackColor.SECONDARY -> MaterialTheme.colorScheme.secondary
        CardBackColor.ACCENT -> MaterialTheme.colorScheme.tertiary
        CardBackColor.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        CardBackColor.WARNING -> MaterialTheme.colorScheme.error
    }

/** 字号三档 → 缩放后的样式；null / M 原样返回，保证默认清单零变化。 */
private fun TextStyle.scaledBy(size: CardBackSize?): TextStyle {
    if (size == null || size == CardBackSize.M) return this
    val factor = if (size == CardBackSize.S) 0.9f else 1.15f
    val scaledFontSize = if (fontSize.isSpecified) (fontSize.value * factor).sp else fontSize
    val scaledLineHeight = if (lineHeight.isSpecified) (lineHeight.value * factor).sp else lineHeight
    return copy(fontSize = scaledFontSize, lineHeight = scaledLineHeight)
}

/**
 * 卡片「轻触翻面」手势，替代原来的 Card(onClick)。
 *
 * 不用 Card(onClick)/clickable 的原因：
 * 1) Material 的 indication 会给整张卡片画按压态涟漪，也就是用户看到的「水波纹」；
 * 2) clickable 默认 awaitFirstDown(requireUnconsumed = true)，卡背滚动或惯性 fling
 *    一旦消费了按下事件，点击就再也翻不回正面。
 *
 * 判定规则：按下 → 中途位移不超过 [tapMaxDragPx] → 抬起，且抬起事件没有被内部
 * 滚动手势消费，才算一次点击。全程不消费任何事件、不画任何视觉反馈。
 *
 * 挂在内层 Column 上、监听 Main 阶段：Main 从叶子往根传播，星星/发音/AI 灯泡/
 * 卡背 verticalScroll 都先于本节点处理，它们消费掉 up 之后这里才读 isConsumed。
 */
private fun Modifier.cardFlipTap(
    tapMaxDragPx: Float,
    onClick: () -> Unit
): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    pointerInput(tapMaxDragPx) {
        awaitEachGesture {
            // Initial 阶段拿按下：此时还没有内部手势消费事件，滚动惯性也拦不住。
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val downPosition = down.position
            var maxDragSq = 0f
            var multiTouch = false
            var upSeen = false
            var upConsumed = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { it.pressed } > 1) {
                    multiTouch = true
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - downPosition
                val dragSq = delta.x * delta.x + delta.y * delta.y
                if (dragSq > maxDragSq) maxDragSq = dragSq
                if (!change.pressed) {
                    upSeen = true
                    upConsumed = change.isConsumed
                    break
                }
            }
            val isTap = upSeen && !upConsumed && !multiTouch &&
                maxDragSq <= tapMaxDragPx * tapMaxDragPx
            if (isTap) currentOnClick()
        }
    }
}

/**
 * 释义按词性分行：v. … / n. … 各占一行，同一词性下的多个义项仍用「；」连在一行。
 * 只在「；」紧跟词性标记时才断行，避免把普通义项分隔符也切开。
 */
private val MEANING_POS_BREAK = Regex(
    """[；;]\s*(?=(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.\s)""" +
        "|" +
        """\s+(?=(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.\s)"""
)

private fun formatMeaningByPos(meaning: String): String {
    if (meaning.isBlank()) return meaning
    return meaning.replace(MEANING_POS_BREAK, "\n").trim()
}

/**
 * 卡背的记忆思路 / 常用搭配区块：一行一条，空行丢弃。
 * 文本自带 ①②③ 时原样显示，不重复编号。
 */
@Composable
private fun WordMemorySection(
    title: String?,
    content: String,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val lines = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = contentStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun WordExtraSection(
    title: String?,
    content: String,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    if (content.isBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!title.isNullOrEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = titleColor
                )
            }
            Text(
                text = content,
                style = contentStyle,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AdaptiveWordText(
    text: String,
    preferredStyle: TextStyle,
    fallbackStyles: List<TextStyle>,
    maxLines: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val availableWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val styleCandidates = remember(preferredStyle, fallbackStyles) {
            buildList {
                add(preferredStyle)
                fallbackStyles.forEach { style ->
                    if (style != preferredStyle) {
                        add(style)
                    }
                }
            }
        }
        val finalStyle = remember(text, styleCandidates, maxLines, availableWidthPx) {
            styleCandidates.firstOrNull { style ->
                val layoutResult = textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = maxLines,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = availableWidthPx)
                )
                !layoutResult.hasVisualOverflow
            } ?: styleCandidates.lastOrNull() ?: preferredStyle
        }

        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = finalStyle,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.adaptiveWordText(): String {
    val trimmed = trim()
    if (trimmed.length <= 12) return trimmed
    return trimmed.toCharArray().joinToString(separator = "\u200B")
}

@Composable
private fun ReviewTimeTag(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .testTag("learning_review_tag")
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun AnimatedContentTransitionScope<WordCardState>.wordCardTransition(
    lastRating: StudyRating?
): ContentTransform {
    val defaultFadeIn = tween<Float>(durationMillis = 180)
    val defaultFadeOut = tween<Float>(durationMillis = 150)

    return when (lastRating) {
        StudyRating.GOOD -> {
            (slideInHorizontally(
                initialOffsetX = { it / 2 },
                animationSpec = tween(durationMillis = 220)
            ) +
                fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 30), initialAlpha = 0.42f) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 220)))
                .togetherWith(
                    slideOutHorizontally(
                        targetOffsetX = { -it / 3 },
                        animationSpec = tween(durationMillis = 190)
                    ) +
                        fadeOut(animationSpec = tween(durationMillis = 170), targetAlpha = 0.2f) +
                        scaleOut(targetScale = 1.03f, animationSpec = tween(durationMillis = 190))
                )
        }

        StudyRating.AGAIN -> {
            (slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(durationMillis = 320)
            ) +
                fadeIn(animationSpec = tween(durationMillis = 220), initialAlpha = 0.28f) +
                scaleIn(initialScale = 0.9f, animationSpec = tween(durationMillis = 300)))
                .togetherWith(
                    slideOutHorizontally(
                        targetOffsetX = { it / 2 },
                        animationSpec = tween(durationMillis = 300)
                    ) +
                        fadeOut(animationSpec = tween(durationMillis = 220), targetAlpha = 0.16f) +
                        scaleOut(targetScale = 0.92f, animationSpec = tween(durationMillis = 280))
                )
        }

        StudyRating.HARD -> {
            (slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = tween(durationMillis = 260)
            ) +
                fadeIn(animationSpec = tween(durationMillis = 210), initialAlpha = 0.26f) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(durationMillis = 240)))
                .togetherWith(
                    slideOutVertically(
                        targetOffsetY = { -it / 6 },
                        animationSpec = tween(durationMillis = 230)
                    ) +
                        fadeOut(animationSpec = tween(durationMillis = 200), targetAlpha = 0.2f) +
                        scaleOut(targetScale = 0.97f, animationSpec = tween(durationMillis = 220))
                )
        }

        else -> {
            fadeIn(animationSpec = defaultFadeIn) togetherWith fadeOut(animationSpec = defaultFadeOut)
        }
    }.using(SizeTransform(clip = false))
}

/**
 * 六脉神剑里的哪一招。生成器输出的 method 是英文枚举，卡背上给人看的是中文短标签。
 * 只做「主打的一招」——语料里每个词只带主招，不要为了凑两个标签去编。
 */
private fun methodLabelOf(method: String): String? = when (method.trim().lowercase()) {
    "sound" -> "谐音"
    "meaning" -> "联想"
    "shape" -> "字形"
    "root" -> "词根"
    "contrast" -> "对比"
    "polysemy" -> "多义"
    else -> null
}

/** 卡背音标行右侧的小标签：告诉人这条思路靠什么记，不抢正文字号。 */
@Composable
private fun MemoryMethodChip(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}


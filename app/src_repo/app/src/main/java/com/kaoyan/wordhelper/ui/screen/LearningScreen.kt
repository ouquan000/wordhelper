package com.kaoyan.wordhelper.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import com.kaoyan.wordhelper.ui.component.AnimatedStarToggle
import com.kaoyan.wordhelper.ui.component.CardStyleDrawer
import com.kaoyan.wordhelper.ui.component.CardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.CardStyleEntryButton
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.HoloCardSurface
import com.kaoyan.wordhelper.ui.component.HoloCardTheme
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.ui.component.rememberCardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.rememberHoloLightState
import com.kaoyan.wordhelper.ui.theme.AlertRed
import com.kaoyan.wordhelper.ui.theme.FuzzyYellow
import com.kaoyan.wordhelper.ui.theme.KnownGreen
import com.kaoyan.wordhelper.ui.viewmodel.LearningCheckInUiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel
import com.kaoyan.wordhelper.ui.component.AppMenuButton
import com.kaoyan.wordhelper.util.PronunciationPlayer
import com.kaoyan.wordhelper.util.rememberPronunciationPlayer
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.Sm2Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(
    viewModel: LearningViewModel = viewModel(),
    initialMode: LearningMode = LearningMode.RECOGNITION,
    onOpenDrawer: () -> Unit = {}
) {
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val cardBackConfig by viewModel.cardBackConfig.collectAsStateWithLifecycle()
    val currentWord by viewModel.currentWord.collectAsStateWithLifecycle()
    // 卡背正文直接订阅库里的思路：电脑端结果一落库就换文，不依赖词对象什么时候被刷新
    val liveApproach by viewModel.liveApproach.collectAsStateWithLifecycle()
    // 方法标签也订阅库：重写落地后胶囊立刻换，不等下一次翻面
    val liveMethod by viewModel.liveMethod.collectAsStateWithLifecycle()
    // 1.4.7.1：这版思路是不是「没复核」——同样盯库，角标跟着卡背正文一起换
    val liveApproachReview by viewModel.liveApproachReview.collectAsStateWithLifecycle()
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
    val checkInState by viewModel.checkInState.collectAsStateWithLifecycle()
    val waitingRewriteCount by viewModel.waitingRewriteCount.collectAsStateWithLifecycle()
    val rewriteMark by viewModel.currentRewriteMark.collectAsStateWithLifecycle()
    val skipRewriteLatchedWordId by viewModel.skipRewriteLatchedWordId.collectAsStateWithLifecycle()
    val rewriteModels by viewModel.rewriteModels.collectAsStateWithLifecycle()
    val rewriteModelId by viewModel.rewriteModelId.collectAsStateWithLifecycle()
    val rewriteProgress by viewModel.rewriteProgress.collectAsStateWithLifecycle()
    val rewriteUpdatedTick by viewModel.rewriteUpdatedTick.collectAsStateWithLifecycle()
    // 1.4.6：这条思路的 👍/👎 状态（null = 还没表过态）
    val rewriteFeedback by viewModel.currentRewriteFeedback.collectAsStateWithLifecycle()
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
    // 1.4.7：发音索引 5 MB 级，别在首帧组合时同步加载（冷启动会 ANR）；
    // 用到时再取，Application 里已经在后台预热。
    val appContext = remember(context) { context.applicationContext as KaoyanWordApp }
    val pronunciationPlayer: PronunciationPlayer = rememberPronunciationPlayer()
    val uiScope = rememberCoroutineScope()

    // 卡片材质三件套：单独订阅，避免整页跟着 settingsFlow 里其它字段一起重组。
    val cardUiPrefs by remember(appContext) {
        appContext.settingsRepository.settingsFlow
            .map { CardUiPrefs(it.cardStyleId, it.cardGlossHigh, it.cardMotionEnabled) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = CardUiPrefs())
    val cardStyle = HoloCardStyle.fromId(cardUiPrefs.styleId)
    val styleDrawerState = rememberCardStyleDrawerState()

    var showRemoveDialog by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showAiAssistantPage by rememberSaveable { mutableStateOf(false) }
    var showAiGuideDialog by remember { mutableStateOf(false) }
    var isCardFlipped by remember { mutableStateOf(false) }
    var lastRating by remember { mutableStateOf<StudyRating?>(null) }
    var pronouncingWordId by remember { mutableStateOf<Long?>(null) }
    var learningMode by rememberSaveable { mutableStateOf(initialMode) }

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
    // W3：点过重写就锁存，生成完成也不收起；换词后 currentWord 变了才失效。
    val showSkipRewrite = skipRewriteLatchedWordId != null && skipRewriteLatchedWordId == currentWord?.id
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
                appContext.pronunciationRepository.getPronunciationAudioUrl(
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
            // 整页左滑（起手在右侧 40% 内）拉出卡片材质抽屉：不依赖右边缘系统手势排除区，
            // 卡片中部、下半屏都能划；只在明确左移超过 touchSlop 后才消费事件，
            // 点击、卡背竖向滚动、右滑开左侧导航抽屉都不受影响。
            .styleDrawerLeftSwipe(styleDrawerState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 右侧材质抽屉展开时，主屏同步微缩到 0.975；读值在 layer lambda 里，只重绘不重组。
                .graphicsLayer {
                    val drawer = styleDrawerState.progress
                    scaleX = 1f - 0.025f * drawer
                    scaleY = 1f - 0.025f * drawer
                    transformOrigin = TransformOrigin.Center
                }
                // 1.4.3：卡背输入框唤起键盘时，整页让到键盘上方，
                // 否则「重写思路」按钮会被键盘盖住，点不到（edge-to-edge 默认不会让）。
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1.4.7：模式切换与一级导航都搬进左侧抽屉，这里只留菜单入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppMenuButton(onClick = onOpenDrawer)
                Spacer(modifier = Modifier.weight(1f))
                // 右上角入口与左侧菜单按钮同一水平线、同样 48dp 热区。
                CardStyleEntryButton(
                    style = cardStyle,
                    onClick = { styleDrawerState.open(uiScope) },
                    modifier = Modifier.testTag("learning_card_style_button")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

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
                    if (waitingRewriteCount > 0) {
                        LearningWaitingRewriteCard(
                            count = waitingRewriteCount,
                            onRefresh = viewModel::syncRewrite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("learning_waiting_rewrite_card")
                        )
                    } else if (checkInState.showCompletionPanel) {
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
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight()
                                            .testTag("learning_word_card")
                                    ) {
                                        WordCard(
                                            // 词对象可能是旧快照，卡背正文与方法标签都以库里的实时值为准
                                            word = state.word.copy(
                                                approach = liveApproach ?: state.word.approach,
                                                method = liveMethod?.takeIf { it.isNotBlank() }
                                                    ?: state.word.method
                                            ),
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
                                            // 1.4.7.1：服务端标了 unverified 的那版思路，卡背挂个中性角标；
                                            // 点角标展开原因。没这个字段（老服务端）就是 false，什么都不显示。
                                            unverified = liveApproachReview.unverified,
                                            unverifiedReason = liveApproachReview.reason,
                                            // 1.4.3.1：重写条挪进卡背底部，只在背面出现；
                                            // live = 背面当前可见，翻回正面时按钮跟着禁用，不接住正面的触摸。
                                            backActions = { live ->
                                                Column(
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    RewriteBar(
                                                        wordId = state.word.id,
                                                        mark = rewriteMark,
                                                        models = rewriteModels,
                                                        selectedModelId = rewriteModelId,
                                                        onSelectModel = viewModel::selectRewriteModel,
                                                        onMark = viewModel::markRewrite,
                                                        onCancel = viewModel::cancelRewrite,
                                                        onUndo = viewModel::undoRewrite,
                                                        enabled = live && !isAnswering,
                                                        progress = rewriteProgress,
                                                        justUpdated = rewriteJustUpdated
                                                    )
                                                    RewriteFeedbackRow(
                                                        feedback = rewriteFeedback,
                                                        onFeedback = viewModel::submitRewriteFeedback,
                                                        enabled = live && !isAnswering
                                                    )
                                                }
                                            },
                                            cardStyle = cardStyle,
                                            glossHigh = cardUiPrefs.glossHigh,
                                            motionEnabled = cardUiPrefs.motionEnabled,
                                            modifier = Modifier.fillMaxHeight()
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
                                },
                                showSkip = showSkipRewrite && rewriteMark != null,
                                skipEnabled = rewriteMark?.status != RewriteStatus.DONE,
                                onSkip = viewModel::skipCurrentWordForRewrite
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

        // 右侧卡片材质抽屉：叠在整页之上，与左侧导航抽屉互相独立。
        CardStyleDrawer(
            state = styleDrawerState,
            selectedStyleId = cardUiPrefs.styleId,
            glossHigh = cardUiPrefs.glossHigh,
            motionEnabled = cardUiPrefs.motionEnabled,
            onSelectStyle = { style ->
                uiScope.launch { appContext.settingsRepository.updateCardStyleId(style.id) }
            },
            onGlossHighChange = { high ->
                uiScope.launch { appContext.settingsRepository.updateCardGlossHigh(high) }
            },
            onMotionEnabledChange = { enabled ->
                uiScope.launch { appContext.settingsRepository.updateCardMotionEnabled(enabled) }
            }
        )
    }
}

private const val SM2_DESCRIPTION =
    "SM-2 调度说明：不认识会在会话内重试，模糊保守推进，认识按记忆因子增长间隔。"
private val RECOGNITION_SECTION_SPACING = 10.dp

/** 卡片材质的三个持久化开关，单独收敛成一个不可变快照，避免整页跟着 settingsFlow 抖。 */
private data class CardUiPrefs(
    val styleId: String = "pearl",
    val glossHigh: Boolean = true,
    val motionEnabled: Boolean = true
)

/**
 * H：当天词背完、但今天发起过的重写卡还没被实际背掉时，不显示「完成」，显示这张等待卡。
 *
 * 1.4.7.2 W2：学习页不再提供「停止重写」入口 —— 停掉生成会让欠卡永远背不掉，
 * 用户也就永远看不到真实完成。停止能力只留在后端/运维侧。
 */
@Composable
private fun LearningWaitingRewriteCard(
    count: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Text("还有 $count 张重写卡没背完", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "正在生成或等待重试；合格一张就插回一张，背完今天才算完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRefresh) {
                Text("现在刷新")
            }
        }
    }
}

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
    showSkip: Boolean = false,
    skipEnabled: Boolean = true,
    onSkip: () -> Unit = {},
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
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AlertRed.copy(alpha = 0.18f),
                    contentColor = AlertRed
                )
            ) {
                Text(
                    text = "不认识",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
            FilledTonalButton(
                onClick = onHard,
                enabled = !isAnswering,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("learning_action_hard"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = FuzzyYellow.copy(alpha = 0.28f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(
                    text = "模糊",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
            val goodText = if (isNewWordsBook) "认识并移出" else "认识"
            FilledTonalButton(
                onClick = onGood,
                enabled = !isAnswering,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("learning_action_good"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = KnownGreen.copy(alpha = 0.22f),
                    contentColor = KnownGreen
                )
            ) {
                Text(
                    text = goodText,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    // 320dp + fontScale 1.5 + 四按钮时，5 个字的「认识并移出」两行放不下；
                    // 第三行只在这一种窄组合出现，正常字号仍是一行。
                    maxLines = 3
                )
            }
            if (showSkip) {
                FilledTonalButton(
                    onClick = onSkip,
                    enabled = !isAnswering && skipEnabled,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("learning_action_skip"),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        text = "跳过",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
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
    /** 1.4.7.1：这版思路没复核（服务端 unverified）。false = 什么都不显示。 */
    unverified: Boolean = false,
    /** 未复核的原因；点角标时展开它。空 = 只有角标没有说明。 */
    unverifiedReason: String? = null,
    backActions: (@Composable (Boolean) -> Unit)? = null,
    /** 卡片材质（7 款之一），由设置里的 cardStyleId 决定。 */
    cardStyle: HoloCardStyle = HoloCardStyle.PEARL,
    /** true = 璀璨炫光，false = 柔和护眼。 */
    glossHigh: Boolean = true,
    /** true = 呼吸游走 + 指尖追光；false = 静止省电。 */
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isFlipped by remember(word.id) { mutableStateOf(false) }
    // 卡背延后到换词动画（最长 320ms，见 wordCardTransition）结束后的静止帧再首次组合。
    // 翻面时它已经在树里，跨 90° 的那一帧就只剩 layer 的 alpha 切换，不再付首次组合 + 测量。
    var backReady by remember(word.id) { mutableStateOf(false) }
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
    // 换词时复位；等卡片切换动画彻底静止后再挂卡背内容（不跟动画抢同一帧）。
    LaunchedEffect(word.id) {
        backReady = false
        delay(400)
        backReady = true
    }
    val density = LocalDensity.current
    val rotation = animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "wordCardRotation"
    )
    // 只在跨过 90° 时翻转一次，而不是每帧：composition 里读的是它，rotation 本体只进 layer lambda。
    val showFront by remember { derivedStateOf { rotation.value <= 90f } }
    val backVisible = !showFront

    val holoLight = rememberHoloLightState(motionEnabled)
    val tapMaxDragPx = with(density) { 4.dp.toPx() }
    // 浅色金属/珠光款：给大单词与音标做雕刻凹陷（深色款用亮字，不加，避免糊边）。
    val engraveShadow = if (
        cardStyle == HoloCardStyle.CLASSIC ||
        cardStyle == HoloCardStyle.PEARL ||
        cardStyle == HoloCardStyle.CHROME
    ) {
        with(density) {
            Shadow(
                color = Color.Black.copy(alpha = 0.30f),
                offset = Offset(0f, 1.4.dp.toPx()),
                blurRadius = 1.1.dp.toPx()
            )
        }
    } else {
        null
    }

    HoloCardTheme(cardStyle) {
        HoloCardSurface(
            style = cardStyle,
            light = holoLight,
            isBack = backVisible,
            glossHigh = glossHigh,
            modifier = modifier.pointerInput(motionEnabled) {
                // Initial 阶段只读坐标、不消费：点击翻面、卡背滚动与重写区全部照旧。
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            holoLight.pointerAt(
                                change.position.x / size.width.coerceAtLeast(1),
                                change.position.y / size.height.coerceAtLeast(1)
                            )
                        } else if (event.changes.none { it.pressed }) {
                            holoLight.pointerUp()
                        }
                    }
                }
            }
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .cardFlipTap(tapMaxDragPx) {
                    isFlipped = !isFlipped
                    onFlipChanged(isFlipped)
                }
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
                        .fillMaxHeight()
                        .graphicsLayer {
                            // 读值留在 layer lambda 里：翻面这 300ms 每帧只重画，不重组。
                            rotationY = rotation.value
                            cameraDistance = 12f * density.density
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    // 正面常驻：背面露出时只把 alpha 归零，不再整棵释放/重建。
                    // align(Center)：内层 Box 的 contentAlignment 已改 TopCenter（给卡背用），
                    // 正面保持原来「垂直居中」的位置，不跟卡背抢同一个默认对齐。
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .graphicsLayer { alpha = if (backVisible) 0f else 1f },
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
                            maxLines = wordMaxLines,
                            engraveShadow = engraveShadow
                        )
                        if (word.phonetic.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    text = word.phonetic,
                                    style = MaterialTheme.typography.titleMedium.copy(shadow = engraveShadow),
                                    color = MaterialTheme.colorScheme.onSurface,
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

                    // 背面同样常驻（词稳定 400ms 后才挂上），反贴 180°，跟正面共用同一次 3D 旋转。
                    // 不可见时 interactive = false：滚动与重写条都不吃触摸，两面不会各响应一次。
                    if (backReady || isFlipped) {
                        // 1.4.3.2：重写区随卡背内容一起滚，排在正文最后，不再固定贴卡片底边。
                        CardBackBlocks(
                            config = cardBackConfig,
                            word = word,
                            displayWord = displayWord,
                            backWordStyle = backWordStyle,
                            scrollState = backContentScrollState,
                            interactive = backVisible,
                            unverified = unverified,
                            unverifiedReason = unverifiedReason,
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
    interactive: Boolean,
    /** 1.4.7.1：这版思路没复核时，MEMORY 区块标题旁挂一个中性角标。 */
    unverified: Boolean = false,
    unverifiedReason: String? = null,
    backActions: (@Composable (Boolean) -> Unit)? = null
) {
    val palette = LocalHoloCardPalette.current
    val panelShape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .graphicsLayer {
                rotationY = 180f
                // 不可见时只把 alpha 归零：它在树里但既不画、也不吃触摸。
                alpha = if (interactive) 1f else 0f
            }
            // 自适应磨砂卡片底：透出全息底色，同时压住长释义/例句的对比度。
            .clip(panelShape)
            .background(palette.frostedPanel)
            .border(0.5.dp, palette.panelBorder, panelShape)
            .verticalScroll(scrollState, enabled = interactive)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(config.spacingDp.dp)
    ) {
        config.renderableBlocks.forEach { block ->
            CardBackBlockView(
                block = block,
                word = word,
                displayWord = displayWord,
                backWordStyle = backWordStyle,
                unverified = unverified,
                unverifiedReason = unverifiedReason
            )
        }
        // 1.4.3.2：重写区放在正文最后，跟着滚动；用分隔线把「内容」和「操作」分开。
        if (backActions != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉空白区的点击，免得点状态文字时把卡片翻回正面（背面不可见时不吞）
                    .clickable(
                        enabled = interactive,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            ) {
                backActions(interactive)
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
    backWordStyle: TextStyle,
    unverified: Boolean = false,
    unverifiedReason: String? = null
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
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle),
            // 角标只挂在「思路」这一块：unverified 说的是这一版重写结果，不是搭配。
            unverified = unverified,
            unverifiedReason = unverifiedReason
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
 * 整页左滑拉出「卡片材质」抽屉。
 *
 * 起手点必须落在屏幕右侧 40% 以内；水平左移超过 touchSlop 才认领手势并开始消费事件，
 * 因此轻触翻卡、卡背竖向滚动、从左侧右滑开导航抽屉都不会被抢。
 * 认领后按手指位移实时改 progress（跟手），松手按位置/速度做弹簧吸附。
 */
private fun Modifier.styleDrawerLeftSwipe(state: CardStyleDrawerState): Modifier = composed {
    val currentState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < size.width * 0.40f) return@awaitEachGesture
            val drawerWidthPx = size.width * 0.74f
            var lastX = down.position.x
            var lastTime = down.uptimeMillis
            var claimed = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (claimed) {
                        val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                        val velocity = (change.position.x - lastX) / dt * 1000f
                        scope.launch { currentState.settle(-velocity / drawerWidthPx) }
                    }
                    break
                }
                if (!claimed) {
                    val totalDx = change.position.x - down.position.x
                    if (totalDx < -viewConfiguration.touchSlop && !change.isConsumed) {
                        claimed = true
                    }
                }
                if (claimed) {
                    change.consume()
                    val dx = change.position.x - lastX
                    currentState.dragBy(-dx / drawerWidthPx)
                }
                lastX = change.position.x
                lastTime = change.uptimeMillis
            }
        }
    }
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
): Modifier = composed {    val currentOnClick by rememberUpdatedState(onClick)
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
    contentStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    /** 1.4.7.1：这段内容是「没复核」的产出（只有 MEMORY 区块会传 true）。 */
    unverified: Boolean = false,
    /** 未复核的原因；点角标展开，空 = 只有角标没有说明。 */
    unverifiedReason: String? = null
) {
    val lines = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    // 换版（正文或原因变了）自动收起：别把上一版的原因留在新正文旁边。
    var reasonExpanded by remember(content, unverifiedReason, unverified) { mutableStateOf(false) }
    val reason = unverifiedReason?.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!title.isNullOrEmpty() || unverified) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = titleColor
                    )
                }
                if (unverified) {
                    UnverifiedChip(
                        hasReason = reason != null,
                        expanded = reasonExpanded,
                        onClick = { reasonExpanded = !reasonExpanded }
                    )
                }
            }
        }
        // 原因跟在标题行下面、正文上面：点开就看到，不打断读思路。
        if (unverified && reasonExpanded && reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * 「未复核」角标（1.4.7.1）。
 *
 * 刻意用中性色（onSurface 低透明度底 + onSurfaceVariant 字）、小字号、药丸形：
 * 它只是说「这版没走完复核」，不是错误，不能用 error 色去吓人。
 * 有原因时点一下展开 / 收起；没有原因就保持静态，别装作能点。
 */
@Composable
private fun UnverifiedChip(hasReason: Boolean, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        modifier = Modifier.clickable(
            enabled = hasReason,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "未复核",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasReason) {
                Text(
                    text = if (expanded) "收起" else "原因",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
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
    textAlign: TextAlign = TextAlign.Start,
    /** 金属冲压凹陷：深色微阴影 + 1dp 底部高光偏移。 */
    engraveShadow: Shadow? = null
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

        Box(modifier = Modifier.fillMaxWidth()) {
            if (engraveShadow != null) {
                // 底部 1dp 白色高光边：和上面的深色微阴影一起做出金属冲压的凹陷感。
                Text(
                    text = text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = 1.dp),
                    style = finalStyle.copy(color = Color.White.copy(alpha = 0.55f), shadow = null),
                    textAlign = textAlign,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                style = if (engraveShadow != null) finalStyle.copy(shadow = engraveShadow) else finalStyle,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun String.adaptiveWordText(): String {
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
        color = MaterialTheme.colorScheme.primaryContainer,
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


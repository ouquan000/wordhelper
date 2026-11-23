package com.kaoyan.wordhelper.ui.screen

import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.data.model.CardBackBlock
import com.kaoyan.wordhelper.data.model.CardBackBlockType
import com.kaoyan.wordhelper.data.model.CardBackColor
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.CardBackSize
import com.kaoyan.wordhelper.data.model.CardMotionMode
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.ui.component.AmbientBackdrop
import com.kaoyan.wordhelper.ui.component.AmbientBackdropMode
import com.kaoyan.wordhelper.ui.component.AnimatedStarToggle
import com.kaoyan.wordhelper.ui.component.AppMenuButton
import com.kaoyan.wordhelper.ui.component.CardEntrance
import com.kaoyan.wordhelper.ui.component.CardStyleDrawer
import com.kaoyan.wordhelper.ui.component.CardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.CardStyleEntryButton
import com.kaoyan.wordhelper.ui.component.FlowBurnProgressBar
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.HoloCardSurface
import com.kaoyan.wordhelper.ui.component.HoloCardTheme
import com.kaoyan.wordhelper.ui.component.HoloLightState
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.ui.component.StudyCardBody
import com.kaoyan.wordhelper.ui.component.StudyCardShape
import com.kaoyan.wordhelper.ui.component.rememberCardLightTime
import com.kaoyan.wordhelper.ui.component.rememberCardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.rememberHoloLightState
import com.kaoyan.wordhelper.ui.component.studyContentInsets
import com.kaoyan.wordhelper.ui.component.turnExposure
import com.kaoyan.wordhelper.ui.theme.AlertRed
import com.kaoyan.wordhelper.ui.theme.FuzzyYellow
import com.kaoyan.wordhelper.ui.theme.KnownGreen
import com.kaoyan.wordhelper.ui.viewmodel.LearningCheckInUiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel
import com.kaoyan.wordhelper.ui.viewmodel.SkipRewriteAvailability
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.MeaningTextFormatter
import com.kaoyan.wordhelper.util.MemoryPosTagger
import com.kaoyan.wordhelper.util.PronunciationPlayer
import com.kaoyan.wordhelper.util.Sm2Scheduler
import com.kaoyan.wordhelper.util.TaggedLine
import com.kaoyan.wordhelper.util.rememberPronunciationPlayer
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val rewriteUpdatedTick by viewModel.rewriteUpdatedTick.collectAsStateWithLifecycle()
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

    if (showMemoryEditDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryEditDialog = false },
            title = { Text("手改记忆思路") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = memoryDraft,
                        onValueChange = { memoryDraft = it },
                        label = { Text("这只属于这本书") },
                        minLines = 8,
                        maxLines = 16,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("memory_edit_field")
                    )
                    Text(
                        text = "保存后立即生效；打开「锁定 · 免洗」后，自动洗词和词卡热更都不会覆盖它。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveMemoryEdit(memoryDraft)
                        showMemoryEditDialog = false
                    },
                    modifier = Modifier.testTag("memory_edit_save")
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = memoryEditCanRestore,
                        onClick = {
                            viewModel.restoreMemoryEdit()
                            showMemoryEditDialog = false
                        },
                        modifier = Modifier.testTag("memory_edit_restore")
                    ) { Text("恢复上一版") }
                    TextButton(onClick = { showMemoryEditDialog = false }) { Text("取消") }
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
        // AI 助手页是整页替换：沿用旧的「整层缩」观感，用一层全屏 Box 承担缩放。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = navDrawerDepthScale()
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                }
        ) {
            RecognitionAiAssistantPage(
                word = currentWord,
                aiState = aiState,
                cachedExampleContent = cachedExampleContent,
                cachedMemoryAidContent = cachedMemoryAidContent,
                showMemoryAidSuggestion = memoryAidSuggestionWordId == currentWord.id,
                canGenerateExample = isCardFlipped,
                onBack = { showAiAssistantPage = false },
                onGenerateExample = { forceRefresh -> viewModel.requestAiExample(forceRefresh) },
                onGenerateMemoryAid = { forceRefresh -> viewModel.requestAiMemoryAid(forceRefresh) }
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 右侧手势在导航抽屉打开时让位。
            .styleDrawerLeftSwipe(styleDrawerState, navDrawerOpen)
    ) {
        AmbientBackdrop(
            mode = AmbientBackdropMode.fromId(cardUiPrefs.ambientBackdropId),
            cardStyle = cardStyle,
            motionMode = if (systemAnimationsOn) cardUiPrefs.motionMode else CardMotionMode.STATIC,
            timeSeconds = lightTime,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 右侧材质抽屉展开时，主屏同步微缩到 0.975；读值在 layer lambda 里，只重绘不重组。
                // 左抽屉展开时的 0.968 景深也乘在这里（与 AppNavigation 同一 lambda），
                // 背景层和圆角背板不进这一层，因此始终贴满屏幕。
                .graphicsLayer {
                    val drawer = styleDrawerState.progress
                    val scale = (1f - 0.025f * drawer) * navDrawerDepthScale()
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                }
                .windowInsetsPadding(studyContentInsets())
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        val cardState = WordCardState(currentWord, isInNewWords)
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
                                CardEntrance(
                                    contentKey = presentation,
                                    motionEnabled = motionEnabled,
                                    modifier = Modifier.fillMaxSize().testTag("learning_word_card")
                                ) {
                                    key(presentation) {
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
                                            lastRewriteText = rewriteMark
                                                ?.takeIf { it.status == RewriteStatus.DONE }
                                                ?.let { "上次重写 ${formatRewriteTime(it.updatedAt)}" },
                                            // 1.4.3.1：重写条挪进卡背底部，只在背面出现；
                                            // live = 背面当前可见，翻回正面时按钮跟着禁用，不接住正面的触摸。
                                            backActions = { live ->
                                                Column(
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    RewriteBar(
                                                        wordId = cardState.word.id,
                                                        mark = rewriteMark,
                                                        models = rewriteModels,
                                                        selectedModelId = rewriteModelId,
                                                        onSelectModel = viewModel::selectRewriteModel,
                                                        onRewrite = viewModel::markRewrite,
                                                        onCancel = viewModel::cancelRewrite,
                                                        onFeedbackUp = { count, reasons, comment ->
                                                            viewModel.submitRewriteFeedback(
                                                                rating = RewriteFeedbackRating.UP,
                                                                count = count,
                                                                reasons = reasons,
                                                                comment = comment
                                                            )
                                                        },
                                                        onFeedbackDown = { count, reasons, comment ->
                                                            viewModel.submitRewriteFeedback(
                                                                rating = RewriteFeedbackRating.DOWN,
                                                                count = count,
                                                                reasons = reasons,
                                                                comment = comment
                                                            )
                                                            val complaint = listOf(
                                                                reasons.joinToString("、"),
                                                                comment
                                                            ).filter { it.isNotBlank() }.joinToString("；")
                                                            if (complaint.isNotBlank()) viewModel.markRewrite(complaint)
                                                        },
                                                        feedback = rewriteFeedback,
                                                        likeCount = rewriteLikeCount,
                                                        enabled = live && !isAnswering,
                                                        progress = rewriteProgress,
                                                        justUpdated = rewriteJustUpdated
                                                    )
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        CompactActionChip(
                                                            text = "编辑思路",
                                                            onClick = {
                                                                memoryDraft = liveApproach
                                                                    ?: cardState.word.approach
                                                                showMemoryEditDialog = true
                                                            },
                                                            enabled = live && !isAnswering,
                                                            modifier = Modifier.testTag("memory_edit")
                                                        )
                                                        CompactActionChip(
                                                            text = if (memoryLocked) "已锁定 · 免洗" else "锁定 · 免洗",
                                                            onClick = viewModel::toggleMemoryLock,
                                                            enabled = live && !isAnswering,
                                                            modifier = Modifier.testTag("memory_lock")
                                                        )
                                                    }
                                                }
                                            },
                                            cardStyle = cardStyle,
                                            glossHigh = cardUiPrefs.glossHigh,
                                            motionMode = if (systemAnimationsOn) cardUiPrefs.motionMode else CardMotionMode.STATIC,
                                            holoLight = holoLight,
                                            modifier = Modifier.fillMaxHeight()
                                        )
                                    }
                                }
                            }
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

            // 填充与右侧计数同源：都直接用 ViewModel 的 currentIndex(1-based)/totalCount；
            // sprintSize 只负责 30 词满格脉冲，组件内部不再为填充另起一套数据源。
            FlowBurnProgressBar(
                currentIndex = currentIndex,
                totalCount = totalCount,
                tier = learningPace.tier,
                elapsedMs = learningPace.elapsedMs,
                motionEnabled = motionEnabled,
                barHeight = 10.dp,
                sprintSize = 30,
                heat = learningPace.heat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
        }

        // 右侧卡片材质抽屉：叠在整页之上；左抽屉开着时它不接边缘手势。
        CardStyleDrawer(
            state = styleDrawerState,
            navDrawerOpen = navDrawerOpen,
            selectedStyleId = cardUiPrefs.styleId,
            activeStyle = cardStyle,
            glossHigh = cardUiPrefs.glossHigh,
            motionMode = cardUiPrefs.motionMode,
            lightTime = lightTime,
            onSelectStyle = { style ->
                uiScope.launch { appContext.settingsRepository.updateCardStyleId(style) }
            },
            onGlossHighChange = { high ->
                uiScope.launch { appContext.settingsRepository.updateCardGlossHigh(high) }
            },
            onMotionModeChange = { mode ->
                uiScope.launch { appContext.settingsRepository.updateCardMotionMode(mode) }
            },
            ambientBackdropId = cardUiPrefs.ambientBackdropId,
            onAmbientBackdropChange = { id ->
                uiScope.launch { appContext.settingsRepository.updateAmbientBackdropId(id) }
            }
        )
    }
}

private const val SM2_DESCRIPTION =
    "SM-2 调度说明：不认识会在会话内重试，模糊保守推进，认识按记忆因子增长间隔。"
private val RECOGNITION_SECTION_SPACING = 8.dp

private data class CardUiPrefs(
    val styleId: String = "random",
    val glossHigh: Boolean = true,
    val motionMode: CardMotionMode = CardMotionMode.FULL,
    val ambientBackdropId: String = "auto"
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

/**
 * 一体化光谱滑轨：左绯红（不认识）→ 中琥珀金（模糊）→ 右翡翠青绿（认识）。
 *
 * - Tap：点任意 1/3 段，游标胶囊吸附过去、同色霓虹光晕亮起、触感反馈，立即回调；
 * - Horizontal Drag：游标实时跟手，跨段触发线性马达 Tick 与高亮切换，松手提交当前档位；
 * - isAnswering = true 时整条轨道只展示不提交；showSkip = true 时右侧并排紧凑「跳过」胶囊。
 */
@Composable
private fun RecognitionActionPanel(
    isAnswering: Boolean,
    isNewWordsBook: Boolean,
    resetKey: Any? = null,
    onAgain: () -> Unit,
    onHard: () -> Unit,
    onGood: () -> Unit,
    showSkip: Boolean = false,
    skipEnabled: Boolean = true,
    onSkip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val currentOnAgain by rememberUpdatedState(onAgain)
    val currentOnHard by rememberUpdatedState(onHard)
    val currentOnGood by rememberUpdatedState(onGood)
    val goodText = if (isNewWordsBook) "认识并移出" else "认识"
    val labels = listOf("× 不认识", "~ 模糊", "✓ $goodText")
    val tierColors = listOf(AlertRed, FuzzyYellow, KnownGreen)
    val trackShape = RoundedCornerShape(50)
    var cursorIndex by remember { mutableIntStateOf(-1) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    // 纵向越界作废：游标不是消失，而是平滑淡出 + 缩到 0.82（只改 layer，不重排）。
    var gestureCanceled by remember { mutableStateOf(false) }
    // 1.5.2：换词/换模式后清掉上一张卡的绿框，评分提交后也立刻复位。
    LaunchedEffect(resetKey) {
        cursorIndex = -1
        gestureCanceled = false
    }
    val cursorAlpha by animateFloatAsState(
        targetValue = if (gestureCanceled) 0f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "recognitionCursorAlpha"
    )
    val cursorScale by animateFloatAsState(
        targetValue = if (gestureCanceled) 0.82f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "recognitionCursorScale"
    )

    fun indexAt(x: Float): Int {
        if (trackWidthPx <= 0f) return 0
        return ((x / trackWidthPx) * 3f).toInt().coerceIn(0, 2)
    }

    fun submit(index: Int) {
        when (index) {
            0 -> currentOnAgain()
            1 -> currentOnHard()
            else -> currentOnGood()
        }
        cursorIndex = -1
        gestureCanceled = false
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .clip(trackShape)
                // 深色磨砂玻璃底
                .background(Color(0xFF12141A).copy(alpha = 0.50f))
                // 半透明光谱：绯红 → 琥珀金 → 翡翠青绿
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            AlertRed.copy(alpha = 0.52f),
                            Color(0xFFFFC65C).copy(alpha = 0.42f),
                            KnownGreen.copy(alpha = 0.48f)
                        )
                    )
                )
                // 玻璃高光：上亮下暗，薄薄一层柔光，不做重 blur
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.12f)
                        )
                    )
                )
                // 细磨砂颗粒：确定性伪随机、静态绘制，只给质感
                .drawBehind {
                    val radius = 0.7.dp.toPx()
                    repeat(140) { i ->
                        val x = ((i * 37) % 101) / 101f * size.width
                        val y = ((i * 61) % 97) / 97f * size.height
                        drawCircle(
                            color = Color.White.copy(alpha = 0.045f),
                            radius = radius,
                            center = Offset(x, y)
                        )
                    }
                }
                .border(1.dp, Color.White.copy(alpha = 0.22f), trackShape)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .pointerInput(isAnswering, trackWidthPx) {
                    if (isAnswering) return@pointerInput
                    // 48dp 纵向越界阈值（PointerInputScope 本身就是 Density，可直接 toPx）
                    val verticalCancelPx = 48.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startY = down.position.y
                        var canceled = false
                        gestureCanceled = false
                        var index = indexAt(down.position.x)
                        cursorIndex = index
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // 纵向越界：整场手势作废，停在当前 index，不再更新评分。
                            if (!canceled && abs(change.position.y - startY) > verticalCancelPx) {
                                canceled = true
                                gestureCanceled = true
                            }
                            if (canceled) continue
                            if (!dragging &&
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragging = true
                            }
                            if (dragging) {
                                val next = indexAt(change.position.x)
                                if (next != index) {
                                    index = next
                                    cursorIndex = next
                                    // 跨区间阈值：线性马达 Tick 微震
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                change.consume()
                            }
                        }
                        // 作废的手势抬手不提交任何评分；下一次按下时 canceled 会重置。
                        if (!canceled) submit(index)
                    }
                }
        ) {
            val segment = maxWidth / 3f
            if (cursorIndex >= 0) {
                val cursorColor = tierColors[cursorIndex]
                val cursorWidth = (segment - 6.dp).coerceAtLeast(24.dp)
                val cursorOffset by animateDpAsState(
                    targetValue = segment * cursorIndex.toFloat() + 3.dp,
                    animationSpec = tween(durationMillis = 140),
                    label = "recognitionCursor"
                )
                Box(
                    modifier = Modifier
                        .offset(x = cursorOffset, y = 3.dp)
                        .width(cursorWidth)
                        .height(48.dp)
                        // 作废时只做 layer 级淡出/缩小，不参与布局，也不改轨道外观
                        .graphicsLayer {
                            alpha = cursorAlpha
                            scaleX = cursorScale
                            scaleY = cursorScale
                            transformOrigin = TransformOrigin.Center
                        }
                        .shadow(
                            elevation = 14.dp,
                            shape = trackShape,
                            clip = false,
                            ambientColor = cursorColor,
                            spotColor = cursorColor
                        )
                        .clip(trackShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.30f),
                                    cursorColor.copy(alpha = 0.38f)
                                )
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.52f), trackShape)
                )
            }
            Row(modifier = Modifier.fillMaxSize()) {
                labels.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag(
                                when (index) {
                                    0 -> "learning_action_again"
                                    1 -> "learning_action_hard"
                                    else -> "learning_action_good"
                                }
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = label
                                onClick {
                                    if (!isAnswering) {
                                        cursorIndex = index
                                        submit(index)
                                    }
                                    true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = Color.White.copy(
                                alpha = when {
                                    isAnswering -> 0.55f
                                    cursorIndex == index && !gestureCanceled -> 1f
                                    else -> 0.90f
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (cursorIndex == index && !gestureCanceled) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Medium
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (showSkip) {
            Box(
                modifier = Modifier
                    .height(54.dp)
                    .clip(trackShape)
                    .background(Color(0xFF12141A).copy(alpha = 0.52f))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.08f), Color.Transparent)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.18f), trackShape)
                    .clickable(enabled = !isAnswering && skipEnabled) { onSkip() }
                    .testTag("learning_action_skip")
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "跳过",
                    color = Color.White.copy(alpha = if (!isAnswering && skipEnabled) 0.90f else 0.45f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
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
    /** 1.5.3v2：上次重写时间（MM-dd HH:mm），显示在卡顶信息栏；null = 不显示。 */
    lastRewriteText: String? = null,
    backActions: (@Composable (Boolean) -> Unit)? = null,
    cardStyle: HoloCardStyle,
    glossHigh: Boolean,
    motionMode: CardMotionMode,
    holoLight: HoloLightState,
    modifier: Modifier = Modifier
) {
    val angle = remember { Animatable(0f) }
    val showBack by remember { derivedStateOf { angle.value >= 90f } }
    var turning by remember { mutableStateOf(false) }
    val flipScope = rememberCoroutineScope()
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
    // 卡背词头：与正面同一支 serif，32sp Medium；长词由 AdaptiveWordText 降档到最多 2 行。
    val backWordStyle = typography.headlineMedium.copy(
        fontFamily = FontFamily.Serif,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )
    val wordMaxLines = when {
        normalizedWordLength <= 20 -> 1
        normalizedWordLength <= 32 -> 2
        normalizedWordLength <= 48 -> 3
        normalizedWordLength <= 72 -> 4
        else -> 5
    }
    val backContentScrollState = rememberScrollState()
    val motionAmount = when (motionMode) {
        CardMotionMode.FULL -> 1f
        CardMotionMode.LIGHT -> 0.46f
        CardMotionMode.STATIC -> 0f
    }

    HoloCardTheme(cardStyle) {
        val palette = LocalHoloCardPalette.current
        val faceInk = palette.ink
        val faceSoftInk = palette.subInk
        val actionsEnabled = starEnabled && !turning
        StudyCardBody(
            modifier = modifier,
            angle = { angle.value },
            back = showBack,
            light = holoLight,
            turnEnabled = !turning,
            onTurn = {
                if (!turning) {
                    turning = true
                    val nextBack = !showBack
                    flipScope.launch {
                        try {
                            angle.animateTo(
                                if (nextBack) 180f else 0f,
                                tween(430, easing = CubicBezierEasing(0.35f, 0.08f, 0.28f, 1f))
                            )
                            onFlipChanged(nextBack)
                        } finally {
                            turning = false
                        }
                    }
                }
            }
        ) {
            HoloCardSurface(
                style = cardStyle,
                light = holoLight,
                isBack = showBack,
                glossHigh = glossHigh,
                shape = StudyCardShape,
                motionAmount = motionAmount,
                turn = { turnExposure(angle.value) * if (showBack) -1f else 1f },
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ReviewTimeTag(text = reviewTag, onClick = onReviewTagClick)
                            // 卡背顶栏：到期胶囊 + 记忆方法胶囊 + 星标同一行；正面保持原样不显示方法。
                            if (showBack) {
                                methodLabelOf(word.method)?.let { label ->
                                    MemoryMethodChip(label = label)
                                }
                            }
                            if (showPronounceButton) {
                                OutlinedButton(
                                    onClick = onPronounceClick,
                                    enabled = actionsEnabled && !pronounceLoading,
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
                        // 1.5.3v2：卡顶右上收藏星仅在正面显示（正面主流程不变）；卡背原位改为「上次重写」极淡浅灰小字。
                        if (!showBack) {
                            AnimatedStarToggle(
                                checked = isInNewWords,
                                onCheckedChange = { onToggleNewWords() },
                                enabled = actionsEnabled,
                                modifier = Modifier.testTag("learning_star_toggle")
                            )
                        } else if (lastRewriteText != null) {
                            Text(
                                text = lastRewriteText,
                                style = MaterialTheme.typography.labelSmall,
                                color = faceSoftInk.copy(alpha = 0.65f),
                                maxLines = 1
                            )
                        }
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
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            if (!showBack) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
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
                                            color = faceInk.copy(alpha = 0.10f)
                                        ) {
                                            Text(
                                                text = word.phonetic,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = faceInk,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "轻触卡片查看释义与例句",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = faceSoftInk,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                CardBackBlocks(
                                    config = cardBackConfig,
                                    word = word,
                                    displayWord = displayWord,
                                    backWordStyle = backWordStyle,
                                    scrollState = backContentScrollState,
                                    interactive = actionsEnabled,
                                    unverified = unverified,
                                    unverifiedReason = unverifiedReason,
                                    backActions = backActions
                                )
                            }
                        }
                        if (aiAvailable) {
                            Box(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .clickable(enabled = actionsEnabled, onClick = onAiEntryClick)
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
                                            color = faceInk
                                        )
                                    }
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
    val blocks = config.renderableBlocks
    // 1.5.3v2：上下两端平滑透明渐隐，消除文字被卡片边缘拦腰截断。
    val fadeTop = Brush.verticalGradient(
        0f to palette.frontTop,
        1f to palette.frontTop.copy(alpha = 0f)
    )
    val fadeBottom = Brush.verticalGradient(
        0f to palette.frontBottom.copy(alpha = 0f),
        1f to palette.frontBottom
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .verticalScroll(scrollState, enabled = interactive)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            blocks.forEachIndexed { index, block ->
                if (index > 0) {
                    val previous = blocks[index - 1]
                    val gap = when {
                        previous.type == CardBackBlockType.TITLE &&
                            block.type == CardBackBlockType.PHONETIC -> 2
                        block.type == CardBackBlockType.MEANING -> 8
                        block.type == CardBackBlockType.MEMORY -> 18
                        else -> config.spacingDp
                    }
                    Spacer(modifier = Modifier.height(gap.dp))
                }
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
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.8f))
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
        // 渐隐遮罩：盖在滚动内容最上/最下，只淡出 22dp，不挡中间正文。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .align(Alignment.TopCenter)
                .background(fadeTop)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .align(Alignment.BottomCenter)
                .background(fadeBottom)
        )
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
    val palette = LocalHoloCardPalette.current
    val overrideColor = block.style.color.resolveCardBackColor()
    val bodyStyle = block.style.size
    // 卡背区块的小标：11sp、字距略开；颜色统一取调色板的 subInk（亮皮肤深灰、暗皮肤亮灰）。
    val sectionLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.9.sp
    )
    val sectionLabelColor = overrideColor ?: palette.subInk
    when (block.type) {
        CardBackBlockType.TITLE -> {
            // 杂志级词头：与正面同一支 serif，32sp Medium；长词自动降档，最多 2 行。
            val titleStyle = backWordStyle.copy(
                color = overrideColor ?: palette.ink
            )
            AdaptiveWordText(
                text = displayWord,
                preferredStyle = titleStyle,
                fallbackStyles = listOf(
                    titleStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
                    titleStyle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                    titleStyle.copy(fontSize = 20.sp, lineHeight = 26.sp)
                ),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }

        CardBackBlockType.PHONETIC -> if (word.phonetic.isNotBlank()) {
            // 音标紧贴词头下方，不再单独占一个大块；记忆方法胶囊已上移到卡背顶栏。
            Text(
                text = word.phonetic,
                style = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle).copy(
                    lineHeight = 18.sp,
                    letterSpacing = 0.3.sp
                ),
                color = overrideColor ?: palette.subInk,
                modifier = Modifier.fillMaxWidth()
            )
        }

        CardBackBlockType.DIVIDER -> HorizontalDivider(
            color = palette.panelBorder.copy(alpha = 0.7f)
        )

        CardBackBlockType.MEANING -> if (word.meaning.isNotBlank()) {
            // 大号蓝色「释义」标题已删除；词性徽章 + 高对比释义直接排版。
            MeaningBlock(
                meaning = word.meaning,
                bodyStyle = bodyStyle,
                overrideColor = overrideColor
            )
        }

        CardBackBlockType.MEMORY -> WordMemorySection(
            title = block.resolvedTitle,
            content = word.approach,
            // 词性小标的合法性来源：只贴 meaning 里真实存在的词性。
            meaning = word.meaning,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle).copy(
                lineHeight = 24.sp
            ),
            // 角标只挂在「思路」这一块：unverified 说的是这一版重写结果，不是搭配。
            unverified = unverified,
            unverifiedReason = unverifiedReason,
            showGuide = true
        )

        CardBackBlockType.COLLOCATIONS -> WordMemorySection(
            title = block.resolvedTitle,
            content = word.collocations,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle).copy(
                lineHeight = 23.sp
            ),
            bilingual = true
        )

        CardBackBlockType.EXAMPLE -> if (word.example.isNotBlank()) {
            WordExampleSection(
                title = block.resolvedTitle,
                content = word.example,
                titleColor = sectionLabelColor,
                titleStyle = sectionLabelStyle,
                contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle).copy(
                    lineHeight = 21.sp
                ),
                overrideColor = overrideColor
            )
        }

        CardBackBlockType.PHRASES -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.phrases,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        CardBackBlockType.SYNONYMS -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.synonyms,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        CardBackBlockType.REL_WORDS -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.relWords,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
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
 * 整页横向滑动拉出/收回「卡片材质」抽屉。
 *
 * 手势仲裁规则（与左侧导航抽屉共用）：
 * - 左抽屉开着或正在关（progress > 0.01）时本手势直接让位，左滑归左抽屉关自己；
 * - 材质抽屉关着时，只有屏幕右侧 40% 内起手、明确左移超过 touchSlop 才认领；
 * - 材质抽屉开着时，整块面板都能右滑收回（右移超过 touchSlop 才认领）；
 * - 认领后按手指位移实时改 progress（跟手），松手按位置/速度做弹簧吸附。
 */
private fun Modifier.styleDrawerLeftSwipe(
    state: CardStyleDrawerState,
    navDrawerOpen: Boolean
): Modifier = composed {
    val currentState by rememberUpdatedState(state)
    val currentNavOpen by rememberUpdatedState(navDrawerOpen)
    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 左侧导航抽屉开着/正在关时，横向拖动归导航抽屉，材质抽屉不抢。
            if (currentNavOpen) return@awaitEachGesture
            val startedOpen = currentState.isOpen
            val inOpenZone = down.position.x >= size.width * 0.60f
            // 关闭状态只在右 40% 起手能拉出；已展开时整块面板都能右滑收回。
            if (!inOpenZone && !startedOpen) return@awaitEachGesture
            val drawerWidthPx = minOf(size.width * 0.74f, 360.dp.toPx()).coerceAtLeast(1f)
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
                    val totalDy = change.position.y - down.position.y
                    val horizontal = abs(totalDx) > abs(totalDy)
                    val opening = totalDx < -viewConfiguration.touchSlop
                    val closing = startedOpen && totalDx > viewConfiguration.touchSlop
                    if (horizontal && (opening || closing) && !change.isConsumed) {
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
    // 1.5.2：统一交给纯 Kotlin 的 MeaningTextFormatter，合并同词性、去重同义项。
    return MeaningTextFormatter.displayText(meaning)
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
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    /** 1.4.7.1：这段内容是「没复核」的产出（只有 MEMORY 区块会传 true）。 */
    unverified: Boolean = false,
    /** 未复核的原因；点角标展开，空 = 只有角标没有说明。 */
    unverifiedReason: String? = null,
    /** MEMORY 左侧 2dp 渐变引导线；搭配区不需要。 */
    showGuide: Boolean = false,
    /** true = 英文亮白、中文柔和灰白（搭配）；false = 整行正文色（思路）。 */
    bilingual: Boolean = false,
    /** 词性小标的合法性来源（该词的 meaning）；空 = 一个标都不贴。 */
    meaning: String = ""
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(content) {
        content.split("\n")
            .map { normalizeMemoryArrows(it.trim()) }
            .filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    // 思路区逐行贴词性小标：只认正文写明的信号 + meaning 里确实有的词性，判不准就留白。
    val taggedLines = remember(content, meaning, bilingual) {
        if (bilingual) emptyList() else MemoryPosTagger.tag(content, meaning)
    }
    // 换版（正文或原因变了）自动收起：别把上一版的原因留在新正文旁边。
    var reasonExpanded by remember(content, unverifiedReason, unverified) { mutableStateOf(false) }
    val reason = unverifiedReason?.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        style = titleStyle,
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
        if (!title.isNullOrEmpty()) {
            // 标题下的 0.5dp 极细分割线：杂志式栏目标记，不靠深色卡片盒分层。
            HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.55f))
        }
        // 原因跟在标题行下面、正文上面：点开就看到，不打断读思路。
        if (unverified && reasonExpanded && reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = palette.subInk
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            if (showGuide) {
                // 2dp 渐变细竖线：把整段思路收成一条可读的「引文」。
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    palette.accent.copy(alpha = 0.85f),
                                    palette.accent.copy(alpha = 0.06f)
                                )
                            )
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (bilingual) 7.dp else 9.dp)
            ) {
                if (bilingual) {
                    lines.forEach { line ->
                        BilingualLine(
                            text = line,
                            style = contentStyle,
                            strongColor = palette.ink,
                            softColor = palette.subInk
                        )
                    }
                } else {
                    taggedLines.forEach { tagged ->
                        MemoryPosLine(
                            line = tagged,
                            style = contentStyle,
                            color = palette.ink,
                            dark = palette.dark
                        )
                    }
                }
            }
        }
    }
}

/** 行内小标占位符的高度（宽度按词性文字长度估算，见 [posInlineContent]）。 */
private val PosInlineBadgeHeight = 16.sp

/**
 * 记忆思路正文的一行（1.5.3：词性小标**行内嵌入**）。
 *
 * 三条硬规矩，都是为了修掉 1.5.2v2 的两个毛病：
 * 1. 一行就是一行：整行正文只交给一个 `Text` 渲染。**不在行首插 Row**，否则 `adj.`
 *    小标会骑到「③」编号的脑门上、左边还多出一段假缩进；也**不把一行拆成几段**去拼 Column，
 *    否则 `→` 后面的义项串会被拦腰劈成两行。
 * 2. [TaggedLine.badges] 里的每个词性做成一枚 `InlineTextContent` 占位符，用
 *    `appendInlineContent` 原地嵌回文本流；小标跟着字走，换行交给系统排版决定。
 * 3. 判不出词性的行（badges 为空）就是纯 `Text`，不加任何额外排版——宁缺毋滥。
 *
 * 翻卡/滑动/选中的手势挂在外层，这里只是文本节点，行间距由外层 Column 的
 * `Arrangement.spacedBy` 决定，未做任何改动。
 */
@Composable
private fun MemoryPosLine(
    line: TaggedLine,
    style: TextStyle,
    color: Color,
    dark: Boolean
) {
    val marks = remember(line) { posMarks(line) }
    if (marks.isEmpty()) {
        Text(
            text = line.text,
            style = style,
            color = color,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    val inlineContent = remember(marks, dark) { posInlineContent(marks, dark) }
    val annotated = remember(line, marks) { buildPosText(line.text, marks) }
    Text(
        text = annotated,
        inlineContent = inlineContent,
        style = style,
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 一个小标在正文上的落点，`id` 同时用于 AnnotatedString 与 inlineContent 映射。 */
private data class PosMarkPlace(
    val id: String,
    val pos: String,
    val start: Int,
    val end: Int
)

/** 把 [TaggedLine.badges] 过滤成可渲染的落点：越界或与前一个重叠的直接丢掉，绝不吞正文。 */
private fun posMarks(line: TaggedLine): List<PosMarkPlace> {
    val marks = ArrayList<PosMarkPlace>(line.badges.size)
    var cursor = 0
    for ((index, badge) in line.badges.withIndex()) {
        if (badge.start < cursor || badge.start > line.text.length) continue
        val end = badge.end.coerceIn(badge.start, line.text.length)
        marks.add(PosMarkPlace("pos-$index-${badge.pos}", badge.pos, badge.start, end))
        cursor = end
    }
    return marks
}

/** 把正文按落点拼成 AnnotatedString：被小标顶替的片段换成占位符，其余原样保留。 */
private fun buildPosText(text: String, marks: List<PosMarkPlace>): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (mark in marks) {
            if (mark.start > cursor) append(text.substring(cursor, mark.start))
            // start == end 的小标就是插在这一点前面，正文一个字都不删。
            appendInlineContent(mark.id, mark.pos)
            cursor = mark.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

/**
 * 小标占位符表。`Placeholder` 必须给死宽高（InlineTextContent 不会 hug 内容），
 * 所以宽度按词性文字长度估：10sp SemiBold 下每字符约 6sp，再加 9sp 内边距。
 */
private fun posInlineContent(
    marks: List<PosMarkPlace>,
    dark: Boolean
): Map<String, InlineTextContent> {
    val map = LinkedHashMap<String, InlineTextContent>(marks.size)
    for (mark in marks) {
        map[mark.id] = InlineTextContent(
            Placeholder(
                width = (mark.pos.length * 6 + 9).sp,
                height = PosInlineBadgeHeight,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
            )
        ) { _ ->
            PosInlineBadge(pos = mark.pos, dark = dark, modifier = Modifier.fillMaxSize())
        }
    }
    return map
}

/** 行内小标本体：与 [PosBadge] 同款配色、圆角、描边，尺寸由占位符给定。 */
@Composable
private fun PosInlineBadge(pos: String, dark: Boolean, modifier: Modifier = Modifier) {
    val color = posBadgeColor(pos.lowercase(), dark)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (dark) 0.18f else 0.11f))
            .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = pos,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp
            ),
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
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
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!title.isNullOrEmpty()) {
            Text(text = title, style = titleStyle, color = titleColor)
            HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.55f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                BilingualLine(
                    text = line,
                    style = contentStyle,
                    strongColor = palette.ink,
                    softColor = palette.subInk
                )
            }
        }
    }
}

/**
 * 例句：标题是 11sp 微标 + 0.5dp 极细分割线，正文不再套 Surface 盒。
 * 英文行亮白、中文行柔和灰白；若整段无法区分中英（没有拉丁字母），整体保持亮白。
 */
@Composable
private fun WordExampleSection(
    title: String?,
    content: String,
    titleColor: androidx.compose.ui.graphics.Color,
    titleStyle: TextStyle,
    contentStyle: TextStyle,
    overrideColor: androidx.compose.ui.graphics.Color?
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    val hasEnglish = remember(lines) {
        lines.any { line -> line.any { it in 'a'..'z' || it in 'A'..'Z' } }
    }
    val strongColor = overrideColor ?: palette.ink
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!title.isNullOrEmpty()) {
            Text(text = title, style = titleStyle, color = titleColor)
            HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.55f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            lines.forEach { line ->
                if (hasEnglish) {
                    BilingualLine(
                        text = line,
                        style = contentStyle,
                        strongColor = strongColor,
                        softColor = palette.subInk
                    )
                } else {
                    Text(text = line, style = contentStyle, color = strongColor)
                }
            }
        }
    }
}

/**
 * 英文在前、中文紧随的同一行：英文用亮色，中文用柔和灰白。
 * 找不到中文起点（整行英文）时整行亮色；整行中文时才整体柔和。
 */
@Composable
private fun BilingualLine(
    text: String,
    style: TextStyle,
    strongColor: Color,
    softColor: Color
) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val cjkIndex = trimmed.indexOfFirst { it.isCjk() }
    when {
        cjkIndex < 0 -> Text(text = trimmed, style = style, color = strongColor)
        cjkIndex == 0 -> Text(text = trimmed, style = style, color = softColor)
        else -> {
            val annotated = buildAnnotatedString {
                withStyle(SpanStyle(color = strongColor)) {
                    append(trimmed.substring(0, cjkIndex).trimEnd())
                }
                append("  ")
                withStyle(SpanStyle(color = softColor)) {
                    append(trimmed.substring(cjkIndex))
                }
            }
            Text(text = annotated, style = style)
        }
    }
}

/**
 * 释义：把词性解析成微型彩色圆角徽章，后面紧跟 19sp Medium 的高对比中文释义。
 * 多词性按行展示；没有词性时直接显示整段释义。保留 [formatMeaningByPos] 的分行能力。
 */
@Composable
private fun MeaningBlock(
    meaning: String,
    bodyStyle: CardBackSize?,
    overrideColor: Color?
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(meaning) {
        MeaningTextFormatter.displayLines(meaning)
    }
    val meaningStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 19.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Medium
    ).scaledBy(bodyStyle)
    val meaningColor = overrideColor ?: palette.ink
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        lines.forEach { line ->
            val pos = line.pos?.lowercase()
            if (pos != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    PosBadge(pos = pos, dark = palette.dark, modifier = Modifier.padding(top = 3.dp))
                    Text(
                        text = line.text,
                        style = meaningStyle,
                        color = meaningColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(text = line.text, style = meaningStyle, color = meaningColor)
            }
        }
    }
}

/** 单个词性徽章：10sp、圆角 4dp、同色描边 + 低透明底，彩色但不抢正文。 */
@Composable
private fun PosBadge(
    pos: String,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    val color = posBadgeColor(pos, dark)
    Text(
        text = pos,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp
        ),
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (dark) 0.18f else 0.11f))
            .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

private fun posBadgeColor(pos: String, dark: Boolean): Color = if (dark) {
    when (pos) {
        "n." -> Color(0xFF8FC2FF)
        "v.", "vt.", "vi." -> Color(0xFFFFC27A)
        "adj." -> Color(0xFFFF9EC4)
        "adv." -> Color(0xFF7FE7CE)
        "prep.", "conj." -> Color(0xFFC9B0FF)
        else -> Color(0xFFB7C6D8)
    }
} else {
    when (pos) {
        "n." -> Color(0xFF2F6FBF)
        "v.", "vt.", "vi." -> Color(0xFF9A5B00)
        "adj." -> Color(0xFFB03A6B)
        "adv." -> Color(0xFF00796B)
        "prep.", "conj." -> Color(0xFF6A4CBF)
        else -> Color(0xFF526273)
    }
}

/** 思路里的箭头统一成 →，避免 ——> / --> / → 三种写法混排。 */
private fun normalizeMemoryArrows(text: String): String =
    text.replace(Regex("""(?:—+|－+|-+)>"""), "→")

private fun Char.isCjk(): Boolean =
    this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

private val MEANING_POS_LINE = Regex(
    """^((?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.)\s*(.*)$""",
    RegexOption.IGNORE_CASE
)

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

internal fun String.adaptiveWordText(): String {
    val trimmed = trim()
    if (trimmed.length <= 12) return trimmed
    return trimmed.toCharArray().joinToString(separator = "\u200B")
}

/**
 * 1.5.3v2：卡背底部「编辑思路 / 锁定·免洗」的极简胶囊，与上方操作胶囊同高同风格，
 * 告别裸蓝字 TextButton。
 */
@Composable
private fun CompactActionChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(colors.surfaceVariant.copy(alpha = 0.42f))
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}

@Composable
private fun ReviewTimeTag(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {    Surface(
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



/**
 * 系统「动画程序时长缩放」是否开启：0 表示系统动画被关掉。
 * 关掉时所有切卡动效退化成 180ms 纯净淡入淡出（卡面材质动效不受影响）。
 */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var enabled by remember(context) { mutableStateOf(readAnimationScale(resolver) > 0f) }
    LifecycleResumeEffect(context) {
        enabled = readAnimationScale(resolver) > 0f
        onPauseOrDispose { }
    }
    return enabled
}

private fun readAnimationScale(resolver: android.content.ContentResolver): Float = try {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (_: Throwable) {
    1f
}


package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.data.model.CardMotionMode
import com.kaoyan.wordhelper.ui.component.AmbientBackdrop
import com.kaoyan.wordhelper.ui.component.AmbientBackdropMode
import com.kaoyan.wordhelper.ui.component.AppMenuButton
import com.kaoyan.wordhelper.ui.component.CardEntrance
import com.kaoyan.wordhelper.ui.component.CardStyleDrawer
import com.kaoyan.wordhelper.ui.component.CardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.CardStyleEntryButton
import com.kaoyan.wordhelper.ui.component.FlowBurnProgressBar
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.studyContentInsets
import com.kaoyan.wordhelper.ui.viewmodel.LearningAiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningPace
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val RECOGNITION_SECTION_SPACING = 8.dp

internal data class CardUiPrefs(
    val styleId: String = "random",
    val glossHigh: Boolean = true,
    val motionMode: CardMotionMode = CardMotionMode.FULL,
    val ambientBackdropId: String = "auto"
)

@Composable
internal fun LearningStudyFrame(
    styleDrawerState: CardStyleDrawerState,
    navDrawerOpen: Boolean,
    navDrawerDepthScale: () -> Float,
    cardUiPrefs: CardUiPrefs,
    cardStyle: HoloCardStyle,
    systemAnimationsOn: Boolean,
    lightTime: () -> Float,
    onOpenDrawer: () -> Unit,
    uiScope: CoroutineScope,
    appContext: KaoyanWordApp,
    currentIndex: Int,
    totalCount: Int,
    learningPace: LearningPace,
    motionEnabled: Boolean,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
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
            content()

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
        overlay()
    }
}

@Composable
internal fun ColumnScope.LearningRecognitionLayout(
    presentation: Long,
    motionEnabled: Boolean,
    card: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
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
                    card()
                }
            }
        }
        actions()
    }
}

@Composable
internal fun ColumnScope.LearningSpellingContent(
    currentWord: Word,
    currentIndex: Int,
    isAnswering: Boolean,
    algorithmV4Enabled: Boolean,
    aiState: LearningAiState,
    viewModel: LearningViewModel
) {
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

package com.kaoyan.wordhelper.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.CardMotionMode
import com.kaoyan.wordhelper.ui.component.AnimatedStarToggle
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.HoloCardSurface
import com.kaoyan.wordhelper.ui.component.HoloCardTheme
import com.kaoyan.wordhelper.ui.component.HoloLightState
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.ui.component.StudyCardBody
import com.kaoyan.wordhelper.ui.component.StudyCardShape
import com.kaoyan.wordhelper.ui.component.turnExposure
import com.kaoyan.wordhelper.ui.theme.AlertRed
import kotlinx.coroutines.launch

@Composable
internal fun WordCard(
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
    memoryLocked: Boolean,
    onEditMemory: () -> Unit,
    onToggleMemoryLock: () -> Unit,
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
                        if (!showBack) {
                            AnimatedStarToggle(
                                checked = isInNewWords,
                                onCheckedChange = { onToggleNewWords() },
                                enabled = actionsEnabled,
                                modifier = Modifier.testTag("learning_star_toggle")
                            )
                        } else {
                            val haptic = LocalHapticFeedback.current
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                MemoryActionIcon(
                                    icon = Icons.Outlined.Edit,
                                    description = "编辑思路",
                                    ink = faceInk,
                                    enabled = actionsEnabled,
                                    onClick = onEditMemory,
                                    modifier = Modifier.testTag("memory_edit")
                                )
                                MemoryActionIcon(
                                    icon = if (memoryLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                    description = if (memoryLocked) "已锁定 · 免洗" else "锁定 · 免洗",
                                    ink = if (memoryLocked) Color(0xFF81500A) else faceInk,
                                    enabled = actionsEnabled,
                                    highlighted = memoryLocked,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleMemoryLock()
                                    },
                                    modifier = Modifier.testTag("memory_lock")
                                )
                            }
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

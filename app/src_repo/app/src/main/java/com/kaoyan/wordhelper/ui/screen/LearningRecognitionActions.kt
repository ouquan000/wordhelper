package com.kaoyan.wordhelper.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.ui.theme.AlertRed
import com.kaoyan.wordhelper.ui.theme.FuzzyYellow
import com.kaoyan.wordhelper.ui.theme.KnownGreen

/**
 * 一体化光谱滑轨：左绯红（不认识）→ 中琥珀金（模糊）→ 右翡翠青绿（认识）。
 *
 * - Tap：点任意 1/3 段，游标胶囊吸附过去、同色霓虹光晕亮起、触感反馈，立即回调；
 * - Horizontal Drag：游标实时跟手，跨段触发线性马达 Tick 与高亮切换，松手提交当前档位；
 * - isAnswering = true 时整条轨道只展示不提交；showSkip = true 时右侧并排紧凑「跳过」胶囊。
 */
@Composable
internal fun RecognitionActionPanel(
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
                        val gesture = LearningRatingGesture(
                            down.position.x, down.position.y, trackWidthPx, verticalCancelPx
                        )
                        gestureCanceled = false
                        cursorIndex = gesture.index
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            when (gesture.move(
                                change.position.x, change.position.y,
                                (change.position - down.position).getDistance(),
                                viewConfiguration.touchSlop, trackWidthPx
                            )) {
                                LearningRatingGesture.Move.Idle -> Unit
                                LearningRatingGesture.Move.Cancel -> gestureCanceled = true
                                LearningRatingGesture.Move.Drag -> change.consume()
                                LearningRatingGesture.Move.ChangeSegment -> {
                                    cursorIndex = gesture.index
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    change.consume()
                                }
                            }
                        }
                        gesture.finish()?.let(::submit)
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

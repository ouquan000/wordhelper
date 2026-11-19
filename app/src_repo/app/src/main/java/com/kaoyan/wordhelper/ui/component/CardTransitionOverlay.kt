package com.kaoyan.wordhelper.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.StudyRating
import kotlin.math.roundToInt

/**
 * 切卡过场（1.4.8 轻量版）。
 *
 * 设计约束：
 * 1. 不抓快照、不建 Bitmap、不跑粒子/Canvas 逐帧绘制——退场只是一块跟随卡片 bounds 的
 *    半透明材质色块，由 `graphicsLayer` 做位移 + rotationZ + alpha，全程 GPU 合成。
 * 2. 评分提交仍在 ViewModel 里完成，这里只负责「旧卡离场示意 + 新卡淡入」的排期，零阻塞。
 * 3. 过场层是 Root Box 上最后一个透明兄弟节点：没有任何 pointerInput / clickable，
 *    绝不拦截触摸，也不会改变页面的命中测试结果。
 */
private const val EXIT_DURATION_MS = 180
private const val ENTRANCE_DURATION_MS = 120

/** 退场方向：认识/模糊向右滑出，不认识向左滑出。 */
enum class CardExitStyle {
    /** 认识 / 模糊：向右滑出。 */
    SLIDE_RIGHT,

    /** 不认识（AGAIN）：向左滑出。 */
    SLIDE_LEFT,

    /** 关闭动效 / 系统动画关闭：原地淡出。 */
    FADE
}

/** 一条正在播放的退场 ghost：只记几何、方向与皮肤，不持任何像素。 */
@Stable
internal class CardExitGhost(
    val id: Long,
    val bounds: Rect,
    val style: CardExitStyle,
    /** 退场时用的是哪套全息皮肤：ghost 的材质底色跟着它走，浅色卡不会「变黑滑走」。 */
    val cardStyle: HoloCardStyle
)

/**
 * 过场控制器：持有退场 ghost 列表 + 入场排期。挂在学习页的 remember 里，跨词复用。
 */
@Stable
class CardTransitionController internal constructor() {

    internal val ghosts = mutableStateListOf<CardExitGhost>()

    /** 新卡延迟多久入场。轻量方案里新旧卡直接交叉淡化，默认不等待。 */
    var entranceDelayMs by mutableStateOf(0L)
        private set

    /** 入场代数：0 表示页面首次进入（不播放入场），>0 每次评分自增。 */
    var entranceGeneration by mutableIntStateOf(0)
        private set

    private var nextGhostId = 0L

    /** 把评分映射成退场方向。规则集中在这里，UI 层只传事实。 */
    @Suppress("UNUSED_PARAMETER")
    fun resolveExitStyle(
        rating: StudyRating,
        removeFromNewWords: Boolean,
        paceTier: Int,
        paceHeat: Float
    ): CardExitStyle = when (rating) {
        StudyRating.AGAIN -> CardExitStyle.SLIDE_LEFT
        StudyRating.HARD,
        StudyRating.GOOD -> CardExitStyle.SLIDE_RIGHT
    }

    /**
     * 评分入口调用：按评分方向启动旧卡离场，并让新卡立刻开始淡入。
     * 返回实际播放的动效种类，便于日志核对。
     */
    fun launchAnswer(
        rating: StudyRating,
        removeFromNewWords: Boolean,
        paceTier: Int,
        paceHeat: Float,
        bounds: Rect?,
        motionEnabled: Boolean,
        /** 当前卡片皮肤：决定退场 ghost 的材质底色。 */
        cardStyle: HoloCardStyle = HoloCardStyle.PEARL
    ): CardExitStyle {
        val style = resolveExitStyle(rating, removeFromNewWords, paceTier, paceHeat)
        launch(bounds, style, motionEnabled, cardStyle)
        return style
    }

    /** 底层入口：沙盒 / 调试也可以直接指定动效。 */
    fun launch(
        bounds: Rect?,
        style: CardExitStyle,
        motionEnabled: Boolean,
        cardStyle: HoloCardStyle = HoloCardStyle.PEARL
    ) {
        // 无论有没有 ghost，都要推进入场代数，保证新卡一定会淡入。
        entranceGeneration++
        entranceDelayMs = 0L
        if (!motionEnabled || bounds == null) return
        if (bounds.width <= 0f || bounds.height <= 0f) return
        // 上限 3 个：极快连点时只能看到最近三次的方向，旧 ghost 直接丢弃（它已淡到接近 0）。
        if (ghosts.size >= MAX_GHOSTS) ghosts.removeAt(0)
        ghosts.add(CardExitGhost(nextGhostId++, bounds, style, cardStyle))
    }

    /** ghost 播完 180ms 后由 Overlay 回收。 */
    internal fun retire(ghost: CardExitGhost) {
        ghosts.remove(ghost)
    }

    private companion object {
        const val MAX_GHOSTS = 3
    }
}

@Composable
fun rememberCardTransitionController(): CardTransitionController =
    remember { CardTransitionController() }

/**
 * 透明过场层：只画退场 ghost。Root Box 没有 pointerInput，摸不到也拦不住任何触摸。
 */
@Composable
fun CardTransitionOverlay(
    controller: CardTransitionController,
    modifier: Modifier = Modifier
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        controller.ghosts.forEach { ghost ->
            key(ghost.id) {
                ExitGhost(
                    ghost = ghost,
                    origin = origin,
                    onFinished = { controller.retire(ghost) }
                )
            }
        }
    }
}

/**
 * 单块退场 ghost：180ms 内位移 + 轻微 rotationZ + alpha 渐隐。
 * 所有逐帧值都在 graphicsLayer 的 lambda 里读，不触发重组。
 */
@Composable
private fun ExitGhost(
    ghost: CardExitGhost,
    origin: Offset,
    onFinished: () -> Unit
) {
    val density = LocalDensity.current
    val progress = remember(ghost.id) { Animatable(0f) }
    LaunchedEffect(ghost.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = EXIT_DURATION_MS,
                easing = FastOutLinearInEasing
            )
        )
        onFinished()
    }

    val shape = RoundedCornerShape(24.dp)
    // 用卡片本体的皮肤取材质底色：深色皮肤仍是深色块，PEARL / CLASSIC / CHROME
    // 这类浅色卡退场时也不会「变黑滑走」。HoloCardTheme 只覆盖配色，不画任何东西。
    HoloCardTheme(style = ghost.cardStyle) {
        val palette = LocalHoloCardPalette.current
        val fillBrush = remember(palette) {
            Brush.verticalGradient(
                colors = listOf(
                    palette.frontTop.copy(alpha = 0.94f),
                    palette.frontBottom.copy(alpha = 0.94f)
                )
            )
        }
        val edgeBrush = remember(palette) {
            Brush.linearGradient(
                colors = listOf(
                    palette.panelBorder.copy(alpha = 0.90f),
                    palette.accent.copy(alpha = 0.35f)
                )
            )
        }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (ghost.bounds.left - origin.x).roundToInt(),
                        (ghost.bounds.top - origin.y).roundToInt()
                    )
                }
                .size(
                    width = with(density) { ghost.bounds.width.toDp() },
                    height = with(density) { ghost.bounds.height.toDp() }
                )
                .graphicsLayer {
                    val p = progress.value
                    val direction = when (ghost.style) {
                        CardExitStyle.SLIDE_LEFT -> -1f
                        CardExitStyle.SLIDE_RIGHT -> 1f
                        CardExitStyle.FADE -> 0f
                    }
                    translationX = direction * size.width * 0.30f * p
                    rotationZ = direction * 3.5f * p
                    alpha = (1f - p) * 0.92f
                    transformOrigin = TransformOrigin.Center
                }
                .clip(shape)
                .background(fillBrush)
                .border(1.dp, edgeBrush, shape)
        )
    }
}

// ---------------------------------------------------------------------------
// 入场：新卡原地 120ms 淡入
// ---------------------------------------------------------------------------

/**
 * 包住 WordCard 的入场层。
 *
 * 只负责新卡淡入：120ms alpha 0 -> 1。旧卡离场由 [CardTransitionOverlay] 的 ghost 负责，
 * 两边一进一出交叉淡化，不做位移/旋转/扫光，避免双重淡入与重复渲染。
 */
@Composable
@Suppress("UNUSED_PARAMETER") // cornerRadius 仅为保持既有调用签名（UI 实验页传 28dp）。
fun CardEntrance(
    contentKey: Any?,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    controller: CardTransitionController? = null,
    content: @Composable () -> Unit
) {
    var previousKey by remember { mutableStateOf(contentKey) }
    val generation = controller?.entranceGeneration ?: 0
    val progress = remember(contentKey, generation) {
        Animatable(if (motionEnabled && (previousKey != contentKey || generation > 0)) 0f else 1f)
    }
    LaunchedEffect(contentKey, generation, motionEnabled) {
        previousKey = contentKey
        if (motionEnabled && progress.value < 1f) {
            progress.animateTo(1f, tween(ENTRANCE_DURATION_MS, easing = LinearOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            // A newly presented word stays readable on the first frame.
            scaleX = 0.988f + 0.012f * progress.value
            scaleY = scaleX
        }
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 叠罗汉牌堆底座
// ---------------------------------------------------------------------------

/**
 * 主卡片下面透出来的一层「底牌」：下移 12dp、缩到 0.95、压一层暗色遮罩，
 * 边缘描一圈全息光泽，让牌堆有实体厚度。
 *
 * 红线：底牌 rotationX 恒 0f（不做任何 3D 翻转）。
 */
@Composable
fun DeckStackUnderlay(
    cardStyle: HoloCardStyle,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp
) {
    HoloCardTheme(style = cardStyle) {
        val palette = LocalHoloCardPalette.current
        val shape = RoundedCornerShape(cornerRadius)
        val density = LocalDensity.current
        val offsetPx = with(density) { 10.dp.toPx() }

        Box(
            modifier = modifier
                .graphicsLayer {
                    rotationX = 0f
                    rotationY = 0f
                    scaleX = 0.95f
                    scaleY = 0.95f
                    translationY = offsetPx
                }
                .clip(shape)
                // 底牌整体压暗：主卡还在时只露出 12dp 的边，主卡离场的那半秒
                // 它必须读成「牌堆里的下一张底牌」，而不是一张空白卡。
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1C1826),
                            if (palette.dark) Color(0xFF0A0810) else Color(0xFF2C2636)
                        )
                    )
                )
                // 轻暗色遮罩：压住底牌，只透出一点材质光
                .background(Color.Black.copy(alpha = 0.16f))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            palette.accent.copy(alpha = 0.45f),
                            palette.accent.copy(alpha = 0.10f)
                        )
                    ),
                    shape = shape
                )
                .drawWithContent {
                    drawContent()
                    // 底部边缘的全息反光：牌堆厚度主要靠这一条亮边读出来
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                palette.accent.copy(alpha = 0.26f),
                                Color.White.copy(alpha = 0.10f)
                            ),
                            startY = size.height * 0.52f,
                            endY = size.height
                        )
                    )
                }
        )
    }
}

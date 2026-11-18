package com.kaoyan.wordhelper.ui.component

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.StudyRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 卡片物理销毁过场核心。
 *
 * 设计约束（对应产品规格 m01119）：
 * 1. 评分提交与切词完全在 ViewModel 里完成，这里只在「点下去的那一帧」抓一张当前卡片的位图快照；
 *    动画再长也不阻塞下一次点击、不阻塞数据推进。
 * 2. 特效画布是学习页最上层的一张透明 Canvas：没有 pointerInput / clickable，绝不拦截触摸。
 * 3. 同时最多 3 个特效；第 4 个进来时最老的一个先平滑淡出再回收 bitmap。
 * 4. Compose BOM 2024.05.00 / UI 1.6.7 没有 `rememberGraphicsLayer()`，
 *    所以抓拍走同步 `View.draw(Canvas(Bitmap))`（软件绘制），并且按卡片圆角裁切。
 */
private const val TAG = "CardTransition"

/** 退场物理动效种类。 */
enum class CardExitStyle {
    /** 随机多向撕碎：太简单 / 斩杀 / 移出生词本。 */
    TEAR,

    /** 不规则 Voronoi 碎裂：不认识（AGAIN）。 */
    VORONOI,

    /** 纸张焚烧：Lv.4 燃烧高燃态下的斩杀。 */
    BURN,

    /** 桌面交替甩飞：认识。 */
    FLING_GOOD,

    /** 右下微弧缩回牌堆：模糊。 */
    FLING_HARD,

    /** 180ms 纯净淡入淡出（关闭动效 / 系统动画关闭）。 */
    FADE
}

/** 一张卡片的同步快照，坐标系是 App 根 View。 */
class CardSnapshot internal constructor(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float
) {
    private var recycled = false

    internal fun recycle() {
        if (recycled) return
        recycled = true
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * 同步抓拍：[view] 是 Activity 的根 View（LocalView），[left]/[top] 是卡片在根 View 坐标系里的位置。
 *
 * 为什么不用 PixelCopy：PixelCopy 是异步的，等回调回来时 ViewModel 可能已经切到下一张卡，
 * 抓到的就不是「用户点下去时看到的那张」。View.draw 是同步的，且本项目卡片全部是 Compose 自绘
 * （没有 TextureView / 视频 / RenderEffect），软件绘制能完整还原。
 */
fun captureCardSnapshot(
    view: View,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerRadiusPx: Float
): CardSnapshot? {
    val w = width.toInt()
    val h = height.toInt()
    if (w <= 0 || h <= 0 || view.width <= 0 || view.height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.translate(-left, -top)
        view.draw(canvas)
        // 兜底自检：完全空白说明软件绘制路径没成立，宁可不播特效也不要播一张黑卡。
        var hits = 0
        var samples = 0
        for (sy in 1..7) {
            for (sx in 1..5) {
                samples++
                if (bitmap.getPixel(w * sx / 6, h * sy / 8) != 0) hits++
            }
        }
        Log.d(TAG, "capture ${w}x$h hits=$hits/$samples at=($left,$top)")
        if (hits == 0) {
            bitmap.recycle()
            null
        } else {
            CardSnapshot(bitmap, left, top, w.toFloat(), h.toFloat(), cornerRadiusPx)
        }
    }.getOrElse {
        Log.e(TAG, "capture failed", it)
        null
    }
}

/**
 * 过场控制器：持有特效列表 + 入场排期。挂在 LearningScreen 的 remember 里，跨词复用。
 */
@Stable
class CardTransitionController internal constructor(
    private val haptics: HapticFeedback?,
    private val scope: CoroutineScope
) {
    internal val effects = mutableStateListOf<BaseEffect>()

    /** 每次 launch 自增，Overlay 用它启动逐帧循环（避免快连点时漏掉开始信号）。 */
    internal var effectGeneration by mutableIntStateOf(0)
        private set

    /** 新卡延迟多久入场；由旧特效总时长的 65% 算出。 */
    var entranceDelayMs by mutableStateOf(0L)
        private set

    /** 入场代数：0 表示页面首次进入（不播放入场），>0 每次评分自增。 */
    var entranceGeneration by mutableIntStateOf(0)
        private set

    private var goodSide = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 把评分映射成退场动效。规则集中在这里，UI 层只传事实。 */
    fun resolveExitStyle(
        rating: StudyRating,
        removeFromNewWords: Boolean,
        paceTier: Int,
        paceHeat: Float
    ): CardExitStyle = when {
        // Lv.4 燃烧高燃态下把词移出生词本 = 斩杀，烧掉
        removeFromNewWords && (paceTier >= 4 || paceHeat >= 0.62f) -> CardExitStyle.BURN
        removeFromNewWords -> CardExitStyle.TEAR
        rating == StudyRating.AGAIN -> CardExitStyle.VORONOI
        rating == StudyRating.HARD -> CardExitStyle.FLING_HARD
        rating == StudyRating.GOOD -> CardExitStyle.FLING_GOOD
        else -> CardExitStyle.FADE
    }

    /**
     * 评分入口调用：先抓到的快照 + 评分 -> 启动退场，并排好新卡 65% 入场的时刻。
     * 返回实际播放的动效种类，便于日志核对。
     */
    fun launchAnswer(
        rating: StudyRating,
        removeFromNewWords: Boolean,
        paceTier: Int,
        paceHeat: Float,
        snapshot: CardSnapshot?,
        motionEnabled: Boolean
    ): CardExitStyle {
        val style = resolveExitStyle(rating, removeFromNewWords, paceTier, paceHeat)
        launch(snapshot, style, motionEnabled)
        Log.d(
            TAG,
            "answer rating=$rating remove=$removeFromNewWords tier=$paceTier " +
                "heat=$paceHeat style=$style motion=$motionEnabled snapshot=${snapshot != null}"
        )
        return style
    }

    /** 底层入口：沙盒 / 调试也可以直接指定动效。 */
    fun launch(snapshot: CardSnapshot?, style: CardExitStyle, motionEnabled: Boolean) {
        effectGeneration++
        if (snapshot == null) {
            // 抓拍失败时也不要卡住新卡：直接让它 0 延迟淡入。
            entranceDelayMs = 0L
            entranceGeneration++
            return
        }
        val now = System.nanoTime()
        // 上限 3 个：最老的一个进入 150ms 淡出 + 回收。
        if (effects.size >= MAX_EFFECTS) {
            effects.firstOrNull()?.retireAtNanos = now
        }
        val effective = if (motionEnabled) style else CardExitStyle.FADE
        val effect: BaseEffect = when (effective) {
            CardExitStyle.TEAR -> TearEffect(snapshot, now, Random.nextLong())
            CardExitStyle.VORONOI -> VoronoiEffect(snapshot, now, Random.nextLong())
            CardExitStyle.BURN -> BurnEffect(snapshot, now, Random.nextLong())
            CardExitStyle.FLING_GOOD -> FlingEffect(snapshot, now, CardExitStyle.FLING_GOOD, goodSide)
            CardExitStyle.FLING_HARD -> FlingEffect(snapshot, now, CardExitStyle.FLING_HARD, 1f)
            CardExitStyle.FADE -> FadeEffect(snapshot, now)
        }
        if (effective == CardExitStyle.FLING_GOOD) goodSide = -goodSide
        effects.add(effect)
        fireHaptics(effective)
        entranceDelayMs = if (effective == CardExitStyle.FADE) 0L else (effect.durationMs * 0.65f).toLong()
        entranceGeneration++
    }

    private fun fireHaptics(style: CardExitStyle) {
        val h = haptics ?: return
        when (style) {
            // 撕裂：三次细腻线性 Tick，沿裂口拉链走
            CardExitStyle.TEAR -> scope.launch {
                repeat(3) { index ->
                    h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (index < 2) delay(85)
                }
            }

            // 碎裂：受击一下清脆震动
            CardExitStyle.VORONOI -> {
                h.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    delay(60)
                    h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }

            // 焚烧：低频闷震 + 一阵细碎
            CardExitStyle.BURN -> {
                h.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    delay(140)
                    h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }

            else -> Unit
        }
    }

    internal fun step(nowNanos: Long) {
        var i = 0
        while (i < effects.size) {
            val effect = effects[i]
            if (effect.shouldRemove(nowNanos)) {
                effects.removeAt(i)
                effect.snapshot.recycle()
                continue
            }
            effect.advance(nowNanos)
            i++
        }
    }

    internal fun draw(canvas: AndroidCanvas, origin: Offset, tick: Int) {
        if (effects.isEmpty()) return
        val now = System.nanoTime()
        // 读一下 tick，确保 Canvas 每帧都被 invalidate
        if (tick < 0) return
        for (effect in effects) {
            val fade = effect.fadeMultiplier(now)
            if (fade <= 0.01f) continue
            paint.reset()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            paint.alpha = 255
            canvas.save()
            effect.draw(canvas, origin, now, paint, fade)
            canvas.restore()
        }
    }

    private companion object {
        const val MAX_EFFECTS = 3
    }
}

@Composable
fun rememberCardTransitionController(): CardTransitionController {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return remember(haptics, scope) { CardTransitionController(haptics, scope) }
}

/**
 * 透明特效画布：铺满学习页，不接任何触摸。
 */
@Composable
fun CardTransitionOverlay(
    controller: CardTransitionController,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // 每次 launch 都自增 effectGeneration，保证快连点时逐帧循环不会漏启动。
    LaunchedEffect(controller) {
        snapshotFlow { controller.effectGeneration }.collectLatest {
            while (controller.effects.isNotEmpty()) {
                withFrameNanos { now ->
                    controller.step(now)
                    tick++
                }
            }
        }
    }

    Canvas(
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        controller.draw(drawContext.canvas.nativeCanvas, origin, tick)
    }
}

// ---------------------------------------------------------------------------
// 入场：右下角牌靴弧线抽新卡
// ---------------------------------------------------------------------------

/**
 * 包住 WordCard 的入场层。
 *
 * - 旧特效播到 65% 时（controller.entranceDelayMs）才开始；
 * - 440ms 从 (+52% 屏宽, +40% 屏高, rotZ=14°, scale=0.88) 阻尼弹簧滑到中心，
 *   Y 轴比 X 滞后 35ms，形成向上托起的微拱弧线；
 * - rotZ 到 0 时带 -1.2° 过冲回弹；
 * - 站稳瞬间（440ms）一道全息高光扫过卡面；
 * - 关闭动效 -> 180ms 纯淡入。
 */
@Composable
fun CardEntrance(
    controller: CardTransitionController,
    contentKey: Any?,
    motionEnabled: Boolean,
    cornerRadius: Dp = 28.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val generation = controller.entranceGeneration
    var elapsedMs by remember(contentKey, generation) { mutableStateOf(if (generation == 0) 999f else 0f) }
    var progress by remember(contentKey, generation) { mutableStateOf(if (generation == 0) 1f else 0f) }

    LaunchedEffect(contentKey, generation) {
        if (generation == 0) {
            elapsedMs = 999f
            progress = 1f
            return@LaunchedEffect
        }
        val delayMs = controller.entranceDelayMs
        val total = if (motionEnabled) 440f else 180f
        if (delayMs > 0L) delay(delayMs)
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val e = (now - start) / 1_000_000f
            elapsedMs = e
            progress = (e / total).coerceIn(0f, 1f)
            if (e >= total) break
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.toPx() }

    Box(
        modifier = modifier
            .graphicsLayer {
                if (generation == 0) {
                    alpha = 1f
                    return@graphicsLayer
                }
                val t = progress
                if (!motionEnabled) {
                    alpha = t
                    return@graphicsLayer
                }
                if (t <= 0f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val pX = easeOutQuart(t)
                val yRaw = ((elapsedMs - 35f) / 405f).coerceIn(0f, 1f)
                val pY = easeOutQuart(yRaw)
                translationX = (1f - pX) * 0.52f * screenW
                translationY = (1f - pY) * 0.40f * screenH -
                    sin(PI * t).toFloat() * screenH * 0.055f
                rotationZ = entranceRotation(t)
                val scale = 0.88f + 0.12f * pX
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                alpha = t.coerceAtLeast(0.001f)
            }
            .drawWithContent {
                drawContent()
                if (motionEnabled && generation > 0 && elapsedMs >= 440f) {
                    val sweep = ((elapsedMs - 440f) / 220f).coerceIn(0f, 1f)
                    if (sweep < 1f) {
                        val band = size.width * 0.24f
                        val x = -band + (size.width + band * 2f) * sweep
                        val radius = with(density) { cornerRadius.toPx() }
                        val clip = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 0f,
                                    top = 0f,
                                    right = size.width,
                                    bottom = size.height,
                                    cornerRadius = CornerRadius(radius, radius)
                                )
                            )
                        }
                        clipPath(clip) {
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.10f),
                                        Color.White.copy(alpha = 0.55f),
                                        Color.White.copy(alpha = 0.10f),
                                        Color.Transparent
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x + band, size.height)
                                ),
                                topLeft = Offset(x, 0f),
                                size = androidx.compose.ui.geometry.Size(band, size.height)
                            )
                        }
                    }
                }
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
    cornerRadius: Dp = 28.dp
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

// ---------------------------------------------------------------------------
// 特效基类
// ---------------------------------------------------------------------------

internal abstract class BaseEffect(
    val snapshot: CardSnapshot,
    val startNanos: Long
) {
    abstract val durationMs: Long

    /** 被第 4 个特效顶掉时的时间点；>0 表示进入淡出回收。 */
    var retireAtNanos: Long = -1L

    /** 1dp 对应的像素（按 360dp 卡宽折算，避免特效里再传 density）。 */
    protected val unit: Float = (snapshot.width / 360f).coerceAtLeast(1f)

    protected fun ageMs(nowNanos: Long): Float = (nowNanos - startNanos) / 1_000_000f

    fun fadeMultiplier(nowNanos: Long): Float {
        if (retireAtNanos <= 0L) return 1f
        return (1f - (nowNanos - retireAtNanos) / 150_000_000f).coerceIn(0f, 1f)
    }

    fun shouldRemove(nowNanos: Long): Boolean {
        if (retireAtNanos > 0L && nowNanos - retireAtNanos > 150_000_000L) return true
        return nowNanos - startNanos > (durationMs + 140L) * 1_000_000L
    }

    abstract fun advance(nowNanos: Long)

    /**
     * @param canvas 根 Canvas（未做任何平移）
     * @param origin Overlay 自身在根坐标系的位置
     */
    abstract fun draw(
        canvas: AndroidCanvas,
        origin: Offset,
        nowNanos: Long,
        paint: Paint,
        fade: Float
    )

    protected fun x0(origin: Offset): Float = snapshot.left - origin.x

    protected fun y0(origin: Offset): Float = snapshot.top - origin.y

    protected val roundRect: RectF
        get() = RectF(0f, 0f, snapshot.width, snapshot.height)

    /** 圆角裁剪 + 位图绘制，保持快照的圆角皮肤。 */
    protected fun drawBitmapRounded(
        canvas: AndroidCanvas,
        ox: Float,
        oy: Float,
        dx: Float,
        dy: Float,
        rotation: Float,
        scale: Float,
        alpha: Float,
        paint: Paint,
        pivotX: Float = snapshot.width / 2f,
        pivotY: Float = snapshot.height / 2f
    ) {
        if (alpha <= 0.004f) return
        val r = snapshot.cornerRadius
        canvas.save()
        canvas.translate(ox + dx + pivotX, oy + dy + pivotY)
        if (rotation != 0f) canvas.rotate(rotation)
        if (scale != 1f) canvas.scale(scale, scale)
        canvas.translate(-pivotX, -pivotY)
        val clip = AndroidPath().apply {
            addRoundRect(RectF(0f, 0f, snapshot.width, snapshot.height), r, r, AndroidPath.Direction.CW)
        }
        canvas.clipPath(clip)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(snapshot.bitmap, 0f, 0f, paint)
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------
// 1. 随机多向撕碎
// ---------------------------------------------------------------------------

private class TearEffect(
    snapshot: CardSnapshot,
    startNanos: Long,
    seed: Long
) : BaseEffect(snapshot, startNanos) {

    override val durationMs: Long = 950L

    private val random = Random(seed)
    private val normal: Offset
    private val points: List<Offset>
    private val pathA: AndroidPath
    private val pathB: AndroidPath
    private val centroidA: Offset
    private val centroidB: Offset
    private val hairs: List<Pair<Offset, Offset>>
    private val camera = Camera()
    private val matrix = Matrix()

    init {
        val w = snapshot.width
        val h = snapshot.height
        val u = unit

        // 随机剖分线角度：横 / 竖 / 斜上 / 斜下
        val angleDeg = when (random.nextInt(4)) {
            0 -> 6f + random.nextFloat() * 24f
            1 -> 84f + random.nextFloat() * 24f
            2 -> 38f + random.nextFloat() * 26f
            else -> 128f + random.nextFloat() * 26f
        }
        val rad = angleDeg * PI / 180.0
        val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
        normal = Offset(-dir.y, dir.x)

        // 穿过卡片中心区域，允许 ±12% 的偏移
        val shift = (random.nextFloat() * 2f - 1f) * 0.12f * min(w, h)
        val center = Offset(w / 2f, h / 2f) + normal * shift

        var tMin = -1e6f
        var tMax = 1e6f
        if (abs(dir.x) > 1e-4f) {
            val t1 = (0f - center.x) / dir.x
            val t2 = (w - center.x) / dir.x
            tMin = max(tMin, min(t1, t2))
            tMax = min(tMax, max(t1, t2))
        }
        if (abs(dir.y) > 1e-4f) {
            val t1 = (0f - center.y) / dir.y
            val t2 = (h - center.y) / dir.y
            tMin = max(tMin, min(t1, t2))
            tMax = min(tMax, max(t1, t2))
        }
        val start = center + dir * (tMin - 0.03f * max(w, h))
        val end = center + dir * (tMax + 0.03f * max(w, h))

        // 高频锯齿纸纤维：沿剖分线的法线做锯齿 + 噪声抖动
        val count = 28
        val list = ArrayList<Offset>(count)
        for (i in 0 until count) {
            val f = i / (count - 1f)
            val base = start + (end - start) * f
            val edgeFactor = sin(PI * f).toFloat()
            val saw = if (i % 2 == 0) 1f else -1f
            val jitter = (saw * 0.85f + (random.nextFloat() - 0.5f) * 1.9f) * edgeFactor
            val wobble = sin(f * 12f + (seed % 9)) * 1.3f * edgeFactor
            list += base + normal * ((jitter + wobble) * 2.1f * u)
        }
        points = list

        val basePath = AndroidPath().apply {
            addRoundRect(RectF(0f, 0f, w, h), snapshot.cornerRadius, snapshot.cornerRadius, AndroidPath.Direction.CW)
        }
        val far = max(w, h) * 4f
        val cut = AndroidPath().apply {
            moveTo(points.first().x + normal.x * far, points.first().y + normal.y * far)
            for (p in points) lineTo(p.x, p.y)
            lineTo(points.last().x + normal.x * far, points.last().y + normal.y * far)
            close()
        }
        val a = AndroidPath(basePath)
        a.op(cut, AndroidPath.Op.INTERSECT)
        val b = AndroidPath(basePath)
        b.op(cut, AndroidPath.Op.DIFFERENCE)
        if (a.isEmpty) {
            pathA = basePath
            pathB = AndroidPath()
        } else {
            pathA = a
            pathB = b
        }
        centroidA = centroidOf(pathA)
        centroidB = centroidOf(pathB)

        // 裂口上的 2dp 毛糙白色纸纤维
        val fiberList = ArrayList<Pair<Offset, Offset>>()
        for (i in points.indices step 2) {
            val p = points[i]
            repeat(2) {
                val len = (2.5f + random.nextFloat() * 4.5f) * u
                val angle = (random.nextFloat() - 0.5f) * 1.6f
                val dx = cos(angle) * len
                val dy = sin(angle) * len
                fiberList += p to Offset(p.x + dx, p.y + dy)
            }
        }
        hairs = fiberList
    }

    override fun advance(nowNanos: Long) = Unit

    override fun draw(canvas: AndroidCanvas, origin: Offset, nowNanos: Long, paint: Paint, fade: Float) {
        val age = ageMs(nowNanos)
        val p = (age / durationMs).coerceIn(0f, 1f)
        val crack = smooth(0f, 0.18f, p)
        val open = smooth(0.18f, 0.30f, p)
        val fall = smooth(0.30f, 1f, p)
        val alpha = fade * (1f - smooth(0.55f, 1f, p) * 0.98f)
        if (alpha <= 0.01f) return

        val ox = x0(origin)
        val oy = y0(origin)
        val w = snapshot.width
        val h = snapshot.height
        val gap = w * 0.045f * open
        val spread = gap + w * 0.16f * fall
        val dropY = h * 0.40f * fall * fall
        val curl = 22f * fall

        paint.style = Paint.Style.FILL
        paint.xfermode = null

        // 先画整体底（裂口未开时保持完整），再画两半，避免操作瞬间露出黑底
        if (open < 0.02f) {
            drawBitmapRounded(canvas, ox, oy, 0f, 0f, 0f, 1f, alpha, paint)
        }

        paint.alpha = (alpha * 255f).toInt()
        drawHalf(canvas, ox + normal.x * spread * 0.5f, oy + dropY + normal.y * spread * 0.2f,
            pathA, centroidA, curl, -6f * fall, paint)
        drawHalf(canvas, ox - normal.x * spread * 0.5f, oy + dropY - normal.y * spread * 0.2f,
            pathB, centroidB, -curl, 6f * fall, paint)

        // 拉链式裂口：白线从一侧边缘沿锯齿推进
        val shown = (crack * (points.size - 1)).toInt()
        if (shown >= 1) {
            val line = AndroidPath()
            line.moveTo(points[0].x, points[0].y)
            for (i in 1..shown) line.lineTo(points[i].x, points[i].y)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * unit
                color = AndroidColor.WHITE
                this.alpha = (alpha * (1f - fall) * 235f).toInt()
            }
            canvas.save()
            canvas.translate(ox, oy)
            canvas.drawPath(line, linePaint)
            canvas.restore()
        }

        // 两侧边缘的毛糙纸纤维
        val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * unit
            color = 0xFFFFFBF2.toInt()
            this.alpha = (alpha * (1f - fall) * 220f).toInt()
        }
        canvas.save()
        canvas.translate(ox, oy)
        for ((from, to) in hairs) {
            val offsetX = normal.x * gap * 0.5f
            val offsetY = normal.y * gap * 0.5f
            canvas.drawLine(from.x + offsetX, from.y + offsetY, to.x + offsetX, to.y + offsetY, fiberPaint)
            canvas.drawLine(from.x - offsetX, from.y - offsetY, to.x - offsetX, to.y - offsetY, fiberPaint)
        }
        canvas.restore()
    }

    private fun drawHalf(
        canvas: AndroidCanvas,
        x: Float,
        y: Float,
        path: AndroidPath,
        pivot: Offset,
        rotY: Float,
        rotX: Float,
        paint: Paint
    ) {
        if (path.isEmpty) return
        canvas.save()
        canvas.translate(x, y)
        if (rotY != 0f || rotX != 0f) {
            camera.save()
            camera.rotateY(rotY)
            camera.rotateX(rotX)
            camera.getMatrix(matrix)
            matrix.preTranslate(-pivot.x, -pivot.y)
            matrix.postTranslate(pivot.x, pivot.y)
            canvas.concat(matrix)
            camera.restore()
        }
        canvas.clipPath(path)
        canvas.drawBitmap(snapshot.bitmap, 0f, 0f, paint)
        canvas.restore()
    }

    private fun centroidOf(path: AndroidPath): Offset {
        if (path.isEmpty) return Offset(snapshot.width / 2f, snapshot.height / 2f)
        val rect = RectF()
        path.computeBounds(rect, true)
        return Offset(rect.centerX(), rect.centerY())
    }
}

// ---------------------------------------------------------------------------
// 2. 不规则 Voronoi 碎裂
// ---------------------------------------------------------------------------

private class VoronoiEffect(
    snapshot: CardSnapshot,
    startNanos: Long,
    seed: Long
) : BaseEffect(snapshot, startNanos) {

    override val durationMs: Long = 1100L

    private class Cell(
        val path: AndroidPath,
        val cx: Float,
        val cy: Float
    ) {
        var tx = 0f
        var ty = 0f
        var rotation = 0f
        var scale = 1f
        var alpha = 1f
        var vx = 0f
        var vy = 0f
        var spin = 0f
        var delayMs = 0f
        var bounced = false
    }

    private val cells: List<Cell>
    private val random = Random(seed)
    private val maxDim = max(snapshot.width, snapshot.height)
    private val gravity = maxDim * 2.4f
    private var lastStepNanos = startNanos

    init {
        val w = snapshot.width
        val h = snapshot.height
        val cx = w / 2f
        val cy = h / 2f
        val count = 32 + random.nextInt(7) // 32..38
        val seeds = ArrayList<Offset>(count)
        var guard = 0
        while (seeds.size < count && guard++ < 500) {
            // u 均匀 -> 半径均匀 -> 越靠中心种子越密；外圈自然形成大块
            val u = random.nextFloat()
            val theta = random.nextFloat() * 2f * PI.toFloat()
            val px = cx + cos(theta) * u * 0.54f * w
            val py = cy + sin(theta) * u * 0.54f * h
            if (px < 2f || px > w - 2f || py < 2f || py > h - 2f) continue
            seeds += Offset(px, py)
        }

        val baseRect = RectF(0f, 0f, w, h)
        val result = ArrayList<Cell>(seeds.size)
        for (index in seeds.indices) {
            val mine = seeds[index]
            var polygon = AndroidPath().apply {
                addRect(baseRect, AndroidPath.Direction.CW)
            }
            val neighbours = seeds.indices
                .filter { it != index }
                .sortedBy {
                    val dx = seeds[it].x - mine.x
                    val dy = seeds[it].y - mine.y
                    dx * dx + dy * dy
                }
                .take(10)
            for (otherIndex in neighbours) {
                val other = seeds[otherIndex]
                val dx = other.x - mine.x
                val dy = other.y - mine.y
                val len = hypot(dx, dy)
                if (len < 1e-3f) continue
                val ux = dx / len
                val uy = dy / len
                val mx = (mine.x + other.x) / 2f
                val my = (mine.y + other.y) / 2f
                val big = (w + h) * 2f
                val halfPlane = AndroidPath().apply {
                    moveTo(mx - uy * big, my + ux * big)
                    lineTo(mx + uy * big, my - ux * big)
                    lineTo(mx + uy * big - ux * big, my - ux * big - uy * big)
                    lineTo(mx - uy * big - ux * big, my + ux * big - uy * big)
                    close()
                }
                polygon.op(halfPlane, AndroidPath.Op.INTERSECT)
            }
            if (polygon.isEmpty) continue
            val rect = RectF()
            polygon.computeBounds(rect, true)
            if (rect.width() < 2f || rect.height() < 2f) continue
            val cell = Cell(polygon, rect.centerX(), rect.centerY())
            // 放射初速度：中心密 -> 中心块更快；边缘大块更慢更重
            val dirX = cell.cx - cx
            val dirY = cell.cy - cy
            val dist = hypot(dirX, dirY).coerceAtLeast(1f)
            val jitter = (random.nextFloat() - 0.5f) * 0.5f
            val speed = maxDim * (0.75f + random.nextFloat() * 0.85f)
            cell.vx = (dirX / dist + jitter) * speed
            cell.vy = (dirY / dist + jitter * 0.6f) * speed - maxDim * 0.12f
            cell.spin = (random.nextFloat() * 2f - 1f) * (140f + random.nextFloat() * 300f)
            cell.delayMs = (dist / maxDim) * 42f
            result += cell
        }
        cells = result
        Log.d(TAG, "voronoi cells=${cells.size} seed=$seed")
    }

    override fun advance(nowNanos: Long) {
        val dt = ((nowNanos - lastStepNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastStepNanos = nowNanos
        if (dt <= 0f) return
        val age = ageMs(nowNanos)
        val floorY = snapshot.height * 1.02f
        for (cell in cells) {
            if (age < cell.delayMs) continue
            // 空气阻尼 + 重力
            val damp = exp(-1.6f * dt)
            cell.vx *= damp
            cell.vy = cell.vy * damp + gravity * dt
            cell.tx += cell.vx * dt
            cell.ty += cell.vy * dt
            cell.rotation += cell.spin * dt
            if (!cell.bounced && cell.ty + cell.cy > floorY) {
                // 屏幕底部一次真实物理反弹，垂直速度衰减 42%
                cell.ty = floorY - cell.cy
                cell.vy *= -0.42f
                cell.vx *= 0.72f
                cell.spin *= 0.78f
                cell.bounced = true
            }
            val p = (age / durationMs).coerceIn(0f, 1f)
            cell.alpha = 1f - smooth(0.72f, 1f, p)
            cell.scale = 1f - 0.24f * smooth(0.68f, 1f, p)
        }
    }

    override fun draw(canvas: AndroidCanvas, origin: Offset, nowNanos: Long, paint: Paint, fade: Float) {
        val age = ageMs(nowNanos)
        val ox = x0(origin)
        val oy = y0(origin)
        val w = snapshot.width
        val h = snapshot.height

        // 60ms 受击白闪
        if (age < 60f) {
            val flashAlpha = (1f - age / 60f).coerceIn(0f, 1f) * 0.85f * fade
            paint.style = Paint.Style.FILL
            paint.xfermode = null
            paint.color = AndroidColor.WHITE
            paint.alpha = (flashAlpha * 255f).toInt()
            val clip = AndroidPath().apply {
                addRoundRect(RectF(0f, 0f, w, h), snapshot.cornerRadius, snapshot.cornerRadius, AndroidPath.Direction.CW)
            }
            canvas.save()
            canvas.translate(ox, oy)
            canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, w, h, paint)
            canvas.restore()
        }

        val thicknessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF241C18.toInt()
        }
        canvas.save()
        canvas.translate(ox, oy)
        for (cell in cells) {
            if (age < cell.delayMs) continue
            val a = (cell.alpha * fade).coerceIn(0f, 1f)
            if (a <= 0.01f) continue
            canvas.save()
            canvas.translate(cell.cx + cell.tx, cell.cy + cell.ty)
            if (cell.rotation != 0f) canvas.rotate(cell.rotation)
            if (cell.scale != 1f) canvas.scale(cell.scale, cell.scale)
            canvas.translate(-cell.cx, -cell.cy)

            // 2dp 暗色立体截面厚度：沿运动方向的偏移
            thicknessPaint.alpha = (a * 210f).toInt()
            canvas.save()
            canvas.translate(2.2f * unit, 2.6f * unit)
            canvas.drawPath(cell.path, thicknessPaint)
            canvas.restore()

            canvas.save()
            canvas.clipPath(cell.path)
            paint.style = Paint.Style.FILL
            paint.xfermode = null
            paint.alpha = (a * 255f).toInt()
            canvas.drawBitmap(snapshot.bitmap, 0f, 0f, paint)
            canvas.restore()
            canvas.restore()
        }
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------
// 3. 纸张焚烧（多八度 fBm 火线 + 网格卷边 + 火星灰屑）
// ---------------------------------------------------------------------------

private class BurnEffect(
    snapshot: CardSnapshot,
    startNanos: Long,
    seed: Long
) : BaseEffect(snapshot, startNanos) {

    override val durationMs: Long = 1400L

    private class Particle(
        val x: Float,
        val y: Float,
        val birth: Float,
        val vx: Float,
        val vy: Float,
        val life: Float,
        val size: Float,
        val spark: Boolean,
        val phase: Float
    )

    private val random = Random(seed)
    private val noiseSeed = seed.toInt() xor 0x5F3759DF
    private val w = snapshot.width
    private val h = snapshot.height
    private val originX = w * (0.26f + random.nextFloat() * 0.48f)
    private val originY = h * 1.02f
    private val maxDist: Float
    private val maskW = 64
    private val maskH: Int

    private val charBmp: Bitmap
    private val hotBmp: Bitmap
    private val innerBmp: Bitmap
    private val cardBmp: Bitmap
    private val charPixels: IntArray
    private val hotPixels: IntArray
    private val innerPixels: IntArray
    private val cardPixels: IntArray
    private val maskRect = RectF()
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val meshX = 10
    private val meshY = 16
    private val verts = FloatArray((meshX + 1) * (meshY + 1) * 2)
    private val baseVerts = FloatArray((meshX + 1) * (meshY + 1) * 2)

    private val ashSamples = ArrayList<FloatArray>(4096)
    private val particles = ArrayList<Particle>(56)

    init {
        maxDist = maxOf(
            hypot(originX, originY),
            hypot(w - originX, originY),
            hypot(originX, h - originY),
            hypot(w - originX, h - originY)
        ).coerceAtLeast(1f)
        maskH = (maskW * h / w).toInt().coerceIn(24, 110)
        charPixels = IntArray(maskW * maskH)
        hotPixels = IntArray(maskW * maskH)
        innerPixels = IntArray(maskW * maskH)
        cardPixels = IntArray(maskW * maskH)
        charBmp = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        hotBmp = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        innerBmp = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        cardBmp = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        maskRect.set(0f, 0f, w, h)

        for (iy in 0..meshY) {
            for (ix in 0..meshX) {
                val index = (iy * (meshX + 1) + ix) * 2
                baseVerts[index] = w * ix / meshX
                baseVerts[index + 1] = h * iy / meshY
            }
        }

        // 灰烬细线采样点（field 只依赖位置，预计算）
        val cols = 46
        val rows = 70
        for (iy in 0 until rows) {
            for (ix in 0 until cols) {
                val px = (ix + 0.5f) / cols * w
                val py = (iy + 0.5f) / rows * h
                ashSamples += floatArrayOf(px, py, fieldAt(px, py))
            }
        }

        // 火星 / 灰屑：按出生点 field 决定何时出现
        var guard = 0
        while (particles.size < 44 && guard++ < 600) {
            val px = random.nextFloat() * w
            val py = random.nextFloat() * h
            val f = fieldAt(px, py)
            if (f > 0.95f) continue
            val spark = particles.size < 28
            val speed = if (spark) -h * (0.10f + random.nextFloat() * 0.22f)
            else h * (0.02f + random.nextFloat() * 0.06f)
            particles += Particle(
                x = px,
                y = py,
                birth = f,
                vx = (random.nextFloat() * 2f - 1f) * w * 0.06f,
                vy = speed,
                life = if (spark) 420f + random.nextFloat() * 320f else 700f + random.nextFloat() * 500f,
                size = if (spark) (1.1f + random.nextFloat() * 1.4f) * unit else (1.6f + random.nextFloat() * 1.8f) * unit,
                spark = spark,
                phase = random.nextFloat() * 6.28f
            )
        }
    }

    /** 多八度 fBm 噪声火线场：0（火源）→ 1.4（未燃）。 */
    private fun fieldAt(px: Float, py: Float): Float {
        val dx = (px - originX) / maxDist
        val dy = (py - originY) / maxDist
        val dist = sqrt(dx * dx + dy * dy)
        val noise = fbm(px / (w * 0.30f), py / (h * 0.30f), noiseSeed)
        return dist * 0.92f + (noise - 0.5f) * 0.44f
    }

    override fun advance(nowNanos: Long) = Unit

    override fun draw(canvas: AndroidCanvas, origin: Offset, nowNanos: Long, paint: Paint, fade: Float) {
        val age = ageMs(nowNanos)
        val p = (age / durationMs).coerceIn(0f, 1f)
        val front = 0.03f + 1.32f * p
        val globalFade = fade * (1f - smooth(0.84f, 1f, p) * 0.96f)
        if (globalFade <= 0.01f) return

        val ox = x0(origin)
        val oy = y0(origin)

        updateMasks(front)
        charBmp.setPixels(charPixels, 0, maskW, 0, 0, maskW, maskH)
        hotBmp.setPixels(hotPixels, 0, maskW, 0, 0, maskW, maskH)
        innerBmp.setPixels(innerPixels, 0, maskW, 0, 0, maskW, maskH)
        cardBmp.setPixels(cardPixels, 0, maskW, 0, 0, maskW, maskH)

        // 1) 焦黑碳化层（暗色下提亮到 #3A322E，保证可见）
        paint.style = Paint.Style.FILL
        paint.xfermode = null
        paint.color = AndroidColor.WHITE
        paint.alpha = (globalFade * 255f).toInt()
        canvas.save()
        canvas.translate(ox, oy)
        canvas.drawBitmap(charBmp, null, maskRect, paint)
        // 卷边阴影：偏 1.5dp 再叠一层，制造碳化边缘厚度
        paint.alpha = (globalFade * 120f).toInt()
        canvas.save()
        canvas.translate(-1.5f * unit, -1.5f * unit)
        canvas.drawBitmap(charBmp, null, maskRect, paint)
        canvas.restore()
        canvas.restore()

        // 2) 未燃卡面：网格顶点受热微翘 + DST_IN 火线遮罩
        paint.alpha = (globalFade * 255f).toInt()
        val layer = canvas.saveLayer(ox, oy, ox + w, oy + h, null)
        canvas.save()
        canvas.translate(ox, oy)
        updateMesh(front, age)
        canvas.drawBitmapMesh(snapshot.bitmap, meshX, meshY, verts, 0, null, 0, paint)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        maskPaint.alpha = 255
        canvas.drawBitmap(cardBmp, null, maskRect, maskPaint)
        canvas.restore()
        canvas.restoreToCount(layer)

        // 3) 高温带：金橙外焰 + 亮黄内焰
        paint.alpha = (globalFade * 255f).toInt()
        canvas.save()
        canvas.translate(ox, oy)
        canvas.drawBitmap(hotBmp, null, maskRect, paint)
        canvas.drawBitmap(innerBmp, null, maskRect, paint)
        canvas.restore()

        // 4) 0.85 半透明银白灰烬细线
        val ashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * unit
            color = 0xFFE9E6E0.toInt()
            alpha = (145f * globalFade).toInt()
        }
        canvas.save()
        canvas.translate(ox, oy)
        for (sample in ashSamples) {
            val delta = abs(sample[2] - front)
            if (delta > 0.010f) continue
            val len = (3.5f + ((sample[0].toInt() * 31 + sample[1].toInt() * 17) % 7)) * unit
            canvas.drawLine(sample[0], sample[1], sample[0] + len * 0.6f, sample[1] - len, ashPaint)
        }
        canvas.restore()

        // 5) 火星 / 灰屑
        val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * unit
        }
        val ashPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.save()
        canvas.translate(ox, oy)
        for (particle in particles) {
            val born = particle.birth / 1.32f * durationMs
            val t = age - born
            if (t < 0f || t > particle.life) continue
            val seconds = t / 1000f
            val px = particle.x + particle.vx * seconds + sin(t * 0.012f + particle.phase) * 5f * unit
            val py = particle.y + particle.vy * seconds +
                if (particle.spark) 0.5f * h * 0.10f * seconds * seconds
                else 0.5f * h * 0.03f * seconds * seconds
            val a = (1f - t / particle.life).coerceIn(0f, 1f) * globalFade
            if (particle.spark) {
                val heat = (1f - t / particle.life).coerceIn(0f, 1f)
                sparkPaint.color = blendColor(0xFFFFE45C.toInt(), 0xFFFF6A18.toInt(), heat)
                sparkPaint.alpha = (a * 235f).toInt()
                canvas.drawLine(px, py, px - particle.vx * 0.02f, py + 4.5f * unit, sparkPaint)
            } else {
                ashPaint2.color = 0xFFB9B4AC.toInt()
                ashPaint2.alpha = (a * 120f).toInt()
                canvas.drawCircle(px, py, particle.size, ashPaint2)
            }
        }
        canvas.restore()
    }

    private fun updateMasks(front: Float) {
        var index = 0
        for (iy in 0 until maskH) {
            val py = (iy + 0.5f) / maskH * h
            for (ix in 0 until maskW) {
                val px = (ix + 0.5f) / maskW * w
                val f = fieldAt(px, py)
                val delta = f - front
                if (delta < 0f) {
                    charPixels[index] = 0xF03A322E.toInt()
                    cardPixels[index] = 0
                } else {
                    charPixels[index] = 0
                    cardPixels[index] = 0xFF000000.toInt()
                }
                hotPixels[index] = when {
                    delta < 0f -> 0
                    delta < 0.012f -> 0xE0FFE45C.toInt()
                    delta < 0.030f -> 0xB0FF9A2E.toInt()
                    delta < 0.060f -> 0x78FF6A18.toInt()
                    else -> 0
                }
                innerPixels[index] = when {
                    delta < 0f -> 0
                    delta < 0.008f -> 0xF2FFFFD0.toInt()
                    delta < 0.018f -> 0xB0FFD24A.toInt()
                    else -> 0
                }
                index++
            }
        }
    }

    private fun updateMesh(front: Float, age: Float) {
        var index = 0
        val phase = age * 0.02f
        for (iy in 0..meshY) {
            for (ix in 0..meshX) {
                val bx = baseVerts[index]
                val by = baseVerts[index + 1]
                val f = fieldAt(bx, by)
                val heat = (1f - abs(f - front) / 0.085f).coerceIn(0f, 1f)
                val wobble = sin(f * 26f + phase) * heat * 3.2f * unit
                val dirX = bx - originX
                val dirY = by - originY
                val len = hypot(dirX, dirY).coerceAtLeast(1f)
                verts[index] = bx + dirX / len * wobble
                verts[index + 1] = by + dirY / len * wobble - heat * 2.6f * unit
                index += 2
            }
        }
    }

    private fun blendColor(from: Int, to: Int, t: Float): Int {
        val r = (AndroidColor.red(from) + (AndroidColor.red(to) - AndroidColor.red(from)) * t).toInt()
        val g = (AndroidColor.green(from) + (AndroidColor.green(to) - AndroidColor.green(from)) * t).toInt()
        val b = (AndroidColor.blue(from) + (AndroidColor.blue(to) - AndroidColor.blue(from)) * t).toInt()
        return AndroidColor.rgb(r, g, b)
    }
}

// ---------------------------------------------------------------------------
// 4. 桌面交替甩飞 / 淡出
// ---------------------------------------------------------------------------

private class FlingEffect(
    snapshot: CardSnapshot,
    startNanos: Long,
    private val style: CardExitStyle,
    private val side: Float
) : BaseEffect(snapshot, startNanos) {

    override val durationMs: Long = if (style == CardExitStyle.FLING_GOOD) 480L else 430L

    override fun advance(nowNanos: Long) = Unit

    override fun draw(canvas: AndroidCanvas, origin: Offset, nowNanos: Long, paint: Paint, fade: Float) {
        val age = ageMs(nowNanos)
        val p = (age / durationMs).coerceIn(0f, 1f)
        val w = snapshot.width
        val h = snapshot.height
        val ox = x0(origin)
        val oy = y0(origin)
        paint.style = Paint.Style.FILL
        paint.xfermode = null

        if (style == CardExitStyle.FLING_GOOD) {
            // 认识：沿抛物线向侧上方甩飞，平面旋转 ±15°
            val ease = 1f - (1f - p) * (1f - p)
            val dx = side * w * 1.25f * ease
            val dy = -h * 0.62f * ease - h * 0.06f * p
            val rotation = side * 15f * ease
            val scale = 1f - 0.06f * ease
            val alpha = fade * (1f - smooth(0.55f, 1f, p))
            drawBitmapRounded(canvas, ox, oy, dx, dy, rotation, scale, alpha, paint)
        } else {
            // 模糊：向右下微弧缩小滑开，像插回牌堆底部
            val ease = p * p
            val dx = w * 0.58f * ease
            val dy = h * 0.50f * ease + h * 0.05f * sin(PI * p).toFloat()
            val rotation = 9f * ease
            val scale = 1f - 0.44f * ease
            val alpha = fade * (1f - smooth(0.62f, 1f, p))
            drawBitmapRounded(canvas, ox, oy, dx, dy, rotation, scale, alpha, paint)
        }
    }
}

private class FadeEffect(
    snapshot: CardSnapshot,
    startNanos: Long
) : BaseEffect(snapshot, startNanos) {

    override val durationMs: Long = 180L

    override fun advance(nowNanos: Long) = Unit

    override fun draw(canvas: AndroidCanvas, origin: Offset, nowNanos: Long, paint: Paint, fade: Float) {
        val p = (ageMs(nowNanos) / durationMs).coerceIn(0f, 1f)
        val alpha = fade * (1f - p) * 0.98f
        paint.style = Paint.Style.FILL
        paint.xfermode = null
        drawBitmapRounded(
            canvas = canvas,
            ox = x0(origin),
            oy = y0(origin),
            dx = 0f,
            dy = 0f,
            rotation = 0f,
            scale = 1f - 0.02f * p,
            alpha = alpha,
            paint = paint
        )
    }
}

// ---------------------------------------------------------------------------
// 数学 / 噪声工具
// ---------------------------------------------------------------------------

private fun smooth(from: Float, to: Float, x: Float): Float {
    if (to <= from) return if (x >= to) 1f else 0f
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutQuart(x: Float): Float {
    val t = 1f - x.coerceIn(0f, 1f)
    return 1f - t * t * t * t
}

/** 入场 rotZ：14° -> 0，再 -1.2° 过冲回弹。 */
private fun entranceRotation(p: Float): Float = when {
    p < 0.72f -> 14f * (1f - easeOutQuart(p / 0.72f))
    p < 0.90f -> -1.2f * ((p - 0.72f) / 0.18f)
    else -> -1.2f * (1f - (p - 0.90f) / 0.10f)
}

private fun hashNoise(ix: Int, iy: Int, seed: Int): Float {
    var h = ix * 0x27d4eb2d
    h = h xor (iy * 0x165667b1)
    h = h xor seed
    h = (h xor (h ushr 15)) * 0x85ebca6b.toInt()
    h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

private fun valueNoise(x: Float, y: Float, seed: Int): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val ux = fx * fx * (3f - 2f * fx)
    val uy = fy * fy * (3f - 2f * fy)
    val a = hashNoise(ix, iy, seed)
    val b = hashNoise(ix + 1, iy, seed)
    val c = hashNoise(ix, iy + 1, seed)
    val d = hashNoise(ix + 1, iy + 1, seed)
    val top = a + (b - a) * ux
    val bottom = c + (d - c) * ux
    return top + (bottom - top) * uy
}

/** 多八度 fBm（3 个八度，振幅 0.55 / 0.275 / 0.1375）。 */
private fun fbm(x: Float, y: Float, seed: Int): Float {
    var amp = 0.55f
    var freq = 1f
    var sum = 0f
    var norm = 0f
    for (i in 0 until 3) {
        sum += valueNoise(x * freq, y * freq, seed + i * 7919) * amp
        norm += amp
        amp *= 0.5f
        freq *= 2.13f
    }
    return sum / norm
}

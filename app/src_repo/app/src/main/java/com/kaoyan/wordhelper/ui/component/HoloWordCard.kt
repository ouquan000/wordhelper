package com.kaoyan.wordhelper.ui.component

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class HoloFinish { PRISM, RAINBOW, PEARL, SPARKLE, CHROME, ETCHED }
enum class HoloFeel { QUIET, STANDARD }
enum class HoloCardKind { WORD, CHECK_IN }

@Composable
fun HoloWordCard(
    finish: HoloFinish,
    feel: HoloFeel = HoloFeel.STANDARD,
    cardKind: HoloCardKind = HoloCardKind.WORD,
    modifier: Modifier = Modifier,
) {
    val state = remember(finish, feel) { HoloCardState(feel) }
    val reducedMotion = rememberReducedMotion()
    val palette = remember(finish) { paletteFor(finish) }
    val glitterDots = remember { makeGlitterDots(950, 0xC0FFEE) }

    val showFront by remember { derivedStateOf { state.flip.floatValue < 90f } }
    val showBack by remember { derivedStateOf { state.flip.floatValue >= 90f } }
    val edgeVisible by remember {
        derivedStateOf { abs(sin(state.flip.floatValue * (PI.toFloat() / 180f))) > 0.02f }
    }
    val isBack by remember { derivedStateOf { state.flip.floatValue >= 90f } }

    val cardDescription = when {
        cardKind == HoloCardKind.WORD && !isBack -> "单词卡正面，serendipity"
        cardKind == HoloCardKind.WORD -> "单词卡背面，释义与例句"
        cardKind == HoloCardKind.CHECK_IN && !isBack -> "打卡纪念卡正面，今日背词完成"
        else -> "打卡纪念卡背面，连续打卡 7 天"
    }
    val cardStateDescription = if (isBack) "已翻面" else "正面"

    val flipToggle: () -> Unit = {
        state.flipTarget = if (state.flipTarget == 0f) 180f else 0f
        if (state.reducedMotion) state.flip.floatValue = state.flipTarget
    }

    LaunchedEffect(state) {
        state.reducedMotion = reducedMotion
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last < 0L) {
                    last = now
                } else {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    last = now
                    state.step(dt)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(440f / 616f)
            .onSizeChanged { state.cardWidthPx = it.width.toFloat() }
            .semantics {
                role = Role.Button
                contentDescription = cardDescription
                stateDescription = cardStateDescription
                onClick("翻面") {
                    flipToggle()
                    true
                }
            }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Spacebar || event.key == Key.Enter)
                ) {
                    flipToggle()
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // requireUnconsumed = false：父级 verticalScroll 已消费 down 时仍进入本手势
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.pointerActive = true
                    state.pointerX = (down.position.x / size.width).coerceIn(0f, 1f)
                    state.pointerY = (down.position.y / size.height).coerceIn(0f, 1f)
                    var moved = false
                    var axisLocked = false
                    var horizontal = false
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        // 按指针 id 跟踪，不拿第一个无关 change（多指时安全）
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val pos = change.position
                        val dx = pos.x - downPos.x
                        val dy = pos.y - downPos.y
                        if (!axisLocked && max(abs(dx), abs(dy)) > slop) {
                            axisLocked = true
                            moved = true
                            horizontal = abs(dx) > abs(dy)
                        }
                        if (axisLocked && horizontal) {
                            // 水平占优：消费并更新倾斜
                            change.consume()
                            state.pointerX = (pos.x / size.width).coerceIn(0f, 1f)
                            state.pointerY = (pos.y / size.height).coerceIn(0f, 1f)
                        }
                        // 垂直占优：不消费，交给父级滚动
                        if (change.changedToUpIgnoreConsumed()) break
                    }
                    state.pointerActive = false
                    if (!moved) flipToggle()
                }
            }
    ) {
        // 阴影：卡片下方的模糊椭圆，随倾斜滑动、随翻面收窄
        Canvas(Modifier.fillMaxSize()) {
            drawShadow(state, size.width, size.height)
        }

        // 倾斜 + 翻面的整体容器（前/后面都在里面）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationX = state.rotX.floatValue
                    rotationY = state.rotY.floatValue + state.flip.floatValue
                    cameraDistance = 1600f * density
                    // 翻转抬升的透视等效（Compose 1.6.7 无 translationZ）：
                    // 40px translateZ 在 cameraDistance 1600 下 ≈ 2.5% 放大
                    val sinFlip = abs(sin(state.flip.floatValue * (PI.toFloat() / 180f)))
                    scaleX = 1f + 0.025f * sinFlip
                    scaleY = 1f + 0.025f * sinFlip
                    transformOrigin = TransformOrigin.Center
                }
        ) {
            if (showFront) {
                CardFace(
                    finish = finish,
                    kind = cardKind,
                    state = state,
                    turnDeg = 0f,
                    isBack = false,
                    dots = glitterDots,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (showBack) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationY = 180f }
                ) {
                    CardFace(
                        finish = finish,
                        kind = cardKind,
                        state = state,
                        turnDeg = 180f,
                        isBack = true,
                        dots = glitterDots,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 侧边厚度：10 层轮廓，仅在翻转过程中绘制
        if (edgeVisible) {
            Canvas(Modifier.fillMaxSize()) {
                drawEdge(palette, state, size.width, size.height)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 物理状态（每帧由 withFrameNanos 驱动；所有输出用 mutableFloatStateOf，
// 只在 graphicsLayer / Canvas 的绘制 lambda 中读取 → 只重绘、不重组）
// ---------------------------------------------------------------------------
private class HoloCardState(feel: HoloFeel) {
    val tiltDeg = if (feel == HoloFeel.QUIET) 6f else 12f
    val tiltStiffness = if (feel == HoloFeel.QUIET) 120f else 140f
    val tiltDamping = if (feel == HoloFeel.QUIET) 18f else 15f
    val swayAmp = if (feel == HoloFeel.QUIET) 0.5f else 1f
    val hoverCap = if (feel == HoloFeel.QUIET) 0.7f else 1f

    // 输出（绘制阶段读取）
    val rotX = mutableFloatStateOf(0f)
    val rotY = mutableFloatStateOf(0f)
    val lightX = mutableFloatStateOf(0.34f)
    val lightY = mutableFloatStateOf(0.22f)
    val hover = mutableFloatStateOf(0f)
    val flip = mutableFloatStateOf(0f)

    // 输入 / 内部积分（只在帧循环与指针回调中读写）
    var flipTarget = 0f
    var pointerActive = false
    var pointerX = 0f
    var pointerY = 0f
    var releaseElapsed = 1f
    var lastTargetX = 0f
    var lastTargetY = 0f
    var springX = 0f
    var springY = 0f
    var tiltVx = 0f
    var tiltVy = 0f
    var lightVx = 0f
    var lightVy = 0f
    var flipV = 0f
    var time = 0f
    var cardWidthPx = 440f
    var reducedMotion = false

    fun step(dt: Float) {
        time += dt

        // hover：逼近 1（有指针）/ 0（无指针），1 - exp(-7·dt) 每帧
        val hoverTarget = if (pointerActive) 1f else 0f
        val k = 1f - exp(-7f * dt)
        hover.floatValue =
            (hover.floatValue + (hoverTarget - hover.floatValue) * k).coerceIn(0f, hoverCap)

        if (reducedMotion) {
            springX = 0f
            springY = 0f
            tiltVx = 0f
            tiltVy = 0f
            rotX.floatValue = 0f
            rotY.floatValue = 0f
            flip.floatValue = flipTarget
            flipV = 0f
        } else {
            // 倾斜目标
            val nx = pointerX * 2f - 1f
            val ny = pointerY * 2f - 1f
            val rawTx = -ny * tiltDeg
            val rawTy = nx * tiltDeg
            val tx: Float
            val ty: Float
            if (pointerActive) {
                releaseElapsed = 0f
                lastTargetX = rawTx
                lastTargetY = rawTy
                tx = rawTx
                ty = rawTy
            } else {
                releaseElapsed += dt
                tx = if (releaseElapsed < 0.6f) lastTargetX else 0f
                ty = if (releaseElapsed < 0.6f) lastTargetY else 0f
            }
            tiltVx += (tiltStiffness * (tx - springX) - tiltDamping * tiltVx) * dt
            springX += tiltVx * dt
            tiltVy += (tiltStiffness * (ty - springY) - tiltDamping * tiltVy) * dt
            springY += tiltVy * dt

            // 静置微晃（随 hover 淡入淡出；Quiet 半幅）
            val swayScale = swayAmp * (1f - hover.floatValue)
            val swayX =
                (2.2f * sin(0.9f * time + 0.6f) + 0.8f * sin(2.3f * time)) * swayScale
            val swayY =
                (3.2f * sin(0.7f * time) + 0.9f * sin(1.9f * time + 1.2f)) * swayScale
            rotX.floatValue = springX + swayX
            rotY.floatValue = springY + swayY

            // 翻面弹簧（70 / 11）
            flipV += (70f * (flipTarget - flip.floatValue) - 11f * flipV) * dt
            flip.floatValue = (flip.floatValue + flipV * dt).coerceIn(0f, 180f)
        }

        // 光斑弹簧（160 / 2·√160，无过冲）；无指针时回到左上并随 sway 移动
        val ltx: Float
        val lty: Float
        if (pointerActive) {
            ltx = pointerX
            lty = pointerY
        } else {
            ltx = (0.34f + rotY.floatValue * 3f / 100f).coerceIn(0f, 1f)
            lty = (0.22f - rotX.floatValue * 3f / 100f).coerceIn(0f, 1f)
        }
        val lightDamping = 2f * sqrt(160f)
        lightVx += (160f * (ltx - lightX.floatValue) - lightDamping * lightVx) * dt
        lightX.floatValue = (lightX.floatValue + lightVx * dt).coerceIn(0f, 1f)
        lightVy += (160f * (lty - lightY.floatValue) - lightDamping * lightVy) * dt
        lightY.floatValue = (lightY.floatValue + lightVy * dt).coerceIn(0f, 1f)
    }
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val resolver = context.contentResolver
        val anim = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val trans =
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        anim == 0f || trans == 0f
    }
}

// ---------------------------------------------------------------------------
// 材质配色（色值严格照 SPEC 第 3 节）
// ---------------------------------------------------------------------------
private data class HoloPalette(
    val edge: Color,
    val ink: Color,
    val depthGlow: Color,
    val depthRing: Color,
    val depthAccent: Color,
    val rimBase: Color,
    val rimLight: Color,
)

private fun paletteFor(finish: HoloFinish): HoloPalette = when (finish) {
    HoloFinish.PRISM -> HoloPalette(
        edge = c(26, 42, 138),
        ink = c(244, 244, 246),
        depthGlow = c(90, 150, 255),
        depthRing = c(140, 170, 255, 0.28f),
        depthAccent = c(255, 175, 235),
        rimBase = c(255, 255, 255, 0.05f),
        rimLight = c(255, 255, 255, 0.5f),
    )
    HoloFinish.RAINBOW -> HoloPalette(
        edge = c(42, 44, 53),
        ink = c(245, 245, 247),
        depthGlow = c(255, 95, 126),
        depthRing = c(255, 184, 107),
        depthAccent = c(244, 255, 107),
        rimBase = c(255, 255, 255, 0.05f),
        rimLight = c(255, 255, 255, 0.5f),
    )
    HoloFinish.PEARL -> HoloPalette(
        edge = c(230, 226, 242),
        ink = c(23, 21, 46),
        depthGlow = c(201, 184, 255),
        depthRing = c(60, 50, 120, 0.06f),
        depthAccent = c(255, 196, 225),
        rimBase = c(60, 50, 120, 0.06f),
        rimLight = c(255, 255, 255, 0.95f),
    )
    HoloFinish.SPARKLE -> HoloPalette(
        edge = c(26, 32, 72),
        ink = c(238, 240, 255),
        depthGlow = c(139, 243, 255),
        depthRing = c(139, 243, 255, 0.35f),
        depthAccent = c(255, 255, 255),
        rimBase = c(255, 255, 255, 0.05f),
        rimLight = c(255, 255, 255, 0.5f),
    )
    HoloFinish.CHROME -> HoloPalette(
        edge = c(180, 184, 194),
        ink = c(27, 29, 36),
        depthGlow = c(143, 214, 255),
        depthRing = c(255, 255, 255, 0.18f),
        depthAccent = c(255, 143, 199),
        rimBase = c(255, 255, 255, 0.18f),
        rimLight = c(255, 255, 255, 0.95f),
    )
    HoloFinish.ETCHED -> HoloPalette(
        edge = c(27, 27, 30),
        ink = c(237, 237, 238),
        depthGlow = c(255, 255, 255, 0.05f),
        depthRing = c(255, 255, 255, 0.05f),
        depthAccent = c(244, 255, 107),
        rimBase = c(255, 255, 255, 0.05f),
        rimLight = c(255, 255, 255, 0.5f),
    )
}

// ---------------------------------------------------------------------------
// 一张卡面：art(底 + 深度) → foil → print(文字) → glare → rim → seal
// ---------------------------------------------------------------------------
@Composable
private fun CardFace(
    finish: HoloFinish,
    kind: HoloCardKind,
    state: HoloCardState,
    turnDeg: Float,
    isBack: Boolean,
    dots: List<GlitterDot>,
    modifier: Modifier = Modifier,
) {
    val palette = paletteFor(finish)
    Box(modifier = modifier) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val scale = w / 440f
            val corner = max(22f * density, w * 32f / 440f)
            val path = cardPath(w, h, corner)
            val lx = (if (isBack) 1f - state.lightX.floatValue else state.lightX.floatValue) * w
            val ly = state.lightY.floatValue * h
            val on = state.hover.floatValue
            clipPath(path) {
                drawBase(finish, lx, ly, w, h, scale)
                drawDepth(finish, kind, palette, state, turnDeg, lx, ly, w, h, scale)
                drawFoil(finish, lx, ly, on, w, h, scale, corner, dots)
            }
        }
        PrintContent(
            kind = kind,
            isBack = isBack,
            ink = palette.ink,
            modifier = Modifier.matchParentSize()
        )
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val scale = w / 440f
            val corner = max(22f * density, w * 32f / 440f)
            val path = cardPath(w, h, corner)
            val lx = (if (isBack) 1f - state.lightX.floatValue else state.lightX.floatValue) * w
            val ly = state.lightY.floatValue * h
            val on = state.hover.floatValue
            clipPath(path) {
                drawGlare(finish, lx, ly, on, w, h)
                drawRim(palette, lx, ly, on, w, h, corner, scale)
                if (kind == HoloCardKind.CHECK_IN && isBack) {
                    drawSeal(palette, lx, ly, state.rotY.floatValue, w, h, scale)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 基础底（第 3 节 base）
// ---------------------------------------------------------------------------
private fun DrawScope.drawBase(
    finish: HoloFinish,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    scale: Float,
) {
    when (finish) {
        HoloFinish.PRISM -> {
            drawRect(
                Brush.radialGradient(
                    0.0f to c(90, 150, 255, 0.55f),
                    1.0f to c(90, 150, 255, 0f),
                    center = Offset(w * 0.5f, 0f),
                    radius = w * 0.6f
                )
            )
            drawRect(
                angleGradient(
                    170f, w, h,
                    stops = listOf(
                        0.0f to c(13, 35, 168),
                        0.55f to c(8, 18, 102),
                        1.0f to c(22, 15, 92)
                    )
                )
            )
        }
        HoloFinish.RAINBOW -> {
            drawRect(
                Brush.radialGradient(
                    0.0f to c(59, 62, 76),
                    0.62f to c(27, 28, 35),
                    1.0f to c(27, 28, 35),
                    center = Offset(w * 0.25f, 0f),
                    radius = w * 1.3f
                )
            )
        }
        HoloFinish.PEARL -> {
            drawRect(
                angleGradient(
                    160f, w, h,
                    stops = listOf(
                        0.0f to c(253, 252, 255),
                        1.0f to c(241, 238, 248)
                    )
                )
            )
        }
        HoloFinish.SPARKLE -> {
            drawRect(
                Brush.radialGradient(
                    0.0f to c(40, 51, 106),
                    0.65f to c(16, 20, 43),
                    1.0f to c(16, 20, 43),
                    center = Offset(w * 0.75f, h * 0.05f),
                    radius = w * 1.2f
                )
            )
        }
        HoloFinish.CHROME -> {
            drawRect(
                angleGradient(
                    125f, w, h,
                    shift = Offset((0.5f - lx / w) * w, (0.5f - ly / h) * h),
                    stops = listOf(
                        0.0f to c(228, 230, 234),
                        0.16f to c(141, 147, 158),
                        0.30f to c(246, 247, 249),
                        0.46f to c(124, 130, 141),
                        0.60f to c(217, 220, 225),
                        0.76f to c(148, 154, 165),
                        1.0f to c(241, 242, 244)
                    )
                )
            )
        }
        HoloFinish.ETCHED -> {
            drawRect(c(18, 18, 20))
            drawEtchedRings(w, h, scale)
            // 中央哑光面板，让同心环在此截断
            drawRoundRect(
                brush = Brush.radialGradient(
                    0.0f to c(29, 29, 33),
                    1.0f to c(15, 15, 17),
                    center = Offset(w * 0.5f, h * 0.35f),
                    radius = w * 0.7f
                ),
                topLeft = Offset(w * 0.20f, h * 0.25f),
                size = Size(w * 0.60f, h * 0.40f),
                cornerRadius = CornerRadius(w * 0.06f)
            )
        }
    }
}

private fun DrawScope.drawEtchedRings(w: Float, h: Float, scale: Float) {
    val center = Offset(w / 2f, h / 2f)
    val maxR = sqrt(w * w + h * h) / 2f
    val step = 7f * scale
    var r = 1.2f * scale
    while (r < maxR) {
        drawCircle(
            color = c(255, 255, 255, 0.05f),
            radius = r,
            center = center,
            style = Stroke(1.2f * scale)
        )
        r += step
    }
}

// ---------------------------------------------------------------------------
// 三层内部视差深度（第 5 节 / E 映射）
// ---------------------------------------------------------------------------
private fun DrawScope.drawDepth(
    finish: HoloFinish,
    kind: HoloCardKind,
    palette: HoloPalette,
    state: HoloCardState,
    turnDeg: Float,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    scale: Float,
) {
    val tiltX = state.rotX.floatValue * (PI.toFloat() / 180f)
    val yaw = (state.rotY.floatValue + turnDeg) * (PI.toFloat() / 180f)
    fun off(d: Float): Offset = Offset(-d * scale * sin(yaw), d * scale * sin(tiltX))
    val center = Offset(w / 2f, h / 2f)

    // d = 90：最背层，比窗口大 100px，保证边缘永不露出
    val d90 = off(90f)
    val backCenter = Offset(center.x + d90.x, center.y + d90.y)
    drawDepthBackmost(finish, palette, backCenter, lx, ly, w, h, scale)

    // d = 50：环 / 印章环
    val d50 = off(50f)
    drawCircle(
        color = palette.depthRing,
        radius = w * 0.30f,
        center = Offset(center.x + d50.x, center.y + d50.y),
        style = Stroke(2f * scale)
    )

    // d = 20：强调图形 / 日期徽章
    val d20 = off(20f)
    if (kind == HoloCardKind.WORD) {
        // 设备反馈修正：强调图形基点移到卡片左下装饰区，避开中央单词/音标
        val accentBase = Offset(w * 0.22f, h * 0.74f)
        val accentCenter = Offset(accentBase.x + d20.x, accentBase.y + d20.y)
        val accentColor = palette.depthAccent.copy(alpha = palette.depthAccent.alpha * 0.8f)
        val l = w * 0.09f
        drawLine(
            accentColor,
            Offset(accentCenter.x - l, accentCenter.y),
            Offset(accentCenter.x + l, accentCenter.y),
            strokeWidth = 2f * scale
        )
        drawLine(
            accentColor,
            Offset(accentCenter.x, accentCenter.y - l),
            Offset(accentCenter.x, accentCenter.y + l),
            strokeWidth = 2f * scale
        )
        drawCircle(
            color = accentColor,
            radius = w * 0.05f,
            center = accentCenter,
            style = Stroke(1.5f * scale)
        )
    } else {
        val accentCenter = Offset(center.x + d20.x, center.y + d20.y)
        drawRoundRect(
            color = palette.depthAccent,
            topLeft = Offset(accentCenter.x - w * 0.17f, accentCenter.y - h * 0.05f),
            size = Size(w * 0.34f, h * 0.10f),
            cornerRadius = CornerRadius(h * 0.05f),
            style = Stroke(1.5f * scale)
        )
    }
}

private fun DrawScope.drawDepthBackmost(
    finish: HoloFinish,
    palette: HoloPalette,
    center: Offset,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    scale: Float,
) {
    val radius = max(w, h) * 0.9f + 100f * scale
    if (finish == HoloFinish.PRISM) {
        // Prism 的 art window：多层光晕 + 底层线性渐变（SPEC 3 Prism art window）
        drawRect(
            angleGradient(
                170f, w, h,
                stops = listOf(
                    0.0f to c(59, 98, 230),
                    0.55f to c(42, 63, 191),
                    1.0f to c(58, 47, 168)
                )
            )
        )
        drawRect(
            Brush.radialGradient(
                0.0f to c(255, 175, 235, 0.55f),
                0.18f to c(255, 175, 235, 0.4f),
                0.38f to c(255, 175, 235, 0.22f),
                0.60f to c(255, 175, 235, 0.09f),
                0.82f to c(255, 175, 235, 0.02f),
                1.0f to c(255, 175, 235, 0f),
                center = Offset(lx, ly),
                radius = radius * 0.5f
            )
        )
        drawRect(
            Brush.radialGradient(
                0.0f to c(125, 225, 255, 0.45f),
                0.18f to c(125, 225, 255, 0.32f),
                0.38f to c(125, 225, 255, 0.17f),
                0.60f to c(125, 225, 255, 0.07f),
                0.82f to c(125, 225, 255, 0.015f),
                1.0f to c(125, 225, 255, 0f),
                center = Offset(w - lx, h * 0.74f),
                radius = radius * 0.56f
            )
        )
        drawRect(
            Brush.radialGradient(
                0.0f to c(150, 255, 210, 0.3f),
                0.75f to c(150, 255, 210, 0f),
                1.0f to c(150, 255, 210, 0f),
                center = Offset(w * 0.3f, h),
                radius = radius * 0.75f
            )
        )
    } else {
        drawRect(
            Brush.radialGradient(
                0.0f to palette.depthGlow.copy(alpha = palette.depthGlow.alpha * 0.5f),
                1.0f to palette.depthGlow.copy(alpha = 0f),
                center = center,
                radius = radius
            )
        )
    }
}

// ---------------------------------------------------------------------------
// 箔层（第 3 节 foil + blend mode）
// ---------------------------------------------------------------------------
private fun DrawScope.drawFoil(
    finish: HoloFinish,
    lx: Float,
    ly: Float,
    on: Float,
    w: Float,
    h: Float,
    scale: Float,
    corner: Float,
    dots: List<GlitterDot>,
) {
    when (finish) {
        HoloFinish.PRISM -> {
            val op = 0.15f + on * 0.45f
            drawRoundRect(
                brush = Brush.radialGradient(
                    0.0f to c(170, 220, 255, 0.9f * op),
                    0.6f to c(170, 220, 255, 0f),
                    center = Offset(lx, ly),
                    radius = 0.6f * sqrt(w * w + h * h)
                ),
                topLeft = Offset(scale, scale),
                size = Size(w - 2f * scale, h - 2f * scale),
                cornerRadius = CornerRadius(corner),
                style = Stroke(2f * scale)
            )
        }
        HoloFinish.RAINBOW -> {
            val op = 0.3f + on * 0.6f
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w) * 0.5f, (ly - 0.5f * h) * 0.5f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0.0f to c(255, 95, 126, op),
                    0.144f to c(255, 184, 107, op),
                    0.288f to c(244, 255, 107, op),
                    0.433f to c(107, 255, 181, op),
                    0.577f to c(95, 210, 255, op),
                    0.722f to c(139, 123, 255, op),
                    0.866f to c(255, 107, 214, op),
                    1.0f to c(255, 95, 126, op)
                )
            )
            val mask = Brush.radialGradient(
                0.0f to Color.Black,
                0.72f to Color.Black.copy(alpha = 0.16f),
                1.0f to Color.Black.copy(alpha = 0.16f),
                center = Offset(lx, ly),
                radius = 0.72f * sqrt(w * w + h * h)
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { blendMode = BlendMode.ColorDodge }
                canvas.saveLayer(Rect(0f, 0f, w, h), paint)
                drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
                // 细栅纹
                val grating = Paint().apply {
                    color = Color.Black.copy(alpha = 0.3f)
                    strokeWidth = 1f * scale
                }
                var i = -8
                while (i <= 8) {
                    val x0 = (i * 3f * scale) - w
                    canvas.drawLine(Offset(x0, 0f), Offset(x0 + w + h, h), grating)
                    i++
                }
                drawRect(brush = mask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
        HoloFinish.PEARL -> {
            val op = 0.7f + on * 0.3f
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w) * 0.6f, (ly - 0.5f * h) * 0.6f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0.0f to c(255, 196, 225, op),
                    0.2f to c(201, 184, 255, op),
                    0.4f to c(180, 233, 255, op),
                    0.6f to c(194, 255, 216, op),
                    0.8f to c(255, 243, 184, op),
                    1.0f to c(255, 196, 225, op)
                )
            )
            val mask = Brush.radialGradient(
                0.3f to Color.Black.copy(alpha = 0.35f),
                1.0f to Color.Black,
                center = Offset(w * 0.5f, h * 0.45f),
                radius = 0.7f * max(w, h)
            )
            maskedFoil(BlendMode.Multiply, foil, mask, w, h)
        }
        HoloFinish.SPARKLE -> {
            val foil1 = angleGradient(
                60f, w, h,
                shift = Offset((lx * 1.6f - 0.5f * w) * 0.5f, (ly - 0.5f * h) * 0.5f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0.000f to c(255, 139, 210),
                    0.107f to c(255, 139, 210),
                    0.179f to Color.Transparent,
                    0.286f to Color.Transparent,
                    0.357f to c(139, 243, 255),
                    0.464f to c(139, 243, 255),
                    0.536f to Color.Transparent,
                    0.643f to Color.Transparent,
                    0.714f to c(255, 243, 139),
                    0.821f to c(255, 243, 139),
                    0.893f to Color.Transparent,
                    1.000f to Color.Transparent
                )
            )
            val foil2 = angleGradient(
                -35f, w, h,
                shift = Offset(((1f - lx / w) - 0.5f) * w * 0.5f, (ly * 1.4f / h - 0.5f) * h * 0.5f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0.000f to c(169, 139, 255),
                    0.100f to c(169, 139, 255),
                    0.167f to Color.Transparent,
                    0.300f to Color.Transparent,
                    0.367f to c(139, 255, 196),
                    0.467f to c(139, 255, 196),
                    0.533f to Color.Transparent,
                    0.667f to Color.Transparent,
                    0.733f to c(255, 255, 255),
                    0.800f to c(255, 255, 255),
                    0.867f to Color.Transparent,
                    1.000f to Color.Transparent
                )
            )
            val radialMask = Brush.radialGradient(
                0.0f to Color.Black,
                0.45f to Color.Black.copy(alpha = 0.3f),
                1.0f to Color.Black.copy(alpha = 0.12f),
                center = Offset(lx, ly),
                radius = 0.75f * max(w, h)
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { blendMode = BlendMode.Screen }
                canvas.saveLayer(Rect(0f, 0f, w, h), paint)
                // 第一层
                drawRect(brush = foil1, topLeft = Offset.Zero, size = Size(w, h))
                drawRect(brush = radialMask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
                canvas.drawGlitterMask(dots, 0f, 0f, w, h, scale)
                // 第二层（glitter 偏移 117, 71）
                drawRect(brush = foil2, topLeft = Offset.Zero, size = Size(w, h))
                drawRect(brush = radialMask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
                canvas.drawGlitterMask(dots, 117f * scale, 71f * scale, w, h, scale)
                canvas.restore()
            }
        }
        HoloFinish.CHROME -> {
            val op = 0.3f + on * 0.35f
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w), (ly - 0.5f * h)),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0.0f to c(255, 143, 199, op),
                    0.2f to c(255, 226, 143, op),
                    0.4f to c(149, 255, 208, op),
                    0.6f to c(143, 214, 255, op),
                    0.8f to c(192, 155, 255, op),
                    1.0f to c(255, 143, 199, op)
                )
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { blendMode = BlendMode.Overlay }
                canvas.saveLayer(Rect(0f, 0f, w, h), paint)
                drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
                canvas.restore()
            }
        }
        HoloFinish.ETCHED -> {
            val op = 0.25f + on * 0.75f
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w) * 1.2f, (ly - 0.5f * h) * 1.2f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0.0f to c(255, 95, 126, op),
                    0.17f to c(255, 184, 107, op),
                    0.33f to c(244, 255, 107, op),
                    0.5f to c(107, 255, 181, op),
                    0.67f to c(95, 210, 255, op),
                    0.83f to c(139, 123, 255, op),
                    1.0f to c(255, 95, 126, op)
                )
            )
            val fadeMask = Brush.radialGradient(
                0.0f to Color.Black,
                0.18f to Color.Black.copy(alpha = 0.5f),
                0.42f to Color.Transparent,
                center = Offset(lx, ly),
                radius = 0.6f * max(w, h)
            )
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
                drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
                canvas.drawEtchedRingMask(w, h, scale)
                drawRect(brush = fadeMask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
    }
}

private fun GraphicsCanvas.drawEtchedRingMask(w: Float, h: Float, scale: Float) {
    val center = Offset(w / 2f, h / 2f)
    val maxR = sqrt(w * w + h * h) / 2f
    val paint = Paint().apply {
        color = Color.White.copy(alpha = 0.05f)
        style = PaintingStyle.Stroke
        strokeWidth = 1.2f * scale
        blendMode = BlendMode.DstIn
    }
    val step = 7f * scale
    var r = 1.2f * scale
    while (r < maxR) {
        drawCircle(center, r, paint)
        r += step
    }
}

private fun DrawScope.maskedFoil(
    blend: BlendMode,
    foil: Brush,
    mask: Brush,
    w: Float,
    h: Float,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply { blendMode = blend }
        canvas.saveLayer(Rect(0f, 0f, w, h), paint)
        drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
        drawRect(brush = mask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------
// 眩光 / 边环（第 2 节 glare / rim）
// ---------------------------------------------------------------------------
private fun DrawScope.drawGlare(
    finish: HoloFinish,
    lx: Float,
    ly: Float,
    on: Float,
    w: Float,
    h: Float,
) {
    val radius = sqrt(w * w + h * h)
    val center = Offset(lx, ly)
    when (finish) {
        HoloFinish.PEARL -> {
            val op = 0.5f + on * 0.5f
            drawRect(
                Brush.radialGradient(
                    0.0f to c(255, 255, 255, 0.9f * op),
                    0.55f to c(255, 255, 255, 0f),
                    center = center,
                    radius = radius * 0.55f
                ),
                blendMode = BlendMode.Softlight
            )
        }
        HoloFinish.CHROME -> {
            val op = 0.35f + on * 0.65f
            drawRect(
                Brush.radialGradient(
                    0.0f to c(255, 255, 255, 0.85f * op),
                    0.30f to c(255, 255, 255, 0.10f * op),
                    0.95f to c(0, 0, 0, 0.18f * op),
                    center = center,
                    radius = radius
                ),
                blendMode = BlendMode.Softlight
            )
        }
        else -> {
            val op = when (finish) {
                HoloFinish.PRISM -> 0.25f + on * 0.45f
                HoloFinish.ETCHED -> 0.2f + on * 0.4f
                else -> 0.35f + on * 0.65f
            }
            drawRect(
                Brush.radialGradient(
                    0.0f to c(255, 255, 255, 0.6f * op),
                    0.28f to c(255, 255, 255, 0.14f * op),
                    0.95f to c(0, 0, 0, 0.3f * op),
                    center = center,
                    radius = radius
                ),
                blendMode = BlendMode.Overlay
            )
        }
    }
}

private fun DrawScope.drawRim(
    palette: HoloPalette,
    lx: Float,
    ly: Float,
    on: Float,
    w: Float,
    h: Float,
    corner: Float,
    scale: Float,
) {
    val op = 0.6f + on * 0.4f
    val stroke = 1.5f * scale
    val inset = stroke / 2f
    val size = Size(w - stroke, h - stroke)
    val cornerR = CornerRadius(corner)
    drawRoundRect(
        color = palette.rimBase.copy(alpha = palette.rimBase.alpha * op),
        topLeft = Offset(inset, inset),
        size = size,
        cornerRadius = cornerR,
        style = Stroke(stroke)
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            0.0f to palette.rimLight.copy(alpha = op),
            0.42f to palette.rimLight.copy(alpha = 0f),
            center = Offset(lx, ly),
            radius = 0.42f * sqrt(w * w + h * h)
        ),
        topLeft = Offset(inset, inset),
        size = size,
        cornerRadius = cornerR,
        style = Stroke(stroke)
    )
}

// ---------------------------------------------------------------------------
// 全息印章（第 2 节 hologram seal）
// ---------------------------------------------------------------------------
private fun DrawScope.drawSeal(
    palette: HoloPalette,
    lx: Float,
    ly: Float,
    rotYdeg: Float,
    w: Float,
    h: Float,
    scale: Float,
) {
    val r = 22f * scale
    val center = Offset(w * 0.5f, h * 0.40f)
    val angleDeg = Math.toDegrees(atan2((ly - h * 0.5f).toDouble(), (lx - w * 0.5f).toDouble()))
        .toFloat() * 1.5f + rotYdeg * 6f
    val sweep = Brush.sweepGradient(
        listOf(
            c(255, 154, 200), c(255, 224, 138), c(164, 255, 207), c(143, 220, 255), c(183, 155, 255),
            c(255, 154, 200), c(255, 224, 138), c(164, 255, 207), c(143, 220, 255), c(183, 155, 255)
        ),
        center = center
    )
    rotate(degrees = angleDeg, pivot = center) {
        drawCircle(brush = sweep, radius = r, center = center)
    }
    // 细环：白色 35%，1px，每 3.5px 一圈
    var rr = r
    while (rr > 0f) {
        drawCircle(
            color = c(255, 255, 255, 0.35f),
            radius = rr,
            center = center,
            style = Stroke(1f * scale)
        )
        rr -= 3.5f * scale
    }
    // 1px 白边，60%
    drawCircle(
        color = c(255, 255, 255, 0.6f),
        radius = r,
        center = center,
        style = Stroke(1f * scale)
    )
}

// ---------------------------------------------------------------------------
// 阴影（第 1 节 shadow）
// ---------------------------------------------------------------------------
private fun DrawScope.drawShadow(state: HoloCardState, w: Float, h: Float) {
    val scale = w / 440f
    val sinFlip = abs(sin(state.flip.floatValue * (PI.toFloat() / 180f)))
    val rx = 0.42f * w * (1f - 0.45f * sinFlip)
    val ry = 35f * scale
    val cx = w / 2f - state.rotY.floatValue * 1.8f * scale
    val cy = h + 30f * scale + state.rotX.floatValue * 1.2f * scale + 14f * scale * sinFlip
    val center = Offset(cx, cy)
    withTransform({
        translate(center.x, center.y)
        scale(scaleX = 1f, scaleY = ry / rx, pivot = Offset.Zero)
        translate(-center.x, -center.y)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.Black.copy(alpha = 0.6f),
                1.0f to Color.Transparent,
                center = center,
                radius = rx
            ),
            radius = rx,
            center = center
        )
    }
}

// ---------------------------------------------------------------------------
// 侧边厚度（第 4 节 thickness：10px / 10 层）
// ---------------------------------------------------------------------------
private fun DrawScope.drawEdge(
    palette: HoloPalette,
    state: HoloCardState,
    w: Float,
    h: Float,
) {
    val scale = w / 440f
    val thickness = 10f * scale
    val sinFlip = abs(sin(state.flip.floatValue * (PI.toFloat() / 180f)))
    val alpha = 0.55f * sinFlip
    val corner = max(22f * density, w * 32f / 440f)
    val count = 10
    for (i in 0 until count) {
        val t = i / (count - 1f) - 0.5f
        val dx = t * thickness * sinFlip
        drawRoundRect(
            color = palette.edge.copy(alpha = alpha),
            topLeft = Offset(dx, 0f),
            size = Size(w, h),
            cornerRadius = CornerRadius(corner),
            style = Stroke(1f * scale)
        )
    }
}

// ---------------------------------------------------------------------------
// 印刷内容（第 D 节卡片文案）
// ---------------------------------------------------------------------------
@Composable
private fun PrintContent(
    kind: HoloCardKind,
    isBack: Boolean,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when (kind) {
            HoloCardKind.WORD -> if (isBack) WordBack(ink) else WordFront(ink)
            HoloCardKind.CHECK_IN -> if (isBack) CheckInBack(ink) else CheckInFront(ink)
        }
    }
}

@Composable
private fun WordFront(ink: Color) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .background(ink.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text("新词", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "serendipity",
                color = ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text("/ˌserənˈdɪpəti/", color = ink.copy(alpha = 0.85f), fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text("n.", color = ink.copy(alpha = 0.9f), fontSize = 18.sp)
        }
    }
}

@Composable
private fun WordBack(ink: Color) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "意外发现珍奇事物的能力；机缘巧合",
            color = ink,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "Finding this café was pure serendipity — I wasn't even looking for it.",
            color = ink.copy(alpha = 0.82f),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "找到这家咖啡馆纯属机缘巧合——我根本没在找。",
            color = ink.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(30.dp))
        Text("点击翻回正面", color = ink.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

@Composable
private fun CheckInFront(ink: Color) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("今日背词完成", color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("2026.11.16", color = ink.copy(alpha = 0.8f), fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CheckInMetric("今日学习", "20", "词", ink)
            CheckInMetric("连续打卡", "7", "天", ink)
            CheckInMetric("本周打卡", "5", "天", ink)
        }
    }
}

@Composable
private fun CheckInBack(ink: Color) {
    Box(Modifier.fillMaxSize().padding(26.dp)) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("打卡纪念", color = ink.copy(alpha = 0.85f), fontSize = 20.sp)
            Spacer(Modifier.height(56.dp))
            Text("连续 7 天", color = ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "点击翻回正面",
            modifier = Modifier.align(Alignment.BottomCenter),
            color = ink.copy(alpha = 0.55f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CheckInMetric(label: String, value: String, unit: String, ink: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = ink.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------
private fun c(r: Int, g: Int, b: Int, a: Float = 1f): Color =
    Color(r / 255f, g / 255f, b / 255f, a)

private fun cardPath(w: Float, h: Float, corner: Float): Path =
    Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner, corner))) }

private fun angleGradient(
    angleDeg: Float,
    w: Float,
    h: Float,
    shift: Offset = Offset.Zero,
    period: Float = -1f,
    stops: List<Pair<Float, Color>>,
): Brush {
    val rad = (angleDeg - 90f) * (PI.toFloat() / 180f)
    val dirX = cos(rad)
    val dirY = sin(rad)
    val cx = w / 2f
    val cy = h / 2f
    val needed = abs(w * dirX) + abs(h * dirY)
    val halfLen = if (period > 0f) {
        val reps = ceil(needed / period) + 1f
        period * reps / 2f
    } else {
        needed / 2f
    }
    val tile = if (period > 0f) TileMode.Repeated else TileMode.Clamp
    val start = Offset(cx - dirX * halfLen + shift.x, cy - dirY * halfLen + shift.y)
    val end = Offset(cx + dirX * halfLen + shift.x, cy + dirY * halfLen + shift.y)
    return Brush.linearGradient(*stops.toTypedArray(), start = start, end = end, tileMode = tile)
}

private data class GlitterDot(val x: Float, val y: Float, val r: Float, val a: Float)

private fun makeGlitterDots(count: Int, seed: Int): List<GlitterDot> {
    var s = seed
    fun rnd(): Float {
        s = (s * 1103515245 + 12345) and 0x7fffffff
        return (s % 100000) / 100000f
    }
    return List(count) {
        GlitterDot(
            x = rnd(),
            y = rnd(),
            r = 0.45f + rnd() * rnd() * 1.4f,
            a = 0.35f + rnd() * 0.65f
        )
    }
}

private fun GraphicsCanvas.drawGlitterMask(
    dots: List<GlitterDot>,
    offX: Float,
    offY: Float,
    w: Float,
    h: Float,
    scale: Float,
) {
    val paint = Paint().apply { blendMode = BlendMode.DstIn }
    for (d in dots) {
        paint.color = Color.White.copy(alpha = d.a)
        drawCircle(
            Offset((d.x + offX / w) * w, (d.y + offY / h) * h),
            d.r * scale,
            paint
        )
    }
}

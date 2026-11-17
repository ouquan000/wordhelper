package com.kaoyan.wordhelper.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 7 款卡片材质。id 与 DataStore 里持久化的 cardStyleId 一一对应。
 *
 * 注意：CLASSIC / PEARL / CHROME 是浅色底板，PRISM / RAINBOW / SPARKLE / ETCHED
 * 是深色底板；`dark` 只描述底板，CLASSIC 在深色模式下会额外自适应（见 [rememberHoloPalette]）。
 */
enum class HoloCardStyle(
    val id: String,
    val label: String,
    val dark: Boolean,
) {
    CLASSIC("classic", "经典悬浮", false),
    PRISM("prism", "棱镜深海蓝", true),
    RAINBOW("rainbow", "金属彩虹", true),
    PEARL("pearl", "珍珠柔光白", false),
    SPARKLE("sparkle", "碎钻星光", true),
    CHROME("chrome", "液态银雕", false),
    ETCHED("etched", "暗刻同心纹", true);

    companion object {
        fun fromId(id: String?): HoloCardStyle =
            entries.firstOrNull { it.id == id } ?: PEARL
    }
}

@Immutable
data class HoloCardPalette(
    val style: HoloCardStyle,
    val dark: Boolean,
    val ink: Color,
    val subInk: Color,
    val accent: Color,
    val panel: Color,
    val panelBorder: Color,
    val frontTop: Color,
    val frontBottom: Color,
) {
    /** 背面内容磨砂底：浅色款白 0.72，深色款 #0F172A 0.62。 */
    val frostedPanel: Color
        get() = if (dark) Color(0xFF0F172A).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.72f)
}

val LocalHoloCardPalette = staticCompositionLocalOf {
    HoloCardPalette(
        style = HoloCardStyle.PEARL,
        dark = false,
        ink = Color(0xFF17152E),
        subInk = Color(0xFF4A4668),
        accent = Color(0xFF6C5CE7),
        panel = Color.White.copy(alpha = 0.72f),
        panelBorder = Color(0xFF8E86C9).copy(alpha = 0.25f),
        frontTop = Color(0xFFFDFCFF),
        frontBottom = Color(0xFFF1EEF8),
    )
}

@Composable
fun rememberHoloPalette(style: HoloCardStyle): HoloCardPalette {
    val themeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val classicDark = style == HoloCardStyle.CLASSIC && themeDark
    return remember(style, themeDark) {
        when (style) {
            HoloCardStyle.CLASSIC -> if (classicDark) HoloCardPalette(
                style = style,
                dark = true,
                ink = Color(0xFFEAF0FF),
                subInk = Color(0xFFB9C6E4),
                accent = Color(0xFFAEC4FF),
                panel = Color(0xFF0F172A).copy(alpha = 0.62f),
                panelBorder = Color.White.copy(alpha = 0.18f),
                frontTop = Color(0xFF1A233C),
                frontBottom = Color(0xFF111A2E),
            ) else HoloCardPalette(
                style = style,
                dark = false,
                ink = Color(0xFF1F2A44),
                subInk = Color(0xFF6D7895),
                accent = Color(0xFF2E5CCB),
                panel = Color.White.copy(alpha = 0.72f),
                panelBorder = Color(0xFFBAC6E3).copy(alpha = 0.35f),
                frontTop = Color(0xFFEAF2FF),
                frontBottom = Color(0xFFFBFDFF),
            )

            HoloCardStyle.PRISM -> darkPalette(style, accent = Color(0xFF9DB8FF))
            HoloCardStyle.RAINBOW -> darkPalette(style, accent = Color(0xFFFFD9F0))
            HoloCardStyle.SPARKLE -> darkPalette(style, accent = Color(0xFF8BF3FF))
            HoloCardStyle.ETCHED -> darkPalette(style, accent = Color(0xFFE6E6EA))
            HoloCardStyle.PEARL -> HoloCardPalette(
                style = style,
                dark = false,
                ink = Color(0xFF17152E),
                subInk = Color(0xFF4A4668),
                accent = Color(0xFF6C5CE7),
                panel = Color.White.copy(alpha = 0.72f),
                panelBorder = Color(0xFF8E86C9).copy(alpha = 0.25f),
                frontTop = Color(0xFFFDFCFF),
                frontBottom = Color(0xFFF1EEF8),
            )

            HoloCardStyle.CHROME -> HoloCardPalette(
                style = style,
                dark = false,
                ink = Color(0xFF1B1D24),
                subInk = Color(0xFF4A4E59),
                accent = Color(0xFF3A3F4B),
                panel = Color.White.copy(alpha = 0.72f),
                panelBorder = Color(0xFF7C828F).copy(alpha = 0.30f),
                frontTop = Color(0xFFEFF1F4),
                frontBottom = Color(0xFFC9CDD4),
            )
        }
    }
}

private fun darkPalette(style: HoloCardStyle, accent: Color) = HoloCardPalette(
    style = style,
    dark = true,
    ink = Color(0xFFF4F4F6),
    subInk = Color(0xFFD7DBE6).copy(alpha = 0.78f),
    accent = accent,
    panel = Color(0xFF0F172A).copy(alpha = 0.62f),
    panelBorder = Color.White.copy(alpha = 0.22f),
    frontTop = Color(0xFF18213A),
    frontBottom = Color(0xFF0C1120),
)

/**
 * 卡片内部的 MaterialTheme 覆盖：把 onSurface / surface / primary 全部换成本款材质
 * 的高对比组合，这样卡背的 CardBackBlocks、RewriteBar 等旧组件不用改一行也能跟着适配。
 */
@Composable
fun HoloCardTheme(style: HoloCardStyle, content: @Composable () -> Unit) {
    val palette = rememberHoloPalette(style)
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, palette) {
        if (palette.dark) {
            base.copy(
                primary = palette.accent,
                onPrimary = Color(0xFF0A1020),
                primaryContainer = Color.White.copy(alpha = 0.16f),
                onPrimaryContainer = palette.ink,
                secondary = palette.accent,
                onSecondary = Color(0xFF0A1020),
                secondaryContainer = palette.accent.copy(alpha = 0.16f),
                onSecondaryContainer = palette.ink,
                tertiary = palette.accent,
                onTertiary = Color(0xFF0A1020),
                surface = palette.panel,
                onSurface = palette.ink,
                surfaceVariant = palette.panel,
                onSurfaceVariant = palette.subInk,
                background = palette.panel,
                onBackground = palette.ink,
                outline = palette.ink.copy(alpha = 0.30f),
                outlineVariant = palette.ink.copy(alpha = 0.16f),
                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),
            )
        } else {
            base.copy(
                primary = palette.accent,
                onPrimary = Color.White,
                primaryContainer = palette.ink.copy(alpha = 0.10f),
                onPrimaryContainer = palette.ink,
                secondary = palette.accent,
                onSecondary = Color.White,
                secondaryContainer = palette.accent.copy(alpha = 0.10f),
                onSecondaryContainer = palette.accent,
                surface = palette.panel,
                onSurface = palette.ink,
                surfaceVariant = palette.panel,
                onSurfaceVariant = palette.subInk,
                background = palette.panel,
                onBackground = palette.ink,
                outline = palette.ink.copy(alpha = 0.22f),
                outlineVariant = palette.ink.copy(alpha = 0.12f),
            )
        }
    }
    CompositionLocalProvider(
        LocalHoloCardPalette provides palette,
        androidx.compose.material3.LocalContentColor provides palette.ink,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * 虚拟光源。卡片本身永远水平（rotationX = 0），所有「流光」都来自这里。
 *
 * - cardMotionEnabled = true：李萨如自动呼吸（12.4s / 10.3s），指针按下后改为追光；
 * - cardMotionEnabled = false：锁定 (0.34, 0.22)，不启动帧循环。
 */
@Stable
class HoloLightState {
    val lightX = mutableFloatStateOf(0.34f)
    val lightY = mutableFloatStateOf(0.22f)
    val hover = mutableFloatStateOf(0f)
    val breath = mutableFloatStateOf(0f)

    internal var motionEnabled = true
        private set
    private var pointerActive = false
    private var pointerX = 0.34f
    private var pointerY = 0.22f
    private var velocityX = 0f
    private var velocityY = 0f
    private var time = 0f

    fun pointerAt(nx: Float, ny: Float) {
        if (!motionEnabled) return
        pointerActive = true
        pointerX = nx.coerceIn(0f, 1f)
        pointerY = ny.coerceIn(0f, 1f)
    }

    fun pointerUp() {
        pointerActive = false
    }

    internal fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
        if (!enabled) {
            pointerActive = false
            lightX.floatValue = 0.34f
            lightY.floatValue = 0.22f
            hover.floatValue = 0f
            breath.floatValue = 0f
            velocityX = 0f
            velocityY = 0f
        }
    }

    internal fun step(dt: Float) {
        time += dt
        val hoverTarget = if (pointerActive) 1f else 0f
        val ease = 1f - exp(-7f * dt)
        hover.floatValue += (hoverTarget - hover.floatValue) * ease

        val targetX: Float
        val targetY: Float
        if (pointerActive) {
            targetX = pointerX
            targetY = pointerY
        } else {
            // 李萨如：x 0.25..0.75，y 0.18..0.65，周期 10~14s。
            targetX = 0.5f + 0.25f * sin(2f * PI.toFloat() * time / 12.4f)
            targetY = 0.415f + 0.235f * sin(2f * PI.toFloat() * time / 10.3f + 1.1f)
        }
        // 无过冲阻尼弹簧：stiffness 160，damping 25.3（≈ 2√160）。
        velocityX += (160f * (targetX - lightX.floatValue) - 25.3f * velocityX) * dt
        lightX.floatValue = (lightX.floatValue + velocityX * dt).coerceIn(0f, 1f)
        velocityY += (160f * (targetY - lightY.floatValue) - 25.3f * velocityY) * dt
        lightY.floatValue = (lightY.floatValue + velocityY * dt).coerceIn(0f, 1f)
        breath.floatValue = 0.5f + 0.5f * sin(2f * PI.toFloat() * time / 6.4f)
    }
}

@Composable
fun rememberHoloLightState(motionEnabled: Boolean): HoloLightState {
    val state = remember { HoloLightState() }
    LaunchedEffect(motionEnabled) {
        state.setMotionEnabled(motionEnabled)
        if (!motionEnabled) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    state.step(dt)
                }
                last = now
            }
        }
    }
    return state
}

/**
 * 悬浮全息卡面。只负责材质 + 阴影 + 装饰；内容通过 [content] 叠在上面，
 * 因此不会吞掉任何触摸事件（所有手势都来自 content / 外部 Modifier）。
 */
@Composable
fun HoloCardSurface(
    style: HoloCardStyle,
    light: HoloLightState,
    isBack: Boolean,
    glossHigh: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val palette = rememberHoloPalette(style)
    val dots = remember(style) { makeHoloDots(950, 0xC0FFEE) }
    val gloss = if (glossHigh) 1f else 0.55f
    val baseColor = if (palette.dark) Color(0xFF10142B) else palette.frontBottom

    Box(modifier = modifier) {
        // 双层弥散阴影：底层大范围环境软投影 alpha .14，近景接触投影 alpha .08。
        // 读 breath 在 graphicsLayer 里，只重绘不重组；呼吸带来 1~2dp 的纵深变化。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    this.shape = shape
                    clip = false
                    shadowElevation = (18f + 2f * light.breath.floatValue) * density
                    ambientShadowColor = Color.Black.copy(alpha = 0.14f)
                    spotShadowColor = Color.Black.copy(alpha = 0.14f)
                }
        )
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    this.shape = shape
                    clip = false
                    shadowElevation = (4f + 1f * light.breath.floatValue) * density
                    ambientShadowColor = Color.Black.copy(alpha = 0.08f)
                    spotShadowColor = Color.Black.copy(alpha = 0.08f)
                }
        )
        // 注意：这一层不能用 matchParentSize —— 全 matchParentSize 的 Box 量出来是 0 宽，
        // 内容会被压成一条缝。让它由 content 决定尺寸，Canvas 再 matchParentSize 铺满。
        Box(
            Modifier
                .clip(shape)
                .background(baseColor)
        ) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val corner = 28.dp.toPx()
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner, corner)))
                }
                clipPath(path) {
                    drawHoloMaterial(
                        style = style,
                        palette = palette,
                        lightX = if (isBack) 1f - light.lightX.floatValue else light.lightX.floatValue,
                        lightY = light.lightY.floatValue,
                        hover = light.hover.floatValue,
                        gloss = gloss,
                        w = w,
                        h = h,
                        corner = corner,
                        dots = dots,
                    )
                }
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// 材质绘制
// ---------------------------------------------------------------------------

private fun DrawScope.drawHoloMaterial(
    style: HoloCardStyle,
    palette: HoloCardPalette,
    lightX: Float,
    lightY: Float,
    hover: Float,
    gloss: Float,
    w: Float,
    h: Float,
    corner: Float,
    dots: List<HoloDot>,
) {
    val lx = lightX * w
    val ly = lightY * h
    val scale = w / 440f
    val diagonal = sqrt(w * w + h * h)

    drawHoloBase(style, palette, lx, ly, w, h, scale, diagonal)
    drawHoloFoil(style, lx, ly, hover, gloss, w, h, scale, diagonal, dots)
    drawHoloGlare(style, lx, ly, hover, gloss, w, h, diagonal)
    drawHoloRim(palette, lx, ly, hover, w, h, corner, scale)
}

private fun DrawScope.drawHoloBase(
    style: HoloCardStyle,
    palette: HoloCardPalette,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    scale: Float,
    diagonal: Float,
) {
    when (style) {
        HoloCardStyle.CLASSIC -> {
            drawRect(
                Brush.linearGradient(
                    colors = listOf(palette.frontTop, palette.frontBottom),
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                )
            )
            drawRect(
                Brush.radialGradient(
                    0f to Color(0xFF6E88E8).copy(alpha = if (palette.dark) 0.22f else 0.16f),
                    1f to Color.Transparent,
                    center = Offset(w * 0.18f, 0f),
                    radius = w * 0.9f,
                )
            )
        }

        HoloCardStyle.PRISM -> {
            // 170deg 深蓝底：0D23A8 -> 081266 -> 160F5C
            drawRect(
                angleGradient(
                    170f, w, h,
                    stops = listOf(
                        0f to Color(0xFF0D23A8),
                        0.55f to Color(0xFF081266),
                        1f to Color(0xFF160F5C),
                    )
                )
            )
            // 中央偏内缩 14dp 的「深海玻璃视窗」
            val inset = 14.dp.toPx()
            val winLeft = inset
            val winTop = inset
            val winW = (w - inset * 2f).coerceAtLeast(1f)
            val winH = (h - inset * 2f).coerceAtLeast(1f)
            val winRadius = CornerRadius(22.dp.toPx())
            drawRoundRect(
                brush = Brush.linearGradient(
                    0f to Color(0xFF3B62E6),
                    0.55f to Color(0xFF2A3FBF),
                    1f to Color(0xFF3A2FA8),
                    start = Offset(winLeft, winTop),
                    end = Offset(winLeft + winW, winTop + winH),
                ),
                topLeft = Offset(winLeft, winTop),
                size = Size(winW, winH),
                cornerRadius = winRadius,
            )
            val windowPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        winLeft, winTop, winLeft + winW, winTop + winH,
                        CornerRadius(winRadius.x, winRadius.y)
                    )
                )
            }
            clipPath(windowPath) {
                // 粉紫 / 冰青 / 薄荷三团随光源游走的柔光
                drawRect(
                    Brush.radialGradient(
                        0f to Color(255, 175, 235).copy(alpha = 0.55f),
                        0.35f to Color(255, 175, 235).copy(alpha = 0.20f),
                        1f to Color(255, 175, 235).copy(alpha = 0f),
                        center = Offset(lx, ly),
                        radius = diagonal * 0.52f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        0f to Color(125, 225, 255).copy(alpha = 0.42f),
                        0.40f to Color(125, 225, 255).copy(alpha = 0.14f),
                        1f to Color(125, 225, 255).copy(alpha = 0f),
                        center = Offset(w - lx, h * 0.72f),
                        radius = diagonal * 0.50f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        0f to Color(150, 255, 210).copy(alpha = 0.34f),
                        1f to Color(150, 255, 210).copy(alpha = 0f),
                        center = Offset(w * 0.32f, h),
                        radius = diagonal * 0.62f,
                    )
                )
            }
            // 冰蓝亮线 rgba(170,200,255,0.28)
            drawRoundRect(
                color = Color(170, 200, 255).copy(alpha = 0.28f),
                topLeft = Offset(winLeft, winTop),
                size = Size(winW, winH),
                cornerRadius = winRadius,
                style = Stroke(1.dp.toPx()),
            )
        }

        HoloCardStyle.RAINBOW -> {
            drawRect(
                Brush.radialGradient(
                    0f to Color(0xFF5A5F72),
                    0.62f to Color(0xFF2A2C38),
                    1f to Color(0xFF20222B),
                    center = Offset(w * 0.25f, 0f),
                    radius = w * 1.3f,
                )
            )
        }

        HoloCardStyle.PEARL -> {
            drawRect(
                angleGradient(
                    160f, w, h,
                    stops = listOf(
                        0f to Color(0xFFFDFCFF),
                        1f to Color(0xFFF1EEF8),
                    )
                )
            )
        }

        HoloCardStyle.SPARKLE -> {
            drawRect(
                Brush.radialGradient(
                    0f to Color(0xFF28336A),
                    0.65f to Color(0xFF10142B),
                    1f to Color(0xFF10142B),
                    center = Offset(w * 0.75f, h * 0.05f),
                    radius = w * 1.2f,
                )
            )
        }

        HoloCardStyle.CHROME -> {
            // 银灰拉丝：随光源反向位移（1f - lightX）
            val shiftX = (0.5f - (1f - lx / w)) * w * 0.55f
            val shiftY = (0.5f - ly / h) * h * 0.25f
            drawRect(
                angleGradient(
                    125f, w, h,
                    shift = Offset(shiftX, shiftY),
                    stops = listOf(
                        0f to Color(0xFFE4E6EA),
                        0.16f to Color(0xFF8D939E),
                        0.30f to Color(0xFFF6F7F9),
                        0.46f to Color(0xFF7C828D),
                        0.60f to Color(0xFFD9DCE1),
                        0.76f to Color(0xFF949AA5),
                        1f to Color(0xFFF1F2F4),
                    )
                )
            )
            // 细拉丝纹
            var x = -h
            val step = 2.6f * scale
            while (x < w + h) {
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(x, 0f),
                    end = Offset(x + h * 0.45f, h),
                    strokeWidth = 0.8f * scale,
                )
                x += step
            }
        }

        HoloCardStyle.ETCHED -> {
            drawRect(Color(0xFF121214))
            drawEtchedRings(w, h, scale, Color.White.copy(alpha = 0.05f))
            drawRect(
                Brush.radialGradient(
                    0f to Color(0xFF1D1D21),
                    1f to Color(0xFF0E0E10),
                    center = Offset(w * 0.5f, h * 0.35f),
                    radius = w * 0.7f,
                )
            )
        }
    }
}

private fun DrawScope.drawHoloFoil(
    style: HoloCardStyle,
    lx: Float,
    ly: Float,
    hover: Float,
    gloss: Float,
    w: Float,
    h: Float,
    scale: Float,
    diagonal: Float,
    dots: List<HoloDot>,
) {
    when (style) {
        HoloCardStyle.CLASSIC -> {
            // 内发光玻璃细框下的极淡蓝紫箔
            val op = (0.10f + hover * 0.18f) * gloss
            drawRect(
                Brush.linearGradient(
                    0f to Color(110, 136, 232).copy(alpha = op),
                    0.55f to Color.Transparent,
                    1f to Color(160, 120, 255).copy(alpha = op * 0.7f),
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                )
            )
        }

        HoloCardStyle.PRISM -> {
            val op = (0.10f + hover * 0.20f) * gloss
            drawRect(
                Brush.radialGradient(
                    0f to Color(170, 220, 255).copy(alpha = op),
                    1f to Color.Transparent,
                    center = Offset(lx, ly),
                    radius = diagonal * 0.55f,
                )
            )
        }

        HoloCardStyle.RAINBOW -> {
            val op = (0.82f + hover * 0.18f) * gloss
            val raw = listOf(
                Color(255, 95, 126), Color(255, 184, 107), Color(244, 255, 107),
                Color(107, 255, 181), Color(95, 210, 255), Color(139, 123, 255),
                Color(255, 107, 214), Color(255, 95, 126),
            ).map { purified(it, op) }
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w) * 0.45f, (ly - 0.5f * h) * 0.45f),
                period = 0.34f * max(w, h),
                stops = raw.mapIndexed { index, color -> index / (raw.size - 1f) to color },
            )
            val mask = Brush.radialGradient(
                0f to Color.Black,
                0.62f to Color.Black.copy(alpha = 0.72f),
                1f to Color.Black.copy(alpha = 0.34f),
                center = Offset(lx, ly),
                radius = 0.92f * diagonal,
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { blendMode = BlendMode.ColorDodge }
                canvas.saveLayer(Rect(0f, 0f, w, h), paint)
                drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
                drawRect(
                    brush = mask, topLeft = Offset.Zero, size = Size(w, h),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
            // 光栅斜纹独立于 ColorDodge 图层：SrcOver 半透明暗线，修复过曝后仍清晰可见。
            var x = -h * 0.8f
            val gap = 3.2f * scale
            while (x < w + h * 0.8f) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.20f),
                    start = Offset(x, h),
                    end = Offset(x + h * 0.70f, 0f),
                    strokeWidth = 1f * scale,
                )
                x += gap
            }
        }

        HoloCardStyle.PEARL -> {
            val op = (0.55f + hover * 0.18f) * gloss
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w) * 0.6f, (ly - 0.5f * h) * 0.6f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0f to Color(255, 196, 225).copy(alpha = op),
                    0.2f to Color(201, 184, 255).copy(alpha = op),
                    0.4f to Color(180, 233, 255).copy(alpha = op),
                    0.6f to Color(194, 255, 216).copy(alpha = op),
                    0.8f to Color(255, 243, 184).copy(alpha = op),
                    1f to Color(255, 196, 225).copy(alpha = op),
                ),
            )
            // 中心保持干净通透（alpha .08），只在四周边缘浓郁成珍珠母贝光
            val mask = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.08f),
                0.55f to Color.Black.copy(alpha = 0.28f),
                0.85f to Color.Black.copy(alpha = 0.70f),
                1f to Color.Black,
                center = Offset(w * 0.5f, h * 0.45f),
                radius = 0.70f * max(w, h),
            )
            maskedHoloFoil(BlendMode.Multiply, foil, mask, w, h)
        }

        HoloCardStyle.SPARKLE -> {
            val op = (0.55f + hover * 0.35f) * gloss
            val foil1 = angleGradient(
                60f, w, h,
                shift = Offset((lx * 1.6f - 0.5f * w) * 0.5f, (ly - 0.5f * h) * 0.5f),
                period = 0.36f * max(w, h),
                stops = listOf(
                    0.00f to Color(255, 139, 210).copy(alpha = op),
                    0.10f to Color(255, 139, 210).copy(alpha = op),
                    0.18f to Color.Transparent,
                    0.28f to Color.Transparent,
                    0.36f to Color(139, 243, 255).copy(alpha = op),
                    0.46f to Color(139, 243, 255).copy(alpha = op),
                    0.54f to Color.Transparent,
                    0.64f to Color.Transparent,
                    0.71f to Color(255, 243, 139).copy(alpha = op),
                    0.82f to Color(255, 243, 139).copy(alpha = op),
                    0.89f to Color.Transparent,
                    1.00f to Color.Transparent,
                ),
            )
            val foil2 = angleGradient(
                -35f, w, h,
                shift = Offset(((1f - lx / w) - 0.5f) * w * 0.5f, (ly * 1.4f / h - 0.5f) * h * 0.5f),
                period = 0.36f * max(w, h),
                stops = listOf(
                    0.00f to Color(169, 139, 255).copy(alpha = op),
                    0.10f to Color(169, 139, 255).copy(alpha = op),
                    0.17f to Color.Transparent,
                    0.30f to Color.Transparent,
                    0.37f to Color(139, 255, 196).copy(alpha = op),
                    0.47f to Color(139, 255, 196).copy(alpha = op),
                    0.53f to Color.Transparent,
                    0.67f to Color.Transparent,
                    0.73f to Color(255, 255, 255).copy(alpha = op),
                    0.80f to Color(255, 255, 255).copy(alpha = op),
                    0.87f to Color.Transparent,
                    1.00f to Color.Transparent,
                ),
            )
            val radialMask = Brush.radialGradient(
                0f to Color.Black,
                0.45f to Color.Black.copy(alpha = 0.30f),
                1f to Color.Black.copy(alpha = 0.10f),
                center = Offset(lx, ly),
                radius = 0.78f * diagonal,
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { blendMode = BlendMode.Screen }
                canvas.saveLayer(Rect(0f, 0f, w, h), paint)
                drawRect(brush = foil1, topLeft = Offset.Zero, size = Size(w, h))
                drawRect(brush = radialMask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
                canvas.drawHoloDots(dots, 0f, 0f, w, h, scale)
                drawRect(brush = foil2, topLeft = Offset.Zero, size = Size(w, h))
                drawRect(brush = radialMask, topLeft = Offset.Zero, size = Size(w, h), blendMode = BlendMode.DstIn)
                canvas.drawHoloDots(dots, 117f * scale, 71f * scale, w, h, scale)
                canvas.restore()
            }
        }

        HoloCardStyle.CHROME -> {
            val op = (0.30f + hover * 0.35f) * gloss
            val foil = angleGradient(
                115f, w, h,
                shift = Offset(lx - 0.5f * w, ly - 0.5f * h),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0f to Color(255, 143, 199).copy(alpha = op),
                    0.2f to Color(255, 226, 143).copy(alpha = op),
                    0.4f to Color(149, 255, 208).copy(alpha = op),
                    0.6f to Color(143, 214, 255).copy(alpha = op),
                    0.8f to Color(192, 155, 255).copy(alpha = op),
                    1f to Color(255, 143, 199).copy(alpha = op),
                ),
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { blendMode = BlendMode.Overlay }
                canvas.saveLayer(Rect(0f, 0f, w, h), paint)
                drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
                canvas.restore()
            }
        }

        HoloCardStyle.ETCHED -> {
            val op = (0.30f + hover * 0.70f) * gloss
            val foil = angleGradient(
                115f, w, h,
                shift = Offset((lx - 0.5f * w) * 1.2f, (ly - 0.5f * h) * 1.2f),
                period = 0.34f * max(w, h),
                stops = listOf(
                    0f to Color(255, 95, 126).copy(alpha = op),
                    0.17f to Color(255, 184, 107).copy(alpha = op),
                    0.33f to Color(244, 255, 107).copy(alpha = op),
                    0.5f to Color(107, 255, 181).copy(alpha = op),
                    0.67f to Color(95, 210, 255).copy(alpha = op),
                    0.83f to Color(139, 123, 255).copy(alpha = op),
                    1f to Color(255, 95, 126).copy(alpha = op),
                ),
            )
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
                drawRect(brush = foil, topLeft = Offset.Zero, size = Size(w, h))
                // 关键修复：遮罩用 White alpha 0.85，而不是看不见的 0.05。
                canvas.drawEtchedRingMask(w, h, scale, Color.White.copy(alpha = 0.85f))
                // 光源径向衰减：亮在光附近，远处保持哑光黑。
                drawRect(
                    brush = Brush.radialGradient(
                        0f to Color.Black,
                        0.30f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.10f),
                        center = Offset(lx, ly),
                        radius = 0.62f * max(w, h),
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawHoloGlare(
    style: HoloCardStyle,
    lx: Float,
    ly: Float,
    hover: Float,
    gloss: Float,
    w: Float,
    h: Float,
    diagonal: Float,
) {
    when (style) {
        HoloCardStyle.PEARL -> {
            val op = (0.45f + hover * 0.45f) * gloss
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.80f * op),
                    0.55f to Color.White.copy(alpha = 0f),
                    center = Offset(lx, ly),
                    radius = diagonal * 0.55f,
                ),
                blendMode = BlendMode.Softlight,
            )
        }

        HoloCardStyle.CHROME -> {
            val op = (0.35f + hover * 0.55f) * gloss
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.85f * op),
                    0.30f to Color.White.copy(alpha = 0.10f * op),
                    0.95f to Color.Black.copy(alpha = 0.16f * op),
                    center = Offset(lx, ly),
                    radius = diagonal,
                ),
                blendMode = BlendMode.Softlight,
            )
        }

        else -> {
            val op = when (style) {
                HoloCardStyle.PRISM -> (0.22f + hover * 0.38f) * gloss
                HoloCardStyle.ETCHED -> (0.16f + hover * 0.30f) * gloss
                else -> (0.30f + hover * 0.55f) * gloss
            }
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.55f * op),
                    0.28f to Color.White.copy(alpha = 0.12f * op),
                    0.95f to Color.Black.copy(alpha = 0.26f * op),
                    center = Offset(lx, ly),
                    radius = diagonal,
                ),
                blendMode = BlendMode.Overlay,
            )
        }
    }
}

private fun DrawScope.drawHoloRim(
    palette: HoloCardPalette,
    lx: Float,
    ly: Float,
    hover: Float,
    w: Float,
    h: Float,
    corner: Float,
    scale: Float,
) {
    val op = (0.55f + hover * 0.45f) * if (palette.dark) 1f else 0.85f
    val stroke = 1.5f * scale
    val inset = stroke / 2f
    drawRoundRect(
        color = if (palette.dark) Color.White.copy(alpha = 0.05f * op)
        else palette.ink.copy(alpha = 0.05f * op),
        topLeft = Offset(inset, inset),
        size = Size(w - stroke, h - stroke),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(stroke),
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.75f * op),
            0.42f to Color.Transparent,
            center = Offset(lx, ly),
            radius = 0.42f * sqrt(w * w + h * h),
        ),
        topLeft = Offset(inset, inset),
        size = Size(w - stroke, h - stroke),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(stroke),
    )
    // 距边缘 14dp 的极细流光内框
    val frameInset = 14.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(
                palette.accent.copy(alpha = 0.42f * op),
                Color.White.copy(alpha = if (palette.dark) 0.10f else 0.55f),
                palette.accent.copy(alpha = 0.18f * op),
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        ),
        topLeft = Offset(frameInset, frameInset),
        size = Size((w - frameInset * 2f).coerceAtLeast(1f), (h - frameInset * 2f).coerceAtLeast(1f)),
        cornerRadius = CornerRadius((corner - frameInset).coerceAtLeast(8f)),
        style = Stroke(0.7.dp.toPx()),
    )
    // 四角微光星芒：避开顶部按钮栏与中央单词区
    val starInset = 22.dp.toPx()
    val topY = 84.dp.toPx().coerceAtMost(h * 0.30f)
    drawCornerSparkle(Offset(starInset, topY), 5.dp.toPx(), palette)
    drawCornerSparkle(Offset(w - starInset, topY), 5.dp.toPx(), palette)
    drawCornerSparkle(Offset(starInset, h - 26.dp.toPx()), 4.dp.toPx(), palette)
    drawCornerSparkle(Offset(w - starInset, h - 26.dp.toPx()), 4.dp.toPx(), palette)
}

private fun DrawScope.drawCornerSparkle(center: Offset, radius: Float, palette: HoloCardPalette) {
    val tint = if (palette.dark) Color.White.copy(alpha = 0.55f) else palette.accent.copy(alpha = 0.45f)
    drawLine(tint, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 0.8.dp.toPx())
    drawLine(tint, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 0.8.dp.toPx())
    drawCircle(Color.White.copy(alpha = 0.7f), radius = 0.9.dp.toPx(), center = center)
}

// ---------------------------------------------------------------------------
// 小预览色块（样式抽屉用；静态光源，不跑帧循环）
// ---------------------------------------------------------------------------

@Composable
fun HoloStyleSwatch(style: HoloCardStyle, modifier: Modifier = Modifier) {
    val palette = rememberHoloPalette(style)
    val dots = remember(style) { makeHoloDots(140, 0xBEEF) }
    Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
        val w = size.width
        val h = size.height
        val corner = 12.dp.toPx()
        val path = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner, corner))) }
        clipPath(path) {
            drawHoloMaterial(
                style = style,
                palette = palette,
                lightX = 0.34f,
                lightY = 0.22f,
                hover = 0f,
                gloss = 1f,
                w = w,
                h = h,
                corner = corner,
                dots = dots,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

private fun c(r: Int, g: Int, b: Int, a: Float = 1f): Color =
    Color(r / 255f, g / 255f, b / 255f, a)

/** 等价于 brightness(0.72) saturate(1.25)：降低明度、提高饱和度，避免 ColorDodge 过曝发白。 */
private fun purified(color: Color, alpha: Float): Color {
    val lum = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return Color(
        red = ((lum + (color.red - lum) * 1.25f) * 0.72f).coerceIn(0f, 1f),
        green = ((lum + (color.green - lum) * 1.25f) * 0.72f).coerceIn(0f, 1f),
        blue = ((lum + (color.blue - lum) * 1.25f) * 0.72f).coerceIn(0f, 1f),
        alpha = color.alpha * alpha,
    )
}

private fun DrawScope.drawEtchedRings(w: Float, h: Float, scale: Float, color: Color) {
    val center = Offset(w / 2f, h / 2f)
    val maxR = sqrt(w * w + h * h) / 2f
    var r = 1.2f * scale
    while (r < maxR) {
        drawCircle(color = color, radius = r, center = center, style = Stroke(1.2f * scale))
        r += 7f * scale
    }
}

private fun GraphicsCanvas.drawEtchedRingMask(w: Float, h: Float, scale: Float, color: Color) {
    val center = Offset(w / 2f, h / 2f)
    val maxR = sqrt(w * w + h * h) / 2f
    val paint = Paint().apply {
        this.color = color
        style = PaintingStyle.Stroke
        strokeWidth = 1.2f * scale
        blendMode = BlendMode.DstIn
    }
    var r = 1.2f * scale
    while (r < maxR) {
        drawCircle(center, r, paint)
        r += 7f * scale
    }
}

private fun DrawScope.maskedHoloFoil(
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

private data class HoloDot(val x: Float, val y: Float, val r: Float, val a: Float)

private fun makeHoloDots(count: Int, seed: Int): List<HoloDot> {
    var s = seed
    fun rnd(): Float {
        s = (s * 1103515245 + 12345) and 0x7fffffff
        return (s % 100000) / 100000f
    }
    return List(count) {
        HoloDot(
            x = rnd(),
            y = rnd(),
            r = 0.45f + rnd() * rnd() * 1.4f,
            a = 0.30f + rnd() * 0.70f,
        )
    }
}

private fun GraphicsCanvas.drawHoloDots(
    dots: List<HoloDot>,
    offX: Float,
    offY: Float,
    w: Float,
    h: Float,
    scale: Float,
) {
    val paint = Paint().apply { blendMode = BlendMode.DstIn }
    for (dot in dots) {
        paint.color = Color.White.copy(alpha = dot.a)
        drawCircle(
            Offset((dot.x + offX / w) * w, (dot.y + offY / h) * h),
            dot.r * scale,
            paint,
        )
    }
}

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

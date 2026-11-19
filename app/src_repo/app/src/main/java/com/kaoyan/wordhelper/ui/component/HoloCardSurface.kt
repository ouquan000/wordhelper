package com.kaoyan.wordhelper.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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
    val panelStrong: Color,
    val panelBorder: Color,
    val frontTop: Color,
    val frontBottom: Color,
)

val LocalHoloCardPalette = staticCompositionLocalOf {
    HoloCardPalette(
        style = HoloCardStyle.PEARL,
        dark = false,
        ink = Color(0xFF111827),
        subInk = Color(0xFF374151),
        accent = Color(0xFF6C5CE7),
        panel = Color.White.copy(alpha = 0.35f),
        panelStrong = Color.White.copy(alpha = 0.55f),
        panelBorder = Color(0xFF111827).copy(alpha = 0.15f),
        frontTop = Color(0xFFFDFCFF),
        frontBottom = Color(0xFFF1EEF8),
    )
}

/** 亮色皮肤的语义色：深墨字 + 半透明白面板（面板不压暗，让全息材质直接透出来）。 */
private val LightSkinInk = Color(0xFF111827)
private val LightSkinSubInk = Color(0xFF374151)
private val LightSkinPanel = Color.White.copy(alpha = 0.35f)
private val LightSkinPanelStrong = Color.White.copy(alpha = 0.55f)
private val LightSkinPanelBorder = Color(0xFF111827).copy(alpha = 0.15f)

/** 暗色皮肤的语义色：近白字 + 低透明度白面板。 */
private val DarkSkinInk = Color(0xFFF8FAFC)
private val DarkSkinSubInk = Color(0xFFCBD5E1)
private val DarkSkinPanel = Color.White.copy(alpha = 0.08f)
private val DarkSkinPanelStrong = Color.White.copy(alpha = 0.12f)
private val DarkSkinPanelBorder = Color.White.copy(alpha = 0.22f)

@Composable
fun rememberHoloPalette(style: HoloCardStyle): HoloCardPalette {
    // 必须读 App 真实明暗：HoloCardTheme 会把 background 换成半透明白面板，
    // 若在此处用 background.luminance() 判断，深色皮肤的卡片会被误判成亮色，
    // 于是内层拿到浅色 palette、外层仍用深色 palette（白字白底）。
    val themeDark = LocalAppDarkTheme.current
    val classicDark = style == HoloCardStyle.CLASSIC && themeDark
    return remember(style, themeDark) {
        when (style) {
            HoloCardStyle.CLASSIC -> if (classicDark) HoloCardPalette(
                style = style,
                dark = true,
                ink = DarkSkinInk,
                subInk = DarkSkinSubInk,
                accent = Color(0xFFAEC4FF),
                panel = DarkSkinPanel,
                panelStrong = DarkSkinPanelStrong,
                panelBorder = DarkSkinPanelBorder,
                frontTop = Color(0xFF1A233C),
                frontBottom = Color(0xFF111A2E),
            ) else HoloCardPalette(
                style = style,
                dark = false,
                ink = LightSkinInk,
                subInk = LightSkinSubInk,
                accent = Color(0xFF2E5CCB),
                panel = LightSkinPanel,
                panelStrong = LightSkinPanelStrong,
                panelBorder = LightSkinPanelBorder,
                frontTop = Color(0xFFEAF2FF),
                frontBottom = Color(0xFFFBFDFF),
            )

            HoloCardStyle.PRISM -> darkPalette(style, themeDark, accent = Color(0xFF9DB8FF))
            HoloCardStyle.RAINBOW -> darkPalette(style, themeDark, accent = Color(0xFFFFD9F0))
            HoloCardStyle.SPARKLE -> darkPalette(style, themeDark, accent = Color(0xFF8BF3FF))
            HoloCardStyle.ETCHED -> darkPalette(style, themeDark, accent = Color(0xFFE6E6EA))
            HoloCardStyle.PEARL -> HoloCardPalette(
                style = style,
                dark = false,
                ink = LightSkinInk,
                subInk = LightSkinSubInk,
                accent = Color(0xFF6C5CE7),
                panel = LightSkinPanel,
                panelStrong = LightSkinPanelStrong,
                panelBorder = LightSkinPanelBorder,
                frontTop = if (themeDark) Color(0xFFEDEAF7) else Color(0xFFFFFAFC),
                frontBottom = if (themeDark) Color(0xFFDCE4F2) else Color(0xFFF5F4FB),
            )

            // chrome 现在是亮银皮肤：归亮色组，不能按「深色款」压成白字。
            HoloCardStyle.CHROME -> HoloCardPalette(
                style = style,
                dark = false,
                ink = LightSkinInk,
                subInk = LightSkinSubInk,
                accent = Color(0xFF3A3F4B),
                panel = LightSkinPanel,
                panelStrong = LightSkinPanelStrong,
                panelBorder = LightSkinPanelBorder,
                frontTop = if (themeDark) Color(0xFFE1E8F1) else Color(0xFFF2F6FB),
                frontBottom = if (themeDark) Color(0xFFB7C4D5) else Color(0xFFD3DAE7),
            )
        }
    }
}

private fun darkPalette(style: HoloCardStyle, themeDark: Boolean, accent: Color): HoloCardPalette {
    val (top, bottom) = when (style) {
        HoloCardStyle.PRISM -> Color(0xFF202C83) to Color(0xFF101647)
        HoloCardStyle.RAINBOW -> Color(0xFF26303C) to Color(0xFF141923)
        HoloCardStyle.SPARKLE -> Color(0xFF161F43) to Color(0xFF0A1429)
        else -> Color(0xFF252A34) to Color(0xFF121820)
    }
    return HoloCardPalette(
        style, true, DarkSkinInk, DarkSkinSubInk, accent, DarkSkinPanel,
        DarkSkinPanelStrong, DarkSkinPanelBorder,
        if (themeDark) top else lerp(top, Color(0xFF697A9B), 0.13f), bottom
    )
}

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
                surfaceVariant = palette.panelStrong,
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
                surfaceVariant = palette.panelStrong,
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


@Stable
class HoloLightState {
    var tiltX by mutableFloatStateOf(0f)
        internal set
    var tiltY by mutableFloatStateOf(0f)
        internal set
    internal var timeSeconds: () -> Float = StillLight
}

private val StillLight: () -> Float = { 0f }

/** One elapsed-time source for the background, main card, and visible preview. */
@Composable
fun rememberCardLightTime(enabled: Boolean): () -> Float {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycle, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (true) {
                withFrameNanos { now ->
                    if (previous == 0L) previous = now
                    val elapsed = now - previous
                    // Slow optical effects are independent of the 60 Hz flip transform.
                    if (elapsed >= 31_000_000L) {
                        seconds.floatValue += (elapsed / 1_000_000_000f).coerceAtMost(0.08f)
                        previous = now
                    }
                }
            }
        }
    }
    return remember(seconds) { { seconds.floatValue } }
}

@Composable
fun rememberHoloLightState(
    motionEnabled: Boolean,
    timeSeconds: (() -> Float)? = null
): HoloLightState {
    val state = remember { HoloLightState() }
    val clock = timeSeconds ?: rememberCardLightTime(motionEnabled)
    SideEffect { state.timeSeconds = clock }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val screenRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    LaunchedEffect(motionEnabled, lifecycle, context, screenRotation) {
        if (!motionEnabled) {
            state.tiltX = 0f
            state.tiltY = 0f
            return@LaunchedEffect
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@LaunchedEffect
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val listener = CardTiltListener(state, screenRotation)
            try {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                awaitCancellation()
            } finally {
                manager.unregisterListener(listener)
                state.tiltX = 0f
                state.tiltY = 0f
            }
        }
    }
    return state
}

private class CardTiltListener(
    private val light: HoloLightState,
    private val screenRotation: Int
) : SensorEventListener {
    private val matrix = FloatArray(9)
    private var baseline: Offset? = null

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val gravity = if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, v)
            Offset(matrix[6], matrix[7])
        } else {
            val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (!length.isFinite() || length < 1e-3f) return
            Offset(v[0] / length, v[1] / length)
        }
        if (!gravity.x.isFinite() || !gravity.y.isFinite()) return
        val oriented = when (screenRotation) {
            Surface.ROTATION_90 -> Offset(-gravity.y, gravity.x)
            Surface.ROTATION_180 -> -gravity
            Surface.ROTATION_270 -> Offset(gravity.y, -gravity.x)
            else -> gravity
        }
        val neutral = baseline ?: run {
            baseline = oriented
            return
        }
        val delta = (oriented - neutral) / 0.29f
        light.tiltX = settleTilt(light.tiltX, delta.x)
        light.tiltY = settleTilt(light.tiltY, delta.y)
    }

    private fun settleTilt(current: Float, raw: Float): Float {
        val target = if (abs(raw) < 0.035f) 0f else raw.coerceIn(-1f, 1f)
        val next = current + 0.14f * (target - current)
        return if (abs(target - next) < 0.002f) target else next
    }
}

@Composable
fun HoloCardSurface(
    style: HoloCardStyle,
    light: HoloLightState,
    isBack: Boolean,
    glossHigh: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(19.dp),
    motionAmount: Float = 1f,
    turn: () -> Float = StillLight,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = rememberHoloPalette(style)
    Box(
        modifier.clip(shape).holoFinish(
            palette = palette,
            intensity = if (glossHigh) 1f else 0.62f,
            cornerSize = (shape as? RoundedCornerShape)?.topStart ?: CornerSize(19.dp),
            motionAmount = motionAmount,
            timeSeconds = { light.timeSeconds() },
            lightX = { light.tiltX },
            lightY = { light.tiltY },
            turn = turn,
            back = isBack,
        ),
        content = content,
    )
}

@Composable
fun HoloStyleSwatch(
    style: HoloCardStyle,
    modifier: Modifier = Modifier,
    timeSeconds: () -> Float = StillLight,
    glossHigh: Boolean = true
) {
    val palette = rememberHoloPalette(style)
    Box(
        modifier.clip(RoundedCornerShape(7.dp)).holoFinish(
            palette, if (glossHigh) 1f else 0.62f, CornerSize(7.dp), 1f, timeSeconds
        )
    )
}

private data class FoilGrain(val x: Float, val y: Float, val radius: Float, val phase: Float)
private val diamondGrain = Random(73).let { random ->
    List(185) {
        FoilGrain(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
    }
}

private fun Modifier.holoFinish(
    palette: HoloCardPalette,
    intensity: Float,
    cornerSize: CornerSize,
    motionAmount: Float,
    timeSeconds: () -> Float,
    lightX: () -> Float = StillLight,
    lightY: () -> Float = StillLight,
    turn: () -> Float = StillLight,
    back: Boolean = false
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    if (w < 1f || h < 1f) return@drawWithCache onDrawBehind {}
    val style = palette.style
    val bleed = maxOf(w, h)
    val overscan = Offset(-bleed, -bleed)
    val fill = Size(w + 2f * bleed, h + 2f * bleed)
    val dark = palette.dark
    val base = Brush.linearGradient(
        listOf(palette.frontTop, lerp(palette.frontTop, palette.frontBottom, 0.55f), palette.frontBottom),
        end = Offset(w * 0.8f, h)
    )
    fun mist(color: Color, x: Float, y: Float, radius: Float) = Brush.radialGradient(
        0f to color, 0.54f to color.copy(alpha = color.alpha * 0.34f),
        1f to color.copy(alpha = 0f),
        center = Offset(w * x, h * y), radius = bleed * radius
    )
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.43f else 0.92f),
        0.46f to palette.accent.copy(alpha = if (dark) 0.12f else 0.15f),
        1f to palette.accent.copy(alpha = 0.50f),
        end = Offset(w * 0.85f, h)
    )

    val fields = when (style) {
        HoloCardStyle.CLASSIC -> listOf(
            mist(Color(0xFF82AFEA).copy(alpha = if (dark) 0.26f else 0.37f), 0.09f, 0.13f, 0.62f),
            mist(Color.White.copy(alpha = if (dark) 0.080f else 0.83f), 0.78f, 0.72f, 0.59f)
        )
        HoloCardStyle.PRISM -> listOf(
            mist(Color(0xFF385BF3).copy(alpha = 0.68f), 0.18f, 0.10f, 0.63f),
            mist(Color(0xFF7E58F9).copy(alpha = 0.39f), 0.91f, 0.75f, 0.54f),
            Brush.linearGradient(
                0f to Color.Transparent, 0.38f to Color.Transparent,
                0.52f to Color(0xFF6FCEFF).copy(alpha = 0.20f),
                0.65f to Color(0xFF8497FF).copy(alpha = 0.10f), 1f to Color.Transparent,
                start = Offset(w * 0.51f, -h * 0.13f), end = Offset(w * 0.86f, h * 0.95f)
            )
        )
        HoloCardStyle.RAINBOW -> listOf(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.21f to Color.Transparent,
                0.31f to Color(0xFFDB76C8).copy(alpha = 0.46f),
                0.39f to Color(0xFFEB9F68).copy(alpha = 0.49f),
                0.48f to Color(0xFFADC865).copy(alpha = 0.40f),
                0.55f to Color(0xFF47C9C3).copy(alpha = 0.41f),
                0.63f to Color(0xFF647CE7).copy(alpha = 0.43f),
                0.75f to Color.Transparent, 1f to Color.Transparent,
                start = Offset(-w * 0.40f, h * 0.44f), end = Offset(w * 1.40f, h * 0.69f)
            )
        )
        HoloCardStyle.PEARL -> listOf(
            mist(Color(0xFFF1CBEC).copy(alpha = if (back) 0.58f else 0.70f), 0.91f, 0.24f, 0.66f),
            mist(Color(0xFFBCE7DE).copy(alpha = 0.65f), 0.12f, 0.70f, 0.65f),
            mist(Color(0xFFF7DCBC).copy(alpha = 0.60f), 0.86f, 0.94f, 0.57f)
        )
        HoloCardStyle.SPARKLE -> listOf(
            mist(Color(0xFF4F5BBE).copy(alpha = 0.43f), 0.78f, 0.18f, 0.65f),
            mist(Color(0xFF308CB1).copy(alpha = 0.30f), 0.17f, 0.92f, 0.50f)
        )
        HoloCardStyle.CHROME -> listOf(
            mist(Color.White.copy(alpha = 0.88f), 0.36f, 0.12f, 0.53f),
            mist(Color(0xFFA6B4D3).copy(alpha = 0.57f), 0.93f, 0.85f, 0.60f)
        )
        HoloCardStyle.ETCHED -> listOf(
            mist(Color(0xFFA7AFBE).copy(alpha = 0.23f), 0.77f, 0.77f, 0.49f),
            mist(Color(0xFF7693AA).copy(alpha = 0.17f), 0.14f, 0.21f, 0.38f)
        )
    }

    val silver = if (style == HoloCardStyle.CHROME) liquidSilver else null
    val silverShade = if (silver != null) ColorFilter.tint(Color(0xFF5D738E)) else null
    val reflectionSpan = IntSize((w * 1.36f).roundToInt(), (h * 1.28f).roundToInt())
    val engraving = if (style == HoloCardStyle.ETCHED) {
        Path().apply {
            val origin = Offset(w * 0.81f, h * 0.73f)
            val step = maxOf(2.dp.toPx(), w * 0.012f)
            for (ring in 0..44) {
                val r = w * 0.02f + step * ring
                addOval(Rect(center = origin, radius = r))
            }
        }
    } else null
    val engraveInk = Brush.linearGradient(
        listOf(Color.Transparent, Color(0xFFB2BBD4).copy(alpha = 0.28f), Color.Transparent),
        start = Offset(w * 0.40f, h * 0.52f), end = Offset(w, h * 0.99f)
    )
    val rimInset = 0.7.dp.toPx()
    val corner = (cornerSize.toPx(size, this) - rimInset).coerceIn(0f, minOf(w, h) * 0.5f)
    val rimSize = Size((w - 2 * rimInset).coerceAtLeast(0f), (h - 2 * rimInset).coerceAtLeast(0f))

    onDrawBehind {
        val t = if (motionAmount == 0f) 0f else timeSeconds()
        val sensorX = lightX()
        val sensorY = lightY()
        val tiltSweep = turn()
        val drift = sin(t * 0.29f + 0.38f) * motionAmount
        val swell = sin(t * 0.19f + 1.7f) * motionAmount
        val dx = w * (sensorX * 0.14f + tiltSweep * 0.21f)
        val dy = h * sensorY * 0.10f

        drawRect(base)
        if (engraving != null) {
            drawPath(engraving, engraveInk, alpha = 0.70f, style = Stroke(0.52.dp.toPx()))
        }
        fields.forEachIndexed { index, brush ->
            val direction = if (index % 2 == 0) 1f else -1f
            val x = dx + drift * w * (if (style == HoloCardStyle.RAINBOW) 0.19f else 0.09f) * direction
            val y = dy + swell * h * (if (style == HoloCardStyle.CHROME) 0.12f else 0.07f) * direction
            val alpha = intensity * (0.90f + 0.10f * swell * direction)
            translate(x, y) {
                drawRect(brush, topLeft = overscan, size = fill, alpha = alpha)
            }
        }
        if (silver != null) {
            val position = IntOffset(
                (-0.18f * w + dx * 0.56f + drift * w * 0.06f).roundToInt(),
                (-0.14f * h + dy * 0.52f + swell * h * 0.07f).roundToInt()
            )
            drawImage(silver.shade, dstOffset = position, dstSize = reflectionSpan,
                alpha = intensity * 0.70f, colorFilter = silverShade)
            drawImage(silver.gloss, dstOffset = position, dstSize = reflectionSpan,
                alpha = intensity)
        }
        if (style == HoloCardStyle.SPARKLE) {
            val glintX = 0.69f + drift * 0.12f + sensorX * 0.19f
            val glintY = 0.73f + swell * 0.08f + sensorY * 0.15f
            for (grain in diamondGrain) {
                val distance = abs(grain.x - glintX) + abs(grain.y - glintY)
                val focus = (1f - distance * 2.4f).coerceIn(0f, 1f)
                val shimmer = 0.70f + 0.30f * sin(t * 0.58f + grain.phase * 6.28f)
                val alpha = (0.091f + focus * grain.phase * shimmer * 0.64f) * intensity
                val radius = minOf(w * 0.008f, (0.26f + grain.radius * 0.71f).dp.toPx())
                drawCircle(
                    color = if (grain.phase < 0.42f) Color(0xFFC2CDFF) else Color(0xFFF5FBFF),
                    radius = radius,
                    center = Offset(grain.x * w, grain.y * h),
                    alpha = alpha,
                )
            }
        }
        drawRoundRect(
            brush = rim, topLeft = Offset(rimInset, rimInset), size = rimSize,
            cornerRadius = CornerRadius(corner), style = Stroke(rimInset)
        )
    }
}

private data class SilverOptics(val gloss: ImageBitmap, val shade: ImageBitmap)

// Neutral masks keep the reflected highlights independent of the card's day/night pigment.
private val liquidSilver: SilverOptics by lazy {
    val width = 144
    val height = 224
    val gloss = IntArray(width * height)
    val shade = IntArray(width * height)
    for (j in 0 until height) {
        val y = j.toFloat() / (height - 1)
        for (i in 0 until width) {
            val x = i.toFloat() / (width - 1)
            val curve = 0.67f + 0.109f * sin(x * 6.65f + 0.84f) +
                0.026f * sin(x * 13.9f - 0.45f)
            val distance = y - curve
            val widthAtX = 0.046f + 0.017f * sin(x * 5.8f + 0.63f)
            val primary = bell((distance - 0.027f) / widthAtX)
            val highlightTail = bell((distance - 0.066f) / 0.10f) * 0.37f
            val secondary = bell((y - 0.23f - 0.040f * sin(x * 5.2f)) / 0.078f) * 0.36f
            val darkShoulder = bell((distance + 0.050f) / (widthAtX * 1.36f)) * 0.70f
            val lowerShoulder = bell((distance - 0.191f) / 0.084f) * 0.37f
            val index = j * width + i
            gloss[index] = silverMask(primary * 0.88f + highlightTail + secondary)
            shade[index] = silverMask(darkShoulder + lowerShoulder)
        }
    }
    fun bitmap(pixels: IntArray) =
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    SilverOptics(bitmap(gloss), bitmap(shade))
}

private fun bell(value: Float): Float = exp(-value * value)

private fun silverMask(alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00FFFFFF

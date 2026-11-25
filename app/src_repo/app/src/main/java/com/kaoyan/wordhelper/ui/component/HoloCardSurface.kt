package com.kaoyan.wordhelper.ui.component

import android.content.Context
import android.hardware.Sensor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.awaitCancellation

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
            val listener = CardTiltListener(screenRotation) { x, y ->
                state.tiltX = settleTilt(state.tiltX, x)
                state.tiltY = settleTilt(state.tiltY, y)
            }
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

private val HoloCardStyle.finish: HoloFinish
    get() = when (this) {
        HoloCardStyle.CLASSIC -> HoloFinish.CLASSIC
        HoloCardStyle.PRISM -> HoloFinish.PRISM
        HoloCardStyle.RAINBOW -> HoloFinish.RAINBOW
        HoloCardStyle.PEARL -> HoloFinish.PEARL
        HoloCardStyle.SPARKLE -> HoloFinish.SPARKLE
        HoloCardStyle.CHROME -> HoloFinish.CHROME
        HoloCardStyle.ETCHED -> HoloFinish.ETCHED
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
): Modifier = drawHoloFinish(
    palette.style.finish, palette.dark, palette.frontTop, palette.frontBottom, palette.accent,
    intensity, cornerSize, motionAmount, timeSeconds, lightX, lightY, turn, back
)

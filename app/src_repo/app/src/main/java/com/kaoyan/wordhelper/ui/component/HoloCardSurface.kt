package com.kaoyan.wordhelper.ui.component

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
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

/** 灵敏度：相对基准倾斜 ±22°（sin 22° ≈ 0.375）即打满整段光程。 */
private const val SENSOR_FULL_TILT_SIN = 0.375f

/** 光源映射跨度：以 0.5 为中心 ±0.42，恰好落在 0.08..0.92。 */
private const val SENSOR_LIGHT_SPAN = 0.42f
private const val SENSOR_LIGHT_MIN = 0.08f
private const val SENSOR_LIGHT_MAX = 0.92f

/** 一阶低通系数：SENSOR_DELAY_GAME（≈50Hz）下约 0.1s 收敛，压手抖但不拖泥带水。 */
private const val SENSOR_LOW_PASS_ALPHA = 0.28f

/** 静止时的呼吸微动比例：只让光斑轻轻「活」着，绝不抢倾斜主体。 */
private const val SENSOR_BREATH_RATIO = 0.04f

/**
 * 虚拟光源。卡片本身永远水平（rotationX = 0），所有「流光」都来自这里。
 *
 * - cardMotionEnabled = true：设备重力矢量相对基准的偏移驱动光源，叠加极微弱呼吸微动；
 * - cardMotionEnabled = false：锁定 (0.34, 0.22)，不注册传感器、也不启动帧循环。
 *
 * 姿态一律用「重力矢量投影」表达，不再解算欧拉角，因此竖屏 / 斜握 / 躺卧都不会出现万向节死锁。
 */
@Stable
class HoloLightState {
    val lightX = mutableFloatStateOf(0.34f)
    val lightY = mutableFloatStateOf(0.22f)
    val hover = mutableFloatStateOf(0f)
    val breath = mutableFloatStateOf(0f)

    internal var motionEnabled = true
        private set
    private var velocityX = 0f
    private var velocityY = 0f
    private var time = 0f

    /** 相对基准姿态的重力分量偏移（单位重力，约 -1..1）：gx 管左右、gy 管前后。 */
    private var gravityDeltaX = 0f
    private var gravityDeltaY = 0f

    /** 是否已有有效姿态；没有时退回极微弱呼吸，保证老设备观感不变。 */
    private var hasTilt = false

    internal fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
        if (!enabled) {
            clearTilt()
            lightX.floatValue = 0.34f
            lightY.floatValue = 0.22f
            hover.floatValue = 0f
            breath.floatValue = 0f
            velocityX = 0f
            velocityY = 0f
        }
    }

    /**
     * 传感器回调入口（已在回调侧做过低通滤波）。
     * 这里只登记「相对基准的重力偏移」，真正的位置变化仍然交给 [step] 里的弹簧，避免跳变。
     */
    internal fun onGravityDelta(dx: Float, dy: Float) {
        gravityDeltaX = dx.coerceIn(-1f, 1f)
        gravityDeltaY = dy.coerceIn(-1f, 1f)
        hasTilt = true
    }

    /** 注销监听 / 关闭动效 / 重置基准时清空姿态，避免残留偏移把光源钉在一边。 */
    internal fun clearTilt() {
        gravityDeltaX = 0f
        gravityDeltaY = 0f
        hasTilt = false
    }

    internal fun step(dt: Float) {
        time += dt
        // 呼吸：极低幅度、两个互质周期，避免机械感，但肉眼几乎只是「光在呼吸」。
        val lissX = 0.25f * sin(2f * PI.toFloat() * time / 12.4f)
        val lissY = 0.235f * sin(2f * PI.toFloat() * time / 10.3f + 1.1f)

        val targetX: Float
        val targetY: Float
        if (hasTilt) {
            // 重力偏移 → 归一化倾斜量 → 0.5 为中心的 ±0.42 光程，再叠极小呼吸。
            val baseX = 0.5f + (gravityDeltaX / SENSOR_FULL_TILT_SIN).coerceIn(-1f, 1f) * SENSOR_LIGHT_SPAN
            val baseY = 0.5f + (gravityDeltaY / SENSOR_FULL_TILT_SIN).coerceIn(-1f, 1f) * SENSOR_LIGHT_SPAN
            targetX = (baseX + lissX * SENSOR_BREATH_RATIO).coerceIn(SENSOR_LIGHT_MIN, SENSOR_LIGHT_MAX)
            targetY = (baseY + lissY * SENSOR_BREATH_RATIO).coerceIn(SENSOR_LIGHT_MIN, SENSOR_LIGHT_MAX)
        } else {
            // 没有可用姿态传感器：光斑停在中央做极微弱呼吸，不再大幅度李萨如巡游。
            targetX = (0.5f + lissX * SENSOR_BREATH_RATIO).coerceIn(SENSOR_LIGHT_MIN, SENSOR_LIGHT_MAX)
            targetY = (0.5f + lissY * SENSOR_BREATH_RATIO).coerceIn(SENSOR_LIGHT_MIN, SENSOR_LIGHT_MAX)
        }
        // 无过冲阻尼弹簧：stiffness 160，damping 25.3（≈ 2√160），几乎不额外滞后。
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

    // 重力传感器：只有开启卡片动效时才注册；跟随宿主生命周期，离开前台即注销，回来重新学习基准。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(motionEnabled, context, lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.let(::pickTiltSensor)
        if (!motionEnabled || sensorManager == null || sensor == null) {
            // 关闭动效 / 没有传感器服务 / 设备缺这两种传感器：静默降级为极微弱呼吸，不注册任何监听。
            state.clearTilt()
            return@DisposableEffect onDispose { }
        }
        val listener = HoloTiltSensorListener(sensor.type, state)
        // registered 标记：ON_RESUME 与「创建时已处于 RESUMED」两条路径只会注册一次，避免重复注册。
        var registered = false
        val register = {
            if (!registered) {
                listener.resetBaseline()
                registered = sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME,
                )
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 回到前台：重新注册并重置重力基准，避免暂停期间累积的偏移。
                Lifecycle.Event.ON_RESUME -> register()
                // 退到后台：立刻注销，省电且不让后台数据继续推光源。
                Lifecycle.Event.ON_PAUSE -> {
                    if (registered) {
                        sensorManager.unregisterListener(listener)
                        registered = false
                    }
                    listener.resetBaseline()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
            state.clearTilt()
        }
    }
    return state
}

/** 按优先级挑选重力传感器：重力计 → 游戏旋转矢量，全部缺失时返回 null。 */
private fun pickTiltSensor(manager: SensorManager): Sensor? =
    manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

/**
 * 重力监听器：把设备坐标系下的重力矢量相对基准的偏移交给 [HoloLightState.onGravityDelta]。
 *
 * - 首选 TYPE_GRAVITY；缺失时退到 TYPE_GAME_ROTATION_VECTOR，取旋转矩阵第三行 [R6,R7,R8]
 *   （即世界竖直方向在设备坐标系中的投影，等价于单位重力）；
 * - 恢复注册后的首个有效事件只记基准（gx0, gy0），之后输出 dx = gx-gx0、dy = gy-gy0；
 * - 一阶低通 alpha = 0.28（SENSOR_DELAY_GAME ≈ 50Hz）；
 * - values 长度不足、矩阵不可用、全零向量等异常输入一律静默丢弃，绝不抛异常。
 */
private class HoloTiltSensorListener(
    private val sensorType: Int,
    private val state: HoloLightState,
) : SensorEventListener {

    private val rotationMatrix = FloatArray(9)
    private var hasBase = false
    private var baseGx = 0f
    private var baseGy = 0f
    private var hasFiltered = false
    private var filteredDx = 0f
    private var filteredDy = 0f

    /** 重置重力基准：重新注册 / 回到前台时调用，把当前姿态认成新的中立位。 */
    fun resetBaseline() {
        hasBase = false
        hasFiltered = false
        filteredDx = 0f
        filteredDy = 0f
        state.clearTilt()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor?.type != sensorType) return
        val values = e.values ?: return
        if (!readGravityDelta(values)) return
        if (!hasFiltered) {
            // 首帧直接对齐，避免从 0 缓慢爬升造成「回前台先偏一下」。
            filteredDx = rawDx
            filteredDy = rawDy
            hasFiltered = true
        } else {
            filteredDx += (rawDx - filteredDx) * SENSOR_LOW_PASS_ALPHA
            filteredDy += (rawDy - filteredDy) * SENSOR_LOW_PASS_ALPHA
        }
        state.onGravityDelta(filteredDx, filteredDy)
    }

    private var rawDx = 0f
    private var rawDy = 0f

    /** 读取相对基准的重力偏移到 [rawDx]/[rawDy]；返回 false 表示本帧不可用（含基准帧 / 坏帧）。 */
    private fun readGravityDelta(values: FloatArray): Boolean {
        if (values.size < 3) return false
        val gx: Float
        val gy: Float
        if (sensorType == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val ok = runCatching {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
            }.isSuccess
            if (!ok) return false
            // R 是 device→world 的旋转：世界 Z 轴（竖直）在设备系的投影 = 第三行 R[6..8]。
            // 直接用它当单位重力，天然没有欧拉角、也没有万向节死锁。
            gx = rotationMatrix[6]
            gy = rotationMatrix[7]
        } else {
            var ax = values[0]
            var ay = values[1]
            val az = values[2]
            val norm = sqrt(ax * ax + ay * ay + az * az)
            // 全零 / 接近失重：归一化会放大噪声，直接丢帧。
            if (!norm.isFinite() || norm < 1e-3f) return false
            ax /= norm
            ay /= norm
            gx = ax
            gy = ay
        }
        if (!gx.isFinite() || !gy.isFinite()) return false
        if (!hasBase) {
            baseGx = gx
            baseGy = gy
            hasBase = true
            return false
        }
        rawDx = (gx - baseGx).coerceIn(-1f, 1f)
        rawDy = (gy - baseGy).coerceIn(-1f, 1f)
        return true
    }
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
    /** 心流档位 0..4 与连续热力 0..1：驱动外框微光晕、流光与暖色氛围。 */
    paceTier: Int = 1,
    paceHeat: Float = 0f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val palette = rememberHoloPalette(style)
    val dots = remember(style) { makeHoloDots(950, 0xC0FFEE) }
    val gloss = if (glossHigh) 1f else 0.55f
    val baseColor = if (palette.dark) Color(0xFF10142B) else palette.frontBottom

    // 卡面与进度条共用同一套 420ms 升温 / 4200ms 降温节奏。
    val paceTierAnim = remember { Animatable(paceTier.coerceIn(0, 4).toFloat()) }
    LaunchedEffect(paceTier, light.motionEnabled) {
        val target = paceTier.coerceIn(0, 4).toFloat()
        if (!light.motionEnabled) {
            paceTierAnim.snapTo(target)
            return@LaunchedEffect
        }
        val from = paceTierAnim.value
        if (from == target) return@LaunchedEffect
        val spec = if (target > from) {
            tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
        } else {
            tween<Float>(durationMillis = 4200, easing = LinearOutSlowInEasing)
        }
        paceTierAnim.animateTo(target, spec)
    }
    val paceHeatAnim = remember { Animatable(paceHeat.coerceIn(0f, 1f)) }
    LaunchedEffect(paceHeat, light.motionEnabled) {
        val target = paceHeat.coerceIn(0f, 1f)
        if (!light.motionEnabled) {
            paceHeatAnim.snapTo(target)
            return@LaunchedEffect
        }
        val from = paceHeatAnim.value
        if (from == target) return@LaunchedEffect
        val spec = if (target > from) {
            tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
        } else {
            tween<Float>(durationMillis = 4200, easing = LinearOutSlowInEasing)
        }
        paceHeatAnim.animateTo(target, spec)
    }

    Box(modifier = modifier) {
        // 心流暖色外发光：随 tier/heat 抬升，卡背自动收敛到约 35%，只做氛围不抢文字。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val pace = (
                        paceTierAnim.value / 4f * 0.42f +
                            paceHeatAnim.value * 0.58f
                        ).coerceIn(0f, 1f) * if (isBack) 0.62f else 1f
                    this.shape = shape
                    clip = false
                    shadowElevation = (8f + 16f * pace) * density
                    ambientShadowColor = Color(0xFFFFB066).copy(alpha = 0.20f + 0.16f * pace)
                    spotShadowColor = Color(0xFFFFB066).copy(alpha = 0.24f + 0.20f * pace)
                }
        )
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
        // 卡面环境溢光：略大于卡片轮廓的柔光，颜色由本款材质的 accent/frontTop 混合，
        // 常态约 0.14、心流高燃约 0.21。放在三层阴影与卡面之间，只在卡外缘发光，
        // 不裁切也不遮挡卡面任何内容。
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { clip = false }
        ) {
            drawAmbientSpill(
                palette = palette,
                lightX = light.lightX.floatValue,
                lightY = light.lightY.floatValue,
                pace = (
                    paceTierAnim.value / 4f * 0.42f +
                        paceHeatAnim.value * 0.58f
                    ).coerceIn(0f, 1f) * if (isBack) 0.62f else 1f,
                isBack = isBack,
            )
        }
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
                        pace = (
                            paceTierAnim.value / 4f * 0.42f +
                                paceHeatAnim.value * 0.58f
                            ).coerceIn(0f, 1f) * if (isBack) 0.62f else 1f,
                    )
                }
            }
            // 卡背：材质之上、内容之下铺一层与卡片同圆角的全幅均匀暗化。
            // 只做整体压暗（让浅色卡背文字站得住、全息流光仍透出来），
            // 不再画任何内嵌小矩形/内衬/描边——那就是用户报的「黑框」。
            // 空 Box 不吃触摸，点击翻面、滚动、重写区照旧落在 content() 里。
            if (isBack) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(Color(0xFF0A0F1D).copy(alpha = 0.36f))
                )
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
    pace: Float = 0f,
) {
    val lx = lightX * w
    val ly = lightY * h
    val scale = w / 440f
    val diagonal = sqrt(w * w + h * h)

    drawHoloBase(style, palette, lx, ly, w, h, scale, diagonal)
    drawHoloFoil(style, lx, ly, hover, gloss, w, h, scale, diagonal, dots)
    drawHoloGlare(style, lx, ly, hover, gloss, w, h, diagonal)
    drawHoloPaceAmbience(palette, lx, ly, pace, w, h)
    drawHoloRim(palette, lx, ly, hover, w, h, corner, scale, pace)
}

/**
 * 心流氛围：底部暖色漫反射 + 跟随光源的一抹暖金。只叠低透明度颜色，
 * 不改变底层材质本身，所以深色不脏、浅色不黄。
 */
private fun DrawScope.drawHoloPaceAmbience(
    palette: HoloCardPalette,
    lx: Float,
    ly: Float,
    pace: Float,
    w: Float,
    h: Float,
) {
    val strength = pace.coerceIn(0f, 1f)
    if (strength <= 0.01f) return
    val warm = if (palette.dark) Color(0xFFFFA95E) else Color(0xFFFFC98A)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                warm.copy(alpha = 0.13f * strength),
                Color.Transparent
            ),
            center = Offset(w * 0.5f, h * 1.04f),
            radius = max(w, h) * 0.92f
        )
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFE3B8).copy(alpha = 0.10f * strength),
                Color.Transparent
            ),
            center = Offset(lx, ly),
            radius = w * 0.55f
        ),
        radius = w * 0.55f,
        center = Offset(lx, ly)
    )
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

/**
 * 卡面环境溢光：三层由宽到窄的圆角描边叠出羽化外晕，重心跟随光源。
 * 常态 alpha 0.14，心流高燃最高 0.21；卡背自动收敛到约 62%。
 */
private fun DrawScope.drawAmbientSpill(
    palette: HoloCardPalette,
    lightX: Float,
    lightY: Float,
    pace: Float,
    isBack: Boolean,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val heat = pace.coerceIn(0f, 1f) * if (isBack) 0.62f else 1f
    val alpha = (0.14f + 0.07f * heat).coerceIn(0f, 0.21f)
    // 颜色来自当前皮肤，而不是写死一层白/蓝。
    val tint = lerp(palette.accent, palette.frontTop, 0.45f)
    val lx = lightX.coerceIn(0f, 1f) * w
    val ly = lightY.coerceIn(0f, 1f) * h
    val expand = 3.dp.toPx()
    val corner = 28.dp.toPx()
    val radius = 1.15f * sqrt(w * w + h * h)
    val strokeWidths = floatArrayOf(22f, 13f, 6f)
    strokeWidths.forEachIndexed { index, widthDp ->
        val falloff = 1f - index * 0.22f
        drawRoundRect(
            brush = Brush.radialGradient(
                0f to tint.copy(alpha = alpha * falloff),
                0.50f to tint.copy(alpha = alpha * falloff * 0.62f),
                0.82f to tint.copy(alpha = alpha * falloff * 0.25f),
                1f to Color.Transparent,
                center = Offset(lx, ly),
                radius = radius,
            ),
            topLeft = Offset(-expand, -expand),
            size = Size(w + expand * 2f, h + expand * 2f),
            cornerRadius = CornerRadius(corner + expand, corner + expand),
            style = Stroke(widthDp.dp.toPx()),
        )
    }
}

private fun DrawScope.drawHoloRim(    palette: HoloCardPalette,
    lx: Float,
    ly: Float,
    hover: Float,
    w: Float,
    h: Float,
    corner: Float,
    scale: Float,
    pace: Float = 0f,
) {
    val op = (0.55f + hover * 0.45f + 0.30f * pace.coerceIn(0f, 1f)) *
        if (palette.dark) 1f else 0.85f
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
    // 1dp 外缘高光：accent 染色 + 重心随 light.lightX/lightY 游走，不是固定白边。
    val rimStroke = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.38f * op),
            0.30f to palette.accent.copy(alpha = 0.30f * op),
            0.72f to palette.accent.copy(alpha = 0.10f * op),
            1f to Color.Transparent,
            center = Offset(lx, ly),
            radius = 0.60f * sqrt(w * w + h * h),
        ),
        topLeft = Offset(rimStroke / 2f, rimStroke / 2f),
        size = Size(w - rimStroke, h - rimStroke),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(rimStroke),
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

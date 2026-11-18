package com.kaoyan.wordhelper.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 学习页最底层的「暗夜烟雾」氛围模式。
 *
 * AUTO 跟随当前卡片材质（一款皮肤一套色雾），另外四套是显式锁定的独立氛围：
 * 选独立模式后不再随皮肤变化。
 */
enum class AmbientBackdropMode(val id: String, val label: String) {
    AUTO("auto", "跟随卡片"),
    AURORA("aurora", "极光暗雾"),
    ABYSS("abyss", "深海波光"),
    EMBER("ember", "星云余烬"),
    VELVET("velvet", "纯黑丝绒");

    companion object {
        fun fromId(id: String?): AmbientBackdropMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 一层烟雾：颜色索引 + 中心点（相对宽高）+ 半径（相对长边）+ 缓慢漂移轨道。
 * 全部是编译期常量，绘制时不会每帧新建对象。
 */
private class AmbientLayer(
    val colorIndex: Int,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val orbitX: Float,
    val orbitY: Float,
    val speed: Float,
    val phase: Float,
)

private val AmbientLayers = listOf(
    AmbientLayer(0, 0.20f, 0.14f, 1.05f, 0.060f, 0.045f, 0.020f, 0.0f),
    AmbientLayer(1, 0.82f, 0.20f, 0.95f, 0.070f, 0.040f, 0.016f, 1.1f),
    AmbientLayer(2, 0.48f, 0.56f, 1.15f, 0.090f, 0.060f, 0.012f, 2.3f),
    AmbientLayer(3, 0.12f, 0.84f, 0.90f, 0.050f, 0.070f, 0.024f, 3.4f),
    AmbientLayer(4, 0.88f, 0.86f, 1.00f, 0.060f, 0.050f, 0.018f, 4.6f),
    AmbientLayer(5, 0.62f, 0.02f, 0.80f, 0.080f, 0.040f, 0.028f, 5.5f),
)

/** 每层峰值 alpha 0.12–0.18；边缘在 1.0 处完全羽化到透明。 */
private val AmbientLayerAlphas = floatArrayOf(
    0.16f, 0.14f, 0.18f, 0.13f, 0.15f, 0.12f,
)

/** 整页最底层：黑曜石底 + 多层羽化径向烟雾 + 四角暗角。 */
@Composable
fun AmbientBackdrop(
    mode: AmbientBackdropMode,
    cardStyle: HoloCardStyle,
    heat: Float,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetColors = remember(mode, cardStyle) { ambientColors(mode, cardStyle) }

    // 六层颜色各自 600ms 过渡：旧色雾淡出、新色雾淡入同时发生，不会先闪黑再亮。
    // 颜色数量固定为 6，组合调用顺序稳定。
    val layerColors = targetColors.map { target ->
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            label = "ambientLayerColor",
        )
    }

    // 热力：升温 420ms / 降温 4200ms，与卡面、进度条同一套视觉节奏。
    val heatAnim = remember { Animatable(heat.coerceIn(0f, 1f)) }
    LaunchedEffect(heat, motionEnabled) {
        val target = heat.coerceIn(0f, 1f)
        if (!motionEnabled) {
            heatAnim.snapTo(target)
            return@LaunchedEffect
        }
        val from = heatAnim.value
        if (from == target) return@LaunchedEffect
        val spec = if (target > from) {
            tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
        } else {
            tween<Float>(durationMillis = 4200, easing = LinearOutSlowInEasing)
        }
        heatAnim.animateTo(target, spec)
    }

    // 烟雾漂移时钟：只在 motionEnabled 时跑帧循环；关闭后停在当前相位（不跳变）。
    var driftSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motionEnabled) {
        if (!motionEnabled) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrameNanos != 0L) {
                    val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    // heat=1 时 1.8×，其余线性过渡。
                    driftSeconds += dt * (1f + 0.8f * heatAnim.value)
                }
                lastFrameNanos = now
            }
        }
    }

    // 重力微视差的光源：复用卡面同一套姿态状态；motionEnabled=false 时它是静态值，不注册传感器。
    val light = rememberHoloLightState(motionEnabled)

    // 只负责绘制，不挂任何 pointerInput，因此不会拦截学习页的点击 / 滑动。
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val longSide = maxOf(w, h)

                // 只有尺寸 / 颜色变化时重建 Brush；稳态每帧只做 translate + drawCircle。
                val brushes = ArrayList<Brush>(AmbientLayers.size)
                val radii = FloatArray(AmbientLayers.size)
                val centers = ArrayList<Offset>(AmbientLayers.size)
                AmbientLayers.forEachIndexed { index, layer ->
                    val color = layerColors[index].value
                    val alpha = AmbientLayerAlphas[index]
                    val radius = longSide * layer.radius
                    radii[index] = radius
                    centers.add(Offset(w * layer.centerX, h * layer.centerY))
                    brushes.add(
                        Brush.radialGradient(
                            0f to color.copy(alpha = alpha),
                            0.42f to color.copy(alpha = alpha * 0.62f),
                            0.72f to color.copy(alpha = alpha * 0.24f),
                            1f to Color.Transparent,
                            center = centers[index],
                            radius = radius,
                        )
                    )
                }

                // 四角暗角：中心透明，越靠角越黑；中间边缘几乎不受影响。
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.78f to Color.Black.copy(alpha = 0.18f),
                    1f to Color.Black.copy(alpha = 0.48f),
                    center = Offset(w / 2f, h / 2f),
                    radius = 0.50f * sqrt(w * w + h * h),
                )

                onDrawBehind {
                    drawRect(Color(0xFF070A11))

                    // 沿重力反方向偏移：光源偏右，烟雾整体向左让，最多约 8.8dp（0.22 × 40dp）。
                    val normalizedX =
                        ((light.lightX.floatValue - 0.5f) / 0.42f).coerceIn(-1f, 1f)
                    val normalizedY =
                        ((light.lightY.floatValue - 0.5f) / 0.42f).coerceIn(-1f, 1f)
                    val maxShift = 40.dp.toPx()
                    val shiftX = -normalizedX * 0.22f * maxShift
                    val shiftY = -normalizedY * 0.22f * maxShift

                    val t = driftSeconds
                    AmbientLayers.forEachIndexed { index, layer ->
                        val phase = t * layer.speed * 2f * PI_FLOAT + layer.phase
                        val orbitX = sin(phase) * layer.orbitX * w
                        val orbitY = cos(phase * 0.83f) * layer.orbitY * h
                        translate(shiftX + orbitX, shiftY + orbitY) {
                            drawCircle(
                                brush = brushes[index],
                                radius = radii[index],
                                center = centers[index],
                            )
                        }
                    }

                    drawRect(vignette)
                }
            }
    )
}

private const val PI_FLOAT = 6.2831855f

/**
 * AUTO 按皮肤区分色系，避免所有皮肤都同一套蓝紫：
 * pearl 冷银/淡紫/冰蓝，rainbow 红/琥珀/绿/蓝/紫，prism 青/蓝/品红，
 * sparkle 紫/金，chrome 银灰/冷蓝，etched 青铜/墨绿，classic 暖米白/深蓝。
 */
private fun ambientColors(mode: AmbientBackdropMode, style: HoloCardStyle): List<Color> = when (mode) {
    AmbientBackdropMode.AUTO -> when (style) {
        HoloCardStyle.PEARL -> listOf(
            Color(0xFFBFD0EA), Color(0xFFCDBFE8), Color(0xFF9FC6E8),
            Color(0xFFD8DEEA), Color(0xFFB7C6DE), Color(0xFFA9BEDF),
        )
        HoloCardStyle.RAINBOW -> listOf(
            Color(0xFFE05A5A), Color(0xFFE0A24A), Color(0xFF5FBF7A),
            Color(0xFF4A8FE0), Color(0xFF8E5FD0), Color(0xFFD05FA8),
        )
        HoloCardStyle.PRISM -> listOf(
            Color(0xFF4FD8D0), Color(0xFF4A7FE0), Color(0xFFD05AA8),
            Color(0xFF58C6E8), Color(0xFF7A6FE0), Color(0xFFE06AC0),
        )
        HoloCardStyle.SPARKLE -> listOf(
            Color(0xFF8F5FD0), Color(0xFFD8B45A), Color(0xFFA06FE0),
            Color(0xFFE0C06A), Color(0xFF6A5FC0), Color(0xFFC69A4A),
        )
        HoloCardStyle.CHROME -> listOf(
            Color(0xFFC8D2DE), Color(0xFF6A8FB8), Color(0xFFA8B8C8),
            Color(0xFF5F7FA8), Color(0xFFD0D8E2), Color(0xFF7F9FBE),
        )
        HoloCardStyle.ETCHED -> listOf(
            Color(0xFFB07A4A), Color(0xFF3F5F4A), Color(0xFF8A6A3A),
            Color(0xFF2F4F3F), Color(0xFFC08A5A), Color(0xFF4A6A52),
        )
        HoloCardStyle.CLASSIC -> listOf(
            Color(0xFFE8DCC0), Color(0xFF3A4F8A), Color(0xFFD8C8A8),
            Color(0xFF2F4270), Color(0xFFC8B890), Color(0xFF4A5F9A),
        )
    }
    AmbientBackdropMode.AURORA -> listOf(
        Color(0xFF3FD8B0), Color(0xFF5A6FE0), Color(0xFF4AE08A),
        Color(0xFF4FC8D8), Color(0xFF7A5FD8), Color(0xFF3FB88A),
    )
    AmbientBackdropMode.ABYSS -> listOf(
        Color(0xFF1F4F9A), Color(0xFF2A8FA8), Color(0xFF1A3F7A),
        Color(0xFF3FB0B0), Color(0xFF245F9A), Color(0xFF2A7F9A),
    )
    AmbientBackdropMode.EMBER -> listOf(
        Color(0xFFA83A2A), Color(0xFFC86A2A), Color(0xFF8A2A2A),
        Color(0xFFD8A04A), Color(0xFF7A2A2A), Color(0xFFB85A2A),
    )
    // 丝绒：紫/品红/深蓝，整体更暗、更收敛。
    AmbientBackdropMode.VELVET -> listOf(
        Color(0xFF6A3FA0), Color(0xFFA03A7A), Color(0xFF3A3F8A),
        Color(0xFF5A2F8A), Color(0xFF8A2F6A), Color(0xFF2F2F6A),
    )
}

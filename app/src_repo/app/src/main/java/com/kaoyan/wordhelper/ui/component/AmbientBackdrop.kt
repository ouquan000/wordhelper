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
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
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

/** 日间：极淡冰蓝/珍珠白/浅薰衣草，峰值 0.25–0.40，清爽通透。 */
private val DayLayerAlphas = floatArrayOf(
    0.34f, 0.26f, 0.38f, 0.30f, 0.40f, 0.32f,
)

/** 夜间：深靛蓝/幽紫/冷蓝，峰值略高于旧版以保住颜色存在感。 */
private val NightLayerAlphas = floatArrayOf(
    0.32f, 0.28f, 0.36f, 0.26f, 0.30f, 0.24f,
)

/** 整页最底层：日夜双模式底色 + 多层羽化径向烟雾 + 随模式变化的暗角。 */
@Composable
fun AmbientBackdrop(
    mode: AmbientBackdropMode,
    cardStyle: HoloCardStyle,
    heat: Float,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // 用 App 自己的明暗信号判断日夜，不能读 MaterialTheme.background（卡片主题会覆盖它）。
    val darkTheme = LocalAppDarkTheme.current
    val layerAlphas = if (darkTheme) NightLayerAlphas else DayLayerAlphas
    val targetColors = remember(mode, cardStyle, darkTheme) {
        ambientColors(mode, cardStyle, darkTheme)
    }

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
                    val alpha = layerAlphas[index]
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

                // 日间用极淡白色提亮四角（不压黑），夜间保留收敛后的黑色暗角。
                val vignetteEdge = if (darkTheme) {
                    Color.Black.copy(alpha = 0.12f)
                } else {
                    Color.White.copy(alpha = 0.10f)
                }
                val vignetteCorner = if (darkTheme) {
                    Color.Black.copy(alpha = 0.34f)
                } else {
                    Color.White.copy(alpha = 0.22f)
                }
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.78f to vignetteEdge,
                    1f to vignetteCorner,
                    center = Offset(w / 2f, h / 2f),
                    radius = 0.50f * sqrt(w * w + h * h),
                )

                onDrawBehind {
                    drawRect(if (darkTheme) Color(0xFF080C14) else Color(0xFFF4F6FA))

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

private fun ambientColors(
    mode: AmbientBackdropMode,
    style: HoloCardStyle,
    darkTheme: Boolean,
): List<Color> = if (darkTheme) nightAmbientColors(mode, style) else dayAmbientColors(mode, style)

/**
 * 日间：冰蓝 / 珍珠白 / 浅薰衣草，AUTO 仍按皮肤微调色相但统一压成淡彩。
 * 色值只做低饱和提亮，叠在 0xFFF4F6FA 基底上保持清爽通透。
 */
private fun dayAmbientColors(mode: AmbientBackdropMode, style: HoloCardStyle): List<Color> =
    when (mode) {
        AmbientBackdropMode.AUTO -> when (style) {
            HoloCardStyle.PEARL -> listOf(
                Color(0xFFDDE6F5), Color(0xFFE8DDF7), Color(0xFFD6E6F7),
                Color(0xFFEDF0F7), Color(0xFFDDE6F2), Color(0xFFD8E4F5),
            )
            HoloCardStyle.RAINBOW -> listOf(
                Color(0xFFFFD9D9), Color(0xFFFFE6C7), Color(0xFFD5F0DD),
                Color(0xFFD2E2FF), Color(0xFFE4D6FF), Color(0xFFFFD6EE),
            )
            HoloCardStyle.PRISM -> listOf(
                Color(0xFFCBEDEA), Color(0xFFD2E2FF), Color(0xFFFFD6EE),
                Color(0xFFD6F0FA), Color(0xFFDDD6FF), Color(0xFFFFE0F2),
            )
            HoloCardStyle.SPARKLE -> listOf(
                Color(0xFFE4D6FF), Color(0xFFFFEFC7), Color(0xFFEDD6FF),
                Color(0xFFFFF2D2), Color(0xFFD8D2FF), Color(0xFFFFE8C2),
            )
            HoloCardStyle.CHROME -> listOf(
                Color(0xFFE6EBF2), Color(0xFFD2E0EE), Color(0xFFDDE6EE),
                Color(0xFFCADBE8), Color(0xFFEDF1F5), Color(0xFFD6E4EF),
            )
            HoloCardStyle.ETCHED -> listOf(
                Color(0xFFF0DFCB), Color(0xFFD6E4D8), Color(0xFFE8D8C2),
                Color(0xFFD0E0D4), Color(0xFFF5E6D2), Color(0xFFDDE8DE),
            )
            HoloCardStyle.CLASSIC -> listOf(
                Color(0xFFF5EEDC), Color(0xFFD6DEF0), Color(0xFFEFE6D2),
                Color(0xFFD2DCF0), Color(0xFFE8DFC8), Color(0xFFDDE4F5),
            )
        }
        AmbientBackdropMode.AURORA -> listOf(
            Color(0xFFBFE8E0), Color(0xFFC7D4FF), Color(0xFFD8F0E4),
            Color(0xFFCDE8F2), Color(0xFFE0D8FF), Color(0xFFD5EDE8),
        )
        AmbientBackdropMode.ABYSS -> listOf(
            Color(0xFFC7E4FF), Color(0xFFCDEFF2), Color(0xFFC2D8F5),
            Color(0xFFD5F0F2), Color(0xFFCFE2FF), Color(0xFFD8EDF8),
        )
        AmbientBackdropMode.EMBER -> listOf(
            Color(0xFFFFD9CC), Color(0xFFFFE4CC), Color(0xFFF5D2C8),
            Color(0xFFFFEBD6), Color(0xFFF0D4CE), Color(0xFFFFE0D0),
        )
        AmbientBackdropMode.VELVET -> listOf(
            Color(0xFFE4D6FF), Color(0xFFF0D6F0), Color(0xFFD8D8FF),
            Color(0xFFEAD8FF), Color(0xFFF2D8E8), Color(0xFFDDD6FA),
        )
    }

/**
 * 夜间：深靛蓝 / 幽紫 / 冷蓝，彻底移除旧版的偏绿、偏褐、品红。
 * 只保留色相上的轻微差别，整体都落在蓝紫色域里。
 */
private fun nightAmbientColors(mode: AmbientBackdropMode, style: HoloCardStyle): List<Color> =
    when (mode) {
        AmbientBackdropMode.AUTO -> when (style) {
            HoloCardStyle.PEARL -> listOf(
                Color(0xFF1E2A5A), Color(0xFF2A1F55), Color(0xFF16305A),
                Color(0xFF232B66), Color(0xFF33245E), Color(0xFF1B2F5E),
            )
            HoloCardStyle.RAINBOW -> listOf(
                Color(0xFF232B66), Color(0xFF33245E), Color(0xFF1E2A5A),
                Color(0xFF2A1F55), Color(0xFF16305A), Color(0xFF3A2A66),
            )
            HoloCardStyle.PRISM -> listOf(
                Color(0xFF16305A), Color(0xFF2A1F55), Color(0xFF232B66),
                Color(0xFF1E2A5A), Color(0xFF33245E), Color(0xFF1B2F5E),
            )
            HoloCardStyle.SPARKLE -> listOf(
                Color(0xFF33245E), Color(0xFF2A1F55), Color(0xFF232B66),
                Color(0xFF3A2A66), Color(0xFF1E2A5A), Color(0xFF2A1F55),
            )
            HoloCardStyle.CHROME -> listOf(
                Color(0xFF1B2F5E), Color(0xFF232B66), Color(0xFF16305A),
                Color(0xFF1E2A5A), Color(0xFF2A3A6A), Color(0xFF1B2F5E),
            )
            HoloCardStyle.ETCHED -> listOf(
                Color(0xFF1E2A5A), Color(0xFF2A1F55), Color(0xFF16305A),
                Color(0xFF232B66), Color(0xFF2A2A5E), Color(0xFF1B2F5E),
            )
            HoloCardStyle.CLASSIC -> listOf(
                Color(0xFF1E2A5A), Color(0xFF232B66), Color(0xFF16305A),
                Color(0xFF2A1F55), Color(0xFF1B2F5E), Color(0xFF33245E),
            )
        }
        AmbientBackdropMode.AURORA -> listOf(
            Color(0xFF1E2A5A), Color(0xFF24406E), Color(0xFF2A1F55),
            Color(0xFF16305A), Color(0xFF2E3A7A), Color(0xFF1B2F5E),
        )
        AmbientBackdropMode.ABYSS -> listOf(
            Color(0xFF16305A), Color(0xFF1E2A5A), Color(0xFF123A66),
            Color(0xFF232B66), Color(0xFF1B2F5E), Color(0xFF24406E),
        )
        AmbientBackdropMode.EMBER -> listOf(
            Color(0xFF2A1F55), Color(0xFF33245E), Color(0xFF1E2A5A),
            Color(0xFF3A2A66), Color(0xFF232B66), Color(0xFF2A1F55),
        )
        // 丝绒：幽紫为主，保持最暗、最收敛。
        AmbientBackdropMode.VELVET -> listOf(
            Color(0xFF33245E), Color(0xFF2A1F55), Color(0xFF232B66),
            Color(0xFF3A2A66), Color(0xFF1E2A5A), Color(0xFF2A1F55),
        )
    }

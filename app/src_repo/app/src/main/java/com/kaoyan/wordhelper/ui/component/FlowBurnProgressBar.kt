package com.kaoyan.wordhelper.ui.component

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 一套心流燃烧色盘。Tier1-4 共 24 套；Tier0 另有一套冷灰蓝/暗夜雾色盘。
 */
data class FlowBurnPalette(
    val name: String,
    val colors: List<Color>
)

/**
 * 色盘池：Tier1 静谧冷调、Tier2 幻彩星河、Tier3 暮光暖焰、Tier4 炽热超频。
 * 每档 6 套、每套 5 个渐变色标；Tier0 不在 24 套里。
 */
object FlowBurnPalettes {
    val nightMist = FlowBurnPalette(
        name = "暗夜雾蓝",
        colors = listOf(
            Color(0xFF62708A),
            Color(0xFF4A5772),
            Color(0xFF333D57),
            Color(0xFF232B40),
            Color(0xFF161D31)
        )
    )

    val tier1 = listOf(
        FlowBurnPalette(
            "冰川晨雾",
            listOf(Color(0xFFBFE9FF), Color(0xFF7FD8F7), Color(0xFF4FA8D8), Color(0xFF2C5F86), Color(0xFF1B3A57))
        ),
        FlowBurnPalette(
            "深海松针",
            listOf(Color(0xFF7FE3C8), Color(0xFF35B49A), Color(0xFF1D7F73), Color(0xFF145C5A), Color(0xFF0E3B45))
        ),
        FlowBurnPalette(
            "雨后石板",
            listOf(Color(0xFFC6D8E8), Color(0xFF8FA8BE), Color(0xFF5E7A92), Color(0xFF3B5468), Color(0xFF263B4D))
        ),
        FlowBurnPalette(
            "极光薄荷",
            listOf(Color(0xFFB6FFE4), Color(0xFF5CE6C0), Color(0xFF22B99A), Color(0xFF0E8A80), Color(0xFF0B5E63))
        ),
        FlowBurnPalette(
            "寒山远黛",
            listOf(Color(0xFFD6D2FF), Color(0xFFA8A0E8), Color(0xFF7A73C4), Color(0xFF4D4A8F), Color(0xFF2E2C5C))
        ),
        FlowBurnPalette(
            "深空星云",
            listOf(Color(0xFF9FD7FF), Color(0xFF6B8DFF), Color(0xFF4B5BD6), Color(0xFF2B2F7A), Color(0xFF171A45))
        )
    )

    val tier2 = listOf(
        FlowBurnPalette(
            "薰衣草夜曲",
            listOf(Color(0xFFE3C7FF), Color(0xFFB88CFF), Color(0xFF8C5CF0), Color(0xFF5F3AAE), Color(0xFF2D1B5E))
        ),
        FlowBurnPalette(
            "Codex 棱镜",
            listOf(Color(0xFFB9F2FF), Color(0xFF7AA7FF), Color(0xFFB57BFF), Color(0xFFFF7AD9), Color(0xFF4A2C8F))
        ),
        FlowBurnPalette(
            "暮色紫罗兰",
            listOf(Color(0xFFFFC7F0), Color(0xFFD98CFF), Color(0xFFA45CE0), Color(0xFF6F3AA8), Color(0xFF3A1C5C))
        ),
        FlowBurnPalette(
            "蓝莓苏打",
            listOf(Color(0xFFA8D8FF), Color(0xFF6FA8FF), Color(0xFF5B6BFF), Color(0xFF3D3AC4), Color(0xFF221E6E))
        ),
        FlowBurnPalette(
            "赛博霓虹雾",
            listOf(Color(0xFF8CFFF0), Color(0xFF38D9FF), Color(0xFF4B6BFF), Color(0xFFB44BFF), Color(0xFF2A145C))
        ),
        FlowBurnPalette(
            "月光珍珠母",
            listOf(Color(0xFFFFF4E0), Color(0xFFE8D8FF), Color(0xFFC7E8FF), Color(0xFF9FB8E8), Color(0xFF5C5F8F))
        )
    )

    val tier3 = listOf(
        FlowBurnPalette(
            "日落桃金",
            listOf(Color(0xFFFFD9A0), Color(0xFFFFB36B), Color(0xFFFF8A5C), Color(0xFFD95B4A), Color(0xFF7A2E3A))
        ),
        FlowBurnPalette(
            "陶土余晖",
            listOf(Color(0xFFFFC9A8), Color(0xFFE8A07A), Color(0xFFC46B5A), Color(0xFF8F4A4A), Color(0xFF4D2A35))
        ),
        FlowBurnPalette(
            "落日熔金",
            listOf(Color(0xFFFFE9A8), Color(0xFFFFC94B), Color(0xFFFF9E2E), Color(0xFFD96A1E), Color(0xFF7A3412))
        ),
        FlowBurnPalette(
            "玫瑰香槟",
            listOf(Color(0xFFFFE1E0), Color(0xFFFFB8C6), Color(0xFFE88AA0), Color(0xFFB85C7A), Color(0xFF6E2E4A))
        ),
        FlowBurnPalette(
            "秋叶焦糖",
            listOf(Color(0xFFFFDCA8), Color(0xFFE8B06B), Color(0xFFC47A3A), Color(0xFF8F5224), Color(0xFF4D2B14))
        ),
        FlowBurnPalette(
            "晚霞火烈鸟",
            listOf(Color(0xFFFFD1D1), Color(0xFFFF9E9E), Color(0xFFFF6B8A), Color(0xFFD94A6B), Color(0xFF7A1E3A))
        )
    )

    val tier4 = listOf(
        FlowBurnPalette(
            "太阳耀斑",
            listOf(Color(0xFFFFF3B0), Color(0xFFFFD24B), Color(0xFFFF8A1E), Color(0xFFFF3D1E), Color(0xFF8F1400))
        ),
        FlowBurnPalette(
            "超新星爆发",
            listOf(Color(0xFFFFFFFF), Color(0xFFFFE0A8), Color(0xFFFF9E4B), Color(0xFFFF5C2E), Color(0xFFB01E4B))
        ),
        FlowBurnPalette(
            "熔岩琥珀",
            listOf(Color(0xFFFFE2A0), Color(0xFFFFB03A), Color(0xFFE86A1E), Color(0xFFB03A14), Color(0xFF5C140B))
        ),
        FlowBurnPalette(
            "白金电弧",
            listOf(Color(0xFFFFFFFF), Color(0xFFE8F4FF), Color(0xFFA8D8FF), Color(0xFFFFE9A8), Color(0xFFFFB03A))
        ),
        FlowBurnPalette(
            "绯红极光",
            listOf(Color(0xFFFFD1E8), Color(0xFFFF6B9E), Color(0xFFFF2E5C), Color(0xFFC4144B), Color(0xFF5C0A2E))
        ),
        FlowBurnPalette(
            "恒星日冕",
            listOf(Color(0xFFFFF8E0), Color(0xFFFFD98A), Color(0xFFFFA83A), Color(0xFFFF7A1E), Color(0xFFB03A0A))
        )
    )

    val tierPools: List<List<FlowBurnPalette>> = listOf(tier1, tier2, tier3, tier4)

    /** 24 套（Tier1-4 各 6 套），沙盒手动锁定用。 */
    val all24: List<FlowBurnPalette> = tierPools.flatten()

    fun poolForTier(tier: Int): List<FlowBurnPalette> = when (tier) {
        0 -> listOf(nightMist)
        in 1..4 -> tierPools[tier - 1]
        else -> tier1
    }
}

/**
 * 心流燃烧颗粒渐变进度条。
 *
 * 纯 Compose Canvas：线性渐变 + 4 个只沿 X 轴单向奔涌的径向色团 + 1:1 平铺噪点 +
 * 18~28 颗圆形星尘。进度填充只裁在当前进度区域内，前端是液态羽化收口，不是圆球滑块。
 *
 * 填充比例与右侧计数严格同源：都直接来自 [currentIndex]/[totalCount]。
 * totalCount <= 30 时整条进度就是 currentIndex/totalCount；只有 totalCount > 30
 * 才切换成 30 词一小格的循环视觉，避免 200+ 词时填充条一直贴满。
 *
 * @param currentIndex 当前进度（沿用 LearningScreen 的 1-based 语义，与右侧计数同源）
 * @param totalCount 总数
 * @param tier 0=迟缓/超时回冷，1=正常，2=快一点，3=更快，4=最快/燃烧
 * @param elapsedMs 当前卡已停留毫秒（Tier0 由 ViewModel 判定）
 * @param motionEnabled false 或系统 Animator duration scale=0 时退化为静止颗粒条
 * @param barHeight 进度条自身高度，默认 11dp；沙盒可放大到 40dp
 * @param showHeader 是否显示「学习进度」与右侧等宽计数器
 * @param paletteOverride 非空时锁定这套色盘（沙盒手动预览用）
 * @param onPaletteSlotChanged 自动流转时每 14 秒回报当前池内色盘序号（0-based）
 * @param sprintSize >0 时每满一格触发一次 30 词高光脉冲（填充循环固定按 30 词）
 * @param heat 连续心流热力 0..1，与卡片共用；升温 420ms、降温 4200ms
 */
@Composable
fun FlowBurnProgressBar(
    currentIndex: Int,
    totalCount: Int,
    tier: Int,
    elapsedMs: Long,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
    barHeight: Dp = 11.dp,
    showHeader: Boolean = true,
    paletteOverride: FlowBurnPalette? = null,
    onPaletteSlotChanged: ((Int) -> Unit)? = null,
    sprintSize: Int = 0,
    heat: Float = 0f
) {
    val safeTier = tier.coerceIn(0, 4)
    val context = LocalContext.current
    val systemAnimationsOn = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }
    val animate = motionEnabled && systemAnimationsOn

    val tierAnim = remember { Animatable(safeTier.toFloat()) }
    LaunchedEffect(safeTier, animate) {
        val target = safeTier.toFloat()
        if (!animate) {
            tierAnim.snapTo(target)
            return@LaunchedEffect
        }
        val from = tierAnim.value
        if (from == target) return@LaunchedEffect
        val spec = if (target > from) {
            tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
        } else {
            tween<Float>(durationMillis = 4200, easing = LinearOutSlowInEasing)
        }
        tierAnim.animateTo(target, spec)
    }

    val heatAnim = remember { Animatable(heat.coerceIn(0f, 1f)) }
    LaunchedEffect(heat, animate) {
        val target = heat.coerceIn(0f, 1f)
        if (!animate) {
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

    val clock = remember { FlowBurnClock() }
    LaunchedEffect(animate) {
        if (!animate) {
            clock.seconds = 0f
            return@LaunchedEffect
        }
        var startNs = 0L
        withFrameNanos { startNs = it }
        while (isActive) {
            withFrameNanos { now ->
                clock.seconds = (now - startNs) / 1_000_000_000f
            }
        }
    }

    // 14 秒自动流转：回报当前池内序号给沙盒展示。绘制侧自行做连续 lerp，不依赖这个回调。
    LaunchedEffect(safeTier, paletteOverride, animate) {
        if (!animate) {
            onPaletteSlotChanged?.invoke(0)
            return@LaunchedEffect
        }
        val poolSize = if (paletteOverride != null) 1 else FlowBurnPalettes.poolForTier(safeTier).size
        snapshotFlow {
            val raw = clock.seconds / FLOW_PALETTE_CYCLE_SECONDS
            val slot = floor(raw).toInt() % poolSize
            if (slot < 0) slot + poolSize else slot
        }.distinctUntilChanged().collect { slot ->
            onPaletteSlotChanged?.invoke(slot)
        }
    }

    // 填充比例必须和右侧计数用同一组实时状态：currentIndex 是 1-based，
    // 右侧计数就是 currentIndex/totalCount，这里直接照抄这条公式。
    // 只有单词数超过一小循环（30）时才切换到 30 词循环视觉；<=30 时永远整条进度。
    val targetProgress = flowProgressTarget(currentIndex, totalCount)

    // 点击评分后目标值立刻更新，240ms 内填充长度与计数对齐；
    // 从上一循环回零也走 240ms，不做旧的 620ms 慢半拍 / 满格 snap。
    val progressAnim by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (animate) {
            tween(durationMillis = 240, easing = FastOutSlowInEasing)
        } else {
            tween(durationMillis = 0)
        },
        label = "flowBurnProgress"
    )

    // 每满一个小循环（30 词）打一次 360ms 高光脉冲；只叠加亮度/描边，不碰真实进度。
    val sprintPulse = remember { Animatable(0f) }
    val safeSprintSize = sprintSize.coerceAtLeast(0)
    val completedSprints = if (safeSprintSize > 0 && totalCount > FLOW_SPRINT_WORDS) {
        currentIndex / FLOW_SPRINT_WORDS
    } else {
        0
    }
    LaunchedEffect(completedSprints, safeSprintSize, animate) {
        if (completedSprints <= 0 || !animate) {
            sprintPulse.snapTo(0f)
            return@LaunchedEffect
        }
        sprintPulse.snapTo(1f)
        sprintPulse.animateTo(0f, tween(durationMillis = 360, easing = LinearOutSlowInEasing))
    }

    val counterText = buildString {
        append(currentIndex.coerceIn(0, max(totalCount, 0)).toString().padStart(2, '0'))
        append('/')
        append(max(totalCount, 0).toString().padStart(2, '0'))
    }
    // 轨道要留出同色系外发光的呼吸空间：10~15dp 的瘦身轨道靠 6dp 兜住 3 层外发光
    // （最大 grow 5.1dp + 描边），再窄就会被 Canvas 高度裁掉顶端；24dp 沙盒模式再放宽。
    val haloPad = when {
        barHeight >= 24.dp -> 9.dp
        barHeight >= 16.dp -> 7.dp
        else -> 6.dp
    }
    val noise = remember { createFlowNoiseBitmap() }
    val noiseBrush = remember(noise) {
        ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
    }
    val fillPath = remember { Path() }

    Column(
        modifier = modifier,
        // 6dp -> 4dp：收紧「学习进度 / 计数」与进度条之间的空隙，把高度还给上方卡片。
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "学习进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = counterText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight + haloPad * 2)
        ) {
            val w = size.width
            val h = barHeight.toPx()
            val top = haloPad.toPx()
            if (w <= 0f || h <= 0f) return@Canvas

            val progress = progressAnim
            val progressWidth = (w * progress).coerceIn(0f, w)
            val heatLevel = if (animate) heatAnim.value else heat.coerceIn(0f, 1f)
            val pulse = if (animate) sprintPulse.value else 0f
            val animatedTier = if (animate) tierAnim.value else safeTier.toFloat()
            val tierLow = floor(animatedTier).toInt().coerceIn(0, 4)
            val tierHigh = ceil(animatedTier).toInt().coerceIn(0, 4)
            val tierMix = (animatedTier - tierLow).coerceIn(0f, 1f)
            // 推进速度：tier/heat 越高，X 轴流量越快；亮度始终克制。
            val speedDpPerSecond = lerpFloat(flowSpeedForTier(tierLow), flowSpeedForTier(tierHigh), tierMix) *
                (1f + 0.45f * heatLevel)
            val brightness = lerpFloat(flowBrightnessForTier(tierLow), flowBrightnessForTier(tierHigh), tierMix)
            val intensity = (lerpFloat(
                flowSparkIntensityForTier(tierLow),
                flowSparkIntensityForTier(tierHigh),
                tierMix
            ) + 0.30f * heatLevel).coerceIn(0f, 1f)
            val flowSeconds = if (animate) clock.seconds else 0f
            // 单调递增的 X 偏移：循环取模由各元素自己做，绝不出现负向/左右往复。
            val flowOffsetX = flowSeconds * speedDpPerSecond * density
            val breath = if (animate && animatedTier < 0.5f) {
                0.5f + 0.5f * sin(clock.seconds * (2f * PI.toFloat() / 28f))
            } else {
                0.5f
            }
            val breathBrightness = if (animatedTier < 0.5f) 0.94f + 0.06f * breath else 1f
            val finalBrightness = (brightness * breathBrightness *
                (1f + 0.10f * heatLevel + 0.22f * pulse)).coerceIn(0f, 1f)

            val cycleRaw = if (animate) clock.seconds / FLOW_PALETTE_CYCLE_SECONDS else 0f
            val cycleSlot = floor(cycleRaw).toInt()
            val cycleBlend = cycleRaw - floor(cycleRaw)
            val lowColors = paletteColors(tierLow, cycleSlot, cycleBlend, paletteOverride)
            val highColors = paletteColors(tierHigh, cycleSlot, cycleBlend, paletteOverride)
            val colors = lerpPalette(lowColors, highColors, tierMix).map { it.scaleRgb(finalBrightness) }
            val trackColor = Color(0xFF161D31).copy(alpha = 0.85f)
            val radius = h / 2f

            // 同色系外发光：多层扩张描边叠出柔光，暗色背景上勾清整条胶囊轮廓。
            val glowColor = colors.first()
            for (layer in 3 downTo 1) {
                val grow = layer * 1.7.dp.toPx()
                val glowAlpha = (0.055f + 0.035f * heatLevel + 0.075f * pulse) *
                    ((4 - layer) / 3f)
                drawRoundRect(
                    color = glowColor.copy(alpha = glowAlpha.coerceIn(0f, 0.24f)),
                    topLeft = Offset(-grow, top - grow),
                    size = Size(w + grow * 2f, h + grow * 2f),
                    cornerRadius = CornerRadius(radius + grow),
                    style = Stroke(width = (1.4f + layer * 0.8f).dp.toPx())
                )
            }

            // 底槽：深色胶囊 + 1dp 淡白渐变微光描边。
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(radius)
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.32f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.24f)
                    ),
                    start = Offset(0f, top),
                    end = Offset(w, top + h)
                ),
                topLeft = Offset(0f, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1.dp.toPx())
            )

            if (progressWidth > 0.5f) {
                // 羽化只做“边缘柔化”，长度控制在半条高度内，避免把已填充段吃成视觉缺口。
                val feather = h * (0.34f + 0.22f * intensity)
                buildFillPath(fillPath, progressWidth, top, h, feather)

                clipPath(fillPath) {
                    // 底层线性渐变：上/前偏冷亮，下/后偏深暖。
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = colors,
                            start = Offset(0f, top),
                            end = Offset(progressWidth.coerceAtLeast(1f), top + h)
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(radius)
                    )

                    // 4 个弥散色团：固定垂直高度（0.42h / 0.58h 微量错位），只沿 +X 单向奔涌。
                    // 每个色团独立取模循环，没有任何 Y 轴上下摇摆或左右往复。
                    for (i in 0 until 4) {
                        val blobRadius = h * (1.65f + 0.20f * i + 0.55f * intensity)
                        val blobSpan = progressWidth + blobRadius * 2f
                        if (blobSpan <= 0f) continue
                        val rawTravel = flowOffsetX * (0.55f + i * 0.11f) + blobSpan * (i * 0.27f)
                        val travel = ((rawTravel % blobSpan) + blobSpan) % blobSpan
                        val cx = travel - blobRadius
                        val cy = top + h * (if (i % 2 == 0) 0.42f else 0.58f)
                        val blobColor = colors[(i + 1) % colors.size]
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    blobColor.copy(alpha = 0.40f),
                                    blobColor.copy(alpha = 0.14f),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy),
                                radius = blobRadius
                            ),
                            radius = blobRadius,
                            center = Offset(cx, cy)
                        )
                    }

                    // 128×128 灰度噪点按 1:1 物理像素平铺（TileMode.Repeated），Overlay 0.28。
                    // 不做 drawImage 拉伸，保证是均匀磨砂胶片颗粒而不是横向条纹。
                    drawRect(
                        brush = noiseBrush,
                        topLeft = Offset(0f, top),
                        size = Size(progressWidth, h),
                        alpha = 0.28f,
                        blendMode = BlendMode.Overlay
                    )

                    // 前端羽化：让噪点/颗粒向进度末端自然褪掉。
                    val fadeStart = (progressWidth - feather * 1.25f).coerceAtLeast(0f)
                    if (progressWidth - fadeStart > 0.5f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, trackColor.copy(alpha = 0.62f)),
                                startX = fadeStart,
                                endX = progressWidth
                            ),
                            topLeft = Offset(fadeStart, top),
                            size = Size(progressWidth - fadeStart, h)
                        )
                    }

                    // 液态柔光羽化收口：径向渐隐的光锋，不是圆球。
                    val tipX = progressWidth - feather * 0.34f
                    val tipY = top + h * 0.5f
                    val tipRadius = h * (0.92f + 1.25f * intensity)
                    val tipColor = colors.first().let {
                        if (intensity > 0.55f) Color.White.copy(alpha = 0.82f) else it
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                tipColor.copy(alpha = (0.20f + 0.56f * intensity).coerceIn(0f, 0.9f)),
                                tipColor.copy(alpha = 0.10f + 0.16f * intensity),
                                Color.Transparent
                            ),
                            center = Offset(tipX, tipY),
                            radius = tipRadius
                        ),
                        radius = tipRadius,
                        center = Offset(tipX, tipY)
                    )

                    // 圆形星尘：18~28 颗独立生命周期，半径 1~2.4dp，径向柔光 + 实心核心。
                    // 只随 flowOffsetX 向右飘散、原地呼吸闪烁淡出，y 固定不漂移。
                    if (progressWidth > h * 0.6f) {
                        val particleCount = (18 + (intensity * 10f).toInt()).coerceIn(18, 28)
                        val travelSpan = (progressWidth + h * 0.8f).coerceAtLeast(h * 2f)
                        val tierGlowScale = if (animatedTier < 0.5f) 0.55f else 1f
                        val sparkColor = if (intensity > 0.55f) Color.White else colors.first()
                        for (index in 0 until particleCount) {
                            val seed = FLOW_STARDUST[index]
                            val rawLife = flowOffsetX * seed.speed / travelSpan + seed.phase
                            val life = rawLife - floor(rawLife)
                            val fade = sin(life * PI.toFloat()).pow(1.35f).coerceIn(0f, 1f)
                            val twinkle = 0.62f + 0.38f * sin(flowSeconds * seed.flicker + seed.phase * 6.283f)
                            val alpha = (seed.alpha * (0.22f + 0.78f * intensity) * tierGlowScale *
                                fade * twinkle).coerceIn(0f, 0.9f)
                            if (alpha <= 0.015f) continue
                            val x = life * travelSpan - h * 0.2f
                            if (x < -h || x > progressWidth + h) continue
                            val y = top + h * (0.16f + 0.68f * seed.y)
                            val dotRadius = (1f + 1.4f * seed.size).dp.toPx()
                            val glowRadius = dotRadius * (2.6f + 1.2f * seed.size)
                            val center = Offset(x, y)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        sparkColor.copy(alpha = alpha * 0.55f),
                                        sparkColor.copy(alpha = alpha * 0.18f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = glowRadius
                                ),
                                radius = glowRadius,
                                center = center
                            )
                            drawCircle(
                                color = sparkColor.copy(alpha = alpha),
                                radius = dotRadius,
                                center = center
                            )
                        }
                    }
                }

                // Tier4 胶囊外微光晕：多层低透明度描边叠出柔光。
                val haloStrength = ((animatedTier - 3.02f) / 0.98f).coerceIn(0f, 1f)
                if (haloStrength > 0.01f) {
                    val haloColor = colors.first()
                    for (layer in 1..3) {
                        val grow = layer * 1.6.dp.toPx()
                        drawRoundRect(
                            color = haloColor.copy(alpha = 0.12f * haloStrength / layer),
                            topLeft = Offset(-grow, top - grow),
                            size = Size(w + grow * 2f, h + grow * 2f),
                            cornerRadius = CornerRadius(radius + grow),
                            style = Stroke(width = (1.3f + layer * 0.9f).dp.toPx())
                        )
                    }
                }
                // 满一个小循环（30 词）的高光脉冲：前端径向绽放 + 全胶囊白描边，360ms 衰减。
                // 不用横向扫光，避免整条横线/拉丝的观感。
                if (pulse > 0.01f) {
                    val bloomCenter = Offset(progressWidth.coerceIn(0f, w), top + h * 0.5f)
                    val bloomRadius = h * (2.0f + 1.4f * pulse)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.24f * pulse),
                                Color.Transparent
                            ),
                            center = bloomCenter,
                            radius = bloomRadius
                        ),
                        radius = bloomRadius,
                        center = bloomCenter
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.42f * pulse),
                        topLeft = Offset(0f, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
            }
        }
    }
}

private const val FLOW_PALETTE_CYCLE_SECONDS = 14f

/** 填充循环的单词格：超过 30 词才启用，避免长会话进度条长期贴满。 */
private const val FLOW_SPRINT_WORDS = 30

private class FlowBurnClock {
    var seconds by mutableFloatStateOf(0f)
}

/**
 * 填充比例的唯一来源：与右侧计数 currentIndex/totalCount 完全同源。
 *
 * - currentIndex <= 0 或 totalCount <= 0 → 0
 * - totalCount <= 30 → currentIndex/totalCount（如 18/20 = 0.90）
 * - totalCount > 30  → 30 词一小格的循环视觉 (((index-1) % 30) + 1) / 30
 */
private fun flowProgressTarget(currentIndex: Int, totalCount: Int): Float {
    if (currentIndex <= 0 || totalCount <= 0) return 0f
    if (totalCount <= FLOW_SPRINT_WORDS) {
        return (currentIndex.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    }
    return ((((currentIndex - 1) % FLOW_SPRINT_WORDS) + 1).toFloat() / FLOW_SPRINT_WORDS.toFloat())
        .coerceIn(0f, 1f)
}

/** 圆形星尘种子：y 固定、只随 X 偏移向右飘散；相位/尺寸/闪烁速度错落。 */
private data class FlowStardustSeed(
    val phase: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val alpha: Float,
    val flicker: Float
)

private val FLOW_STARDUST: List<FlowStardustSeed> = run {
    val random = Random(20261117)
    List(28) {
        FlowStardustSeed(
            phase = random.nextFloat(),
            y = random.nextFloat(),
            size = random.nextFloat(),
            speed = 0.55f + random.nextFloat() * 0.65f,
            alpha = 0.42f + random.nextFloat() * 0.58f,
            flicker = 1.1f + random.nextFloat() * 1.9f
        )
    }
}

private fun createFlowNoiseBitmap(): ImageBitmap {
    val size = 128
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = Random(20260517)
    val pixels = IntArray(size * size)
    for (index in pixels.indices) {
        val grey = 92 + random.nextInt(132)
        pixels[index] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

/** 色团/星尘的推进速度（dp/秒）：tier0 几乎静止，tier4 明显奔涌。 */
private fun flowSpeedForTier(tier: Int): Float = when (tier) {
    0 -> 10f
    1 -> 46f
    2 -> 74f
    3 -> 110f
    else -> 150f
}

private fun flowBrightnessForTier(tier: Int): Float = if (tier == 0) 0.72f else 1f

private fun flowSparkIntensityForTier(tier: Int): Float = when (tier) {
    0 -> 0f
    1 -> 0f
    2 -> 0.22f
    3 -> 0.62f
    else -> 1f
}

private fun paletteColors(
    tier: Int,
    cycleSlot: Int,
    cycleBlend: Float,
    override: FlowBurnPalette?
): List<Color> {
    if (override != null) return override.colors
    val pool = FlowBurnPalettes.poolForTier(tier)
    if (pool.size <= 1) return pool.first().colors
    val index = ((cycleSlot % pool.size) + pool.size) % pool.size
    val next = (index + 1) % pool.size
    return lerpPalette(pool[index].colors, pool[next].colors, smoothStep(cycleBlend))
}

private fun lerpPalette(from: List<Color>, to: List<Color>, fraction: Float): List<Color> {
    if (from === to) return from
    val count = max(from.size, to.size)
    return List(count) { index ->
        val a = from[index % from.size]
        val b = to[index % to.size]
        lerpColor(a, b, fraction)
    }
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = lerpFloat(from.red, to.red, t),
        green = lerpFloat(from.green, to.green, t),
        blue = lerpFloat(from.blue, to.blue, t),
        alpha = lerpFloat(from.alpha, to.alpha, t)
    )
}

private fun lerpFloat(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction.coerceIn(0f, 1f)

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun Color.scaleRgb(factor: Float): Color {
    val f = factor.coerceIn(0f, 1f)
    return Color(
        red = (red * f).coerceIn(0f, 1f),
        green = (green * f).coerceIn(0f, 1f),
        blue = (blue * f).coerceIn(0f, 1f),
        alpha = alpha
    )
}

private fun buildFillPath(path: Path, width: Float, top: Float, height: Float, feather: Float) {
    path.reset()
    val radius = height / 2f
    if (width <= height * 0.65f) {
        path.addRoundRect(
            RoundRect(
                left = 0f,
                top = top,
                right = width.coerceAtLeast(0.5f),
                bottom = top + height,
                cornerRadius = CornerRadius(width.coerceAtLeast(0.5f) / 2f)
            )
        )
        return
    }
    val bodyRight = (width - feather).coerceAtLeast(radius)
    path.moveTo(radius, top)
    path.lineTo(bodyRight, top)
    path.cubicTo(
        bodyRight + feather * 0.62f,
        top,
        width - feather * 0.04f,
        top + height * 0.24f,
        width,
        top + height * 0.5f
    )
    path.cubicTo(
        width - feather * 0.04f,
        top + height * 0.76f,
        bodyRight + feather * 0.62f,
        top + height,
        bodyRight,
        top + height
    )
    path.lineTo(radius, top + height)
    path.arcTo(
        rect = Rect(0f, top, height, top + height),
        startAngleDegrees = 90f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false
    )
    path.close()
}

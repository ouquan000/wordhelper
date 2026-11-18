package com.kaoyan.wordhelper.ui.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaoyan.wordhelper.ui.component.CardEntrance
import com.kaoyan.wordhelper.ui.component.CardExitStyle
import com.kaoyan.wordhelper.ui.component.CardTransitionOverlay
import com.kaoyan.wordhelper.ui.component.DeckStackUnderlay
import com.kaoyan.wordhelper.ui.component.FlowBurnPalette
import com.kaoyan.wordhelper.ui.component.FlowBurnPalettes
import com.kaoyan.wordhelper.ui.component.FlowBurnProgressBar
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.HoloCardSurface
import com.kaoyan.wordhelper.ui.component.HoloCardTheme
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.ui.component.rememberCardTransitionController
import com.kaoyan.wordhelper.ui.component.rememberHoloLightState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DemoTierNames = listOf("迟缓", "正常", "快一点", "更快", "最快")

/** 与 CardTransitionOverlay 的 TAG 对齐，方便 adb logcat 一条命令核对四种退场。 */
private const val DemoTransitionTag = "CardTransition"

/**
 * /ui-demo 页面（UI 动效沙盒）：进度条流光调色实验室。
 *
 * 旧版 3D 倾斜卡已移除；本页只保留新进度条的实时预览、速度档位、
 * 24 套色盘锁定/自动流转，以及「模拟秒杀一词」的 Tier4 升温 + 4.2s 冷却演示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiDemoScreen(onBack: () -> Unit) {
    var selectedTier by rememberSaveable { mutableStateOf(1) }
    var simulatedTier by remember { mutableStateOf<Int?>(null) }
    var manualPalette by rememberSaveable { mutableStateOf(false) }
    var manualIndex by rememberSaveable { mutableStateOf(0) }
    var autoSlot by remember { mutableStateOf(0) }
    var simulationJob by remember { mutableStateOf<Job?>(null) }

    // 04 切卡过场实验室：只记录预览卡 bounds，退场 ghost 与卡片同位置同尺寸。
    val cardTransition = rememberCardTransitionController()
    var previewKey by remember { mutableStateOf(0) }
    var previewStyle by remember { mutableStateOf(CardExitStyle.SLIDE_RIGHT) }
    var previewCardBounds by remember { mutableStateOf(Rect.Zero) }

    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose { simulationJob?.cancel() }
    }

    val activeTier = simulatedTier ?: selectedTier
    val activePool = FlowBurnPalettes.poolForTier(activeTier)
    val autoPalette = activePool[autoSlot % activePool.size]
    val displayPalette = if (manualPalette) FlowBurnPalettes.all24[manualIndex] else autoPalette
    val displayNumber = if (manualPalette) {
        manualIndex + 1
    } else if (activeTier == 0) {
        0
    } else {
        (activeTier - 1) * 6 + (autoSlot % 6) + 1
    }
    val displayTierLabel = if (manualPalette) {
        "Tier${manualIndex / 6 + 1} 池 · 手动锁定"
    } else if (activeTier == 0) {
        "Tier0 专属 · 不计入 24 套"
    } else {
        "Tier$activeTier 池 · 14s 自动流转"
    }
    val paletteSlotCallback: ((Int) -> Unit)? = if (manualPalette) {
        null
    } else {
        { slot -> autoSlot = slot }
    }

    fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            simulatedTier = 4
            delay(420)
            simulatedTier = 1
            delay(4200)
            simulatedTier = null
            simulationJob = null
        }
    }

    /**
     * 播放一次退场：把预览卡 bounds 交给过场控制器（不抓像素），
     * 然后自增 contentKey 让 CardEntrance 播一次 120ms 淡入。
     */
    fun playExitCard(style: CardExitStyle) {
        val bounds = previewCardBounds.takeIf { it.width > 0f && it.height > 0f }
        previewStyle = style
        Log.d(DemoTransitionTag, "sandbox launch style=$style bounds=$bounds")
        cardTransition.launch(bounds, style, motionEnabled = true, cardStyle = HoloCardStyle.PEARL)
        previewKey++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("进度条流光调色实验室") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("ui_demo_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        // 根 Box：滚动内容 + 全屏过场画布。画布是最后一个兄弟节点，只画特效、不接触摸。
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            PreviewCard(
                tier = activeTier,
                palette = displayPalette,
                paletteOverride = if (manualPalette) displayPalette else null,
                onPaletteSlotChanged = paletteSlotCallback,
                displayNumber = displayNumber,
                displayTierLabel = displayTierLabel
            )

            SectionCard(
                title = "01 · 速度档位",
                subtitle = "点击立即切换。迟缓档 >22s 自动进入，答完/切卡回正常；≤6s 为最快档。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DemoTierNames.forEachIndexed { index, name ->
                        FilterChip(
                            selected = activeTier == index,
                            onClick = {
                                simulationJob?.cancel()
                                simulationJob = null
                                simulatedTier = null
                                selectedTier = index
                            },
                            label = {
                                Text(
                                    text = name,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }
                Text(
                    text = "当前：Tier$activeTier · ${DemoTierNames[activeTier]}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            SectionCard(
                title = "02 · 燃速演示",
                subtitle = "升温 420ms FastOutSlowIn；降温 4200ms LinearOutSlowIn，不跳变。"
            ) {
                Button(
                    onClick = { startSimulation() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚡ 模拟秒杀一词", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = if (simulatedTier != null) {
                        "高能升温中，随后 4.2s 缓慢冷却回 Tier1…"
                    } else {
                        "点击瞬间触发 Tier4，随后展示 4.2s 冷却回 Tier1。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(
                title = "03 · 24 套色盘",
                subtitle = "自动模式每 14 秒在 6 套池内连续 lerp 流转；手动模式锁定单套。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !manualPalette,
                        onClick = { manualPalette = false },
                        label = { Text("14s 自动流转") }
                    )
                    FilterChip(
                        selected = manualPalette,
                        onClick = { manualPalette = true },
                        label = { Text("手动锁定单套") }
                    )
                }

                if (manualPalette) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { manualIndex = (manualIndex + 23) % 24 }
                        ) {
                            Text("上一套")
                        }
                        OutlinedButton(
                            onClick = { manualIndex = (manualIndex + 1) % 24 }
                        ) {
                            Text("下一套")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "当前 #${displayNumber.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = displayPalette.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = displayTierLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PaletteSwatches(displayPalette)
                }
            }

            Text(
                text = "Tier0 迟缓 >22s · Tier1 13~22s · Tier2 10~13s · Tier3 8~10s · Tier4 ≤6s；6~8s 按 Tier4 处理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SectionCard(
                title = "04 · 切卡过场实验室",
                subtitle = "轻量 GPU 方案：ghost 位移 + 轻微旋转 + 淡出，新卡 120ms 淡入。"
            ) {
                val cardLight = rememberHoloLightState(motionEnabled = true)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .testTag("ui_demo_transition_card")
                        .onGloballyPositioned { previewCardBounds = it.boundsInRoot() }
                ) {
                    DeckStackUnderlay(
                        cardStyle = HoloCardStyle.PEARL,
                        modifier = Modifier.fillMaxSize()
                    )
                    CardEntrance(
                        controller = cardTransition,
                        contentKey = previewKey,
                        motionEnabled = true,
                        cornerRadius = 28.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        HoloCardTheme(style = HoloCardStyle.PEARL) {
                            HoloCardSurface(
                                style = HoloCardStyle.PEARL,
                                light = cardLight,
                                isBack = false,
                                glossHigh = true,
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                TransitionPreviewContent()
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExitStyleChip(
                        label = "认识 · 右滑出",
                        style = CardExitStyle.SLIDE_RIGHT,
                        selectedStyle = previewStyle,
                        modifier = Modifier.weight(1f)
                    ) { playExitCard(CardExitStyle.SLIDE_RIGHT) }
                    ExitStyleChip(
                        label = "不认识 · 左滑出",
                        style = CardExitStyle.SLIDE_LEFT,
                        selectedStyle = previewStyle,
                        modifier = Modifier.weight(1f)
                    ) { playExitCard(CardExitStyle.SLIDE_LEFT) }
                    ExitStyleChip(
                        label = "原地淡出",
                        style = CardExitStyle.FADE,
                        selectedStyle = previewStyle,
                        modifier = Modifier.weight(1f)
                    ) { playExitCard(CardExitStyle.FADE) }
                }
                Text(
                    text = "当前退场：${previewStyle.demoLabel()} · 已播放 $previewKey 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 铺满页面根部的特效画布；Canvas 没有 pointerInput，不拦滚动与胶囊点击。
        CardTransitionOverlay(controller = cardTransition, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 04 实验室里的假词卡内容：浅色 PEARL 底 + 深墨字，颜色全部取自 HoloCardTheme。 */
@Composable
private fun TransitionPreviewContent() {
    val palette = LocalHoloCardPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PEARL · 珍珠柔光白",
                style = MaterialTheme.typography.labelSmall,
                color = palette.subInk,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "04",
                style = MaterialTheme.typography.labelMedium,
                color = palette.accent,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "resilient",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 40.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = palette.ink
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.ink.copy(alpha = 0.08f)
            ) {
                Text(
                    text = "/rɪˈzɪliənt/",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.subInk
                )
            }
            Text(
                text = "有弹性的；能恢复的",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Children are often more resilient than we expect.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.subInk,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }

        Text(
            text = "点击下方胶囊 · 退场后从右下角抽回新卡",
            style = MaterialTheme.typography.labelSmall,
            color = palette.subInk.copy(alpha = 0.85f)
        )
    }
}

/** 轻量选中态胶囊：选中只换容器色/字色，不产生全屏手势层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExitStyleChip(
    label: String,
    style: CardExitStyle,
    selectedStyle: CardExitStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selectedStyle == style,
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                softWrap = false,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    )
}

private fun CardExitStyle.demoLabel(): String = when (this) {
    CardExitStyle.SLIDE_RIGHT -> "认识 · 右滑出"
    CardExitStyle.SLIDE_LEFT -> "不认识 · 左滑出"
    CardExitStyle.FADE -> "原地淡出"
}

@Composable
private fun PreviewCard(
    tier: Int,
    palette: FlowBurnPalette,
    paletteOverride: FlowBurnPalette?,
    onPaletteSlotChanged: ((Int) -> Unit)?,
    displayNumber: Int,
    displayTierLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "实时预览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tier$tier",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "实际尺寸 · 11dp · 07/20",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowBurnProgressBar(
                currentIndex = 7,
                totalCount = 20,
                tier = tier,
                elapsedMs = 0L,
                motionEnabled = true,
                paletteOverride = paletteOverride,
                onPaletteSlotChanged = onPaletteSlotChanged
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Text(
                text = "放大检阅 · 40dp · 颗粒/暗纹/羽化收口",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowBurnProgressBar(
                currentIndex = 7,
                totalCount = 20,
                tier = tier,
                elapsedMs = 0L,
                motionEnabled = true,
                barHeight = 40.dp,
                showHeader = false,
                paletteOverride = paletteOverride
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${displayNumber.toString().padStart(2, '0')} ${palette.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayTierLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PaletteSwatches(palette: FlowBurnPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        palette.colors.forEach { color: Color ->
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

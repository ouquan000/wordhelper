package com.kaoyan.wordhelper.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.ui.component.HoloCardKind
import com.kaoyan.wordhelper.ui.component.HoloFeel
import com.kaoyan.wordhelper.ui.component.HoloFinish
import com.kaoyan.wordhelper.ui.component.HoloWordCard

/**
 * /ui-demo 页面（UI 动效沙盒）：HoloWordCard 的预览沙盒。
 *
 * 主题说明：工程主题（ui/theme/Theme.kt + Color.kt）中原本没有
 * 「暗黑工作室 Dark Studio」与「暖色纸质手账 Warm Paper Stationery」的 token，
 * 只有 KaoyanWordTheme 的 light/dark 两套 Material3 配色。
 * 因此本页用「局部主题」实现这两套预览皮肤：仅在本页内包裹一层
 * MaterialTheme(colorScheme = ...)，不修改全局主题、不改 MainActivity。
 * 页面背景只用 Compose 渐变/纯色（无流体着色器、无新增依赖）。
 *
 * 布局（12-layout-fix 紧凑化）：首屏必须完整看到整张卡（含正面单词）。
 * 顶部一行紧凑主题行（36dp）→ 卡片紧跟（0.76 宽、上下 8dp、不 clip 留阴影空间）
 * → 材质/手感/类型三行紧凑行（36dp/行，小标签 + 横滚胶囊）→ 底部提示。
 * 1080x2340 @ density2.75 预算 ≈614dp 内容高度，可用 ≈675dp，首屏全量可见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiDemoScreen(onBack: () -> Unit) {
    // 状态 rememberSaveable：进程重建/旋转后保留当前选择。
    var isDarkStudio by rememberSaveable { mutableStateOf(true) }
    var finish by rememberSaveable { mutableStateOf(HoloFinish.ETCHED) }
    var feel by rememberSaveable { mutableStateOf(HoloFeel.STANDARD) }
    var cardKind by rememberSaveable { mutableStateOf(HoloCardKind.WORD) }

    val palette = if (isDarkStudio) DarkStudioPalette else WarmPaperPalette

    // 主题切换：全部颜色 ~300ms 过渡，避免生硬闪变。
    val bgTop by animateColorAsState(palette.bgTop, tween(durationMillis = 300), label = "ui_demo_bg_top")
    val bgBottom by animateColorAsState(palette.bgBottom, tween(durationMillis = 300), label = "ui_demo_bg_bottom")
    val onBg by animateColorAsState(palette.onBg, tween(durationMillis = 300), label = "ui_demo_on_bg")
    val onBgVariant by animateColorAsState(palette.onBgVariant, tween(durationMillis = 300), label = "ui_demo_on_bg_variant")
    val accent by animateColorAsState(palette.accent, tween(durationMillis = 300), label = "ui_demo_accent")
    val onAccent by animateColorAsState(palette.onAccent, tween(durationMillis = 300), label = "ui_demo_on_accent")
    val chipContainer by animateColorAsState(palette.chipContainer, tween(durationMillis = 300), label = "ui_demo_chip_container")
    val selectedContainer by animateColorAsState(palette.selectedContainer, tween(durationMillis = 300), label = "ui_demo_selected_container")
    val onSelectedContainer by animateColorAsState(palette.onSelectedContainer, tween(durationMillis = 300), label = "ui_demo_on_selected_container")
    val outlineColor by animateColorAsState(palette.outline, tween(durationMillis = 300), label = "ui_demo_outline")

    val localScheme = if (isDarkStudio) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = bgTop,
            surface = chipContainer,
            onSurface = onBg,
            surfaceVariant = chipContainer,
            onSurfaceVariant = onBgVariant,
            secondaryContainer = selectedContainer,
            onSecondaryContainer = onSelectedContainer,
            outline = outlineColor,
            outlineVariant = outlineColor
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = bgTop,
            surface = chipContainer,
            onSurface = onBg,
            surfaceVariant = chipContainer,
            onSurfaceVariant = onBgVariant,
            secondaryContainer = selectedContainer,
            onSecondaryContainer = onSelectedContainer,
            outline = outlineColor,
            outlineVariant = outlineColor
        )
    }

    MaterialTheme(colorScheme = localScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(bgTop, bgBottom)))
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(text = "UI 动效沙盒") },
                        navigationIcon = {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("ui_demo_back")
                            ) {
                                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // ── 主题行：标签与胶囊同一行（36dp），整行可横滚 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "主题",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = isDarkStudio,
                            onClick = {
                                if (!isDarkStudio) {
                                    isDarkStudio = true
                                    // 切换主题时自动带回该主题默认材质；手动选择不受影响。
                                    finish = HoloFinish.ETCHED
                                }
                            },
                            label = { Text(text = "暗黑工作室 Dark Studio") },
                            modifier = Modifier
                                .testTag("ui_demo_theme_dark")
                                .height(36.dp)
                        )
                        FilterChip(
                            selected = !isDarkStudio,
                            onClick = {
                                if (isDarkStudio) {
                                    isDarkStudio = false
                                    finish = HoloFinish.PEARL
                                }
                            },
                            label = { Text(text = "暖色纸质手账 Warm Paper Stationery") },
                            modifier = Modifier
                                .testTag("ui_demo_theme_warm")
                                .height(36.dp)
                        )
                    }

                    // ── 卡片：紧跟主题行。0.76 宽、440:616 比例、居中；
                    //    容器不 clip，上下各 8dp 给核心组件卡底阴影（~24dp）留绘制空间。──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        HoloWordCard(
                            finish = finish,
                            feel = feel,
                            cardKind = cardKind,
                            modifier = Modifier
                                .fillMaxWidth(0.76f)
                                .aspectRatio(440f / 616f)
                                .testTag("ui_demo_card")
                        )
                    }

                    // ── 材质：紧凑行（小标签 + 横滚胶囊，36dp）──
                    UiDemoCompactRow(label = "材质") {
                        HoloFinish.entries.forEach { item ->
                            FilterChip(
                                selected = item == finish,
                                onClick = { finish = item },
                                label = { Text(text = uiDemoFinishLabel(item)) },
                                modifier = Modifier
                                    .testTag("ui_demo_finish_${item.name.lowercase()}")
                                    .height(36.dp)
                            )
                        }
                    }

                    // ── 手感 ──
                    UiDemoCompactRow(label = "手感") {
                        FilterChip(
                            selected = feel == HoloFeel.QUIET,
                            onClick = { feel = HoloFeel.QUIET },
                            label = { Text(text = "Quiet") },
                            modifier = Modifier
                                .testTag("ui_demo_feel_quiet")
                                .height(36.dp)
                        )
                        FilterChip(
                            selected = feel == HoloFeel.STANDARD,
                            onClick = { feel = HoloFeel.STANDARD },
                            label = { Text(text = "Standard") },
                            modifier = Modifier
                                .testTag("ui_demo_feel_standard")
                                .height(36.dp)
                        )
                    }

                    // ── 卡片类型 ──
                    UiDemoCompactRow(label = "类型") {
                        FilterChip(
                            selected = cardKind == HoloCardKind.WORD,
                            onClick = { cardKind = HoloCardKind.WORD },
                            label = { Text(text = "单词学习主卡") },
                            modifier = Modifier
                                .testTag("ui_demo_kind_word")
                                .height(36.dp)
                        )
                        FilterChip(
                            selected = cardKind == HoloCardKind.CHECK_IN,
                            onClick = { cardKind = HoloCardKind.CHECK_IN },
                            label = { Text(text = "打卡纪念卡") },
                            modifier = Modifier
                                .testTag("ui_demo_kind_checkin")
                                .height(36.dp)
                        )
                    }

                    // 底部操作提示（bottom ≥20dp，不被底导航视觉贴边）。
                    Text(
                        text = "拖动倾斜 · 点击翻面 · 静置会自己呼吸",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                    )
                }
            }
        }
    }
}

/** 紧凑行：固定宽度小标签 + weight(1f) 横滚胶囊区（行高由 36dp 胶囊决定）。 */
@Composable
private fun UiDemoCompactRow(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

private fun uiDemoFinishLabel(finish: HoloFinish): String = when (finish) {
    HoloFinish.PRISM -> "Prism"
    HoloFinish.RAINBOW -> "Rainbow"
    HoloFinish.PEARL -> "Pearl"
    HoloFinish.SPARKLE -> "Sparkle"
    HoloFinish.CHROME -> "Chrome"
    HoloFinish.ETCHED -> "Etched"
}

/** 本页局部主题调色板（仅 /ui-demo 使用，不进 ui/theme）。 */
private data class UiDemoPalette(
    val bgTop: Color,
    val bgBottom: Color,
    val onBg: Color,
    val onBgVariant: Color,
    val accent: Color,
    val onAccent: Color,
    val chipContainer: Color,
    val selectedContainer: Color,
    val onSelectedContainer: Color,
    val outline: Color
)

/** 暗黑工作室 Dark Studio：深炭黑/冷灰渐变，浅色文字；默认材质 Etched。 */
private val DarkStudioPalette = UiDemoPalette(
    bgTop = Color(0xFF0E1117),
    bgBottom = Color(0xFF151A22),
    onBg = Color(0xFFE7ECF5),
    onBgVariant = Color(0xFF98A3B4),
    accent = Color(0xFF8FB4FF),
    onAccent = Color(0xFF101F3C),
    chipContainer = Color(0xFF1A202B),
    selectedContainer = Color(0xFF2C3A57),
    onSelectedContainer = Color(0xFFC9D8FF),
    outline = Color(0xFF39424F)
)

/** 暖色纸质手账 Warm Paper Stationery：暖纸色渐变，深棕文字；默认材质 Pearl。 */
private val WarmPaperPalette = UiDemoPalette(
    bgTop = Color(0xFFF6F0E4),
    bgBottom = Color(0xFFEFE4D2),
    onBg = Color(0xFF4A3925),
    onBgVariant = Color(0xFF867356),
    accent = Color(0xFF8A6238),
    onAccent = Color(0xFFFFF6E8),
    chipContainer = Color(0xFFFFFAF0),
    selectedContainer = Color(0xFFE8D8B8),
    onSelectedContainer = Color(0xFF5C4626),
    outline = Color(0xFFD8C5A3)
)

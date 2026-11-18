package com.kaoyan.wordhelper.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.MethodCount
import com.kaoyan.wordhelper.ui.component.AppPageHeader
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.BuildConfig
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.repository.DarkMode
import com.kaoyan.wordhelper.data.repository.SyncStatusStore
import com.kaoyan.wordhelper.ui.component.HeatmapGrid
import com.kaoyan.wordhelper.ui.component.HeatmapLegend
import com.kaoyan.wordhelper.ui.component.MemoryLineChart
import com.kaoyan.wordhelper.ui.component.StandardPanel
import com.kaoyan.wordhelper.ui.viewmodel.AiLabSummary
import com.kaoyan.wordhelper.ui.viewmodel.BookStats
import com.kaoyan.wordhelper.ui.viewmodel.HeatmapCell
import com.kaoyan.wordhelper.ui.viewmodel.LineChartEntry
import com.kaoyan.wordhelper.ui.viewmodel.ProfileViewModel
import com.kaoyan.wordhelper.ui.viewmodel.ProfileStats
import com.kaoyan.wordhelper.ui.viewmodel.StatsScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = viewModel(),
    onOpenStats: () -> Unit = {},
    onOpenAiLab: () -> Unit = {},
    onOpenSyncStatus: () -> Unit = {},
    onOpenUiDemo: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    // 日夜双模式：由 AppNavigation 从 settingsFlow 传入，默认日间
    darkThemeEnabled: Boolean = false,
    onDarkThemeEnabledChange: (Boolean) -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val showBuiltinFullBooks by viewModel.showBuiltinFullBooks.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val statsScope by viewModel.statsScope.collectAsStateWithLifecycle()
    val bookStats by viewModel.bookStats.collectAsStateWithLifecycle()
    val methodCounts by viewModel.methodCounts.collectAsStateWithLifecycle()
    val bookLineData by viewModel.bookLineData.collectAsStateWithLifecycle()
    val bookHeatmap by viewModel.bookHeatmap.collectAsStateWithLifecycle()
    val aiLabSummary by viewModel.aiLabSummary.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val rewritePendingCount by viewModel.rewritePendingCount.collectAsStateWithLifecycle()
    val syncPending by viewModel.syncPending.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    // 1.4.7：状态页与「我的」入口读的是同一份快照，入口那行不用再单独请求一次。
    val syncStatus by SyncStatusStore.state.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 1.4.7.4：全局全屏沉浸开关直接写 SettingsRepository，settingsFlow 会让整棵 UI 立即跟着变。
    val uiScope = rememberCoroutineScope()
    val settingsRepository = remember(context) {
        (context.applicationContext as KaoyanWordApp).settingsRepository
    }
    var statsVisible by remember { mutableStateOf(false) }
    var dataVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var aiVisible by remember { mutableStateOf(false) }
    var aboutVisible by remember { mutableStateOf(false) }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val exportFileName = remember {
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        "kaoyan_words_backup_$date.db"
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        viewModel.exportDatabase(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingRestoreUri = uri
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSyncPending()
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAiSummary()
        statsVisible = true
        delay(45)
        dataVisible = true
        delay(45)
        settingsVisible = true
        delay(45)
        aiVisible = true
        delay(45)
        aboutVisible = true
    }

    if (pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text(text = "恢复数据") },
            text = { Text(text = "恢复会覆盖当前数据，建议先备份。确认继续？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importDatabase(pendingRestoreUri)
                    pendingRestoreUri = null
                }) {
                    Text(text = "继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text(text = "取消")
                }
            }
        )
    }

    val pageBrush = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            Color.Transparent
        )
    )

    Scaffold(
        // 状态栏内边距由外层 AppNavigation 统一处理，这里不再吃第二遍
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBrush)
                .padding(innerPadding)
        ) {
            AppPageHeader(
                title = "我的",
                onOpenDrawer = onOpenDrawer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .testTag("profile_list"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
            // UI 动效沙盒临时入口（feat/frontend-ui）：放在第一个 item，StatsCard 之前。
            item {
                // 沙盒入口：统一成标准透明细描边面板，不再单独铺 primary 实心底；
                // 标题与图标仍用 primary 做强调，日夜模式都跟着主题走。
                StandardPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_ui_demo_entry"),
                    onClick = onOpenUiDemo
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "进入 UI 动效沙盒",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "心流燃烧进度条：速度档位 / 24 套色盘 / 颗粒流光",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "试试 ›",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = statsVisible,
                    enter = fadeIn(animationSpec = tween(220)) +
                        slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(300))
                ) {
                    StatsCard(
                        stats = stats,
                        bookStats = bookStats,
                        methodCounts = methodCounts,
                        bookLineData = bookLineData,
                        bookHeatmap = bookHeatmap,
                        statsScope = statsScope,
                        onStatsScopeChange = viewModel::setStatsScope,
                        onOpenStats = onOpenStats
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = dataVisible,
                    enter = fadeIn(animationSpec = tween(220)) +
                        slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(300))
                ) {
                    DataManageCard(
                        onExport = { exportLauncher.launch(exportFileName) },
                        onImport = { importLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*")) },
                        syncPending = syncPending,
                        syncing = syncing,
                        syncMessage = syncMessage,
                        syncStatusSummary = syncStatusEntrySummary(syncStatus),
                        onSync = viewModel::syncLearningRecords,
                        onOpenSyncStatus = onOpenSyncStatus
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = settingsVisible,
                    enter = fadeIn(animationSpec = tween(220)) +
                        slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(300))
                ) {
                    SettingsCard(
                        newWordsLimit = settings.newWordsLimit,
                        fontScale = settings.fontScale,
                        darkMode = settings.darkMode,
                        plannedNewWordsEnabled = settings.plannedNewWordsEnabled,
                        showBuiltinFullBooks = showBuiltinFullBooks,
                        cardBackUrl = settings.cardBackUrl,
                        cardBackLanAuto = settings.cardBackLanAuto,
                        immersiveFullscreenEnabled = settings.immersiveFullscreenEnabled,
                        rewritePendingCount = rewritePendingCount,
                        darkThemeEnabled = darkThemeEnabled,
                        onNewWordsLimitChange = viewModel::updateNewWordsLimit,
                        onFontScaleChange = viewModel::updateFontScale,
                        onDarkModeChange = viewModel::updateDarkMode,
                        onPlannedNewWordsEnabledChange = viewModel::updatePlannedNewWordsEnabled,
                        onShowBuiltinFullBooksChange = viewModel::updateShowBuiltinFullBooks,
                        onCardBackUrlChange = viewModel::updateCardBackUrl,
                        onCardBackLanAutoChange = viewModel::updateCardBackLanAuto,
                        onDarkThemeEnabledChange = onDarkThemeEnabledChange,
                        onImmersiveFullscreenEnabledChange = { enabled ->
                            uiScope.launch {
                                settingsRepository.updateImmersiveFullscreenEnabled(enabled)
                            }
                        }
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = aiVisible,
                    enter = fadeIn(animationSpec = tween(220)) +
                        slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(300))
                ) {
                    LabEntryCard(
                        summary = aiLabSummary,
                        onOpenAiLab = onOpenAiLab
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = aboutVisible,
                    enter = fadeIn(animationSpec = tween(220)) +
                        slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(300))
                ) {
                    AboutCard()
                }
            }
            }
        }
    }
}

@Composable
private fun StatsCard(
    stats: ProfileStats,
    bookStats: BookStats,
    methodCounts: List<MethodCount>,
    bookLineData: List<LineChartEntry>,
    bookHeatmap: List<HeatmapCell>,
    statsScope: StatsScope,
    onStatsScopeChange: (StatsScope) -> Unit,
    onOpenStats: () -> Unit
) {
    // 外层统一用标准透明面板：20dp 圆角 + 1dp 细描边，氛围背景直接透上来。
    // 内部图表（折线图 / 热力图）本身是透明绘制，不需要再套实心方块。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 标题加 weight：字体放大档（settings.fontScale）下让标题先让位，切换按钮不被挤出屏幕。
                Text(
                    text = "学习统计",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 右上角口径切换：全局 = 事件「次数」（跨全部词书）；当前词书 = 去重「词数」（单本）。
                // 两套数字不是同一口径，不做合计、也不互相换算。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = statsScope == StatsScope.GLOBAL,
                        onClick = { onStatsScopeChange(StatsScope.GLOBAL) },
                        label = { Text(text = "全局", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.testTag("profile_stats_scope_global")
                    )
                    FilterChip(
                        selected = statsScope == StatsScope.BOOK,
                        onClick = { onStatsScopeChange(StatsScope.BOOK) },
                        label = { Text(text = "当前词书", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.testTag("profile_stats_scope_book")
                    )
                }
            }
            if (statsScope == StatsScope.GLOBAL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem(label = "连续打卡", value = "${stats.streakDays}", unit = "天")
                    StatItem(label = "本周打卡", value = "${stats.weekCheckInDays}", unit = "天")
                    StatItem(label = "近7天学习", value = "${stats.weekStudyCount}", unit = "次")
                    StatItem(label = "今日学习", value = "${stats.todayStudyCount}", unit = "次")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem(label = "近7天拼写", value = "${stats.weekSpellCount}", unit = "次")
                    StatItem(label = "今日拼写", value = "${stats.todaySpellCount}", unit = "次")
                }
                Text(text = "过去一周趋势", style = MaterialTheme.typography.titleMedium)
                if (stats.weekLineData.isEmpty()) {
                    EmptyState(text = "暂无学习记录")
                } else {
                    MemoryLineChart(
                        data = stats.weekLineData,
                        modifier = Modifier.height(180.dp),
                        showLegend = false
                    )
                }
                Text(text = "日历热力图", style = MaterialTheme.typography.titleMedium)
                if (stats.heatmap.isEmpty()) {
                    EmptyState(text = "暂无学习记录")
                } else {
                    HeatmapGrid(cells = stats.heatmap)
                    HeatmapLegend()
                }
            } else if (!bookStats.hasBook) {
                EmptyState(text = "暂无可统计的词书")
            } else {
                Text(
                    text = bookStats.bookName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem(label = "累计已学", value = "${bookStats.learnedCount}", unit = "词")
                    StatItem(label = "已掌握", value = "${bookStats.masteredCount}", unit = "词")
                    StatItem(label = "待复习", value = "${bookStats.dueCount}", unit = "词")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 没有复习记录时显示「—」，不算 0%、更不会出现 NaN。
                    StatItem(
                        label = "正确率",
                        value = bookStats.accuracyPercent?.toString() ?: "—",
                        unit = if (bookStats.accuracyPercent == null) "" else "%"
                    )
                    StatItem(label = "今日接触", value = "${bookStats.todayTouchedCount}", unit = "词")
                }
                Text(
                    text = "按当前词书去重统计；与全局的「次」不是同一口径，两者不能相加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = "过去一周趋势（这本书）", style = MaterialTheme.typography.titleMedium)
                if (bookLineData.all { it.newCount == 0 && it.reviewCount == 0 }) {
                    EmptyState(text = "从今天起按书记账，明天就能看到曲线")
                } else {
                    MemoryLineChart(
                        data = bookLineData,
                        modifier = Modifier.height(180.dp),
                        showLegend = false
                    )
                }
                Text(text = "日历热力图（这本书）", style = MaterialTheme.typography.titleMedium)
                // 空态判断用「有没有量」而不是 isEmpty：热力图窗口本身永远有格子，
                // 没学过这本书时是一整片 0，看 isEmpty 会永远显示一张全空的图。
                if (bookHeatmap.none { it.count > 0 }) {
                    EmptyState(text = "暂无学习记录（从升级到 1.4.5 当天开始按书记账）")
                } else {
                    HeatmapGrid(cells = bookHeatmap)
                    HeatmapLegend()
                }
                Text(
                    text = "按书记账从升级到 1.4.5 当天开始，更早的历史没有按书拆分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 按记忆方法分布：与上面两套口径都不冲突，计的是「词」；跟着口径切换一起换数据源。
            MethodBreakdown(counts = methodCounts)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onOpenStats,
                    modifier = Modifier.testTag("profile_open_stats")
                ) {
                    Text(text = "查看学习数据")
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(text = unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 「按记忆方法」分布：主招 × 词数，按多少降序，最多 6 个。没有已学词就整块不出现。 */
@Composable
private fun MethodBreakdown(counts: List<MethodCount>) {
    // 分子分母必须同口径：只统计有标签的词，没标签的单独报一句，否则「加起来对不上 N」像丢数据。
    val labeled = counts.filter { it.method.isNotBlank() && it.count > 0 }
    val total = labeled.sumOf { it.count }
    if (total <= 0) return
    val unlabeled = counts.filter { it.method.isBlank() }.sumOf { it.count }
    val parts = labeled
        .sortedByDescending { it.count }
        .take(6)
        .map { "${methodLabelOfProfile(it.method)} ${it.count}" }
    if (parts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "按记忆方法（已标注 $total 词）",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (unlabeled > 0) {
            Text(
                text = "另有 $unlabeled 词还没有标签（重写思路后会补上）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 方法键 → 中文标签；这里只用于统计展示，卡背上的胶囊另有实现。 */
private fun methodLabelOfProfile(method: String): String = when (method) {
    "sound" -> "谐音"
    "meaning" -> "联想"
    "shape" -> "字形"
    "root" -> "词根"
    "contrast" -> "对比"
    "polysemy" -> "多义"
    else -> method
}

@Composable
private fun DataManageCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
    syncPending: Int,
    syncing: Boolean,
    syncMessage: String?,
    syncStatusSummary: String,
    onSync: () -> Unit,
    onOpenSyncStatus: () -> Unit
) {
    // 数据管理卡：透明底 + 细描边，交给 StandardPanel 统一处理日夜两种描边色。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "数据管理", style = MaterialTheme.typography.titleLarge)
            Text(text = "备份可导出 .db 文件，恢复将覆盖当前数据。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onExport) {
                    Text(text = "数据备份")
                }
                OutlinedButton(onClick = onImport) {
                    Text(text = "数据恢复")
                }
            }
            Text(
                text = "学习记录可以跟电脑对一下：手机先在本地排队，同一 WiFi 下点同步才联网；" +
                    "冲突按「最后复习时间」自动判，不弹窗。不点也不影响背诵，换设备前点一次就行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSync,
                    enabled = !syncing,
                    modifier = Modifier.testTag("profile_sync_now")
                ) {
                    Text(
                        text = when {
                            syncing -> "正在同步…"
                            syncPending > 0 -> "同步学习记录（$syncPending）"
                            else -> "同步学习记录"
                        }
                    )
                }
                if (!syncMessage.isNullOrBlank()) {
                    Text(
                        text = syncMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 1.4.7：同步状态入口。一行就够 —— 当前阶段 + 最后成功时间，详情进状态页。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSyncStatus)
                    .testTag("profile_sync_status")
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "同步状态",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = syncStatusSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "查看 ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    newWordsLimit: Int,
    fontScale: Float,
    darkMode: DarkMode,
    plannedNewWordsEnabled: Boolean,
    showBuiltinFullBooks: Boolean,
    cardBackUrl: String,
    cardBackLanAuto: Boolean,
    immersiveFullscreenEnabled: Boolean,
    rewritePendingCount: Int,
    // 日夜双模式总开关（旧 darkMode 字段保留兼容）
    darkThemeEnabled: Boolean,
    onNewWordsLimitChange: (Int) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    onDarkThemeEnabledChange: (Boolean) -> Unit,
    onPlannedNewWordsEnabledChange: (Boolean) -> Unit,
    onShowBuiltinFullBooksChange: (Boolean) -> Unit,
    onCardBackUrlChange: (String) -> Unit,
    onCardBackLanAutoChange: (Boolean) -> Unit,
    onImmersiveFullscreenEnabledChange: (Boolean) -> Unit
) {
    // 电脑端给的是「地址#口令」一整行，这里只让用户粘一次，解析在 SettingsRepository 里做。
    var cardBackUrlInput by remember(cardBackUrl) { mutableStateOf(cardBackUrl) }
    var dailyNewWordsInput by remember(newWordsLimit) { mutableStateOf(newWordsLimit.toString()) }
    val parsedDailyNewWords = dailyNewWordsInput.toIntOrNull()
    val canSaveDailyNewWords = parsedDailyNewWords != null &&
        parsedDailyNewWords in 1..500 &&
        parsedDailyNewWords != newWordsLimit

    // 学习设置卡：同样收进标准透明面板，内部间距保持 16dp 不变。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "学习设置", style = MaterialTheme.typography.titleLarge)
            // 日夜双模式总开关：放在最顶部，和抽屉里的「外观」快捷开关共用同一份设置。
            SettingRow(
                title = "外观模式",
                options = listOf(false, true),
                optionLabels = listOf("日间", "夜间"),
                optionTags = listOf("settings_theme_light", "settings_theme_dark"),
                selected = darkThemeEnabled,
                onSelect = onDarkThemeEnabledChange
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "每日新学词数（不含待复习）", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dailyNewWordsInput,
                        onValueChange = { input ->
                            dailyNewWordsInput = input.filter { it.isDigit() }.take(3)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(text = "1-500") }
                    )
                    TextButton(
                        onClick = { parsedDailyNewWords?.let(onNewWordsLimitChange) },
                        enabled = canSaveDailyNewWords
                    ) {
                        Text(text = "保存")
                    }
                }
                Text(
                    text = "待复习单词会自动加入学习队列，不计入每日新学词数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingRow(
                title = "字号",
                options = listOf(0.9f, 1.0f, 1.1f),
                optionLabels = listOf("小", "标准", "大"),
                selected = fontScale,
                onSelect = onFontScaleChange
            )
            // 旧「深色模式」三选项行保留兼容（tags 不动）：实际状态统一映射到
            // darkThemeEnabled，避免和上面的「外观模式」两套开关打架。
            SettingRow(
                title = "深色模式",
                options = listOf(DarkMode.FOLLOW_SYSTEM, DarkMode.LIGHT, DarkMode.DARK),
                optionLabels = listOf("跟随系统", "浅色", "深色"),
                optionTags = listOf("settings_dark_follow", "settings_dark_light", "settings_dark_dark"),
                selected = if (darkThemeEnabled) DarkMode.DARK else DarkMode.LIGHT,
                onSelect = { onDarkThemeEnabledChange(it == DarkMode.DARK) }
            )
            SettingRow(
                title = "规划单词",
                options = listOf(false, true),
                optionLabels = listOf("关闭", "开启"),
                optionTags = listOf("settings_plan_words_off", "settings_plan_words_on"),
                selected = plannedNewWordsEnabled,
                onSelect = onPlannedNewWordsEnabledChange
            )
            Text(
                text = "开启后将停止自动推送新词，仅按“今日新词（自主选择）”中的规划进行学习。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingRow(
                title = "内置大词库",
                options = listOf(false, true),
                optionLabels = listOf("隐藏", "显示"),
                optionTags = listOf("settings_full_books_hidden", "settings_full_books_shown"),
                selected = showBuiltinFullBooks,
                onSelect = onShowBuiltinFullBooksChange
            )
            Text(
                text = "「完整词库」「完整词库（精选）」各 5000 多词，日常背词用不到；隐藏只是不显示、不导入，开回来下次启动生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingRow(
                title = "同 WiFi 自动直连",
                options = listOf(false, true),
                optionLabels = listOf("关闭", "开启"),
                optionTags = listOf("settings_lan_auto_off", "settings_lan_auto_on"),
                selected = cardBackLanAuto,
                onSelect = onCardBackLanAutoChange
            )
            Text(
                text = "开启后同一 WiFi 下会先找这台电脑的局域网地址，失败再走域名。" +
                    "仅建议在可信的家庭 WiFi 使用：同一网段的主动攻击无法被明文 HTTP 完全防住；" +
                    "在咖啡馆/公司等公共网络请保持关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingRow(
                title = "全局全屏沉浸",
                options = listOf(false, true),
                optionLabels = listOf("关闭", "开启"),
                optionTags = listOf(
                    "settings_immersive_fullscreen_off",
                    "settings_immersive_fullscreen_on"
                ),
                selected = immersiveFullscreenEnabled,
                onSelect = onImmersiveFullscreenEnabledChange
            )
            Text(
                text = "开启后学习 / 词库 / 我的三页统一隐藏系统状态栏，切换页面不再弹出；顶部下滑可临时呼出。关闭则恢复系统状态栏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "卡背结构地址", style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = cardBackUrlInput,
                        onValueChange = { cardBackUrlInput = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_cardback_url"),
                        placeholder = { Text(text = "粘贴电脑上那一行") }
                    )
                    TextButton(
                        onClick = { onCardBackUrlChange(cardBackUrlInput.trim()) },
                        enabled = cardBackUrlInput.trim() != cardBackUrl
                    ) {
                        Text(text = "保存")
                    }
                }
                Text(
                    text = "电脑上开卡背编辑器，点右下角「复制」；或看启动窗口里「手机：粘贴这一行」那行。" +
                        "整行粘进来就行，不用分开填，存一次以后自动带着用。留空 = 只用手机上的结构。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (rewritePendingCount > 0) {
                    Text(
                        text = "还有 $rewritePendingCount 条重写思路在等电脑处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SettingRow(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabels: List<String> = options.map { it.toString() },
    optionTags: List<String>? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                val label = optionLabels.getOrNull(index) ?: option.toString()
                val tag = optionTags?.getOrNull(index)
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text = label) },
                    modifier = if (tag != null) Modifier.testTag(tag) else Modifier
                )
            }
        }
    }
}

@Composable
private fun LabEntryCard(
    summary: AiLabSummary,
    onOpenAiLab: () -> Unit
) {
    // 实验室入口：透明细描边面板，标题与正文沿用主题文字色。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "实验室", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "AI：${summary.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "包含 AI 实验室、发音实验室、算法实验室。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenAiLab,
                modifier = Modifier.testTag("profile_open_ai_lab")
            ) {
                Text(text = "进入实验室")
            }
        }
    }
}

@Composable
private fun AboutCard() {
    // 版本号现读系统里的安装信息：BuildConfig 的常量会被增量编译内联成旧值（1.4.5 时还显示 1.4.4）。
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // 关于卡：统一透明细描边，版本信息等文案全部走主题色。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "关于软件", style = MaterialTheme.typography.titleLarge)
            Text(text = "真的不想背单词了:(", fontWeight = FontWeight.SemiBold)
            Text(text = "版本 $versionName", style = MaterialTheme.typography.bodySmall)
            Text(text = "完全离线运行，无广告干扰。", style = MaterialTheme.typography.bodySmall)
        }
    }
}


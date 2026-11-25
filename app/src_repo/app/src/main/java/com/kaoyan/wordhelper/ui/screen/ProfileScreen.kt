package com.kaoyan.wordhelper.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.MethodCount
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.repository.SyncStatusStore
import com.kaoyan.wordhelper.util.AutoBackupManager
import com.kaoyan.wordhelper.ui.component.HeatmapGrid
import com.kaoyan.wordhelper.ui.component.HeatmapLegend
import com.kaoyan.wordhelper.ui.component.MemoryLineChart
import com.kaoyan.wordhelper.ui.component.StandardPanel
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
    val summary by viewModel.aiLabSummary.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val rewritePendingCount by viewModel.rewritePendingCount.collectAsStateWithLifecycle()
    val rewriteQueueBreakdown by viewModel.rewriteQueueBreakdown.collectAsStateWithLifecycle()
    val syncPending by viewModel.syncPending.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    // 1.4.7：状态页与「我的」入口读的是同一份快照，入口那行不用再单独请求一次。
    val syncStatus by SyncStatusStore.state.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val wordbookState by viewModel.wordbookUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 自动备份在 Application 启动后 4 秒才跑（见 KaoyanWordApp.onCreate），之前只读一次
    // 永远读到「启动后会自动备份一次」的旧状态 —— 1.5.0 的 bug：首次备份完成后页面不刷新。
    // 改成进页面后按 2 秒一格轮询：拿到新时间戳就停，最多 20 格（约 40 秒）后也停，不留常驻轮询。
    var autoBackupState by remember { mutableStateOf(AutoBackupManager.readState(context)) }
    LaunchedEffect(Unit) {
        val initial = autoBackupState
        repeat(20) {
            delay(2_000)
            val latest = AutoBackupManager.readState(context)
            if (latest != initial) {
                autoBackupState = latest
                return@LaunchedEffect
            }
        }
    }
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

    ProfilePageLayout(onOpenDrawer, onOpenUiDemo) {
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
                ProfileDataCard(
                    onExport = { exportLauncher.launch(exportFileName) },
                    onImport = { importLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*")) },
                    syncPending = syncPending,
                    syncing = syncing,
                    syncMessage = syncMessage,
                    syncStatusSummary = syncStatusEntrySummary(syncStatus),
                    onSync = viewModel::syncLearningRecords,
                    onOpenSyncStatus = onOpenSyncStatus,
                    wordbookState = wordbookState,
                    onSyncWordbook = viewModel::syncWordbook,
                    autoBackupLabel = autoBackupStatusText(autoBackupState)
                )
            }
        }
        item {
            AnimatedVisibility(
                visible = settingsVisible,
                enter = fadeIn(animationSpec = tween(220)) +
                    slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(300))
            ) {
                ProfileSettingsCard(
                    newWordsLimit = settings.newWordsLimit,
                    fontScale = settings.fontScale,
                    darkMode = settings.darkMode,
                    plannedNewWordsEnabled = settings.plannedNewWordsEnabled,
                    showBuiltinFullBooks = showBuiltinFullBooks,
                    cardBackUrl = settings.cardBackUrl,
                    cardBackLanAuto = settings.cardBackLanAuto,
                    wordbookRemoteUrl = settings.wordbookRemoteUrl,
                    immersiveFullscreenEnabled = settings.immersiveFullscreenEnabled,
                    rewritePendingCount = rewritePendingCount,
                    rewriteQueueBreakdown = rewriteQueueBreakdown,
                    darkThemeEnabled = darkThemeEnabled,
                    onNewWordsLimitChange = viewModel::updateNewWordsLimit,
                    onFontScaleChange = viewModel::updateFontScale,
                    onDarkModeChange = viewModel::updateDarkMode,
                    onPlannedNewWordsEnabledChange = viewModel::updatePlannedNewWordsEnabled,
                    onShowBuiltinFullBooksChange = viewModel::updateShowBuiltinFullBooks,
                    onCardBackUrlChange = viewModel::updateCardBackUrl,
                    onCardBackLanAutoChange = viewModel::updateCardBackLanAuto,
                    onWordbookRemoteUrlChange = viewModel::updateWordbookRemoteUrl,
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
                ProfileLabEntryCard(
                    statusText = "AI：${summary.status}",
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
                ProfileAboutCard()
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
                    ProfileStatItem(label = "连续打卡", value = "${stats.streakDays}", unit = "天")
                    ProfileStatItem(label = "本周打卡", value = "${stats.weekCheckInDays}", unit = "天")
                    ProfileStatItem(label = "近7天学习", value = "${stats.weekStudyCount}", unit = "次")
                    ProfileStatItem(label = "今日学习", value = "${stats.todayStudyCount}", unit = "次")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ProfileStatItem(label = "近7天拼写", value = "${stats.weekSpellCount}", unit = "次")
                    ProfileStatItem(label = "今日拼写", value = "${stats.todaySpellCount}", unit = "次")
                }
                Text(text = "过去一周趋势", style = MaterialTheme.typography.titleMedium)
                if (stats.weekLineData.isEmpty()) {
                    ProfileEmptyState(text = "暂无学习记录")
                } else {
                    MemoryLineChart(
                        data = stats.weekLineData,
                        modifier = Modifier.height(180.dp),
                        showLegend = false
                    )
                }
                Text(text = "日历热力图", style = MaterialTheme.typography.titleMedium)
                if (stats.heatmap.isEmpty()) {
                    ProfileEmptyState(text = "暂无学习记录")
                } else {
                    HeatmapGrid(cells = stats.heatmap)
                    HeatmapLegend()
                }
            } else if (!bookStats.hasBook) {
                ProfileEmptyState(text = "暂无可统计的词书")
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
                    ProfileStatItem(label = "累计已学", value = "${bookStats.learnedCount}", unit = "词")
                    ProfileStatItem(label = "已掌握", value = "${bookStats.masteredCount}", unit = "词")
                    ProfileStatItem(label = "待复习", value = "${bookStats.dueCount}", unit = "词")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 没有复习记录时显示「—」，不算 0%、更不会出现 NaN。
                    ProfileStatItem(
                        label = "正确率",
                        value = bookStats.accuracyPercent?.toString() ?: "—",
                        unit = if (bookStats.accuracyPercent == null) "" else "%"
                    )
                    ProfileStatItem(label = "今日接触", value = "${bookStats.todayTouchedCount}", unit = "词")
                }
                Text(
                    text = "按当前词书去重统计；与全局的「次」不是同一口径，两者不能相加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = "过去一周趋势（这本书）", style = MaterialTheme.typography.titleMedium)
                if (bookLineData.all { it.newCount == 0 && it.reviewCount == 0 }) {
                    ProfileEmptyState(text = "从今天起按书记账，明天就能看到曲线")
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
                    ProfileEmptyState(text = "暂无学习记录（从升级到 1.4.5 当天开始按书记账）")
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
            ProfileMethodBreakdown(counts = methodCounts)
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

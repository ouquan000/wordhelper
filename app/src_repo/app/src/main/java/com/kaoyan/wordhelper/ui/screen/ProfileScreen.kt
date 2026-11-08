package com.kaoyan.wordhelper.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.MethodCount
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.BuildConfig
import com.kaoyan.wordhelper.data.repository.DarkMode
import com.kaoyan.wordhelper.ui.component.HeatmapGrid
import com.kaoyan.wordhelper.ui.component.HeatmapLegend
import com.kaoyan.wordhelper.ui.component.MemoryLineChart
import com.kaoyan.wordhelper.ui.viewmodel.AiLabSummary
import com.kaoyan.wordhelper.ui.viewmodel.BookStats
import com.kaoyan.wordhelper.ui.viewmodel.ProfileViewModel
import com.kaoyan.wordhelper.ui.viewmodel.ProfileStats
import com.kaoyan.wordhelper.ui.viewmodel.StatsScope
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = viewModel(),
    onOpenStats: () -> Unit = {},
    onOpenAiLab: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val showBuiltinFullBooks by viewModel.showBuiltinFullBooks.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val statsScope by viewModel.statsScope.collectAsStateWithLifecycle()
    val bookStats by viewModel.bookStats.collectAsStateWithLifecycle()
    val methodCounts by viewModel.methodCounts.collectAsStateWithLifecycle()
    val aiLabSummary by viewModel.aiLabSummary.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val rewritePendingCount by viewModel.rewritePendingCount.collectAsStateWithLifecycle()
    val syncPending by viewModel.syncPending.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "我的") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBrush)
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("profile_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                        onSync = viewModel::syncLearningRecords
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
                        cardBackToken = settings.cardBackToken,
                        rewritePendingCount = rewritePendingCount,
                        onNewWordsLimitChange = viewModel::updateNewWordsLimit,
                        onFontScaleChange = viewModel::updateFontScale,
                        onDarkModeChange = viewModel::updateDarkMode,
                        onPlannedNewWordsEnabledChange = viewModel::updatePlannedNewWordsEnabled,
                        onShowBuiltinFullBooksChange = viewModel::updateShowBuiltinFullBooks,
                        onCardBackUrlChange = viewModel::updateCardBackUrl,
                        onCardBackTokenChange = viewModel::updateCardBackToken
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

@Composable
private fun StatsCard(
    stats: ProfileStats,
    bookStats: BookStats,
    methodCounts: List<MethodCount>,
    statsScope: StatsScope,
    onStatsScopeChange: (StatsScope) -> Unit,
    onOpenStats: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
    val total = counts.sumOf { it.count }
    if (total <= 0) return
    val parts = counts
        .filter { it.method.isNotBlank() && it.count > 0 }
        .sortedByDescending { it.count }
        .take(6)
        .map { "${methodLabelOfProfile(it.method)} ${it.count}" }
    if (parts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "按记忆方法（已学 $total 词）",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    onSync: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
    cardBackToken: String,
    rewritePendingCount: Int,
    onNewWordsLimitChange: (Int) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    onPlannedNewWordsEnabledChange: (Boolean) -> Unit,
    onShowBuiltinFullBooksChange: (Boolean) -> Unit,
    onCardBackUrlChange: (String) -> Unit,
    onCardBackTokenChange: (String) -> Unit
) {
    var cardBackUrlInput by remember(cardBackUrl) { mutableStateOf(cardBackUrl) }
    // 口令是长串随机字符，只显示圆点；粘贴进来能直接存。
    var cardBackTokenInput by remember(cardBackToken) { mutableStateOf(cardBackToken) }
    var dailyNewWordsInput by remember(newWordsLimit) { mutableStateOf(newWordsLimit.toString()) }
    val parsedDailyNewWords = dailyNewWordsInput.toIntOrNull()
    val canSaveDailyNewWords = parsedDailyNewWords != null &&
        parsedDailyNewWords in 1..500 &&
        parsedDailyNewWords != newWordsLimit

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "学习设置", style = MaterialTheme.typography.titleLarge)
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
            SettingRow(
                title = "深色模式",
                options = listOf(DarkMode.FOLLOW_SYSTEM, DarkMode.LIGHT, DarkMode.DARK),
                optionLabels = listOf("跟随系统", "浅色", "深色"),
                optionTags = listOf("settings_dark_follow", "settings_dark_light", "settings_dark_dark"),
                selected = darkMode,
                onSelect = onDarkModeChange
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
                        placeholder = { Text(text = "http://192.168.1.10:8765") }
                    )
                    TextButton(
                        onClick = { onCardBackUrlChange(cardBackUrlInput.trim()) },
                        enabled = cardBackUrlInput.trim() != cardBackUrl
                    ) {
                        Text(text = "保存")
                    }
                }
                Text(
                    text = "电脑上跑着卡背编辑器时填它的地址；只填 IP:端口也行，会自动补成 /cardback.json。" +
                        "留空 = 只用手机上的结构。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "连接口令", style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = cardBackTokenInput,
                        onValueChange = { cardBackTokenInput = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_cardback_token"),
                        placeholder = { Text(text = "电脑端启动窗口里的「手机口令」") }
                    )
                    TextButton(
                        onClick = { onCardBackTokenChange(cardBackTokenInput.trim()) },
                        enabled = cardBackTokenInput.trim() != cardBackToken
                    ) {
                        Text(text = "保存")
                    }
                }
                Text(
                    text = "电脑端第一次启动会生成并打印一串口令（存在 tools/cardback-editor/.cardback_token）。" +
                        "手机和电脑不在同一台机器上时必须填，否则会提示「口令不对」；" +
                        "在电脑本机开浏览器编辑卡背不用填。",
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "关于软件", style = MaterialTheme.typography.titleLarge)
            Text(text = "真的不想背单词了:(", fontWeight = FontWeight.SemiBold)
            Text(text = "版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text(text = "完全离线运行，无广告干扰。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.dao.RewriteQueueBreakdown
import com.kaoyan.wordhelper.data.repository.DarkMode
import com.kaoyan.wordhelper.ui.component.StandardPanel

@Composable
internal fun ProfileSettingsCard(
    newWordsLimit: Int,
    fontScale: Float,
    darkMode: DarkMode,
    plannedNewWordsEnabled: Boolean,
    showBuiltinFullBooks: Boolean,
    cardBackUrl: String,
    cardBackLanAuto: Boolean,
    wordbookRemoteUrl: String,
    immersiveFullscreenEnabled: Boolean,
    rewritePendingCount: Int,
    rewriteQueueBreakdown: RewriteQueueBreakdown = RewriteQueueBreakdown(),
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
    onWordbookRemoteUrlChange: (String) -> Unit,
    onImmersiveFullscreenEnabledChange: (Boolean) -> Unit
) {
    // 电脑端给的是「地址#口令」一整行，这里只让用户粘一次，解析在 SettingsRepository 里做。
    var cardBackUrlInput by remember(cardBackUrl) { mutableStateOf(cardBackUrl) }
    var wordbookRemoteInput by remember(wordbookRemoteUrl) { mutableStateOf(wordbookRemoteUrl) }
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
                text = "默认开启；已配对后同一 WiFi 下会先找这台电脑的局域网地址，局域网不通再走域名。" +
                    "未配对时不会自动发现，仍按手填地址或配对码连接。" +
                    "仅建议在可信的家庭 WiFi 使用：同一网段的主动攻击无法被明文 HTTP 完全防住；" +
                    "在咖啡馆/公司等公共网络建议关闭。",
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "词卡公网同步地址（可选）", style = MaterialTheme.typography.bodySmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = wordbookRemoteInput,
                            onValueChange = { wordbookRemoteInput = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_wordbook_remote_url"),
                            placeholder = { Text(text = "https://xxx.trycloudflare.com") }
                        )
                        TextButton(
                            onClick = { onWordbookRemoteUrlChange(wordbookRemoteInput.trim()) },
                            enabled = wordbookRemoteInput.trim() != wordbookRemoteUrl
                        ) {
                            Text(text = "保存")
                        }
                    }
                    Text(
                        text = "在局域网「卡背结构地址」之外再配一个公网地址：同一 WiFi 先走局域网，" +
                            "2 秒连不上就自动切这里；只填域名，不用带 /cards/... 路径。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (rewritePendingCount > 0) {
                    Text(
                        text = "待上传 ${rewriteQueueBreakdown.pendingUpload} 条 · 电脑处理中 ${rewriteQueueBreakdown.processing} 条",
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

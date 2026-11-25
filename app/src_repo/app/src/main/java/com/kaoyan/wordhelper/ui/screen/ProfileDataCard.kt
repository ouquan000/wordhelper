package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.repository.WordbookUpdateState
import com.kaoyan.wordhelper.util.AutoBackupManager
import com.kaoyan.wordhelper.ui.component.StandardPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ProfileDataCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
    syncPending: Int,
    syncing: Boolean,
    syncMessage: String?,
    syncStatusSummary: String,
    onSync: () -> Unit,
    onOpenSyncStatus: () -> Unit,
    wordbookState: WordbookUpdateState,
    onSyncWordbook: () -> Unit,
    autoBackupLabel: String
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
                text = autoBackupLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = "词卡热更", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "电脑端发布了新的记忆卡内容，点一下就能拉下来；不用重装 APK，" +
                    "也不会动你的学习进度和手改过的助记。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 1.5.1：状态/错误提示从按钮右边挪到按钮正下方整行 —— 长文案（「局域网连不上，且未配置
            // 公网地址；请检查电脑 IP、网络或到「我的」页填写公网地址」）在窄栏里会被挤成一列竖字。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onSyncWordbook,
                    enabled = !wordbookState.syncing,
                    modifier = Modifier.testTag("profile_wordbook_sync")
                ) {
                    Text(text = if (wordbookState.syncing) "正在同步词卡…" else "同步最新词卡")
                }
                Text(
                    text = wordbookStatusText(wordbookState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_wordbook_status")
                )
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

/** 自动备份状态一行：这是「安静的说明」，不抢备份/恢复按钮的位置。 */
internal fun autoBackupStatusText(state: AutoBackupManager.AutoBackupState): String {
    val base = "自动备份已开启 · 存至 下载/${AutoBackupManager.BACKUP_DIR_NAME}"
    if (state.lastSuccessAt <= 0L) return "$base · 启动后会自动备份一次"
    val time = Instant.ofEpochMilli(state.lastSuccessAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    return "$base · 上次：$time"
}

/** 词卡同步状态一行：优先显示本次操作的反馈，否则显示上次成功时间。 */
private fun wordbookStatusText(state: WordbookUpdateState): String {
    state.lastMessage?.takeIf { it.isNotBlank() }?.let { return it }
    if (state.lastSyncAt <= 0L) return "还没同步过"
    val time = Instant.ofEpochMilli(state.lastSyncAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    return if (state.lastWordCount > 0) "上次同步：$time · ${state.lastWordCount} 词" else "上次同步：$time"
}

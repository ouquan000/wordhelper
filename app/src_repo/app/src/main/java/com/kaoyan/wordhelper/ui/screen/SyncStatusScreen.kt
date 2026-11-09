package com.kaoyan.wordhelper.ui.screen

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.data.repository.SyncPhase
import com.kaoyan.wordhelper.data.repository.SyncStatusSnapshot
import com.kaoyan.wordhelper.ui.theme.FuzzyYellow
import com.kaoyan.wordhelper.ui.theme.KnownGreen
import com.kaoyan.wordhelper.ui.viewmodel.SyncStatusViewModel
import com.kaoyan.wordhelper.util.DateUtils
import kotlinx.coroutines.delay

/**
 * 「同步状态」页（1.4.7）。
 *
 * 只做三件事：现在是什么状态、为什么没成功、待发多少 / 什么时候再试。
 * 所有事实来自 [com.kaoyan.wordhelper.data.repository.SyncStatusStore]，
 * 页面自己不探测网络；「立即重试」调仓库已有的同步入口（见 SyncStatusViewModel）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    viewModel: SyncStatusViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val snapshot by viewModel.status.collectAsStateWithLifecycle()
    val retrying by viewModel.retrying.collectAsStateWithLifecycle()
    val rewritePending by viewModel.rewritePending.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 进页面刷一次待发条数（只读本地队列，不联网）。
    LaunchedEffect(Unit) { viewModel.refreshPending() }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    // 倒计时每秒走一格；没有「下次重试时间」就不起 ticker，省得页面闲着还每秒重组。
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.retryAtEpochMs) {
        val retryAt = snapshot.retryAtEpochMs ?: return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= retryAt) break
            delay(1_000L)
        }
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
                title = { Text(text = "同步状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBrush)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("sync_status_content"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusHeroCard(snapshot)

            SyncInfoCard(
                snapshot = snapshot,
                rewritePending = rewritePending,
                nowMs = nowMs
            )

            if (showsFailureDetail(snapshot.phase)) {
                FailureCard(snapshot)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::retryNow,
                    enabled = !retrying,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_status_retry")
                ) {
                    Text(text = if (retrying) "正在重试…" else "立即重试")
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                buildDiagnosticText(
                                    snapshot = snapshot,
                                    rewritePending = rewritePending,
                                    generatedAt = System.currentTimeMillis()
                                )
                            )
                        )
                        Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_status_copy")
                ) {
                    Text(text = "复制诊断信息")
                }
            }
        }
    }
}

// ---- 顶部：状态胶囊 ----

@Composable
private fun StatusHeroCard(snapshot: SyncStatusSnapshot) {
    val tint = phaseTint(snapshot.phase)
    BaseCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "当前状态", style = MaterialTheme.typography.titleLarge)
            Surface(
                shape = RoundedCornerShape(50),
                color = tint.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))
            ) {
                Text(
                    text = syncPhaseLabel(snapshot.phase),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    color = tint,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                text = syncPhaseHint(snapshot.phase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- 信息行 ----

@Composable
private fun SyncInfoCard(
    snapshot: SyncStatusSnapshot,
    rewritePending: Int,
    nowMs: Long
) {
    BaseCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val retryAccent = phaseTint(SyncPhase.RETRYING)
            Text(text = "连接信息", style = MaterialTheme.typography.titleLarge)
            InfoRow(label = "最后成功", value = formatTimestamp(snapshot.lastSuccessAt))
            InfoRow(label = "最后尝试", value = formatTimestamp(snapshot.lastAttemptAt))
            InfoRow(label = "实际连接", value = snapshot.lastOrigin ?: "—")
            InfoRow(label = "待发送", value = "${snapshot.pendingCount} 条")
            InfoRow(
                label = "下次重试",
                value = retryCountdownText(snapshot.retryAtEpochMs, nowMs),
                valueColor = if (snapshot.retryAtEpochMs != null) retryAccent else null
            )
            InfoRow(label = "电脑端版本", value = snapshot.serverVersion ?: "—")
            InfoRow(label = "电脑端启动于", value = snapshot.serverStartedAt ?: "—")
            if (snapshot.capabilities.isNotEmpty()) {
                InfoRow(
                    label = "电脑端能力",
                    value = snapshot.capabilities.sorted().joinToString(" / ")
                )
            }
            InfoRow(label = "待重写", value = "$rewritePending 条")
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---- 失败原因 ----

@Composable
private fun FailureCard(snapshot: SyncStatusSnapshot) {
    val errorColor = MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, errorColor.copy(alpha = 0.3f)),
        colors = CardDefaults.cardColors(containerColor = errorColor.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "这次为什么没成功", style = MaterialTheme.typography.titleLarge)
            Text(
                text = snapshot.lastHttpCode?.let { "HTTP $it" } ?: "HTTP —",
                style = MaterialTheme.typography.bodySmall,
                color = errorColor,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = failureReason(snapshot.phase, snapshot.lastHttpCode, snapshot.lastMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val raw = snapshot.lastMessage
            if (!raw.isNullOrBlank()) {
                Text(
                    text = "原始信息：$raw",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- 公共小件 ----

@Composable
private fun BaseCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

@Composable
private fun phaseTint(phase: SyncPhase): Color = when (phase) {
    SyncPhase.OK -> KnownGreen
    SyncPhase.CONNECTING -> MaterialTheme.colorScheme.primary
    SyncPhase.RETRYING, SyncPhase.RATE_LIMITED -> FuzzyYellow
    SyncPhase.FAILED, SyncPhase.UNAUTHORIZED, SyncPhase.SERVER_TOO_OLD ->
        MaterialTheme.colorScheme.error
    SyncPhase.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 状态胶囊上的文案。ProfileScreen 的入口行也复用它。 */
internal fun syncPhaseLabel(phase: SyncPhase): String = when (phase) {
    SyncPhase.IDLE -> "未连接"
    SyncPhase.CONNECTING -> "连接中"
    SyncPhase.OK -> "已连接"
    SyncPhase.RETRYING -> "重试中"
    SyncPhase.FAILED -> "失败"
    SyncPhase.UNAUTHORIZED -> "需要口令"
    SyncPhase.RATE_LIMITED -> "被限流"
    SyncPhase.SERVER_TOO_OLD -> "电脑端版本过旧"
}

private fun syncPhaseHint(phase: SyncPhase): String = when (phase) {
    SyncPhase.IDLE -> "还没同步过。点下面的「立即重试」先连一次电脑端。"
    SyncPhase.CONNECTING -> "正在连电脑端…"
    SyncPhase.OK -> "上次同步成功，本机的学习记录和电脑端一致。"
    SyncPhase.RETRYING -> "上次没成功，正在等下一次重试。"
    SyncPhase.FAILED -> "这次没连上，原因见下面。"
    SyncPhase.UNAUTHORIZED -> "电脑端认不出这串口令，把「手机：粘贴这一行」整行重新粘一次。"
    SyncPhase.RATE_LIMITED -> "请求太频繁，等倒计时走完再试，别一直点。"
    SyncPhase.SERVER_TOO_OLD -> "电脑端还是旧进程，重启（或升级）电脑端后再试。"
}

/** 「我的」页入口那一行：当前阶段 + 最后成功时间。 */
internal fun syncStatusEntrySummary(snapshot: SyncStatusSnapshot): String {
    val lastSuccess = snapshot.lastSuccessAt?.let { formatTimestamp(it) } ?: "—"
    return "${syncPhaseLabel(snapshot.phase)} · 最后成功 $lastSuccess"
}

private fun formatTimestamp(millis: Long?): String {
    if (millis == null || millis <= 0L) return "—"
    return DateUtils.formatDateTime(millis)
}

/** 下次重试的倒计时；没有排期就是「—」，已经到点就「马上重试」。 */
private fun retryCountdownText(retryAtEpochMs: Long?, nowMs: Long): String {
    if (retryAtEpochMs == null) return "—"
    val remainMs = retryAtEpochMs - nowMs
    if (remainMs <= 0L) return "马上重试"
    val totalSeconds = (remainMs + 999L) / 1000L
    val minutes = totalSeconds / 60L
    return if (minutes > 0L) {
        "${minutes} 分 ${totalSeconds % 60L} 秒后"
    } else {
        "${totalSeconds} 秒后"
    }
}

/** 哪种阶段值得展开失败原因。 */
private fun showsFailureDetail(phase: SyncPhase): Boolean = when (phase) {
    SyncPhase.FAILED,
    SyncPhase.UNAUTHORIZED,
    SyncPhase.RATE_LIMITED,
    SyncPhase.SERVER_TOO_OLD,
    SyncPhase.RETRYING -> true

    else -> false
}

/**
 * 失败时说人话。401/404/429 是 1.4.7 验收矩阵里写死要能看懂的三种；
 * 其余情况宁可放弃原始报错，也不要把「口令错」说成「电脑没连上」。
 */
private fun failureReason(phase: SyncPhase, httpCode: Int?, message: String?): String = when (phase) {
    SyncPhase.UNAUTHORIZED -> "口令不对，需要重新粘贴连接串"
    SyncPhase.SERVER_TOO_OLD -> "电脑端版本过旧，请重启电脑端"
    SyncPhase.RATE_LIMITED -> "请求太频繁，正在等待"
    else -> when {
        httpCode == 401 -> "口令不对，需要重新粘贴连接串"
        httpCode == 404 -> "电脑端版本过旧，请重启电脑端"
        httpCode == 429 -> "请求太频繁，正在等待"
        httpCode != null && httpCode >= 500 -> "电脑端内部出错，重启电脑端再试"
        !message.isNullOrBlank() -> message
        else -> "没连上电脑端，检查地址和网络"
    }
}

/** 复制到剪贴板的诊断文本：只有快照，**不带 token、不带完整连接串**。 */
private fun buildDiagnosticText(
    snapshot: SyncStatusSnapshot,
    rewritePending: Int,
    generatedAt: Long
): String = buildString {
    appendLine("English App 同步诊断")
    appendLine("生成时间：${formatTimestamp(generatedAt)}")
    appendLine("阶段：${syncPhaseLabel(snapshot.phase)}（${snapshot.phase.name}）")
    appendLine("最后成功：${formatTimestamp(snapshot.lastSuccessAt)}")
    appendLine("最后尝试：${formatTimestamp(snapshot.lastAttemptAt)}")
    appendLine("实际连接：${snapshot.lastOrigin ?: "—"}")
    appendLine("HTTP 码：${snapshot.lastHttpCode ?: "—"}")
    appendLine("最后信息：${snapshot.lastMessage ?: "—"}")
    appendLine("待发送（学习记录）：${snapshot.pendingCount} 条")
    appendLine("待重写：$rewritePending 条")
    appendLine("下次重试：${retryCountdownText(snapshot.retryAtEpochMs, generatedAt)}")
    appendLine("电脑端版本：${snapshot.serverVersion ?: "—"}")
    appendLine("电脑端启动于：${snapshot.serverStartedAt ?: "—"}")
    val capabilitiesText = if (snapshot.capabilities.isEmpty()) {
        "—"
    } else {
        snapshot.capabilities.sorted().joinToString(" / ")
    }
    appendLine("电脑端能力：$capabilitiesText")
}

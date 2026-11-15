package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 卡背里的「重写思路」区（1.4.3.2）。
 *
 * 1.4.3.2 起它跟着卡背内容一起滚，不再贴卡片底边，所以可以放宽排布：
 * 按钮 / 模型 / 输入框各占一整行，谁都不用为了给别人让位而压缩文字。
 *
 * 第一行：重写按钮（整行，按文字显示，不省略）
 * 第二行：模型下拉（整行）
 * 第三行：折叠的指令行（默认只显示「哪里有问题？点开补充」，点开才展开输入框；留空 = 整段重写）
 * 有标记时再跟：进度条 + 状态小字 / 撤销；还没生成好时另有一颗整行「跳过」按钮
 *
 * 1.4.6.1 P1：👍/👎 已从卡背滚动区挪到卡背外的底部操作区上方（见 RewriteFeedbackRow），
 * 不用滑到底就能看到。
 */
@Composable
internal fun RewriteBar(
    wordId: Long,
    mark: RewriteQueueItem?,
    models: List<RewriteModel>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    onMark: (String) -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    enabled: Boolean,
    progress: RewriteProgressUi?,
    justUpdated: Boolean,
    /** 1.4.6 H：只在点过重写、结果还没回来时由外层置 true。 */
    showSkip: Boolean = false,
    onSkip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 按词重置：换词时不要把上一个词写了一半的指令带过去
    var instruction by remember(wordId) { mutableStateOf("") }
    // 输入框默认折叠；展开状态也按词重置，换一张卡不继承上一张的展开态。
    var instructionExpanded by remember(wordId) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 跑完别把 100% 钉在那儿：收掉进度条，「撤销」那行才回到可视区。
    var keepProgress by remember(wordId, mark?.status) { mutableStateOf(true) }
    LaunchedEffect(wordId, mark?.status) {
        if (mark?.status == RewriteStatus.DONE) {
            delay(1500)
            keepProgress = false
        } else {
            keepProgress = true
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(
            onClick = {
                val typed = instruction.trim()
                when (mark?.status) {
                    // 已标记但用户又改了指令：直接覆盖这一条，不逼他先取消再点
                    RewriteStatus.PENDING -> if (typed.isNotEmpty() && typed != mark.instruction) {
                        onMark(instruction)
                    } else {
                        onCancel()
                    }
                    else -> {
                        // QUEUED 也允许再点：电脑端数据被清掉时能重发，不至于永久卡住
                        onMark(instruction)
                        instruction = ""
                    }
                }
            },
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = buttonLabel(mark),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }

        ModelPicker(
            models = models,
            selectedModelId = selectedModelId,
            onSelectModel = onSelectModel,
            modifier = Modifier.fillMaxWidth()
        )

        // 1.4.6.1：默认只占一行。写过的内容收起后给「已填写」，别让人以为字丢了。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { instructionExpanded = !instructionExpanded }
                .testTag("rewrite_instruction_toggle")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (instructionExpanded) "哪里有问题？" else "哪里有问题？点开补充",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!instructionExpanded && instruction.isNotBlank()) {
                Text(
                    text = "已填写",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (instructionExpanded) {
                Text(
                    text = "收起",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (instructionExpanded) {
            OutlinedTextField(
                value = instruction,
                onValueChange = { if (it.length <= MAX_INSTRUCTION) instruction = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rewrite_instruction_field"),
                placeholder = { Text("哪里有问题？留空＝整段重写", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 1,
                maxLines = 4,
                // 打完按键盘上的「完成」就把键盘收起来，不用去点别处（点卡片会翻面）
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                shape = MaterialTheme.shapes.medium
            )
        }

        // 1.4.6.1：跳过从状态行的小字改成整行大按钮，和「重写思路」同宽同高、同样好点。
        if (showSkip && mark != null && mark.status != RewriteStatus.DONE) {
            OutlinedButton(
                onClick = onSkip,
                enabled = enabled,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rewrite_skip"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "跳过，先背下一个",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }

        // 有标记才画进度；跑完/失败后保留最后一格（100% 或红条），换词时才清掉
        if (keepProgress && mark != null && (progress != null ||
                mark.status == RewriteStatus.PENDING || mark.status == RewriteStatus.QUEUED)
        ) {
            ProgressRow(progress)
        }

        mark?.let { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (justUpdated) "已更新" else statusText(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (justUpdated) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                if (item.status == RewriteStatus.DONE) {
                    TextButton(
                        onClick = onUndo,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(text = "撤销", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

    }
}

/**
 * 1.4.6.1 P1：卡背上的 👍/👎 评价行。
 *
 * 从 RewriteBar 的滚动内容里抽出来，放在卡背与「不认识/模糊/认识」之间：
 * 卡背内容再长也不用滑到底才能看到，普通卡和重写卡共用同一个入口。
 * 👎 只记录，不触发自动重写，也不调模型；评价绑当前展示的 rev。
 */
@Composable
internal fun RewriteFeedbackRow(
    feedback: String?,
    onFeedback: (String, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var showDownDialog by remember { mutableStateOf(false) }
    var downReason by remember { mutableStateOf("") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "这条思路",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { onFeedback(RewriteFeedbackRating.UP, "") },
            enabled = enabled && feedback != RewriteFeedbackRating.UP,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("rewrite_feedback_up")
        ) {
            Text(
                text = if (feedback == RewriteFeedbackRating.UP) "👍 已认可" else "👍 有帮助",
                style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(
            onClick = {
                downReason = ""
                showDownDialog = true
            },
            enabled = enabled && feedback != RewriteFeedbackRating.DOWN,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("rewrite_feedback_down")
        ) {
            Text(
                text = if (feedback == RewriteFeedbackRating.DOWN) "👎 已记录" else "👎 没帮助",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    // 点踩时顺手问一句「哪里没帮上」。不写也能记，写了下一版会当硬约束。
    if (showDownDialog) {
        AlertDialog(
            onDismissRequest = { showDownDialog = false },
            title = { Text("哪里没帮上？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "可以写具体一点，比如「谐音太牵强」「画面和词义对不上」。留空就直接记下。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = downReason,
                        onValueChange = { if (it.length <= MAX_INSTRUCTION) downReason = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rewrite_feedback_reason"),
                        placeholder = { Text("哪里没帮上？（可不填）", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val reason = downReason.trim()
                        showDownDialog = false
                        onFeedback(RewriteFeedbackRating.DOWN, reason)
                    },
                    modifier = Modifier.testTag("rewrite_feedback_down_confirm")
                ) { Text("记下") }
            },
            dismissButton = {
                TextButton(onClick = { showDownDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 一行进度：细条 + 右侧小字。
 * stage 不明时用不确定进度条；failed 停在最后已知百分比并变红；连不上电脑就直说。
 */
@Composable
private fun ProgressRow(progress: RewriteProgressUi?) {
    val failed = progress?.failed == true
    val offline = progress?.offline == true
    val fraction = progress?.fraction
    val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = progressLabel(fraction, failed, offline),
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun progressLabel(fraction: Float?, failed: Boolean, offline: Boolean): String = when {
    offline -> "等电脑上线（连不上就重贴那一行）"
    failed -> "重写失败"
    fraction == null -> "生成中…"
    else -> "${(fraction * 100).roundToInt()}%"
}

@Composable
private fun ModelPicker(
    models: List<RewriteModel>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = RewriteModels.nameOf(models, selectedModelId) + " ▾",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(text = model.name, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelectModel(model.id)
                        open = false
                    }
                )
            }
        }
    }
}

private fun buttonLabel(mark: RewriteQueueItem?): String = when (mark?.status) {
    RewriteStatus.PENDING -> "已标记 · 再点取消"
    RewriteStatus.QUEUED -> "已送电脑 · 再点重发"
    RewriteStatus.DONE -> "再重写一次"
    RewriteStatus.FAILED -> "重试重写"
    else -> "重写思路"
}

private fun statusText(mark: RewriteQueueItem): String = when (mark.status) {
    RewriteStatus.PENDING -> "已标记 · 待上传（再点一次取消）"
    RewriteStatus.QUEUED -> "电脑已收到，排队重生中（再点一次可重发）"
    RewriteStatus.DONE -> "上次重写 ${formatTime(mark.updatedAt)}"
    RewriteStatus.FAILED -> "上次没成功：${mark.error ?: "未知原因"}"
    else -> ""
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

private const val MAX_INSTRUCTION = 200

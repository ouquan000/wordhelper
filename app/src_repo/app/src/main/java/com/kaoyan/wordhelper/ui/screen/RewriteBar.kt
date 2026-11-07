package com.kaoyan.wordhelper.ui.screen

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
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
 * 第三行：输入框（常显，一行起，最多四行；留空 = 整段重写）
 * 有标记时再跟一行：进度条 + 状态小字 / 撤销
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
    modifier: Modifier = Modifier
) {
    // 按词重置：换词时不要把上一个词写了一半的指令带过去
    var instruction by remember(wordId) { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

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

        OutlinedTextField(
            value = instruction,
            onValueChange = { if (it.length <= MAX_INSTRUCTION) instruction = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("哪里有问题？留空＝整段重写", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            minLines = 1,
            maxLines = 4,
            // 打完按键盘上的「完成」就把键盘收起来，不用去点别处（点卡片会翻面）
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            shape = MaterialTheme.shapes.medium
        )

        // 有标记才画进度；跑完/失败后保留最后一格（100% 或红条），换词时才清掉
        if (mark != null && (progress != null ||
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
    offline -> "等电脑上线"
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

package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus

private const val SM2_DESCRIPTION =
    "SM-2 调度说明：不认识会在会话内重试，模糊保守推进，认识按记忆因子增长间隔。"

@Composable
internal fun RemoveLearningWordDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(text = "移出生词本") },
        text = { Text(text = "确认将该单词从生词本移出吗？") },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) {
                Text(text = "移出")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(text = "取消")
            }
        }
    )
}

@Composable
internal fun LearningAiGuideDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(text = "AI 功能未配置") },
        text = { Text(text = "请先在“我的 - AI 实验室”配置 API Key 解锁智能助记。") },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(text = "知道了", modifier = Modifier.testTag("learning_ai_guide_confirm"))
            }
        }
    )
}

@Composable
internal fun LearningMemoryEditDialog(
    rewriteMark: RewriteQueueItem?,
    memoryDraft: String,
    onDraftChange: (String) -> Unit,
    memoryEditCanRestore: Boolean,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("编辑思路")
                rewriteMark?.takeIf { it.status == RewriteStatus.DONE }?.let {
                    Text(
                        "上次重写 ${formatRewriteTime(it.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = memoryDraft,
                    onValueChange = { onDraftChange(it) },
                    label = { Text("这只属于这本书") },
                    minLines = 8,
                    maxLines = 16,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_edit_field")
                )
                Text(
                    text = "保存后立即生效；打开「锁定 · 免洗」后，自动洗词和词卡热更都不会覆盖它。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave()
                    onDismiss()
                },
                modifier = Modifier.testTag("memory_edit_save")
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = memoryEditCanRestore,
                    onClick = {
                        onRestore()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("memory_edit_restore")
                ) { Text("恢复上一版") }
                TextButton(onClick = { onDismiss() }) { Text("取消") }
            }
        }
    )
}

@Composable
internal fun LearningReviewDialog(
    lastReviewText: String,
    nextReviewText: String,
    reviewDescription: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(
                text = "复习时间",
                modifier = Modifier.testTag("review_dialog_title")
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "上次复习：$lastReviewText",
                    modifier = Modifier.testTag("review_last_review_time")
                )
                Text(
                    text = "下次复习：$nextReviewText",
                    modifier = Modifier.testTag("review_next_review_time")
                )
                Text(
                    text = "提示：$reviewDescription",
                    modifier = Modifier.testTag("review_hint")
                )
                Text(text = SM2_DESCRIPTION)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(
                    text = "知道了",
                    modifier = Modifier.testTag("review_dialog_confirm")
                )
            }
        }
    )
}

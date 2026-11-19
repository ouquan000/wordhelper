package com.kaoyan.wordhelper.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
 * 卡背里的「重写思路」区（1.4.7.4：紧凑化重排）。
 *
 * 旧版按钮 / 模型 / 指令各占一整行，卡背一滚到底要空掉两百多 dp。现在压成三块：
 *
 * 1) 工具栏（40.dp 一行）：左侧 42% 是胶囊模型下拉（当前模型名 + ▾），
 *    右侧是圆角主操作胶囊「✨ + 按 mark 状态变化的文案」。
 * 2) 指令胶囊（36.dp 单行）：默认只显示「哪里有问题？点开补充」，点开平滑展开成
 *    多行输入（200 字上限、Done 收键盘），testTag rewrite_instruction_toggle /
 *    rewrite_instruction_field 原样保留。
 * 3) 元数据行（约 22.dp）：上次重写时间 / 失败原因 + 撤销，和下面 RewriteFeedbackRow
 *    的小胶囊连成一条紧凑的反馈带。
 *
 * 业务语义一律没动：PENDING 再点取消、QUEUED 再点重发、DONE 再重写、FAILED 重试、
 * 进度 / 离线 / 失败状态、撤销、skip 回调都照旧。
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
    val instructionFocus = remember(wordId) { FocusRequester() }

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

    // 点开胶囊后把焦点交给输入框：否则用户还得再点一次才出键盘。
    LaunchedEffect(wordId, instructionExpanded) {
        if (instructionExpanded) {
            runCatching { instructionFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ① 工具栏：模型 42% + 主操作 58%，同一行 40.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelPicker(
                models = models,
                selectedModelId = selectedModelId,
                onSelectModel = onSelectModel,
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxSize()
            )
            RewriteActionPill(
                label = "✨ ${buttonLabel(mark)}",
                enabled = enabled,
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
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxSize()
            )
        }

        // ② 指令胶囊：36.dp 单行，点开才展开成多行。高度变化用 animateContentSize 过渡。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 180))
        ) {
            if (!instructionExpanded) {
                CollapsedInstructionCapsule(
                    hasText = instruction.isNotBlank(),
                    enabled = enabled,
                    onExpand = { instructionExpanded = true }
                )
            } else {
                ExpandedInstructionEditor(
                    instruction = instruction,
                    enabled = enabled,
                    focusRequester = instructionFocus,
                    onInstructionChange = { if (it.length <= MAX_INSTRUCTION) instruction = it },
                    onCollapse = {
                        keyboardController?.hide()
                        instructionExpanded = false
                    }
                )
            }
        }

        // 有标记才画进度；跑完/失败后保留最后一格（100% 或红条），换词时才清掉
        if (keepProgress && mark != null && (progress != null ||
                mark.status == RewriteStatus.PENDING || mark.status == RewriteStatus.QUEUED)
        ) {
            ProgressRow(progress)
        }

        // ③ 元数据行：上次重写时间 / 失败原因 + 撤销，压到一行 22.dp
        mark?.let { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.status == RewriteStatus.DONE) {
                    UndoCapsule(enabled = enabled, onUndo = onUndo)
                }
            }
        }
    }
}

/**
 * 折叠态的指令胶囊：一行 36.dp，占位文案「哪里有问题？点开补充」。
 * 已经写过内容时右侧给个「已填写」，收起也不会让人以为字丢了。
 */
@Composable
private fun CollapsedInstructionCapsule(
    hasText: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onExpand)
            .testTag("rewrite_instruction_toggle")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp).height(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "哪里有问题？点开补充",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasText) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "已填写",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

/**
 * 展开态的指令输入：标题行（带 rewrite_instruction_toggle，用于收起）+ 多行输入框。
 * 200 字上限、Done 收键盘与旧实现完全一致。
 */
@Composable
private fun ExpandedInstructionEditor(
    instruction: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onInstructionChange: (String) -> Unit,
    onCollapse: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clickable(enabled = enabled, onClick = onCollapse)
                .testTag("rewrite_instruction_toggle")
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "哪里有问题？",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "收起",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        OutlinedTextField(
            value = instruction,
            onValueChange = onInstructionChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("rewrite_instruction_field"),
            placeholder = { Text("哪里有问题？留空＝整段重写", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 4,
            // 打完按键盘上的「完成」就把键盘收起来，不用去点别处（点卡片会翻面）
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            shape = RoundedCornerShape(14.dp)
        )
    }
}

/**
 * 1.4.6.1 P1：卡背上的 👍/👎 评价行。
 *
 * 从 RewriteBar 的滚动内容里抽出来，放在卡背与「不认识/模糊/认识」之间：
 * 卡背内容再长也不用滑到底才能看到，普通卡和重写卡共用同一个入口。
 * 👎 只记录，不触发自动重写，也不调模型；评价绑当前展示的 rev。
 *
 * 1.4.7.4：两个 TextButton 换成 28.dp 的小胶囊，和上面的元数据行贴成一条反馈带。
 */
@Composable
internal fun RewriteFeedbackRow(
    feedback: String?,
    onFeedback: (String, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "这条思路",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        FeedbackCapsule(
            label = if (feedback == RewriteFeedbackRating.UP) "👍 已认可" else "👍 有帮助",
            selected = feedback == RewriteFeedbackRating.UP,
            enabled = enabled && feedback != RewriteFeedbackRating.UP,
            tag = "rewrite_feedback_up",
            onClick = { onFeedback(RewriteFeedbackRating.UP, "") }
        )
        FeedbackCapsule(
            label = if (feedback == RewriteFeedbackRating.DOWN) "👎 已记录" else "👎 没帮助",
            selected = feedback == RewriteFeedbackRating.DOWN,
            enabled = enabled && feedback != RewriteFeedbackRating.DOWN,
            tag = "rewrite_feedback_down",
            // 点踩直接记一条空原因的差评：不再弹「哪里没帮上？」，
            // 少一次输入就能留下机器可用的负样本；原因可选项由重写指令框承担。
            onClick = { onFeedback(RewriteFeedbackRating.DOWN, "") }
        )
    }
}

/** 反馈小胶囊：28.dp 高，选中态用 primaryContainer 提亮。 */
@Composable
private fun FeedbackCapsule(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(
                if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.42f)
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    colors.primary.copy(alpha = 0.45f)
                } else {
                    colors.outlineVariant.copy(alpha = 0.55f)
                },
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 撤销：22.dp 的小胶囊，只保留「撤销」两个字，不再撑一整颗 TextButton 的高度。 */
@Composable
private fun UndoCapsule(enabled: Boolean, onUndo: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(shape)
            .background(colors.surfaceVariant.copy(alpha = 0.42f))
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(enabled = enabled, onClick = onUndo)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "撤销",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) colors.primary else colors.onSurfaceVariant,
            maxLines = 1
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
                        .height(4.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
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

/**
 * 模型下拉：42% 宽的胶囊，只显示当前模型名 + ▾，点开还是原来的 DropdownMenu。
 */
@Composable
private fun ModelPicker(
    models: List<RewriteModel>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.surfaceVariant.copy(alpha = 0.55f))
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
                .clickable { open = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = RewriteModels.nameOf(models, selectedModelId),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "切换模型",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.width(14.dp).height(14.dp)
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

/** 主操作胶囊：40.dp 高，primary 横向渐变；禁用时退回灰底，不靠透明度装死。 */
@Composable
private fun RewriteActionPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(colors.primary, colors.primary.copy(alpha = 0.86f))
                        )
                    )
                } else {
                    Modifier.background(colors.onSurface.copy(alpha = 0.10f))
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

private fun buttonLabel(mark: RewriteQueueItem?): String = when (mark?.status) {
    RewriteStatus.PENDING -> "已标记·再点取消"
    RewriteStatus.QUEUED -> "已送电脑·再点重发"
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

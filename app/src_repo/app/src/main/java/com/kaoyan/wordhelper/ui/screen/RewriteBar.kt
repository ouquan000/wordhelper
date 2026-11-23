package com.kaoyan.wordhelper.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
import com.kaoyan.wordhelper.util.FeedbackGestureStateMachine
import com.kaoyan.wordhelper.util.GestureContract
import com.kaoyan.wordhelper.util.GestureDecision
import com.kaoyan.wordhelper.util.GestureEvent
import com.kaoyan.wordhelper.util.SwipeDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 1.5.3v2：卡背操作区收口两排 + 全员一维直滑手势。
 *
 * 第 1 排 [⇄模型滑动胶囊] + [✨再重写一次]；第 2 排 [👍有帮助] + [👎没帮助]。
 * 「哪里有问题？点开补充」输入框、「这条思路」文字、「撤销」胶囊都已移除：
 * 指令改为下滑「写要求重写」输入条；撤销移进「编辑思路」弹窗里的「恢复上一版」。
 *
 * 触摸逻辑全部交给 [FeedbackGestureStateMachine]（纯 Kotlin），这里只把
 * [GestureEvent] 映射成业务回调；浮层用独立 [Popup] 盖在卡片上方，不被卡片圆角裁剪。
 */
@Composable
internal fun RewriteBar(
    wordId: Long,
    mark: RewriteQueueItem?,
    models: List<RewriteModel>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    onRewrite: (String) -> Unit,
    onCancel: () -> Unit,
    onFeedbackUp: (count: Int, reasons: List<String>, comment: String) -> Unit,
    onFeedbackDown: (count: Int, reasons: List<String>, comment: String) -> Unit,
    feedback: String?,
    likeCount: Int,
    enabled: Boolean,
    progress: RewriteProgressUi?,
    justUpdated: Boolean,
    modifier: Modifier = Modifier
) {
    // 跑完别把 100% 钉在那儿：收掉进度条。
    var keepProgress by remember(wordId, mark?.status) { mutableStateOf(true) }
    LaunchedEffect(wordId, mark?.status) {
        if (mark?.status == RewriteStatus.DONE) {
            delay(1500)
            keepProgress = false
        } else {
            keepProgress = true
        }
    }

    val presentation = rewriteBarPresentation(
        mark = mark,
        hasProgress = progress != null,
        justUpdated = justUpdated
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 第 1 排：模型滑动胶囊（42%）+ ✨再重写一次（58%），40.dp 一行。
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
                enabled = enabled,
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxSize()
            )
            RewriteActionPill(
                label = "✨ ${buttonLabel(mark)}",
                enabled = enabled,
                onClick = {
                    // 主操作短按：PENDING 再点=取消；其余状态=直接重写（带空指令）。
                    if (mark?.status == RewriteStatus.PENDING) onCancel() else onRewrite("")
                },
                onSwipeDownInstruction = onRewrite,
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxSize()
            )
        }

        // 进度条：C2 修复后 QUEUED&&hasProgress 时 presentation.showProgress=true，这里活代码。
        if (keepProgress && presentation.showProgress && progress != null) {
            ProgressRow(progress, queued = mark?.status == RewriteStatus.QUEUED)
        }

        // 失败原因单行（上次重写时间已移到卡顶信息栏，这里只保留失败原因）。
        if (mark != null && mark.status == RewriteStatus.FAILED && presentation.statusLine.isNotBlank()) {
            Text(
                text = presentation.statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 第 2 排：👍有帮助 + 👎没帮助。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FeedbackPill(
                contract = GestureContract.THUMB_UP,
                label = if (likeCount > 0) "👍 有帮助 $likeCount" else "👍 有帮助",
                selected = feedback == RewriteFeedbackRating.UP,
                enabled = enabled,
                tag = "rewrite_feedback_up",
                upOptions = listOf("一秒记住", "画面很稳", "谐音绝了"),
                downPlaceholder = "哪里帮上了？",
                onTap = { onFeedbackUp(1, emptyList(), "") },
                onLongPressCommit = { count -> onFeedbackUp(count, emptyList(), "") },
                onSwipeUpItem = { reason -> onFeedbackUp(1, listOf(reason), "") },
                onSwipeDownComment = { comment -> onFeedbackUp(1, emptyList(), comment) },
                modifier = Modifier.weight(1f)
            )
            FeedbackPill(
                contract = GestureContract.THUMB_DOWN,
                label = if (feedback == RewriteFeedbackRating.DOWN) "👎 没帮助 · 已记" else "👎 没帮助",
                selected = feedback == RewriteFeedbackRating.DOWN,
                enabled = enabled,
                tag = "rewrite_feedback_down",
                upOptions = listOf("不像人话", "没有逻辑", "太烂了"),
                downPlaceholder = "哪里没帮上？",
                onTap = { onFeedbackDown(1, emptyList(), "") },
                onLongPressCommit = { count -> onFeedbackDown(count, emptyList(), "") },
                onSwipeUpItem = { reason -> onFeedbackDown(1, listOf(reason), "") },
                onSwipeDownComment = { comment -> onFeedbackDown(1, emptyList(), comment) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 手势接线：把状态机的 GestureDecision 转成 Compose 事件 + 浮层拖拽。 */

@Composable
private fun Modifier.gestureArea(
    contract: GestureContract,
    enabled: Boolean,
    onEvent: (GestureEvent) -> Unit,
    onMenuDrag: (Float, Float) -> Unit = { _, _ -> }
): Modifier {
    val machine = remember(contract) { FeedbackGestureStateMachine(contract) }
    val haptic = LocalHapticFeedback.current
    var menuActive by remember(contract) { mutableStateOf(false) }
    if (!enabled) return this
    return pointerInput(enabled, contract) {
        fun emit(decision: GestureDecision) {
            if (decision.haptic) {
                val type = if (decision.event is GestureEvent.LongPressTick) {
                    HapticFeedbackType.LongPress
                } else {
                    HapticFeedbackType.TextHandleMove
                }
                haptic.performHapticFeedback(type)
            }
            when (val e = decision.event) {
                is GestureEvent.SwipeSelect -> {
                    menuActive = true
                    onEvent(e)
                }
                is GestureEvent.SwipeCommit, is GestureEvent.SwipeCancel -> {
                    menuActive = false
                    onEvent(e)
                }
                null -> Unit
                else -> onEvent(e)
            }
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downX = down.position.x
            val downY = down.position.y
            var lastX = downX
            var lastY = downY
            var lastEventTime = down.uptimeMillis
            var decision = machine.onDown(downX, downY, lastEventTime)
            if (decision.consume) down.consume()
            emit(decision)

            var finished = false
            while (!finished) {
                val event = withTimeoutOrNull(16) { awaitPointerEvent() }
                if (event == null) {
                    // ticker 超时：没有新指针事件，用虚拟时间推进长按连发（测试时钟可控）。
                    lastEventTime += 16L
                    emit(machine.onTick(lastEventTime))
                    continue
                }
                val change = event.changes.firstOrNull()
                if (change == null) {
                    finished = true
                    break
                }
                lastX = change.position.x
                lastY = change.position.y
                lastEventTime = change.uptimeMillis
                decision = machine.onMove(lastX, lastY, lastEventTime)
                if (decision.consume) change.consume()
                emit(decision)
                if (menuActive) {
                    onMenuDrag(lastX - downX, lastY - downY)
                }
                if (event.changes.none { it.pressed }) finished = true
            }

            emit(machine.onUp(lastX, lastY, lastEventTime))
            menuActive = false
        }
    }
}

/* ------------------------------------------------------------------ */
/* 模型滑动胶囊：短按展开轻量列表兜底；左右横滑切档（每档震动）。 */

@Composable
private fun ModelPicker(
    models: List<RewriteModel>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme

    fun switchModel(direction: SwipeDirection) {
        if (models.size < 2) return
        val idx = models.indexOfFirst { it.id == selectedModelId }.coerceAtLeast(0)
        val next = when (direction) {
            SwipeDirection.LEFT -> (idx + 1) % models.size
            SwipeDirection.RIGHT -> (idx - 1 + models.size) % models.size
            else -> return
        }
        onSelectModel(models[next].id)
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.surfaceVariant.copy(alpha = 0.55f))
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
                .testTag("rewrite_model_pill")
                .gestureArea(
                    contract = GestureContract.MODEL_SWITCH,
                    enabled = enabled,
                    onEvent = { event ->
                        when (event) {
                            is GestureEvent.Tap -> open = true
                            is GestureEvent.SwipeSelect -> switchModel(event.direction)
                            else -> Unit
                        }
                    }
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = shortNameOf(RewriteModels.nameOf(models, selectedModelId)),
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
        // 卡背的 HoloCardTheme 把 surface 改成 35% 白，菜单沿用默认色会透出正文。
        val menuSurface = if (LocalAppDarkTheme.current) Color(0xFF1C1F26) else Color.White
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = menuSurface,
                surfaceContainerLowest = menuSurface,
                surfaceContainerLow = menuSurface,
                surfaceContainer = menuSurface,
                surfaceContainerHigh = menuSurface,
                surfaceContainerHighest = menuSurface,
                surfaceTint = menuSurface
            )
        ) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.testTag("rewrite_model_menu")
            ) {
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
}

/** 模型胶囊内的精简短名：只留第一个词，避免截断成 "DeepSeek..."。 */
private fun shortNameOf(name: String): String = name.substringBefore(" ").ifBlank { name }

/* ------------------------------------------------------------------ */
/* ✨再重写一次：短按直接重写；下滑（1 项「写要求重写」）拉输入条。 */

@Composable
private fun RewriteActionPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onSwipeDownInstruction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    var showHint by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                .gestureArea(
                    contract = GestureContract.REWRITE_AGAIN,
                    enabled = enabled,
                    onEvent = { event ->
                        when (event) {
                            is GestureEvent.Tap -> onClick()
                            is GestureEvent.SwipeSelect -> showHint = true
                            is GestureEvent.SwipeCommit -> {
                                showHint = false
                                if (event.direction == SwipeDirection.DOWN) showInput = true
                            }
                            is GestureEvent.SwipeCancel -> showHint = false
                            else -> Unit
                        }
                    }
                ),
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

        if (showHint) {
            MenuPopup(alignment = Alignment.TopCenter, items = listOf("写要求重写"), highlightedIndex = 0)
        }
        if (showInput) {
            InstructionInputBar(
                placeholder = "写要求重写（留空＝整段重写）",
                onSubmit = { text ->
                    showInput = false
                    onSwipeDownInstruction(text)
                },
                onDismiss = { showInput = false }
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 👍/👎：短按/长按连发/上滑 3 项/下滑写原因。 */

@Composable
private fun FeedbackPill(
    contract: GestureContract,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    upOptions: List<String>,
    downPlaceholder: String,
    onTap: () -> Unit,
    onLongPressCommit: (Int) -> Unit,
    onSwipeUpItem: (String) -> Unit,
    onSwipeDownComment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme
    val scale = remember { Animatable(1f) }
    var menuVisible by remember { mutableStateOf(false) }
    var menuDirection by remember { mutableStateOf(SwipeDirection.UP) }
    var highlightIndex by remember { mutableStateOf(0) }
    var showInput by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        if (!selected) return@LaunchedEffect
        scale.animateTo(1.1f, animationSpec = tween(90))
        scale.animateTo(1f, animationSpec = tween(120))
    }

    Box(modifier = modifier.testTag(tag)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
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
                .gestureArea(
                    contract = contract,
                    enabled = enabled,
                    onEvent = { event ->
                        when (event) {
                            is GestureEvent.Tap -> onTap()
                            is GestureEvent.LongPressCommit -> onLongPressCommit(event.count)
                            is GestureEvent.SwipeSelect -> {
                                menuDirection = event.direction
                                highlightIndex = 0
                                menuVisible = true
                            }
                            is GestureEvent.SwipeCommit -> {
                                menuVisible = false
                                when (event.direction) {
                                    SwipeDirection.UP -> upOptions
                                        .getOrNull(highlightIndex)
                                        ?.let(onSwipeUpItem)
                                    SwipeDirection.DOWN -> showInput = true
                                    else -> Unit
                                }
                            }
                            is GestureEvent.SwipeCancel -> menuVisible = false
                            else -> Unit
                        }
                    },
                    onMenuDrag = { dx, dy ->
                        if (!menuVisible) return@gestureArea
                        if (menuDirection == SwipeDirection.UP) {
                            // 超过触发阈值后，每多滑 ~40dp 高亮下一项。
                            val extra = -dy - 18f
                            highlightIndex = (extra / 40f).toInt().coerceIn(0, upOptions.lastIndex)
                        }
                    }
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                maxLines = 1
            )
        }

        if (menuVisible) {
            MenuPopup(
                alignment = Alignment.TopCenter,
                items = if (menuDirection == SwipeDirection.UP) upOptions else listOf(downPlaceholder),
                highlightedIndex = highlightIndex
            )
        }
        if (showInput) {
            InstructionInputBar(
                placeholder = downPlaceholder,
                onSubmit = { text ->
                    showInput = false
                    onSwipeDownComment(text)
                },
                onDismiss = { showInput = false }
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 浮层：独立 Popup 盖在卡片上方，纵向胶囊。 */

@Composable
private fun MenuPopup(
    alignment: Alignment,
    items: List<String>,
    highlightedIndex: Int
) {
    val colors = MaterialTheme.colorScheme
    val menuSurface = if (LocalAppDarkTheme.current) Color(0xF01C1F26) else Color(0xF5FFFFFF)
    Popup(alignment = alignment, properties = PopupProperties(focusable = false)) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(menuSurface)
                .border(
                    1.dp,
                    colors.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(16.dp)
                )
                .padding(6.dp)
                .testTag("rewrite_feedback_menu"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { index, item ->
                val highlighted = index == highlightedIndex
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (highlighted) {
                                colors.primary.copy(alpha = 0.22f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlighted) colors.primary else colors.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 下滑拉起的极简输入条：贴合软键盘上沿（imePadding），回车/点击提交即走。 */
@Composable
private fun InstructionInputBar(
    placeholder: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    val surface = if (LocalAppDarkTheme.current) Color(0xFF1C1F26) else Color.White

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Popup(
        alignment = Alignment.BottomCenter,
        properties = PopupProperties(focusable = true)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(surface)
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 200) text = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("rewrite_instruction_field"),
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onSubmit(text.trim())
                }),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "提交",
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        keyboard?.hide()
                        onSubmit(text.trim())
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 进度条（与旧版一致）。 */

@Composable
private fun ProgressRow(progress: RewriteProgressUi?, queued: Boolean = false) {
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
            text = progressLabel(fraction, failed, offline, queued),
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun progressLabel(
    fraction: Float?,
    failed: Boolean,
    offline: Boolean,
    queued: Boolean = false
): String = when {
    offline -> "已记下，等电脑上线后自动重写"
    failed -> "重写失败"
    fraction == null && queued -> "排队中"
    fraction == null -> "生成中…"
    else -> "${(fraction * 100).roundToInt()}%"
}

private fun buttonLabel(mark: RewriteQueueItem?): String = when (mark?.status) {
    RewriteStatus.PENDING -> "再点取消"
    RewriteStatus.QUEUED -> "再点重发"
    RewriteStatus.DONE -> "再重写一次"
    RewriteStatus.FAILED -> "重试重写"
    else -> "重写思路"
}

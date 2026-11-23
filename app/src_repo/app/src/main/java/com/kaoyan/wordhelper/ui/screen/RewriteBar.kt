package com.kaoyan.wordhelper.ui.screen

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
import com.kaoyan.wordhelper.util.SwipeRailStateMachine
import com.kaoyan.wordhelper.util.RailFeedback

private enum class RailControl(val tag: String, val tint: Color) {
    Model("rewrite_model_pill", Color(0xFF96A7FA)),
    Rewrite("rewrite_action_pill", Color(0xFFBE9AF3)),
    Up("rewrite_feedback_up", Color(0xFF55C5B2)),
    Down("rewrite_feedback_down", Color(0xFFF184A4))
}

private data class RailOption(val label: String, val submit: () -> Unit)
private data class OpenRail(
    val control: RailControl,
    val options: List<RailOption>,
    val selection: SwipeRailStateMachine.Selection
)
internal data class RewriteInstructionRequest(val placeholder: String, val submit: (String) -> Unit)

private enum class Instruction(val placeholder: String) {
    Rewrite("写要求重写"),
    Up("哪里帮上了？"),
    Down("哪里没帮上？")
}

@Composable
internal fun RewriteBar(
    wordId: Long,
    mark: RewriteQueueItem?,
    models: List<RewriteModel>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    onRewrite: (String) -> Unit,
    onCancel: () -> Unit,
    onInstruction: (RewriteInstructionRequest) -> Unit,
    onFeedbackUp: (count: Int, reasons: List<String>, comment: String) -> Unit,
    onFeedbackDown: (count: Int, reasons: List<String>, comment: String) -> Unit,
    feedback: String?,
    likeCount: Int,
    enabled: Boolean,
    progress: RewriteProgressUi?,
    modifier: Modifier = Modifier
) {
    val currentModels by rememberUpdatedState(models)
    val currentModelId by rememberUpdatedState(selectedModelId)
    val currentMark by rememberUpdatedState(mark)
    val selectModel by rememberUpdatedState(onSelectModel)
    val rewrite by rememberUpdatedState(onRewrite)
    val cancel by rememberUpdatedState(onCancel)
    val feedbackUp by rememberUpdatedState(onFeedbackUp)
    val feedbackDown by rememberUpdatedState(onFeedbackDown)
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val dark = LocalAppDarkTheme.current
    val presentation = rewriteBarPresentation(mark, progress)
    val showInstruction by rememberUpdatedState(onInstruction)
    var rail by remember(wordId) { mutableStateOf<OpenRail?>(null) }
    var railVisible by remember(wordId) { mutableStateOf(false) }
    var areaCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun select(id: String) {
        if (currentModels.any { it.id == id }) selectModel(id)
    }

    fun nextModel() {
        val available = currentModels
        if (available.size < 2) return
        val index = available.indexOfFirst { it.id == currentModelId }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        select(available[(index + 1) % available.size].id)
    }

    fun submitFeedback(rating: RailFeedback, reason: String? = null, comment: String = "") {
        rating.submit(reason, comment, feedbackUp, feedbackDown, rewrite)
    }

    fun openInstruction(editor: Instruction) {
        showInstruction(RewriteInstructionRequest(editor.placeholder) { text ->
            when (editor) {
                Instruction.Rewrite -> rewrite(text)
                Instruction.Up -> if (text.isNotBlank()) submitFeedback(RailFeedback.Up, comment = text)
                Instruction.Down -> if (text.isNotBlank()) submitFeedback(RailFeedback.Down, comment = text)
            }
        })
    }

    fun feedbackOptions(rating: RailFeedback, reasons: List<String>, editor: Instruction) =
        reasons.map { reason -> RailOption(reason) { submitFeedback(rating, reason) } } +
            RailOption("写一句") { openInstruction(editor) }

    val modelOptions = models.map { model ->
        RailOption(shortNameOf(model.name)) { select(model.id) }
    }
    val rewriteOptions = if (presentation.fill != null) {
        listOf(RailOption("再点重发") { rewrite("") }, RailOption("取消排队") { cancel() })
    } else {
        listOf(RailOption("直接重写") { rewrite("") }, RailOption("写要求重写") {
            openInstruction(Instruction.Rewrite)
        })
    }
    val upOptions = feedbackOptions(RailFeedback.Up, listOf("一秒记住", "画面很稳", "谐音绝了"), Instruction.Up)
    val downOptions = feedbackOptions(
        RailFeedback.Down, listOf("不像人话", "没有逻辑", "太烂了"), Instruction.Down
    )
    val rowAlpha by animateFloatAsState(
        if (railVisible) 0.15f else 1f, tween(150), label = "rewriteActionsAlpha"
    )
    val publishRail: (OpenRail?) -> Unit = { next ->
        if (next != null) rail = next
        railVisible = next != null
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { areaCoordinates = it }
                .testTag("rewrite_action_area"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.fillMaxWidth().graphicsLayer { alpha = rowAlpha },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(
                        wordId = wordId,
                        control = RailControl.Model,
                        label = "${shortNameOf(RewriteModels.nameOf(models, selectedModelId))}",
                        enabled = enabled && models.isNotEmpty(),
                        options = modelOptions,
                        onTap = ::nextModel,
                        areaCoordinates = { areaCoordinates },
                        onRail = publishRail,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    ActionPill(
                        wordId = wordId,
                        control = RailControl.Rewrite,
                        label = "${presentation.label}",
                        enabled = enabled,
                        options = rewriteOptions,
                        onTap = {
                            if (currentMark?.status == RewriteStatus.PENDING) cancel() else rewrite("")
                        },
                        areaCoordinates = { areaCoordinates },
                        onRail = publishRail,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        fill = presentation.fill
                    )
                }
                Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(
                        wordId = wordId,
                        control = RailControl.Up,
                        label = if (likeCount > 0) "有帮助 $likeCount" else "有帮助",
                        enabled = enabled,
                        options = upOptions,
                        onTap = { submitFeedback(RailFeedback.Up) },
                        areaCoordinates = { areaCoordinates },
                        onRail = publishRail,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        selected = feedback == RewriteFeedbackRating.UP
                    )
                    ActionPill(
                        wordId = wordId,
                        control = RailControl.Down,
                        label = "没帮助",
                        enabled = enabled,
                        options = downOptions,
                        onTap = { submitFeedback(RailFeedback.Down) },
                        areaCoordinates = { areaCoordinates },
                        onRail = publishRail,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        selected = feedback == RewriteFeedbackRating.DOWN
                    )
                }
            }
            ActionRailOverlay(railVisible, rail, dark)
        }
        presentation.error?.let {
            Text(
                it,
                color = colors.error,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).testTag("rewrite_error")
            )
        }
    }

}

@Composable
private fun ActionPill(
    wordId: Long,
    control: RailControl,
    label: String,
    enabled: Boolean,
    options: List<RailOption>,
    onTap: () -> Unit,
    areaCoordinates: () -> LayoutCoordinates?,
    onRail: (OpenRail?) -> Unit,
    modifier: Modifier,
    selected: Boolean = false,
    fill: Float? = null
) {
    val palette = LocalHoloCardPalette.current
    val shape = RoundedCornerShape(19.dp)
    val primary = control == RailControl.Rewrite
    val glow = when (control) {
        RailControl.Model -> Color(0xFF899BE4)
        RailControl.Rewrite -> Color(0xFFAA8DE8)
        RailControl.Up -> Color(0xFF73C9B4)
        RailControl.Down -> Color(0xFFE8A2B6)
    }
    val frosted = if (palette.dark) Color(0xFF252A38) else Color(0xFFF9FAFF)
    val top = lerp(frosted, glow, if (primary || selected) 0.31f else 0.04f)
    val bottom = lerp(frosted, glow, if (primary || selected) 0.15f else 0.01f)
    val icon = when (control) {
        RailControl.Model -> Icons.Outlined.SwapHoriz
        RailControl.Rewrite -> Icons.Outlined.AutoAwesome
        RailControl.Up -> if (selected) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp
        RailControl.Down -> if (selected) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown
    }
    val ink = when {
        !enabled -> palette.subInk.copy(alpha = 0.55f)
        palette.dark -> if (primary || selected) lerp(Color.White, glow, 0.29f) else palette.ink
        primary -> Color(0xFF493571)
        selected && control == RailControl.Up -> Color(0xFF245B50)
        selected && control == RailControl.Down -> Color(0xFF773B50)
        else -> palette.ink
    }
    val haptic = LocalHapticFeedback.current
    val currentHaptic by rememberUpdatedState(haptic)
    val tap by rememberUpdatedState(onTap)
    val publish by rememberUpdatedState(onRail)
    val currentOptions by rememberUpdatedState(options)
    val getAreaCoordinates by rememberUpdatedState(areaCoordinates)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val progress by animateFloatAsState(fill ?: 0f, tween(240), label = "rewriteFill")
    val gesture = if (enabled) {
        Modifier.pointerInput(wordId, control) {
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Main)
                    val area = getAreaCoordinates()?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val button = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    fun inArea(position: Offset): Offset = area.localPositionOf(button, position) / density
                    val origin = inArea(down.position)
                    val machine = SwipeRailStateMachine(origin.x, origin.y)
                    var lockedOptions: List<RailOption>? = null

                    fun lock(elapsed: Long) {
                        val available = SwipeRailStateMachine.displayOrder(currentOptions, control == RailControl.Down || control == RailControl.Rewrite)
                        if (machine.hold(elapsed, area.size.width / density, available.size)) {
                            lockedOptions = available
                            currentHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            publish(OpenRail(control, available, checkNotNull(machine.selection)))
                        }
                    }

                    val deadline = SystemClock.uptimeMillis() + SwipeRailStateMachine.HOLD_MILLIS
                    while (true) {
                        val event = if (lockedOptions == null) {
                            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)
                            withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Main) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                        if (event == null) {
                            lock(SwipeRailStateMachine.HOLD_MILLIS)
                            if (lockedOptions == null) break
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) {
                            if (lockedOptions != null) {
                                publish(null)
                                var remaining = checkNotNull(event)
                                while (true) {
                                    remaining.changes.forEach { it.consume() }
                                    if (remaining.changes.none { it.pressed }) break
                                    remaining = awaitPointerEvent(PointerEventPass.Main)
                                }
                            }
                            break
                        }
                        val elapsed = change.uptimeMillis - down.uptimeMillis
                        val point = inArea(change.position)
                        if (elapsed >= SwipeRailStateMachine.HOLD_MILLIS && lockedOptions == null) {
                            machine.move(point.x, point.y)
                            if (machine.yielded) break
                            lock(elapsed)
                        }
                        val before = machine.selection
                        machine.move(point.x, point.y)
                        if (machine.yielded) break
                        val choices = lockedOptions
                        if (choices != null) {
                            event.changes.forEach { it.consume() }
                            val after = checkNotNull(machine.selection)
                            if (after != before) {
                                if (after.canceled != before?.canceled || !after.canceled) {
                                    currentHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                publish(OpenRail(control, choices, after))
                            }
                        }
                        if (!change.pressed) {
                            val release = machine.release(elapsed)
                            if (release != null) change.consume()
                            publish(null)
                            when (release) {
                                SwipeRailStateMachine.Release.Tap -> tap()
                                is SwipeRailStateMachine.Release.Commit -> choices?.get(release.index)?.submit?.invoke()
                                null -> Unit
                            }
                            break
                        }
                        if (choices == null) {
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.any { it.id == down.id && it.isConsumed }) {
                                machine.yield()
                                break
                            }
                        }
                    }
                    publish(null)
                }
            } finally {
                publish(null)
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier.clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(top.copy(alpha = if (enabled) 0.74f else 0.38f), bottom.copy(alpha = 0.48f))
                )
            )
            .border(
                0.6.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (palette.dark) 0.27f else 0.78f),
                        glow.copy(alpha = if (palette.dark) 0.19f else 0.28f)
                    )
                ),
                shape
            )
            .testTag(control.tag)
            .onGloballyPositioned { coordinates = it }
            .then(gesture)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (enabled) {
                    onClick { onTap(); true }
                    customActions = options.map { option ->
                        CustomAccessibilityAction(option.label) { option.submit(); true }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (fill != null && enabled) {
            val charge = if (palette.dark) {
                listOf(Color(0xFF4A3A73), Color(0xFF39456C))
            } else {
                listOf(Color(0xFFCBB9F1), Color(0xFFBFCDF4))
            }
            Box(Modifier.fillMaxSize().padding(2.5.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(charge))
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Text(
                label,
                color = ink,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (primary || selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun shortNameOf(name: String): String {
    val lower = name.lowercase()
    return when {
        "deepseek" in lower && "flash" in lower -> "DS 4.1F"
        "deepseek" in lower && "pro" in lower -> "DS 4 Pro"
        "glm" in lower -> "GLM 5.3"
        "minimax" in lower -> "MiniMax"
        else -> name.substringBefore(" ").ifBlank { name }
    }
}

@Composable
private fun ActionRailOverlay(visible: Boolean, rail: OpenRail?, dark: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(130)) + scaleIn(spring(dampingRatio = 0.78f, stiffness = 520f), 0.94f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), 0.94f)
    ) {
        if (rail != null) ActionRail(rail, dark)
    }
}

@Composable
private fun ActionRail(rail: OpenRail, dark: Boolean) {
    val foreground = if (dark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val shell = if (dark) Color(0xEB151822) else Color(0xF2FFFFFF)
    val neutral = if (dark) Color(0xEB222631) else Color(0xF2E9EDF2)
    val shape = RoundedCornerShape(24.dp)
    val thumbShape = RoundedCornerShape(19.dp)
    val canceled = rail.selection.canceled
    val thumb by animateFloatAsState(
        rail.selection.index.toFloat(), spring(dampingRatio = 0.78f, stiffness = 520f), label = "actionRailThumb"
    )
    val scale by animateFloatAsState(
        if (canceled) 0.96f else 1f, spring(dampingRatio = 0.78f, stiffness = 520f), label = "actionRailScale"
    )
    val tint by animateFloatAsState(if (canceled) 0f else 1f, tween(140), label = "actionRailTint")
    val wash = rail.control.tint
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(56.dp).testTag("rewrite_action_rail")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(12.dp, shape)
            .clip(shape)
            .background(lerp(neutral, shell, tint))
            .background(
                Brush.horizontalGradient(listOf(wash.copy(alpha = tint * 0.20f), Color.Transparent, wash.copy(alpha = tint * 0.12f)))
            )
            .border(
                1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f))), shape
            )
            .drawBehind {
                val step = size.width / rail.options.size
                repeat(rail.options.size - 1) { i ->
                    val x = step * (i + 1)
                    drawLine(
                        (if (dark) Color.White else Color.Black).copy(alpha = 0.08f * tint),
                        Offset(x, 14.dp.toPx()), Offset(x, size.height - 14.dp.toPx()), strokeWidth = 1.dp.toPx()
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val segment = maxWidth / rail.options.size
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = segment * thumb + 4.dp)
                .width((segment - 8.dp).coerceAtLeast(1.dp)).height(46.dp)
                .graphicsLayer { alpha = tint }
                .shadow(5.dp, thumbShape, clip = false, ambientColor = wash, spotColor = wash)
                .clip(thumbShape)
                .background(
                    Brush.verticalGradient(
                        if (dark) listOf(
                            lerp(Color(0xFF30344B), wash, 0.27f),
                            lerp(Color(0xFF262A40), wash, 0.10f)
                        ) else listOf(Color.White, lerp(Color(0xFFE7EDF9), wash, 0.25f))
                    )
                )
                .border(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.15f))), thumbShape)
        )
        Row(Modifier.fillMaxSize().graphicsLayer { alpha = tint }) {
            rail.options.forEachIndexed { index, option ->
                val active = rail.selection.index == index
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        option.label,
                        color = foreground.copy(alpha = if (active) 1f else 0.62f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .graphicsLayer { scaleX = if (active) 1.04f else 1f; scaleY = scaleX }
                            .testTag("rewrite_rail_option_$index")
                    )
                }
            }
        }
        if (canceled) {
            Text(
                "松手取消",
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("rewrite_rail_canceled")
            )
        }
    }
}

@Composable
internal fun InstructionInputBar(
    placeholder: String,
    onSubmit: (String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = LocalAppDarkTheme.current
    val surface = if (dark) Color(0xFF1C1F26) else Color(0xFFFFFFFF)
    val foreground = if (dark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val accent = if (dark) Color(0xFFA5B4FC) else Color(0xFF4338CA)
    val border = foreground.copy(alpha = 0.24f)

    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        keyboard?.hide()
        onSubmit(text.trim())
    }
    fun dismiss() {
        keyboard?.hide()
        onDismissRequest()
    }
    BackHandler(onBack = ::dismiss)
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Box(
        modifier.navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.44f)).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "关闭输入",
                onClick = ::dismiss
            )
        )
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(surface).border(1.dp, border, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("rewrite_instruction_bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 200) text = it },
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("rewrite_instruction_field"),
                placeholder = {
                    Text(placeholder, color = foreground.copy(alpha = 0.70f), style = MaterialTheme.typography.bodySmall)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = foreground),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = foreground, unfocusedTextColor = foreground,
                    cursorColor = accent, focusedBorderColor = accent, unfocusedBorderColor = border
                ),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(12.dp)
            )
            TextButton(onClick = { submit() }) { Text("提交", color = accent) }
        }
    }
}

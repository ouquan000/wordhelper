package com.kaoyan.wordhelper.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import com.kaoyan.wordhelper.util.SwipeRailStateMachine

internal fun railGesture(
    wordId: Long,
    control: RailControl,
    getAreaCoordinates: () -> LayoutCoordinates?,
    getButtonCoordinates: () -> LayoutCoordinates?,
    currentOptions: () -> List<RailOption>,
    currentHaptic: () -> HapticFeedback,
    tap: () -> Unit,
    publish: (OpenRail?) -> Unit
): Modifier = Modifier.pointerInput(wordId, control) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Main)
            val area = getAreaCoordinates()?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val button = getButtonCoordinates()?.takeIf { it.isAttached } ?: return@awaitEachGesture
            fun inArea(position: Offset): Offset = area.localPositionOf(button, position) / density
            val origin = inArea(down.position)
            val machine = SwipeRailStateMachine(origin.x, origin.y)
            var fingerX = origin.x
            var lockedOptions: List<RailOption>? = null

            fun lock(elapsed: Long, fingerX: Float) {
                val available = SwipeRailStateMachine.displayOrder(currentOptions(), control == RailControl.Down || control == RailControl.Rewrite)
                if (machine.hold(elapsed, area.size.width / density, available.size, fingerX)) {
                    lockedOptions = available
                    currentHaptic().performHapticFeedback(HapticFeedbackType.LongPress)
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
                    lock(SwipeRailStateMachine.HOLD_MILLIS, fingerX)
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
                fingerX = point.x
                if (elapsed >= SwipeRailStateMachine.HOLD_MILLIS && lockedOptions == null) {
                    lock(elapsed, fingerX)
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
                            currentHaptic().performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

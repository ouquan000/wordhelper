package com.kaoyan.wordhelper.util

import kotlin.math.abs

/** Coordinates are local to the action area and expressed in dp. */
internal class ActionRailGestureStateMachine(private val originX: Float, private val originY: Float) {
    private enum class Phase { Waiting, Yielded, Tracking, Finished }

    private var phase = Phase.Waiting
    private var width = 0f
    private var optionCount = 0
    private var movedHorizontally = false
    val yielded: Boolean get() = phase == Phase.Yielded
    var selection: Selection? = null
        private set

    data class Selection(val index: Int, val canceled: Boolean = false)
    sealed interface Release {
        data object Tap : Release
        data class Commit(val index: Int) : Release
    }

    fun move(x: Float, y: Float) {
        when (phase) {
            Phase.Waiting -> if (abs(x - originX) > TOUCH_SLOP_DP || abs(y - originY) > TOUCH_SLOP_DP) {
                yield()
            }
            Phase.Tracking -> {
                val current = checkNotNull(selection)
                movedHorizontally = movedHorizontally || abs(x - originX) > 2f
                val index = if (movedHorizontally) {
                    (x / width * optionCount).toInt().coerceIn(0, optionCount - 1)
                } else {
                    current.index
                }
                selection = Selection(index, abs(y - originY) > CANCEL_DISTANCE_DP)
            }
            else -> Unit
        }
    }

    fun hold(elapsedMillis: Long, width: Float, optionCount: Int, initialIndex: Int): Boolean {
        if (phase != Phase.Waiting || elapsedMillis < HOLD_MILLIS || width <= 0f || optionCount <= 0) {
            return false
        }
        this.width = width
        this.optionCount = optionCount
        selection = Selection(initialIndex.coerceIn(0, optionCount - 1))
        phase = Phase.Tracking
        return true
    }

    fun yield() {
        phase = Phase.Yielded
        selection = null
    }

    fun release(elapsedMillis: Long): Release? {
        val result = when (phase) {
            Phase.Waiting -> Release.Tap.takeIf { elapsedMillis < HOLD_MILLIS }
            Phase.Tracking -> selection?.takeUnless { it.canceled }?.let { Release.Commit(it.index) }
            else -> null
        }
        phase = Phase.Finished
        selection = null
        return result
    }

    companion object {
        const val HOLD_MILLIS = 180L
        private const val TOUCH_SLOP_DP = 10f
        private const val CANCEL_DISTANCE_DP = 44f
    }
}

internal enum class RailFeedback {
    Up, Down;

    fun submit(
        reason: String? = null,
        comment: String = "",
        onUp: (Int, List<String>, String) -> Unit,
        onDown: (Int, List<String>, String) -> Unit,
        onRewrite: (String) -> Unit
    ) {
        val reasons = listOfNotNull(reason)
        when (this) {
            Up -> onUp(1, reasons, comment)
            Down -> {
                onDown(1, reasons, comment)
                val instruction = (reasons + comment).filter(String::isNotBlank).joinToString("；")
                if (instruction.isNotEmpty()) onRewrite(instruction)
            }
        }
    }
}

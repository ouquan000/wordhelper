package com.kaoyan.wordhelper.util

import kotlin.math.abs

/** Coordinates are local to the action area and expressed in dp. */
internal class SwipeRailStateMachine(private val originX: Float, private val originY: Float) {
    private enum class Phase { Waiting, Yielded, Tracking, Finished }

    private var phase = Phase.Waiting
    private var width = 0f
    private var optionCount = 0
    private var lastX = originX
    private var openingX = originX
    private var trackingX = false
    val yielded: Boolean get() = phase == Phase.Yielded
    var selection: Selection? = null
        private set

    data class Selection(val index: Int, val canceled: Boolean = false)
    sealed interface Release {
        data object Tap : Release
        data class Commit(val index: Int) : Release
    }

    fun move(x: Float, y: Float) {
        lastX = x
        when (phase) {
            Phase.Waiting -> if (abs(x - originX) > TOUCH_SLOP_DP || abs(y - originY) > TOUCH_SLOP_DP) {
                yield()
            }
            Phase.Tracking -> {
                val current = checkNotNull(selection)
                trackingX = trackingX || abs(x - openingX) > OPEN_SLOP_DP
                val index = if (trackingX) hit(x) else current.index
                selection = Selection(index, originY - y > UPWARD_CANCEL_DP)
            }
            else -> Unit
        }
    }

    fun hold(elapsedMillis: Long, width: Float, optionCount: Int): Boolean {
        if (phase != Phase.Waiting || elapsedMillis < HOLD_MILLIS || width <= 0f || optionCount <= 0) {
            return false
        }
        this.width = width
        this.optionCount = optionCount
        openingX = lastX
        selection = Selection(hit(openingX))
        phase = Phase.Tracking
        return true
    }

    private fun hit(x: Float) = (x / width * optionCount).toInt().coerceIn(0, optionCount - 1)

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
        fun <T> displayOrder(options: List<T>, right: Boolean) = if (right) options.asReversed() else options
        private const val OPEN_SLOP_DP = 2f
        const val HOLD_MILLIS = 180L
        private const val TOUCH_SLOP_DP = 10f
        private const val UPWARD_CANCEL_DP = 96f
    }
}

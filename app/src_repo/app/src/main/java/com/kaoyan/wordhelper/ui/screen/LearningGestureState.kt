package com.kaoyan.wordhelper.ui.screen

import kotlin.math.abs

/** One recognition-rail pointer sequence. All positions and distances are pixels. */
internal class LearningRatingGesture(
    downX: Float,
    private val startY: Float,
    trackWidthPx: Float,
    private val verticalCancelPx: Float
) {
    enum class Move { Idle, Cancel, Drag, ChangeSegment }

    var index = segmentAt(downX, trackWidthPx)
        private set
    private var canceled = false
    private var dragging = false

    fun move(x: Float, y: Float, distancePx: Float, touchSlopPx: Float, widthPx: Float): Move {
        if (canceled) return Move.Idle
        if (abs(y - startY) > verticalCancelPx) {
            canceled = true
            return Move.Cancel
        }
        if (distancePx > touchSlopPx) dragging = true
        if (!dragging) return Move.Idle
        val next = segmentAt(x, widthPx)
        if (next == index) return Move.Drag
        index = next
        return Move.ChangeSegment
    }

    fun finish(): Int? = if (canceled) null else index

    private fun segmentAt(x: Float, widthPx: Float): Int {
        if (widthPx <= 0f) return 0
        return ((x / widthPx) * 3f).toInt().coerceIn(0, 2)
    }
}

/** Drawer drag arbitration and final velocity in fractions of the measured drawer width. */
internal class LearningDrawerGesture(
    private val downX: Float,
    private val downY: Float,
    downTimeMs: Long,
    private val startedOpen: Boolean,
    private val drawerWidthPx: Float
) {
    private var claimed = false
    private var lastX = downX
    private var lastTimeMs = downTimeMs

    fun move(x: Float, y: Float, timeMs: Long, touchSlopPx: Float, consumed: Boolean): Float? {
        if (!claimed) {
            val dx = x - downX
            val dy = y - downY
            val opening = dx < -touchSlopPx
            val closing = startedOpen && dx > touchSlopPx
            claimed = abs(dx) > abs(dy) && (opening || closing) && !consumed
        }
        val delta = if (claimed) -(x - lastX) / drawerWidthPx else null
        lastX = x
        lastTimeMs = timeMs
        return delta
    }

    fun release(x: Float, timeMs: Long): Float? {
        if (!claimed) return null
        val elapsedMs = (timeMs - lastTimeMs).coerceAtLeast(1L)
        val velocity = (x - lastX) / elapsedMs * 1000f
        return -velocity / drawerWidthPx
    }

    companion object {
        fun acceptsDown(x: Float, widthPx: Int, startedOpen: Boolean): Boolean =
            startedOpen || x >= widthPx * 0.60f

        fun widthPx(screenWidthPx: Int, maxWidthPx: Float): Float =
            minOf(screenWidthPx * 0.74f, maxWidthPx).coerceAtLeast(1f)
    }
}

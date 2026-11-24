package com.kaoyan.wordhelper.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningGestureStateTest {
    private val idle = LearningRatingGesture.Move.Idle
    private val drag = LearningRatingGesture.Move.Drag
    private val change = LearningRatingGesture.Move.ChangeSegment
    private val cancel = LearningRatingGesture.Move.Cancel

    private fun rating(x: Float = 10f, width: Float = 300f) =
        LearningRatingGesture(x, 20f, width, 48f)

    private fun drawer(open: Boolean = false) = LearningDrawerGesture(
        downX = 160f, downY = 30f, downTimeMs = 100L, startedOpen = open, drawerWidthPx = 200f
    )

    @Test fun tapDividesTheMeasuredWidthIntoThreeClampedSegments() {
        for ((x, expected) in listOf(-30f to 0, 99f to 0, 100f to 1, 199f to 1, 200f to 2, 400f to 2)) {
            assertEquals(expected, rating(x).finish())
        }
    }

    @Test fun zeroAndNegativeTrackWidthsSelectTheFirstSegment() {
        assertEquals(0, rating(200f, 0f).finish())
        assertEquals(0, rating(200f, -1f).finish())
    }

    @Test fun touchSlopMustBeExceededAndDraggingStaysClaimedOnReturn() {
        val gesture = rating(99f)
        assertEquals(idle, gesture.move(100f, 20f, 1f, 1f, 300f))
        assertEquals(change, gesture.move(101f, 20f, 2f, 1f, 300f))
        assertEquals(drag, gesture.move(100f, 20f, 1f, 1f, 300f))
        assertEquals(change, gesture.move(99f, 20f, 0f, 1f, 300f))
        assertEquals(0, gesture.finish())
    }

    @Test fun cancellationWinsOverCrossingAndReturningInsideDoesNotSubmit() {
        val gesture = rating()
        assertEquals(cancel, gesture.move(299f, -29f, 300f, 8f, 300f))
        assertEquals(0, gesture.index)
        assertEquals(idle, gesture.move(299f, 20f, 290f, 8f, 300f))
        assertNull(gesture.finish())
        assertEquals(2, rating(299f).finish())
    }

    @Test fun exactly48DpRemainsActiveAcrossDensities() {
        for (density in listOf(1f, 1.5f, 2.75f, 4f)) {
            val width = 300f * density
            val threshold = 48f * density
            val gesture = LearningRatingGesture(0f, 0f, width, threshold)
            assertEquals(change, gesture.move(width, threshold, width, 8f * density, width))
            assertEquals(2, gesture.finish())
            assertEquals(cancel, gesture.move(width, threshold + 1f, width, 8f * density, width))
            assertNull(gesture.finish())
        }
    }

    @Test fun draggingUsesCurrentMeasuredWidthAndClampsAtBothEnds() {
        val gesture = rating()
        assertEquals(change, gesture.move(600f, 20f, 590f, 8f, 300f))
        assertEquals(2, gesture.index)
        assertEquals(change, gesture.move(150f, 20f, 140f, 8f, 450f))
        assertEquals(1, gesture.index)
        assertEquals(change, gesture.move(-1f, 20f, 11f, 8f, 300f))
        assertEquals(0, gesture.finish())
    }

    @Test fun closedDrawerStartsOnlyInRightFortyPercent() {
        assertFalse(LearningDrawerGesture.acceptsDown(59f, 100, false))
        assertTrue(LearningDrawerGesture.acceptsDown(300 * 0.60f, 300, false))
        assertTrue(LearningDrawerGesture.acceptsDown(0f, 300, true))
    }

    @Test fun drawerWidthAppliesDpCapAtTheInputDensityAndHasOnePixelFloor() {
        for (density in listOf(1f, 1.5f, 2.75f, 4f)) {
            val cap = 360f * density
            assertEquals(cap, LearningDrawerGesture.widthPx((800 * density).toInt(), cap), 0f)
            assertEquals(296f * density, LearningDrawerGesture.widthPx((400 * density).toInt(), cap), 0.001f)
            assertEquals(1f, LearningDrawerGesture.widthPx(0, cap), 0f)
        }
    }

    @Test fun verticalTiesConsumedMovesAndExactSlopDoNotClaim() {
        val gesture = drawer()
        assertNull(gesture.move(152f, 30f, 105L, 8f, false))
        assertNull(gesture.move(140f, 50f, 110L, 8f, false))
        assertNull(gesture.move(140f, 30f, 115L, 8f, true))
        assertNull(gesture.release(130f, 120L))
    }

    @Test fun claimUsesLastUnclaimedSampleAndTracksDirectionReversal() {
        val gesture = drawer()
        assertNull(gesture.move(155f, 30f, 105L, 8f, false))
        assertEquals(0.1f, gesture.move(135f, 30f, 110L, 8f, false)!!, 0f)
        assertEquals(-0.05f, gesture.move(145f, 30f, 115L, 8f, true)!!, 0f)
        assertEquals(-5f, gesture.release(155f, 125L)!!, 0f)
    }

    @Test fun rightwardClaimRequiresOpenDrawerAndReleaseClampsElapsedTime() {
        assertNull(drawer().move(180f, 30f, 105L, 8f, false))
        val open = drawer(open = true)
        assertEquals(-0.1f, open.move(180f, 30f, 105L, 8f, false)!!, 0f)
        assertEquals(10f, open.release(178f, 105L)!!, 0f)
        assertEquals(10f, open.release(178f, 104L)!!, 0f)
    }

    @Test fun openDrawerStillClaimsLeftwardMotion() {
        assertEquals(0.1f, drawer(open = true).move(140f, 30f, 110L, 8f, false)!!, 0f)
    }
}

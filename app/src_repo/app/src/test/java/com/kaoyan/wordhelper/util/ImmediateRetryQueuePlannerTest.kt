package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ImmediateRetryQueuePlannerTest {

    @Test
    fun legacyPolicy_insertsAtTail_whenQueueIsShort() {
        val queueSize = 5
        val index = ImmediateRetryQueuePlanner.resolveInsertIndex(
            queueSize = queueSize,
            policy = ImmediateRetryQueuePlanner.LegacyPolicy,
            randomSource = RetryRandomSource { _, _ -> error("should not use random") }
        )
        assertEquals(5, index)
    }

    @Test
    fun legacyPolicy_usesConfiguredRandomRange_whenQueueIsLong() {
        val queueSize = 8
        var capturedFrom = -1
        var capturedUntil = -1
        val index = ImmediateRetryQueuePlanner.resolveInsertIndex(
            queueSize = queueSize,
            policy = ImmediateRetryQueuePlanner.LegacyPolicy,
            randomSource = RetryRandomSource { from, until ->
                capturedFrom = from
                capturedUntil = until
                4
            }
        )
        assertEquals(2, capturedFrom)
        assertEquals(8, capturedUntil)
        assertEquals(4, index)
    }

    @Test
    fun waitingRewrite_emptyQueue_insertsAtZero_withoutRandom() {
        val index = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
            queueSize = 0,
            randomSource = RetryRandomSource { _, _ -> error("should not use random") }
        )
        assertEquals(0, index)
    }

    @Test
    fun waitingRewrite_usesOneToThreeAfterCurrent() {
        var capturedFrom = -1
        var capturedUntil = -1
        val index = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
            queueSize = 10,
            randomSource = RetryRandomSource { from, until ->
                capturedFrom = from
                capturedUntil = until
                2
            }
        )
        assertEquals(0, capturedFrom)
        assertEquals(3, capturedUntil)
        assertEquals(3, index)
    }

    @Test
    fun waitingRewrite_clampsToQueueSize() {
        val index = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
            queueSize = 2,
            randomSource = RetryRandomSource { _, _ -> 2 }
        )
        assertEquals(2, index)
    }

    @Test
    fun waitingRewrite_shortQueue_placesRightAfterCurrent() {
        val index = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
            queueSize = 1,
            randomSource = RetryRandomSource { _, _ -> 0 }
        )
        assertEquals(1, index)
    }

    @Test
    fun v4Policy_usesExpectedRandomRange() {
        val queueSize = 7
        var capturedFrom = -1
        var capturedUntil = -1
        val index = ImmediateRetryQueuePlanner.resolveInsertIndex(
            queueSize = queueSize,
            policy = ImmediateRetryQueuePlanner.V4Policy,
            randomSource = RetryRandomSource { from, until ->
                capturedFrom = from
                capturedUntil = until
                3
            }
        )
        assertEquals(1, capturedFrom)
        assertEquals(7, capturedUntil)
        assertEquals(3, index)
    }
}

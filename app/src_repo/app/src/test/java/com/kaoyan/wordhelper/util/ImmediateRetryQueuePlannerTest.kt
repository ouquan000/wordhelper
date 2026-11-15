package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun waitingRewrite_emptyQueue_insertsAtZero() {
        assertEquals(
            0,
            ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(queueSize = 0, seed = 42L)
        )
    }

    @Test
    fun waitingRewrite_slotIsAlwaysTheSecondOrThirdCardAfterCurrent() {
        val slots = (1L..200L)
            .map { seed -> ImmediateRetryQueuePlanner.waitingInsertSlot(seed * 7_919L) }
            .toSet()
        assertEquals(setOf(2, 3), slots)
    }

    @Test
    fun waitingRewrite_indexIsOnePastSlot_becauseIndexZeroIsTheCurrentCard() {
        // 当前卡在下标 0，「第 N 张普通卡之后」= 下标 N + 1（add(i) 插在原下标 i 之前）。
        (1L..100L).forEach { seed ->
            val slot = ImmediateRetryQueuePlanner.waitingInsertSlot(seed)
            assertEquals(
                slot + 1,
                ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(queueSize = 10, seed = seed)
            )
        }
    }

    @Test
    fun waitingRewrite_shortQueue_clampsToQueueSize() {
        (1L..100L).forEach { seed ->
            assertEquals(1, ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(1, seed))
            assertEquals(2, ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(2, seed))
            val expected = minOf(ImmediateRetryQueuePlanner.waitingInsertSlot(seed) + 1, 3)
            assertEquals(expected, ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(3, seed))
        }
    }

    @Test
    fun waitingRewrite_sameWordAndCreatedAtStaysInTheSameSlotAfterRestart() {
        val createdAt = 1_735_000_000_000L
        val first = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
            queueSize = 20,
            wordId = 9_001L,
            createdAt = createdAt
        )
        val second = ImmediateRetryQueuePlanner.resolveWaitingInsertIndex(
            queueSize = 20,
            wordId = 9_001L,
            createdAt = createdAt
        )
        assertEquals(first, second)
        val slot = ImmediateRetryQueuePlanner.waitingInsertSlot(
            ImmediateRetryQueuePlanner.waitingRewriteSeed(9_001L, createdAt)
        )
        assertEquals(slot + 1, first)
    }

    @Test
    fun waitingRewrite_adjacentWordIdsStillCoverBothSlots() {
        // 没有随机源之后，连续 wordId 必须靠打散覆盖 2/3 两档，否则会全部挤在同一格。
        val createdAt = 1_735_000_000_000L
        val slots = (1L..50L).map { wordId ->
            ImmediateRetryQueuePlanner.waitingInsertSlot(
                ImmediateRetryQueuePlanner.waitingRewriteSeed(wordId, createdAt)
            )
        }.toSet()
        assertEquals(setOf(2, 3), slots)
    }

    @Test
    fun waitingRewrite_seedChangesWhenCreatedAtChanges() {
        val a = ImmediateRetryQueuePlanner.waitingRewriteSeed(7L, 1_735_000_000_000L)
        val b = ImmediateRetryQueuePlanner.waitingRewriteSeed(7L, 1_735_000_060_000L)
        assertNotEquals(a, b)
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

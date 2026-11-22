package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackBurstPolicyTest {

    @Test
    fun countsAccumulateAndCapAtFifty() {
        assertEquals(1, FeedbackBurstPolicy.nextCount(0))
        assertEquals(5, FeedbackBurstPolicy.nextCount(4))
        assertEquals(50, FeedbackBurstPolicy.nextCount(49, 5))
        assertEquals(50, FeedbackBurstPolicy.nextCount(80))
    }

    @Test
    fun flushOnlyAfterDebounceWindow() {
        assertFalse(FeedbackBurstPolicy.shouldFlush(0))
        assertFalse(FeedbackBurstPolicy.shouldFlush(1_799))
        assertTrue(FeedbackBurstPolicy.shouldFlush(1_800))
        assertTrue(FeedbackBurstPolicy.shouldFlush(5_000))
    }
}

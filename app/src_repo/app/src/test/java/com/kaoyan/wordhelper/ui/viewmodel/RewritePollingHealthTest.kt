package com.kaoyan.wordhelper.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewritePollingHealthTest {
    @Test
    fun oneSlowResponseDoesNotShowOffline() {
        assertFalse(rewritePollingOffline(1, null, 80_000L))
    }

    @Test
    fun ongoingSyncKeepsTheConnectionHealthyDuringProgressTimeouts() {
        assertFalse(rewritePollingOffline(3, 78_000L, 80_000L))
    }

    @Test
    fun repeatedFailuresWithNoRecentSuccessShowOffline() {
        assertTrue(rewritePollingOffline(2, null, 80_000L))
        assertTrue(rewritePollingOffline(2, 10_000L, 80_000L))
    }
}

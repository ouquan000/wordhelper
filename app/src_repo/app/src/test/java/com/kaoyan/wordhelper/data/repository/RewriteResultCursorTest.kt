package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteResultCursorTest {
    @Test
    fun sameSequenceAfterStoreResetReplaysWithNewStream() {
        val binding = RewriteResultCursor(1, "old").bind("new", highWater = 1, legacyNext = 1)
        assertEquals(RewriteResultCursor(0, "new"), binding.cursor)
        assertTrue(binding.rewind)
        assertTrue(binding.replaceSkipped)
    }

    @Test
    fun tailInUnchangedStreamDoesNotRewind() {
        val cursor = RewriteResultCursor(1, "same")
        val binding = cursor.bind("same", highWater = 1, legacyNext = 1)
        assertEquals(cursor, binding.cursor)
        assertFalse(binding.rewind)
        assertFalse(binding.replaceSkipped)
    }

    @Test
    fun firstBindingMayRecoverEqualSequenceWithoutDiscardingDeferredRows() {
        val binding = RewriteResultCursor(1, null).bind("current", highWater = 1, legacyNext = 1)
        assertEquals(RewriteResultCursor(0, "current"), binding.cursor)
        assertTrue(binding.rewind)
        assertFalse(binding.replaceSkipped)
    }

    @Test
    fun confirmedRollbackCanBeDetectedAgainstLegacyNext() {
        val binding = RewriteResultCursor(4, null).bind(null, highWater = null, legacyNext = 1)
        assertEquals(0L, binding.cursor.seq)
        assertTrue(binding.rewind)
    }
}

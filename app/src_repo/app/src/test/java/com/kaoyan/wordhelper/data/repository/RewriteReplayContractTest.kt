package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteReplayContractTest {
    private fun rewind(
        rewound: Boolean = false,
        stream: String? = null,
        since: Long = 7,
        next: Long? = 7,
        empty: Boolean = true,
        now: Long = 61_000,
        previousSeq: Long = 6
    ) = RewriteReplayPolicy.shouldRewind(rewound, stream, since, next, empty, now, 1_000, previousSeq)

    @Test fun legacyReplayOpensAtExactlyOneMinute() {
        assertFalse(rewind(now = 60_999))
        assertTrue(rewind(now = 61_000))
        assertTrue(rewind(stream = " "))
    }

    @Test fun replayRequiresStalledPositiveCursorAndEmptyLegacyPage() {
        assertFalse(rewind(stream = "stream"))
        assertFalse(rewind(since = 0, next = 0))
        assertFalse(rewind(next = null))
        assertFalse(rewind(next = 8))
        assertFalse(rewind(empty = false))
    }

    @Test fun replayCannotRepeatInRoundOrAtPersistedSequence() {
        assertFalse(rewind(rewound = true))
        assertFalse(rewind(previousSeq = 7))
        assertTrue(rewind(previousSeq = 8))
    }

    private val legacy = RewriteQueueItem(wordId = 42, word = " Test ", createdAt = 10, updatedAt = 20)

    @Test fun duplicateExactRequestIdsCannotClaimEitherBook() {
        val first = legacy.copy(bookId = 1, requestId = "shared")
        val second = first.copy(bookId = 2)
        assertNull(matchRewriteResultMark(listOf(first, second), "shared"))
        assertNull(matchRewriteResultMark(listOf(first), null))
        assertEquals(legacy, matchRewriteResultMark(listOf(legacy), " "))
        assertEquals("w42-10", rewriteClientId(legacy))
    }

    @Test fun staleQueuedWordBlocksNewerRequestAcrossBooksAtBoundary() {
        val queued = legacy.copy(bookId = 1, requestId = "sent")
        val pending = legacy.copy(word = "test", bookId = 2, requestId = "next")
        val other = legacy.copy(word = "other")
        assertEquals(listOf(other), selectRewriteUploads(listOf(pending, other), listOf(queued), 20))
        assertEquals(listOf(queued, other), selectRewriteUploads(listOf(pending, other), listOf(queued), 21))
    }
}

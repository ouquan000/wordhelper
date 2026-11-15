package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepNeedsReinsertTest {
    private fun row(bookId: Long, needs: Boolean = true) = RewriteQueueItem(
        id = bookId + 10,
        wordId = 7,
        bookId = bookId,
        word = "anchor",
        status = RewriteStatus.DONE,
        needsReinsert = needs,
        createdAt = 1,
        updatedAt = 1
    )

    @Test
    fun sameBookReenqueueKeepsDebtAndOriginalDayButRotatesRequest() {
        val old = row(3)
        val replacement = buildRewriteRequest(old, 7, 3, "anchor", "m", "try a new cue", 172800001, "new")
        assertTrue(replacement.needsReinsert)
        assertEquals(old.id, replacement.id)
        assertEquals(old.createdAt, replacement.createdAt)
        assertEquals("new", rewriteClientId(replacement))
        assertNull(matchRewriteResultMark(listOf(replacement), rewriteClientId(old)))
        assertEquals(RewriteStatus.PENDING, replacement.status)
    }

    @Test
    fun otherBookDoesNotAdoptDebtAndAnswerDoesNotCreateIt() {
        val fromOtherBook = buildRewriteRequest(row(3), 7, 4, "anchor", "", "", 2, "new")
        assertEquals(0L, fromOtherBook.id)
        assertFalse(fromOtherBook.needsReinsert)
        assertEquals(2L, fromOtherBook.createdAt)
        assertFalse(buildRewriteRequest(row(3, false), 7, 3, "anchor", "", "", 2, "next").needsReinsert)
    }

    @Test
    fun bookAndGenerationRoutingRejectsUncorrelatedResults() {
        val a = row(3).copy(requestId = "w7-b3-a")
        val b = row(4).copy(requestId = "w7-b4-b")
        val legacy = row(0)
        assertEquals(a, matchRewriteResultMark(listOf(a, b, legacy), "w7-b3-a"))
        assertEquals(b, matchRewriteResultMark(listOf(a, b, legacy), "w7-b4-b"))
        assertEquals(legacy, matchRewriteResultMark(listOf(a, b, legacy), "w7-1"))
        assertNull(matchRewriteResultMark(listOf(a, b), "w7-b3-old"))
        assertNull(matchRewriteResultMark(listOf(a, b, legacy), null))
        assertNull(matchRewriteResultMark(listOf(a), null))
        assertEquals(legacy, matchRewriteResultMark(listOf(legacy), null))
    }

    @Test
    fun legacyServerDoesNotSupersedeAnotherBooksRemoteWork() {
        val a = row(3).copy(status = RewriteStatus.QUEUED, updatedAt = 10)
        val b = row(4).copy(status = RewriteStatus.PENDING, word = " ANCHOR ")
        val anotherWord = b.copy(wordId = 8, word = "ship")
        assertEquals(listOf(anotherWord), selectRewriteUploads(listOf(b, anotherWord), listOf(a), 10))
        assertEquals(listOf(a, anotherWord), selectRewriteUploads(listOf(b, anotherWord), listOf(a), 11))
        assertEquals(listOf(b, anotherWord), selectRewriteUploads(listOf(b, anotherWord), emptyList(), 11))
    }
}

package com.kaoyan.wordhelper.ui.screen

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.3：RewriteBar 的单一状态源互斥契约。 */
class RewriteBarStatusTest {

    private fun mark(status: String, error: String? = null) = RewriteQueueItem(
        wordId = 1L,
        bookId = 2L,
        word = "create",
        status = status,
        error = error,
        createdAt = 1L,
        updatedAt = 1_700_000_000_000L,
        requestId = "r1"
    )

    @Test
    fun pendingOnlyShowsOneStatusLineEvenWithStaleProgress() {
        val p = rewriteBarPresentation(mark(RewriteStatus.PENDING), hasProgress = true, justUpdated = false)
        assertEquals("已标记 · 待上传", p.statusLine)
        assertFalse(p.showProgress)
        assertFalse(p.showUndo)
    }

    @Test
    fun queuedWithoutProgressHasNoSecondQueuedLine() {
        val p = rewriteBarPresentation(mark(RewriteStatus.QUEUED), hasProgress = false, justUpdated = false)
        assertEquals("电脑已收到 · 排队中", p.statusLine)
        assertFalse(p.showProgress)
    }

    @Test
    fun queuedWithRealProgressStillHasNoSecondQueuedLine() {
        val p = rewriteBarPresentation(mark(RewriteStatus.QUEUED), hasProgress = true, justUpdated = false)
        assertEquals("电脑已收到 · 排队中", p.statusLine)
        assertFalse(p.showProgress)
    }

    @Test
    fun doneShowsUndoAndTime() {
        val p = rewriteBarPresentation(mark(RewriteStatus.DONE), hasProgress = false, justUpdated = false)
        assertTrue(p.statusLine.startsWith("上次重写 "))
        assertTrue(p.showUndo)
        assertFalse(p.showProgress)
    }

    @Test
    fun failedShowsOnlyReason() {
        val p = rewriteBarPresentation(mark(RewriteStatus.FAILED, "生成超时"), hasProgress = false, justUpdated = false)
        assertEquals("上次没成功：生成超时", p.statusLine)
        assertFalse(p.showUndo)
    }

    @Test
    fun justUpdatedOverridesButKeepsUndoForDone() {
        val p = rewriteBarPresentation(mark(RewriteStatus.DONE), hasProgress = false, justUpdated = true)
        assertEquals("已更新", p.statusLine)
        assertTrue(p.showUndo)
    }
}


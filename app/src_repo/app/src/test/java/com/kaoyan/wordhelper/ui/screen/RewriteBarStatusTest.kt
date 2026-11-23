package com.kaoyan.wordhelper.ui.screen

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteBarStatusTest {
    private fun mark(status: String, error: String? = null) = RewriteQueueItem(
        wordId = 1L, word = "create", status = status, error = error, createdAt = 1L, updatedAt = 1L
    )

    @Test
    fun pendingIgnoresPreviousRequestProgress() {
        assertEquals(
            RewriteBarPresentation("待上传", fill = 0.04f),
            rewriteBarPresentation(mark(RewriteStatus.PENDING), RewriteProgressUi(fraction = 0.75f))
        )
    }

    @Test
    fun queuedDisplaysItsStageInsideTheButton() {
        val queued = mark(RewriteStatus.QUEUED)
        assertEquals(RewriteBarPresentation("排队中", fill = 0.08f), rewriteBarPresentation(queued, null))
        assertEquals(
            RewriteBarPresentation("生成中 45%", fill = 0.45f),
            rewriteBarPresentation(queued, RewriteProgressUi(fraction = 0.45f))
        )
        assertEquals(
            RewriteBarPresentation("等待电脑上线", fill = 0.45f),
            rewriteBarPresentation(queued, RewriteProgressUi(fraction = 0.45f, offline = true))
        )
    }

    @Test
    fun terminalStatesShowOnlyAConcreteFailure() {
        val idle = RewriteBarPresentation("重写思路")
        assertEquals(idle, rewriteBarPresentation(mark(RewriteStatus.DONE), null))
        assertEquals(idle, rewriteBarPresentation(mark(RewriteStatus.FAILED, " "), null))
        assertEquals(
            idle.copy(error = "生成超时"),
            rewriteBarPresentation(mark(RewriteStatus.FAILED, "生成超时"), null)
        )
    }
}

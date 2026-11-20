package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.4.7v7-bugfix：右侧「跳过」的显示/可用判定。
 *
 * 这组不测 Compose，只钉住「按钮条件」和「点击命令条件」共用的那份纯函数：
 * 之前 UI 用 `mark != null` 显示、再用 status 判断可用，导致 mark 已被撤销/换书时
 * 按钮亮着但点下去被 ViewModel 静默 return。
 */
class SkipRewriteAvailabilityTest {

    private fun mark(
        wordId: Long = 11L,
        bookId: Long = 2L,
        status: String = RewriteStatus.QUEUED,
        needsReinsert: Boolean = false
    ) = RewriteQueueItem(
        wordId = wordId,
        bookId = bookId,
        word = "system",
        status = status,
        needsReinsert = needsReinsert,
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun pendingAndQueuedMarksMakeSkipAvailable() {
        // 离线点「重写」时 markRewrite 也会先落本地队列，留下的是 PENDING；
        // 联网补发成功后变 QUEUED。两种状态都必须让「跳过」入口可用。
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(11L, 2L, mark(status = RewriteStatus.QUEUED))
        )
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(11L, 2L, mark(status = RewriteStatus.PENDING))
        )
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(11L, 2L, mark(status = RewriteStatus.FAILED))
        )
    }

    @Test
    fun resultReadyIsNotSkippable() {
        // 已经合格的稿按既有插回规则处理：入口保留但不可点，命令侧也只提示不换词。
        assertEquals(
            SkipRewriteAvailability.RESULT_READY,
            skipRewriteAvailability(11L, 2L, mark(status = RewriteStatus.DONE, needsReinsert = true))
        )
    }

    @Test
    fun missingMarkHidesEntry() {
        assertEquals(SkipRewriteAvailability.NO_MARK, skipRewriteAvailability(11L, 2L, null))
    }

    @Test
    fun otherWordOrBookIsRejected() {
        assertEquals(
            SkipRewriteAvailability.OTHER_WORD,
            skipRewriteAvailability(11L, 2L, mark(wordId = 99L))
        )
        assertEquals(
            SkipRewriteAvailability.OTHER_BOOK,
            skipRewriteAvailability(11L, 2L, mark(bookId = 3L))
        )
    }

    @Test
    fun legacyGlobalRowStillBelongsToCurrentBook() {
        // book_id = 0 是 DB17 迁移留下的全局行，和 observeMark 一样算当前书。
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(11L, 2L, mark(bookId = 0L))
        )
    }

    @Test
    fun noCurrentCardIsNotSkippable() {
        assertEquals(
            SkipRewriteAvailability.OTHER_WORD,
            skipRewriteAvailability(null, 2L, mark())
        )
        assertEquals(
            SkipRewriteAvailability.OTHER_WORD,
            skipRewriteAvailability(11L, null, mark())
        )
    }
}

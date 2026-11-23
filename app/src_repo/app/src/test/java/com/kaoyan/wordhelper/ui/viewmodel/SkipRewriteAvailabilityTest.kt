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
        needsReinsert: Boolean = false,
        requestId: String = "r1"
    ) = RewriteQueueItem(
        wordId = wordId,
        bookId = bookId,
        word = "system",
        status = status,
        needsReinsert = needsReinsert,
        createdAt = 1L,
        updatedAt = 1L,
        requestId = requestId
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
            skipRewriteAvailability(
                11L,
                2L,
                mark(status = RewriteStatus.DONE, needsReinsert = true, requestId = "r1"),
                activeRequestId = "r1"
            )
        )
    }

    @Test
    fun historicalDoneWithoutActiveRoundHidesEntry() {
        // 1.5.3：没点过重写的词，库里可能还留着历史 DONE；这个入口必须彻底隐藏，
        // 不能因为「历史 DONE」就常驻亮着跳过按钮。
        assertEquals(
            SkipRewriteAvailability.NO_MARK,
            skipRewriteAvailability(11L, 2L, mark(status = RewriteStatus.DONE, requestId = "old"))
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
    fun oldDoneRoundDoesNotDisableTheNewRound() {
        val oldDone = mark(status = RewriteStatus.DONE, requestId = "r1")
        // 刚点下新一轮、requestId 还没落库：空串代表「本轮已开始」。
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(11L, 2L, oldDone, activeRequestId = "")
        )
        // 新一轮已拿到 requestId，但库里还是上一轮的 DONE：必须仍可跳过。
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(11L, 2L, oldDone, activeRequestId = "r2")
        )
    }

    @Test
    fun onlyCurrentRoundResultIsReady() {
        val currentDone = mark(status = RewriteStatus.DONE, requestId = "r2")
        assertEquals(
            SkipRewriteAvailability.RESULT_READY,
            skipRewriteAvailability(11L, 2L, currentDone, activeRequestId = "r2")
        )
    }

    @Test
    fun threeRewriteSkipRoundsStayClickable() {
        // 同一词连续三轮「重写 → 跳过」：每轮开始都先把 activeRequestId 置空/换新，
        // 旧轮的 DONE 不能把新一轮的跳过入口置灰。
        for (round in 1..3) {
            val previous = mark(status = RewriteStatus.DONE, requestId = "r${round - 1}")
            assertEquals(
                "round $round before enqueue",
                SkipRewriteAvailability.AVAILABLE,
                skipRewriteAvailability(11L, 2L, previous, activeRequestId = "")
            )
            val current = mark(status = RewriteStatus.PENDING, requestId = "r$round")
            assertEquals(
                "round $round pending",
                SkipRewriteAvailability.AVAILABLE,
                skipRewriteAvailability(11L, 2L, current, activeRequestId = "r$round")
            )
        }
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

    @Test
    fun c1_doneLandingClearsActiveRequestIdSoSkipIsFullyRetracted() {
        // 1.5.3v2 C1：本轮重写 DONE 落地后，pollRewriteProgressOnce 会把该词从
        // _activeRewriteRequestIds 清掉。清理后纯函数必须落到 NO_MARK —— 右下角
        // 「跳过」彻底消失，而不是留在 RESULT_READY 灰色占位把三段胶囊挤窄。
        val done = mark(status = RewriteStatus.DONE, needsReinsert = true, requestId = "r1")
        assertEquals(
            SkipRewriteAvailability.NO_MARK,
            skipRewriteAvailability(11L, 2L, done, activeRequestId = null)
        )
        // 清理前仍是 RESULT_READY；1.5.3v2 的 UI 层 showSkip 已不再渲染该状态。
        assertEquals(
            SkipRewriteAvailability.RESULT_READY,
            skipRewriteAvailability(11L, 2L, done, activeRequestId = "r1")
        )
    }

    @Test
    fun c1_whileRoundIsInFlightSkipStaysAvailable() {
        // 重写排队中（本轮 requestId 已落库、状态还是 QUEUED/PENDING）时，
        // 跳过入口必须仍然可点 —— 这才是「同词无限轮 重写→跳过→回队」的前提。
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(
                11L,
                2L,
                mark(status = RewriteStatus.QUEUED, requestId = "r1"),
                activeRequestId = "r1"
            )
        )
        assertEquals(
            SkipRewriteAvailability.AVAILABLE,
            skipRewriteAvailability(
                11L,
                2L,
                mark(status = RewriteStatus.PENDING, requestId = "r1"),
                activeRequestId = "r1"
            )
        )
    }
}

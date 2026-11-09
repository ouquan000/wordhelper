package com.kaoyan.wordhelper.data.model

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.6.1 P0：重启后等待重写的恢复规则。
 *
 * 复现链路：DB 有 pending 行 → 进程重启（内存等待集合为空）→ 补注册 → 收到 DONE
 * → 词不能丢，也不能重复插回。DB20 后还要覆盖「自动同步先写成 done」的冷启动竞态：
 * needs_reinsert=1 的 done 行必须直接补插。
 */
class WaitingRewriteRestoreTest {

    private fun row(
        wordId: Long,
        status: String,
        needsReinsert: Boolean = true,
        bookId: Long = 2L
    ) = RewriteQueueItem(
        wordId = wordId,
        bookId = bookId,
        word = "w$wordId",
        status = status,
        needsReinsert = needsReinsert,
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun emptyMemoryRegistersEveryOpenWord() {
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(
                row(7L, RewriteStatus.PENDING),
                row(9L, RewriteStatus.QUEUED)
            ),
            alreadyTracked = emptySet()
        )
        assertEquals(listOf(7L, 9L), plan.watchWordIds)
        assertTrue(plan.reinsertWordIds.isEmpty())
    }

    @Test
    fun repeatedLoadDoesNotRegisterAgain() {
        val rows = listOf(
            row(7L, RewriteStatus.PENDING),
            row(9L, RewriteStatus.QUEUED)
        )
        val first = WaitingRewriteRestore.plan(rows, alreadyTracked = emptySet())
        val second = WaitingRewriteRestore.plan(rows, alreadyTracked = first.watchWordIds.toSet())
        assertTrue(first.watchWordIds.isNotEmpty())
        assertTrue(second.watchWordIds.isEmpty())
        assertTrue(second.reinsertWordIds.isEmpty())
    }

    @Test
    fun doneWordIsInsertedWhenQueueIsEmpty() {
        assertTrue(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = 7L,
                currentWordId = null,
                queuedWordIds = emptySet(),
                answeredToday = false
            )
        )
    }

    @Test
    fun doneWordAlreadyInQueueIsNotInsertedTwice() {
        assertFalse(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = 7L,
                currentWordId = 7L,
                queuedWordIds = setOf(7L),
                answeredToday = false
            )
        )
        assertFalse(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = 7L,
                currentWordId = 8L,
                queuedWordIds = setOf(7L, 8L),
                answeredToday = false
            )
        )
    }

    @Test
    fun answeredTodayIsNotPushedBackIntoTheSession() {
        assertFalse(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = 7L,
                currentWordId = null,
                queuedWordIds = emptySet(),
                answeredToday = true
            )
        )
    }

    @Test
    fun restartThenDoneKeepsExactlyOneCopy() {
        // 重启：内存为空，DB 里还有 1 条 pending。
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(row(7L, RewriteStatus.PENDING)),
            alreadyTracked = emptySet()
        )
        assertEquals(listOf(7L), plan.watchWordIds)
        assertTrue(plan.reinsertWordIds.isEmpty())

        // 结果 DONE：队列空、今天没答过 → 插回。
        val shouldInsert = WaitingRewriteRestore.shouldInsertOnReady(
            wordId = plan.watchWordIds.single(),
            currentWordId = null,
            queuedWordIds = emptySet(),
            answeredToday = false
        )
        assertTrue(shouldInsert)

        // 如果同一事件又回调一次，词已在队列里 → 不再重复插入。
        assertFalse(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = plan.watchWordIds.single(),
                currentWordId = null,
                queuedWordIds = setOf(7L),
                answeredToday = false
            )
        )
    }

    @Test
    fun invalidIdsAreIgnored() {
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(
                row(0L, RewriteStatus.DONE),
                row(-1L, RewriteStatus.DONE)
            ),
            alreadyTracked = emptySet()
        )
        assertTrue(plan.watchWordIds.isEmpty())
        assertTrue(plan.reinsertWordIds.isEmpty())
    }

    @Test
    fun doneRowWithNeedsReinsertIsReinsertedOnColdStart() {
        // 冷启动：自动同步已经把状态写成 done，内存等待集合为空。
        // restore 不再看 status，靠 needs_reinsert 直接补插。
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(row(7L, RewriteStatus.DONE)),
            alreadyTracked = emptySet()
        )
        assertEquals(emptyList<Long>(), plan.watchWordIds)
        assertEquals(listOf(7L), plan.reinsertWordIds)

        assertTrue(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = 7L,
                currentWordId = null,
                queuedWordIds = emptySet(),
                answeredToday = false
            )
        )

        // 插回后（或已注册 observer 后）同一行不能再次进入计划。
        val again = WaitingRewriteRestore.plan(
            rows = listOf(row(7L, RewriteStatus.DONE)),
            alreadyTracked = setOf(7L)
        )
        assertTrue(again.watchWordIds.isEmpty())
        assertTrue(again.reinsertWordIds.isEmpty())
    }

    @Test
    fun queuedRowWithNeedsReinsertWaitsForObserverThenInserts() {
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(row(7L, RewriteStatus.QUEUED)),
            alreadyTracked = emptySet()
        )
        assertEquals(listOf(7L), plan.watchWordIds)
        assertTrue(plan.reinsertWordIds.isEmpty())

        // observer 收到 DONE 后仍走同一套插回判定。
        assertTrue(
            WaitingRewriteRestore.shouldInsertOnReady(
                wordId = 7L,
                currentWordId = null,
                queuedWordIds = emptySet(),
                answeredToday = false
            )
        )
    }

    @Test
    fun ordinaryRewriteWithoutNeedsReinsertIsNeverReinserted() {
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(
                row(7L, RewriteStatus.DONE, needsReinsert = false),
                row(8L, RewriteStatus.QUEUED, needsReinsert = false)
            ),
            alreadyTracked = emptySet()
        )
        assertTrue(plan.watchWordIds.isEmpty())
        assertTrue(plan.reinsertWordIds.isEmpty())
    }

    @Test
    fun failedRowWithNeedsReinsertIsNotReinserted() {
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(row(7L, RewriteStatus.FAILED)),
            alreadyTracked = emptySet()
        )
        assertTrue(plan.watchWordIds.isEmpty())
        assertTrue(plan.reinsertWordIds.isEmpty())
    }

    @Test
    fun invalidAndDuplicateRowsAreDeduped() {
        val plan = WaitingRewriteRestore.plan(
            rows = listOf(
                row(0L, RewriteStatus.DONE),
                row(-1L, RewriteStatus.DONE),
                row(7L, RewriteStatus.DONE),
                row(7L, RewriteStatus.DONE)
            ),
            alreadyTracked = emptySet()
        )
        assertEquals(listOf(7L), plan.reinsertWordIds)
        assertTrue(plan.watchWordIds.isEmpty())
    }
}

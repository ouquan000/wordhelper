package com.kaoyan.wordhelper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.7.2 W2：合格重写稿的插回位置。
 *
 * 约定：下标 0 是正在展示、还没作答的当前卡；slot 表示「当前卡之后第几张普通卡之后」。
 */
class WaitingReinsertPlacementTest {

    @Test
    fun emptyQueue_showsTheFirstReadyCardImmediately() {
        val result = WaitingReinsertPlacement.apply(
            queue = emptyList(),
            pending = listOf(pending(wordId = 9L, slot = 2))
        )
        assertEquals(listOf(9L), result)
    }

    @Test
    fun onlyCurrentCard_appendsWithoutMovingCurrent() {
        assertEquals(
            listOf(100L, 9L),
            WaitingReinsertPlacement.apply(
                queue = listOf(100L),
                pending = listOf(pending(wordId = 9L, slot = 2))
            )
        )
        assertEquals(
            listOf(100L, 9L),
            WaitingReinsertPlacement.apply(
                queue = listOf(100L),
                pending = listOf(pending(wordId = 9L, slot = 3))
            )
        )
    }

    @Test
    fun fewerOrdinaryCardsThanSlot_runsThemAllThenInserts() {
        // 只有 1 张普通卡，槽位是第 3 张之后：先把普通卡走完，再接到队尾。
        assertEquals(
            listOf(100L, 1L, 9L),
            WaitingReinsertPlacement.apply(
                queue = listOf(100L, 1L),
                pending = listOf(pending(wordId = 9L, slot = 3))
            )
        )
    }

    @Test
    fun slotTwo_landsRightAfterTheSecondOrdinaryCard() {
        val result = WaitingReinsertPlacement.apply(
            queue = listOf(100L, 1L, 2L, 3L, 4L),
            pending = listOf(pending(wordId = 9L, slot = 2))
        )
        assertEquals(listOf(100L, 1L, 2L, 9L, 3L, 4L), result)
        assertEquals(3, result.indexOf(9L))
    }

    @Test
    fun slotThree_landsRightAfterTheThirdOrdinaryCard() {
        val result = WaitingReinsertPlacement.apply(
            queue = listOf(100L, 1L, 2L, 3L, 4L),
            pending = listOf(pending(wordId = 9L, slot = 3))
        )
        assertEquals(listOf(100L, 1L, 2L, 3L, 9L, 4L), result)
        assertEquals(4, result.indexOf(9L))
    }

    @Test
    fun multipleReadyCards_areSpreadInsteadOfStacked() {
        val result = WaitingReinsertPlacement.apply(
            queue = listOf(100L, 1L, 2L, 3L, 4L),
            pending = listOf(
                pending(wordId = 9L, slot = 2, order = 0L),
                pending(wordId = 8L, slot = 2, order = 1L)
            )
        )
        assertEquals(listOf(100L, 1L, 2L, 9L, 3L, 8L, 4L), result)
        // 两张稿不能挤在同一格。
        assertTrue(result.indexOf(8L) - result.indexOf(9L) > 1)
    }

    @Test
    fun alreadyQueuedCard_keepsItsPositionNotDuplicated() {
        val result = WaitingReinsertPlacement.apply(
            queue = listOf(100L, 1L, 9L, 2L),
            pending = listOf(pending(wordId = 9L, slot = 2))
        )
        // 已经在队列里的稿不能每次重建都重插到“当前卡后第 2 张”，
        // 否则它会永远停在同一格、轮不到它。保留原位，随前面的卡被答掉自然前移。
        assertEquals(listOf(100L, 1L, 9L, 2L), result)
        assertEquals(1, result.count { it == 9L })
    }

    @Test
    fun currentCardIsNotReinserted() {
        val queue = listOf(100L, 1L, 2L)
        val result = WaitingReinsertPlacement.apply(
            queue = queue,
            pending = listOf(pending(wordId = 100L, slot = 2))
        )
        assertEquals(queue, result)
    }

    @Test
    fun rebuildingWithTheSameInput_doesNotReshuffle() {
        val pending = listOf(pending(wordId = 9L, slot = 2))
        val first = WaitingReinsertPlacement.apply(listOf(100L, 1L, 2L, 3L, 4L), pending)
        val second = WaitingReinsertPlacement.apply(listOf(100L, 1L, 2L, 3L, 4L), pending)
        assertEquals(first, second)
        // 插入后再重排一次也必须稳定（作答后重建队列走的就是这条路）。
        assertEquals(first, WaitingReinsertPlacement.apply(first, pending))
    }

    @Test
    fun invalidWordIdsAreIgnored() {
        val queue = listOf(100L, 1L)
        assertEquals(
            queue,
            WaitingReinsertPlacement.apply(
                queue = queue,
                pending = listOf(pending(wordId = 0L, slot = 2), pending(wordId = -5L, slot = 3))
            )
        )
    }

    private fun pending(wordId: Long, slot: Int, order: Long = 0L) =
        WaitingReinsertPlacement.Pending(wordId = wordId, slot = slot, order = order)
}

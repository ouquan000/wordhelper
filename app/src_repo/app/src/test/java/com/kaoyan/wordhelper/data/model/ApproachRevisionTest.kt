package com.kaoyan.wordhelper.data.model

import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApproachRevisionTest {

    @Test
    fun bookApproachWinsOverGlobalAndUsesBookRevision() {
        val rev = ApproachRevision.resolve(
            bookApproach = "书里的新版思路",
            bookRev = 7,
            globalApproach = "全局旧思路",
            globalRev = 3
        )
        assertEquals(7, rev)
    }

    @Test
    fun fallsBackToGlobalRevisionWhenBookHasNoApproach() {
        assertEquals(
            4,
            ApproachRevision.resolve(
                bookApproach = "",
                bookRev = 99,
                globalApproach = "全局思路",
                globalRev = 4
            )
        )
        assertEquals(
            4,
            ApproachRevision.resolve(
                bookApproach = null,
                bookRev = 99,
                globalApproach = "全局思路",
                globalRev = 4
            )
        )
    }

    @Test
    fun seedApproachUsesRevisionZero() {
        assertEquals(0, ApproachRevision.resolve(null, 5, null, 9))
        assertEquals(0, ApproachRevision.resolve("", 5, "   ", 9))
    }

    @Test
    fun negativeRevisionIsClampedToZero() {
        assertEquals(0, ApproachRevision.resolve("思路", -3, null, 0))
        assertEquals(0, ApproachRevision.resolve(null, 0, "思路", -1))
    }

    @Test
    fun feedbackIsMatchedByDisplayedRevision() {
        val old = feedback(wordId = 1, rating = "down", approachRev = 2)
        val current = feedback(wordId = 1, rating = "up", approachRev = 3)

        assertEquals("up", feedbackRatingForRevision(listOf(current, old), 3))
        assertEquals("down", feedbackRatingForRevision(listOf(current, old), 2))
        // 换到还没评价过的第 4 版：按钮回到未评价，允许对新版重新表态。
        assertNull(feedbackRatingForRevision(listOf(current, old), 4))
    }

    private fun feedback(wordId: Long, rating: String, approachRev: Int) = RewriteFeedback(
        wordId = wordId,
        bookId = 1,
        word = "test",
        rating = rating,
        approach = "思路",
        createdAt = 1L,
        approachRev = approachRev
    )
}

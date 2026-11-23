package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.util.SwipeRailStateMachine.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeRailStateMachineTest {
    private val machine = SwipeRailStateMachine(30f, 60f)

    @Test
    fun openingAndOnePixelMovementUseTheSameSegmentAcrossDensities() {
        for (density in listOf(1f, 2f, 3.5f)) {
            for ((x, slot) in listOf(10f to 0, 159.9f to 1, 310f to 3)) {
                val rail = SwipeRailStateMachine(x, 0f)
                assertTrue(rail.hold(180, 320f, 4))
                assertEquals(slot, rail.selection?.index)
                rail.move(x + 1f / density, 0f)
                assertEquals(Release.Commit(slot), rail.release(190))
            }
        }
    }

    @Test
    fun holdUsesLatestPointEvenWhenItCrossesASegmentBeforeTheDeadline() {
        val rail = SwipeRailStateMachine(78f, 20f)
        rail.move(81f, 20f)
        assertTrue(rail.hold(180, 320f, 4))
        assertEquals(Release.Commit(1), rail.release(181))
    }

    @Test
    fun rightHandOrderPlacesPrimaryAtTheRightEdge() {
        val choices = listOf("primary", "second", "third")
        assertEquals(choices.reversed(), SwipeRailStateMachine.displayOrder(choices, true))
        assertEquals(choices, SwipeRailStateMachine.displayOrder(choices, false))
    }

    @Test
    fun quickStationaryReleaseIsATapExactlyOnce() {
        machine.move(32f, 59f)
        assertEquals(Release.Tap, machine.release(179))
        assertNull(machine.release(179))
    }

    @Test
    fun verticalMoveAboveTenDpYieldsEvenIfTheFingerReturns() {
        machine.move(30f, 49.9f)
        assertTrue(machine.yielded)
        machine.move(30f, 60f)
        assertFalse(machine.hold(200, 320f, 4))
        assertNull(machine.release(200))
    }

    @Test
    fun holdsAt180MsAndStartsAtFinger() {
        assertFalse(machine.hold(179, 320f, 4))
        machine.move(40f, 70f)
        assertTrue(machine.hold(180, 320f, 4))
        machine.move(30f, 60f)
        assertEquals(0, machine.selection?.index)
        assertEquals(Release.Commit(0), machine.release(181))
    }

    @Test
    fun horizontalDragCrossesMultipleSegmentsInBothDirections() {
        machine.hold(180, 320f, 4)
        listOf(82f to 1, 165f to 2, 260f to 3, 154f to 1, -20f to 0).forEach { (x, index) ->
            machine.move(x, 60f)
            assertEquals(index, machine.selection?.index)
        }
        assertEquals(Release.Commit(0), machine.release(350))
    }

    @Test
    fun draggingUp110DpCancels() {
        machine.hold(180, 320f, 4)
        machine.move(220f, -50f)
        assertTrue(machine.selection?.canceled == true)
        assertNull(machine.release(300))
    }

    @Test
    fun draggingDown60DpKeepsTheChoiceAndCommits() {
        machine.hold(180, 320f, 4)
        machine.move(220f, 120f)
        assertEquals(SwipeRailStateMachine.Selection(2), machine.selection)
        assertEquals(Release.Commit(2), machine.release(300))
    }

    @Test
    fun draggingBackToThe96DpBoundaryRecoversTheCurrentSlot() {
        machine.hold(180, 320f, 4)
        machine.move(220f, -50f)
        assertTrue(machine.selection?.canceled == true)
        machine.move(255f, -36f)
        assertEquals(SwipeRailStateMachine.Selection(3), machine.selection)
        assertEquals(Release.Commit(3), machine.release(340))
    }

    @Test
    fun everyLikeRouteRecordsOneLikeAndNeverRewrites() {
        val reasons = mutableListOf<List<String>>()
        val notes = mutableListOf<String>()
        fun submit(reason: String? = null, comment: String = "") {
            RailFeedback.Up.submit(
                reason, comment,
                onUp = { count, labels, note ->
                    assertEquals(1, count)
                    reasons.add(labels)
                    notes.add(note)
                },
                onDown = { _, _, _ -> error("Like dispatched a dislike") },
                onRewrite = { error("Like dispatched a rewrite") }
            )
        }
        submit()
        listOf("一秒记住", "画面很稳", "谐音绝了").forEach { submit(it) }
        submit(comment = "记住了")
        assertEquals(listOf(emptyList(), listOf("一秒记住"), listOf("画面很稳"), listOf("谐音绝了"), emptyList()), reasons)
        assertEquals(listOf("", "", "", "", "记住了"), notes)
    }

    @Test
    fun onlyReasonedDislikeRewrites() {
        val instructions = mutableListOf<String>()
        var dislikes = 0
        val down: (Int, List<String>, String) -> Unit = { count, _, _ -> dislikes += count }
        val up: (Int, List<String>, String) -> Unit = { _, _, _ -> error("Dislike dispatched a like") }
        RailFeedback.Down.submit(onUp = up, onDown = down, onRewrite = { instructions.add(it) })
        RailFeedback.Down.submit("太烂了", onUp = up, onDown = down, onRewrite = { instructions.add(it) })
        RailFeedback.Down.submit(comment = "例句偏题", onUp = up, onDown = down, onRewrite = { instructions.add(it) })
        assertEquals(3, dislikes)
        assertEquals(listOf("太烂了", "例句偏题"), instructions)
    }
}

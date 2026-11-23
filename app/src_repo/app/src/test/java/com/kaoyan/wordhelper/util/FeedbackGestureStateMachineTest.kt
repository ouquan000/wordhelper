package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeedbackGestureStateMachine 的纯 JVM 单测。
 *
 * 覆盖开工令红线：180ms 意图锁、长按连发（连赞/连踩同一机制）、上滑呼出浮层、
 * 下滑呼出浮层、滑回原点取消、锁死滑动后消费事件（外层滚动不跟滚）、
 * 以及「👍有帮助」契约绝不支持水平切档/任何重写语义。
 */
class FeedbackGestureStateMachineTest {

    private fun upMachine() = FeedbackGestureStateMachine(GestureContract.THUMB_UP)
    private fun downMachine() = FeedbackGestureStateMachine(GestureContract.THUMB_DOWN)
    private fun modelMachine() = FeedbackGestureStateMachine(GestureContract.MODEL_SWITCH)

    // ---------- 短按 ----------

    @Test
    fun tap_releaseInsideIntentWindowWithNoMove() {
        val m = upMachine()
        assertEquals(GestureDecision.CONSUME, m.onDown(100f, 100f, 0L))
        val d = m.onUp(101f, 100f, 150L)
        assertEquals(GestureEvent.Tap, d.event)
        assertFalse(d.consume)
    }

    // ---------- 长按连发 ----------

    @Test
    fun longPress_startsWhenTickCrosses180msWhileStationary() {
        val m = upMachine()
        m.onDown(100f, 100f, 0L)
        // 170ms 内原地 tick：还不长按
        assertEquals(GestureDecision.NONE, m.onTick(170L))
        // 180ms 整点：进入长按第 1 次
        val d = m.onTick(180L)
        assertEquals(GestureEvent.LongPressTick(1), d.event)
        assertTrue(d.haptic)
    }

    @Test
    fun longPress_repeatsEvery110msUpToCap() {
        val m = upMachine()
        m.onDown(100f, 100f, 0L)
        assertEquals(GestureEvent.LongPressTick(1), m.onTick(180L).event)
        // 180+109=289 未到 110ms 间隔：不动作
        assertEquals(GestureDecision.CONSUME, m.onTick(289L))
        assertEquals(GestureEvent.LongPressTick(2), m.onTick(290L).event)
        assertEquals(GestureEvent.LongPressTick(3), m.onTick(400L).event)
        assertEquals(GestureEvent.LongPressTick(4), m.onTick(510L).event)
    }

    @Test
    fun longPress_capsAt50() {
        val m = upMachine()
        m.onDown(100f, 100f, 0L)
        m.onTick(180L)
        var last = 0
        var t = 290L
        while (t < 60_000L) {
            val d = m.onTick(t)
            val ev = d.event
            if (ev is GestureEvent.LongPressTick) last = ev.count
            t += 110L
        }
        assertEquals(50, last)
        // 到顶后不再涨
        assertEquals(GestureDecision.CONSUME, m.onTick(t))
    }

    @Test
    fun longPress_commitOnUpReportsCount() {
        val m = upMachine()
        m.onDown(100f, 100f, 0L)
        m.onTick(180L)
        m.onTick(290L) // 2 次
        val d = m.onUp(100f, 100f, 300L)
        assertEquals(GestureEvent.LongPressCommit(2), d.event)
    }

    // ---------- 180ms 意图锁：锁死滑动 ----------

    @Test
    fun moveOver8dpInside180ms_locksSlidingAndBlocksLongPress() {
        val m = upMachine()
        m.onDown(100f, 100f, 0L)
        // 100ms 内向上位移 12dp（dy=-12），超过 8dp 锁死滑动
        val d = m.onMove(100f, 88f, 100L)
        assertTrue(d.consume)
        assertNull(d.event)
        // 180ms 时 tick 不应进长按（已锁滑动）
        assertEquals(GestureDecision.NONE, m.onTick(180L))
    }

    @Test
    fun moveUnder8dpInside180ms_staysPressedAndTapsOnUp() {
        val m = upMachine()
        m.onDown(100f, 100f, 0L)
        m.onMove(100f, 95f, 100L) // 5dp 抖动
        assertEquals(GestureEvent.Tap, m.onUp(100f, 95f, 150L).event)
    }

    // ---------- 上滑 / 下滑呼出浮层 ----------

    @Test
    fun swipeUp_triggersMenuAt18dpWithHaptic() {
        val m = upMachine()
        m.onDown(100f, 200f, 0L)
        m.onMove(100f, 190f, 100L) // 10dp：锁滑动未达阈值
        assertNull(m.onTick(180L).event)
        val d = m.onMove(100f, 180f, 120L) // 20dp 上滑
        assertEquals(GestureEvent.SwipeSelect(SwipeDirection.UP), d.event)
        assertTrue(d.haptic)
        assertTrue(d.consume)
    }

    @Test
    fun swipeDown_triggersMenuAt18dp() {
        val m = downMachine()
        m.onDown(100f, 100f, 0L)
        m.onMove(100f, 110f, 100L) // 10dp 锁滑动
        val d = m.onMove(100f, 120f, 120L) // 20dp 下滑
        assertEquals(GestureEvent.SwipeSelect(SwipeDirection.DOWN), d.event)
    }

    @Test
    fun swipeCommit_onReleaseAwayFromOrigin() {
        val m = upMachine()
        m.onDown(100f, 200f, 0L)
        m.onMove(100f, 180f, 120L) // 呼出上滑浮层
        val d = m.onUp(100f, 160f, 200L) // 在远离原点处松手
        assertEquals(GestureEvent.SwipeCommit(SwipeDirection.UP), d.event)
    }

    @Test
    fun swipeCancel_slideBackToOriginBeforeRelease() {
        val m = upMachine()
        m.onDown(100f, 200f, 0L)
        m.onMove(100f, 180f, 120L) // 呼出浮层
        m.onMove(100f, 199f, 160L) // 滑回原点（1dp 内）
        val d = m.onUp(100f, 199f, 200L)
        assertEquals(GestureEvent.SwipeCancel, d.event)
    }

    @Test
    fun swipeCancel_releaseBeforeThreshold() {
        val m = upMachine()
        m.onDown(100f, 200f, 0L)
        m.onMove(100f, 190f, 100L) // 10dp：锁滑动但未达 18dp 阈值
        val d = m.onUp(100f, 190f, 150L)
        assertEquals(GestureEvent.SwipeCancel, d.event)
    }

    // ---------- 模型胶囊：左右横滑切档 ----------

    @Test
    fun model_swipeLeft_selectsLeft() {
        val m = modelMachine()
        m.onDown(200f, 100f, 0L)
        m.onMove(192f, 100f, 100L) // 8dp 锁滑动
        val d = m.onMove(178f, 100f, 120L) // 22dp 左滑
        assertEquals(GestureEvent.SwipeSelect(SwipeDirection.LEFT), d.event)
        assertTrue(d.haptic)
    }

    @Test
    fun model_swipeRight_selectsRight() {
        val m = modelMachine()
        m.onDown(100f, 100f, 0L)
        m.onMove(108f, 100f, 100L)
        val d = m.onMove(120f, 100f, 120L)
        assertEquals(GestureEvent.SwipeSelect(SwipeDirection.RIGHT), d.event)
    }

    @Test
    fun model_verticalSwipe_consumesButNeverShowsMenu() {
        val m = modelMachine()
        m.onDown(100f, 200f, 0L)
        m.onMove(100f, 190f, 100L) // 竖直上滑，但模型胶囊不支持
        val d = m.onMove(100f, 170f, 120L) // 超过阈值也不该呼出浮层
        assertNull(d.event)
        assertTrue(d.consume)
        // 松手 = 取消，零提交
        assertEquals(GestureEvent.SwipeCancel, m.onUp(100f, 170f, 200L).event)
    }

    @Test
    fun model_tapWithinWindowFallsBackToList() {
        val m = modelMachine()
        m.onDown(100f, 100f, 0L)
        assertEquals(GestureEvent.Tap, m.onUp(100f, 101f, 120L).event)
    }

    // ---------- 契约：👍 绝不触发重写 ----------

    @Test
    fun thumbUpContract_neverSupportsHorizontalOrRewrite() {
        val c = GestureContract.THUMB_UP
        assertTrue(c.supports(SwipeDirection.UP))
        assertTrue(c.supports(SwipeDirection.DOWN))
        assertFalse(c.supports(SwipeDirection.LEFT))
        assertFalse(c.supports(SwipeDirection.RIGHT))
        // 状态机所有事件里没有任何「重写」语义：只有 Tap/长按/滑动选择与提交。
        // 重写与否由 UI 层根据控件身份决定，👍 的上滑/下滑事件绝不路由到重写 enqueue。
    }

    @Test
    fun thumbDownContract_supportsUpDownAndLongPress() {
        val c = GestureContract.THUMB_DOWN
        assertTrue(c.longPress)
        assertTrue(c.supports(SwipeDirection.UP))
        assertTrue(c.supports(SwipeDirection.DOWN))
        assertFalse(c.supports(SwipeDirection.LEFT))
    }

    @Test
    fun swipeConsumesEveryFrame() {
        val m = upMachine()
        m.onDown(100f, 200f, 0L)
        // 锁死滑动后的每一帧都消费，外层 verticalScroll 不跟滚
        assertTrue(m.onMove(100f, 190f, 50L).consume)
        assertTrue(m.onMove(100f, 180f, 100L).consume)
        assertTrue(m.onMove(100f, 170f, 150L).consume)
        assertTrue(m.onMove(100f, 160f, 200L).consume)
    }
}

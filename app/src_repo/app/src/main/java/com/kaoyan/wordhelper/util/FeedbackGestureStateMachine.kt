package com.kaoyan.wordhelper.util

/**
 * 卡背反馈区的「一维直滑手势」状态机（纯 Kotlin/JVM，不依赖 Android / Compose）。
 *
 * 1.5.3v2 起，卡背四个控件（模型滑动胶囊 / ✨再重写一次 / 👍有帮助 / 👎没帮助）共用
 * 同一个手势语义，只是方向能力不同：
 *
 * - 模型胶囊：短按展开列表兜底；左右横滑切档（每档轻震动）。
 * - ✨再重写一次：短按直接重写；下滑呼出「写要求重写」输入条。
 * - 👍有帮助：短按 +1；长按连赞；上滑 3 项点赞原因；下滑写具体原因（都绝不触发重写）。
 * - 👎没帮助：短按踩；长按连踩（只记差评不重写）；上滑 3 项差评原因（立即带原因重写）；
 *   下滑写吐槽（立即带吐槽重写）。
 *
 * 物理红线（全部落在这里，UI 只执行 [GestureDecision]）：
 * 1. 180ms 意图锁 —— 按下后 180ms 内位移超 8dp 锁死为「滑动」并消费事件（外层
 *    verticalScroll 不跟滚）；180ms 内原地不动则进「长按连发」（每 110ms +1，上限 50，
 *    伴随震动），松手结算。
 * 2. 逃生舱 —— 滑动呼出浮层后滑回原按钮区域（位移 < [cancelBackDp]）松手 = 取消，
 *    零提交、零重写。
 * 3. 下滑/上滑达到 [triggerMinDp] 即「吸附高亮 + 震动」，进入 MENU 态。
 *
 * 时间推进：Compose 层按下后用一个 ticker 协程持续喂 [onTick]（状态机内部用时间戳判断
 * 180ms 意图窗与 110ms 连发间隔），所以静止长按也能正确触发；JVM 单测同样用带时间戳的
 * onDown/onTick/onMove/onUp 驱动，无需真实时钟。
 */
class FeedbackGestureStateMachine(
    /** 这个控件支持哪些方向与长按。 */
    private val contract: GestureContract,
    /** 意图锁时长（毫秒）。 */
    private val intentLockMs: Long = 180L,
    /** 意图锁窗口内锁死滑动的最小位移（dp）。 */
    private val moveLockDp: Float = 8f,
    /** 滑动呼出浮层的最小主位移（dp）。 */
    private val triggerMinDp: Float = 18f,
    /** 滑回原按钮区域判定取消的最大距离（dp）。 */
    private val cancelBackDp: Float = 10f,
) {

    private enum class Phase {
        /** 未按下。 */
        IDLE,

        /** 已按下、意图未定（180ms 窗口内）。 */
        PRESSED,

        /** 180ms 内原地不动 → 长按连发。 */
        LONG_PRESS,

        /** 已锁死滑动，但主位移还没到呼出浮层的阈值。 */
        SLIDING,

        /** 浮层已呼出（上滑/下滑），等待松手或滑回取消。 */
        MENU,
    }

    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastRepeatAt = 0L
    private var repeatCount = 0
    private var activeDirection: SwipeDirection? = null

    /** 回到未按下状态；换控件 / 手势结束后调用。 */
    fun reset() {
        phase = Phase.IDLE
        activeDirection = null
        repeatCount = 0
    }

    fun onDown(x: Float, y: Float, timeMs: Long): GestureDecision {
        phase = Phase.PRESSED
        downX = x
        downY = y
        downTime = timeMs
        lastRepeatAt = timeMs
        repeatCount = 0
        activeDirection = null
        // 按下即消费：这些控件都在卡背滚动区内，不能让 down 穿透给外层滚动。
        return GestureDecision.CONSUME
    }

    fun onMove(x: Float, y: Float, timeMs: Long): GestureDecision {
        if (phase == Phase.IDLE) return GestureDecision.NONE
        val dx = x - downX
        val dy = y - downY
        return when (phase) {
            Phase.PRESSED -> {
                if (distance(dx, dy) > moveLockDp) {
                    // 180ms 内位移超 8dp → 锁死滑动。即使刚过 180ms 也算：意图窗内没被
                    // ticker 抢进长按，说明用户就是动了，宁可判滑动也不要误判长按。
                    enterSliding(dx, dy)
                } else {
                    GestureDecision.CONSUME
                }
            }
            Phase.LONG_PRESS -> GestureDecision.CONSUME
            Phase.SLIDING, Phase.MENU -> handleSlideMove(dx, dy)
            Phase.IDLE -> GestureDecision.NONE
        }
    }

    /** 由 UI 的 ticker 协程持续调用；负责 180ms 长按抢占与 110ms 连发。 */
    fun onTick(timeMs: Long): GestureDecision {
        return when (phase) {
            Phase.PRESSED -> {
                if (contract.longPress && timeMs - downTime >= intentLockMs) {
                    phase = Phase.LONG_PRESS
                    repeatCount = 1
                    lastRepeatAt = timeMs
                    GestureDecision(consume = true, haptic = true, event = GestureEvent.LongPressTick(1))
                } else {
                    GestureDecision.NONE
                }
            }
            Phase.LONG_PRESS -> {
                if (repeatCount >= contract.longPressMax) return GestureDecision.CONSUME
                if (timeMs - lastRepeatAt >= contract.longPressRepeatMs) {
                    repeatCount += 1
                    lastRepeatAt = timeMs
                    GestureDecision(consume = true, haptic = true, event = GestureEvent.LongPressTick(repeatCount))
                } else {
                    GestureDecision.CONSUME
                }
            }
            else -> GestureDecision.NONE
        }
    }

    fun onUp(x: Float, y: Float, timeMs: Long): GestureDecision {
        val decision = when (phase) {
            Phase.PRESSED -> GestureDecision(event = GestureEvent.Tap)
            Phase.LONG_PRESS -> GestureDecision(event = GestureEvent.LongPressCommit(repeatCount))
            // 锁死滑动但没呼出浮层就松手：视为没滑成，取消。
            Phase.SLIDING -> GestureDecision(event = GestureEvent.SwipeCancel)
            Phase.MENU -> {
                val direction = activeDirection ?: SwipeDirection.UP
                if (distance(x - downX, y - downY) < cancelBackDp) {
                    GestureDecision(event = GestureEvent.SwipeCancel)
                } else {
                    GestureDecision(event = GestureEvent.SwipeCommit(direction))
                }
            }
            Phase.IDLE -> GestureDecision.NONE
        }
        reset()
        return decision
    }

    private fun enterSliding(dx: Float, dy: Float): GestureDecision {
        phase = Phase.SLIDING
        activeDirection = dominantDirection(dx, dy)
        // 第一次越过 8dp 时位移可能已超过 18dp 触发阈值：一步到位直接呼出浮层。
        return handleSlideMove(dx, dy)
    }

    private fun handleSlideMove(dx: Float, dy: Float): GestureDecision {
        // 锁死滑动时主方向还没定（阈值内就锁了）：到能定方向的位移再定。
        if (activeDirection == null) {
            activeDirection = dominantDirection(dx, dy)
        }
        val direction = activeDirection ?: return GestureDecision.CONSUME
        // 这个方向不是本控件的能力（例如模型胶囊上竖直滑动）：锁死但不呼出浮层，松手取消。
        if (!contract.supports(direction)) return GestureDecision.CONSUME

        val dist = mainAxisDistance(direction, dx, dy)
        if (phase == Phase.SLIDING) {
            if (dist >= triggerMinDp) {
                phase = Phase.MENU
                return GestureDecision(consume = true, haptic = true, event = GestureEvent.SwipeSelect(direction))
            }
            return GestureDecision.CONSUME
        }
        // MENU 态：滑回原按钮区域就进入「可取消」状态，松手时再判定（见 onUp）。
        return GestureDecision.CONSUME
    }

    private fun dominantDirection(dx: Float, dy: Float): SwipeDirection? {
        if (dx == 0f && dy == 0f) return null
        return if (Math.abs(dx) > Math.abs(dy)) {
            if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
        } else {
            if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
        }
    }

    private fun mainAxisDistance(direction: SwipeDirection, dx: Float, dy: Float): Float =
        when (direction) {
            SwipeDirection.UP -> -dy
            SwipeDirection.DOWN -> dy
            SwipeDirection.LEFT -> -dx
            SwipeDirection.RIGHT -> dx
        }

    private fun distance(dx: Float, dy: Float): Float = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
}

/** 一个控件的方向能力。 */
data class GestureContract(
    /** 左右横滑切档（模型胶囊）。 */
    val horizontal: Boolean = false,

    /** 上滑呼出纵向浮层（👍/👎）。 */
    val swipeUp: Boolean = false,

    /** 下滑呼出输入条（✨/👍/👎）。 */
    val swipeDown: Boolean = false,

    /** 长按连发（👍/👎）。 */
    val longPress: Boolean = false,

    /** 连发间隔（毫秒）。 */
    val longPressRepeatMs: Long = 110L,

    /** 连发上限。 */
    val longPressMax: Int = 50,
) {
    fun supports(direction: SwipeDirection): Boolean = when (direction) {
        SwipeDirection.UP -> swipeUp
        SwipeDirection.DOWN -> swipeDown
        SwipeDirection.LEFT, SwipeDirection.RIGHT -> horizontal
    }

    companion object {
        /** 模型滑动胶囊：短按展开列表，左右横滑切档。 */
        val MODEL_SWITCH = GestureContract(horizontal = true)

        /** ✨再重写一次：短按直接重写，下滑写要求重写。 */
        val REWRITE_AGAIN = GestureContract(swipeDown = true)

        /** 👍有帮助：短按 +1 / 长按连赞 / 上滑 3 项 / 下滑写原因。 */
        val THUMB_UP = GestureContract(swipeUp = true, swipeDown = true, longPress = true)

        /** 👎没帮助：短按踩 / 长按连踩 / 上滑 3 项 / 下滑写吐槽。 */
        val THUMB_DOWN = GestureContract(swipeUp = true, swipeDown = true, longPress = true)
    }
}

/** 状态机对外的动作（UI 层执行，状态机只做决策，不碰 View）。 */
sealed interface GestureEvent {
    /** 短按：未锁滑动、未进长按就松手。 */
    data object Tap : GestureEvent

    /** 长按连发第 [count] 次（count=1 为进入长按的那一下）。 */
    data class LongPressTick(val count: Int) : GestureEvent

    /** 长按松手结算（连按了 [count] 次）。 */
    data class LongPressCommit(val count: Int) : GestureEvent

    /** 滑动到达触发阈值：呼出浮层（UP/DOWN）或切一档（LEFT/RIGHT）。 */
    data class SwipeSelect(val direction: SwipeDirection) : GestureEvent

    /** 浮层呼出后、在非取消位置松手：提交当前高亮项。 */
    data class SwipeCommit(val direction: SwipeDirection) : GestureEvent

    /** 滑回原按钮区域松手，或锁死滑动后没呼出浮层就松手：取消，零提交。 */
    data object SwipeCancel : GestureEvent
}

/** 一帧的决策结果：是否消费事件、是否震动、附带什么动作。 */
data class GestureDecision(
    val consume: Boolean = false,
    val haptic: Boolean = false,
    val event: GestureEvent? = null,
) {
    companion object {
        val NONE = GestureDecision()
        val CONSUME = GestureDecision(consume = true)
    }
}

/** 滑动方向（主位移轴 + 符号；屏幕坐标 y 向下增大，所以 dy<0 为 UP）。 */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

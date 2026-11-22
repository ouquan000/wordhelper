package com.kaoyan.wordhelper.data.repository

/**
 * 1.5.2「点赞++」的合并策略（纯 Kotlin，可单测）。
 *
 * 连续点击只在本地累加，停手 1.8 秒或离开卡片时合并成一条反馈；
 * 累计值与单批上报值都封顶 50，和后端 `count` 的 1..50 校验对齐。
 */
object FeedbackBurstPolicy {
    const val DEBOUNCE_MS = 1_800L
    const val MAX_COUNT = 50

    fun nextCount(current: Int, taps: Int = 1): Int =
        (current.coerceAtLeast(0) + taps.coerceAtLeast(1)).coerceAtMost(MAX_COUNT)

    fun shouldFlush(idleMs: Long): Boolean = idleMs >= DEBOUNCE_MS
}

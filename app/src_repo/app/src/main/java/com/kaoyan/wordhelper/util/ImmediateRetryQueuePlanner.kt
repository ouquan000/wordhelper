package com.kaoyan.wordhelper.util

import kotlin.random.Random

data class RetryInsertPolicy(
    val tailInsertMaxSize: Int,
    val randomInsertStartIndex: Int,
    val excludeTailInRandom: Boolean
)

fun interface RetryRandomSource {
    fun nextInt(from: Int, until: Int): Int

    companion object {
        val Default: RetryRandomSource = RetryRandomSource { from, until ->
            Random.nextInt(from, until)
        }
    }
}

object ImmediateRetryQueuePlanner {
    val LegacyPolicy = RetryInsertPolicy(
        tailInsertMaxSize = 5,
        randomInsertStartIndex = 2,
        excludeTailInRandom = true
    )

    val V4Policy = RetryInsertPolicy(
        tailInsertMaxSize = 3,
        randomInsertStartIndex = 1,
        excludeTailInRandom = true
    )

    /**
     * 1.4.6 H：跳过重写后词生成好了，插到当前词后 1–3 位。
     * queueSize 是「当前词还在队首」的队列长度；队列短就直接贴到队尾。
     */
    fun resolveWaitingInsertIndex(
        queueSize: Int,
        randomSource: RetryRandomSource = RetryRandomSource.Default
    ): Int {
        val safeQueueSize = queueSize.coerceAtLeast(0)
        if (safeQueueSize == 0) return 0
        return (1 + randomSource.nextInt(0, 3)).coerceAtMost(safeQueueSize)
    }

    fun resolveInsertIndex(
        queueSize: Int,
        policy: RetryInsertPolicy,
        randomSource: RetryRandomSource = RetryRandomSource.Default
    ): Int {
        val safeQueueSize = queueSize.coerceAtLeast(0)
        if (safeQueueSize == 0) return 0
        if (safeQueueSize <= policy.tailInsertMaxSize) return safeQueueSize

        val from = policy.randomInsertStartIndex.coerceIn(0, safeQueueSize - 1)
        val until = if (policy.excludeTailInRandom) safeQueueSize else safeQueueSize + 1
        if (from >= until) return safeQueueSize
        return randomSource.nextInt(from, until)
    }
}

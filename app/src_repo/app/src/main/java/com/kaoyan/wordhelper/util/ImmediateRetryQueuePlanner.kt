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
    /** 1.4.7.2 W2：合格稿插在当前卡之后的第 2 或第 3 张普通卡之后。 */
    private const val WAITING_INSERT_MIN_AFTER_CURRENT = 2

    /** wordId 先乘一个大奇数再叠时间戳，避免相邻 id 的种子只差一点。 */
    private const val WAITING_SEED_WORD_ID_PRIME = 1_000_003L

    /** 0x9E3779B97F4A7C15（splitmix 黄金比例常数）的有符号写法。 */
    private const val MIX_GAMMA = -7046029254386353131L

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
     * 1.4.7.2 W2：合格重写稿插回队列的位置。
     *
     * 用户口径（覆盖 1.4.6 的「当前词后 1–3 位」）：插到**当前卡之后的第 2 或第 3 张普通卡
     * 之后**，当前卡保持不动；剩余普通卡不足时先走完剩余的再接。
     *
     * `queueSize` 是「当前卡还在队首」的队列长度。队列下标 0 = 正在展示、还没作答的当前卡，
     * 所以「第 N 张普通卡之后」= 下标 N + 1（add(i) 是把元素放到原下标 i 之前）。
     * 同一个词（wordId + 发起时间）永远算出同一个位置，重开不会重新随机。
     */
    fun resolveWaitingInsertIndex(queueSize: Int, seed: Long): Int {
        val safeQueueSize = queueSize.coerceAtLeast(0)
        if (safeQueueSize == 0) return 0
        return (waitingInsertSlot(seed) + 1).coerceAtMost(safeQueueSize)
    }

    /** 便捷重载：直接用词和它这次重写的发起时间当稳定种子。 */
    fun resolveWaitingInsertIndex(queueSize: Int, wordId: Long, createdAt: Long): Int =
        resolveWaitingInsertIndex(queueSize, waitingRewriteSeed(wordId, createdAt))

    /**
     * 稳定槽位：返回 2 或 3，表示「当前卡之后的第几张普通卡之后」。
     *
     * 用 deterministic hash 而不是 Random：同一张稿在多稿并发、重开、杀进程恢复时
     * 都必须落在同一档，否则用户会觉得队列每次都在重排。
     */
    fun waitingInsertSlot(seed: Long): Int {
        val bucket = (mix64(seed) ushr 63).toInt() and 1
        return WAITING_INSERT_MIN_AFTER_CURRENT + bucket
    }

    /** 插回位置的种子：wordId 打散后叠上这次重写的发起时间（毫秒）。 */
    fun waitingRewriteSeed(wordId: Long, createdAt: Long): Long =
        wordId * WAITING_SEED_WORD_ID_PRIME + createdAt

    /** splitmix 风格的 64 位打散，保证相邻 wordId 不会都落到同一档。 */
    private fun mix64(value: Long): Long {
        var h = value * MIX_GAMMA
        h = h xor (h ushr 32)
        h *= MIX_GAMMA
        h = h xor (h ushr 29)
        return h
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

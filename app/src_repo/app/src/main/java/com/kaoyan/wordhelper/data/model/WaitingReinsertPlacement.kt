package com.kaoyan.wordhelper.data.model

/**
 * 1.4.7.2 W2：合格重写稿在会话队列里的稳定插回位置。
 *
 * 纯计划器（只排 id 顺序），方便 JVM 单测；查词、注册订阅仍在 LearningViewModel。
 *
 * 规则（对应验收口径）：
 * - 下标 0 是正在展示、还没作答的当前卡，保持不动；
 * - 其余都算「普通卡」，每张稿落在当前卡之后的第 `slot` 张普通卡之后（slot 只有 2 或 3）；
 * - 剩余普通卡不够时接在队尾，先把剩下的普通卡走完；
 * - 队列里已经有同一张卡时保留原位，不再重排——它已经插入过，应随前面的卡被答掉自然前移；
 * - 多张稿落到同一档时依次往后挪一格，尽量分散、不重叠；
 * - 输入相同就输出相同：重开/杀进程恢复后不会重新随机。
 */
object WaitingReinsertPlacement {

    /**
     * 一张待插回的卡。
     *
     * @param slot 当前卡之后的第几张普通卡之后（2 或 3）
     * @param order 同档内的稳定先后（小的先插），一般用登记顺序
     */
    data class Pending(
        val wordId: Long,
        val slot: Int,
        val order: Long = 0L
    )

    fun apply(queue: List<Long>, pending: List<Pending>): List<Long> {
        val currentId = queue.firstOrNull()
        val queued = queue.toHashSet()
        // 已经在队列里的稿不再重排：它上次插入后应随着前面的卡被答掉而自然前移，
        // 每次重建都把它摘掉再按“当前卡后第 2/3 张”重插，会永远停在原位。
        val active = pending.asSequence()
            .filter { it.wordId > 0L && it.wordId != currentId && it.wordId !in queued }
            .distinctBy { it.wordId }
            .sortedWith(compareBy({ it.slot }, { it.order }, { it.wordId }))
            .toList()
        if (active.isEmpty()) return queue

        // 普通卡 = 当前卡之外的部分；已插入的稿保留在原位，不摘掉。
        val ordinary = queue.drop(1)
        val maxKeepBefore = ordinary.size

        val buckets = HashMap<Int, MutableList<Long>>()
        val tail = ArrayList<Long>()
        active.forEach { item ->
            var keepBefore = item.slot.coerceAtLeast(1)
            // 同一档被占了就往后挪，多张稿不会挤在同一格。
            while (keepBefore <= maxKeepBefore && buckets.containsKey(keepBefore)) {
                keepBefore += 1
            }
            if (keepBefore > maxKeepBefore) {
                tail.add(item.wordId)
            } else {
                buckets.getOrPut(keepBefore) { ArrayList() }.add(item.wordId)
            }
        }

        val result = ArrayList<Long>(queue.size + active.size)
        if (currentId != null) result.add(currentId)
        var kept = 0
        ordinary.forEach { cardId ->
            result.add(cardId)
            kept += 1
            buckets.remove(kept)?.let { result.addAll(it) }
        }
        result.addAll(tail)
        return result
    }
}

package com.kaoyan.wordhelper.data.model

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus

/**
 * 1.4.6.1 P0：进程重启后的「等待重写」恢复规则。
 *
 * 跳过重写只把词放进内存集合，进程一死就没了；DB20 起 `tb_rewrite_queue.needs_reinsert`
 * 把「跳过、等结果回来再插回」的意图持久化。冷启动时自动同步可能先把行写成 done，
 * 所以恢复必须看 needs_reinsert，而不是只看 pending/queued。
 *
 * 这里只放纯决策，方便单元测试；真正的订阅、查词、插队仍在 LearningViewModel。
 */
object WaitingRewriteRestore {

    /** 一次恢复要做的事：哪些词补监听、哪些词已经 done 直接补插。 */
    data class RestorePlan(
        val watchWordIds: List<Long>,
        val reinsertWordIds: List<Long>
    )

    /**
     * 把 `needs_reinsert=1` 的队列行分成两类：
     *
     * - `pending/queued`：结果还没回来，补注册 observer 等 DONE；
     * - `done`：结果已经落库（常见于冷启动自动同步先跑），直接走补插；
     * - `failed`：只提示，不插回，等下一次手动重写/重试。
     *
     * 已经在本进程等待集合/已有监听里的词不重复处理；同一批 rows 只出一次。
     */
    fun plan(
        rows: List<RewriteQueueItem>,
        alreadyTracked: Set<Long>
    ): RestorePlan {
        val valid = rows.asSequence()
            .filter { it.needsReinsert && it.wordId > 0L && it.wordId !in alreadyTracked }
        val watching = ArrayList<Long>()
        val reinsert = ArrayList<Long>()
        valid.forEach { row ->
            when (row.status) {
                RewriteStatus.PENDING, RewriteStatus.QUEUED -> watching.add(row.wordId)
                RewriteStatus.DONE -> reinsert.add(row.wordId)
                else -> Unit
            }
        }
        return RestorePlan(
            watchWordIds = watching.distinct(),
            reinsertWordIds = reinsert.distinct()
        )
    }

    /**
     * 结果 DONE 时要不要把词插回队列。
     *
     * 已经在队列/正在展示的词不能重复插入；今天已经答过的词也不能再插回来
     * （用户标记重写后照常答完，结果晚到不该让他重背一遍）。只有真正「跳过后
     * 一直没背」的词，才符合随机插回 1–3 位的原有语义。
     */
    fun shouldInsertOnReady(
        wordId: Long,
        currentWordId: Long?,
        queuedWordIds: Set<Long>,
        answeredToday: Boolean
    ): Boolean = wordId > 0L &&
        wordId != currentWordId &&
        wordId !in queuedWordIds &&
        !answeredToday
}

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

    /** 一次恢复要做的事：哪些词继续监听、哪些词已经合格可以插回。 */
    data class RestorePlan(
        val watchWordIds: List<Long>,
        val reinsertWordIds: List<Long>
    )

    /**
     * 把 `needs_reinsert=1` 的队列行分成两类：
     *
     * - `pending/queued`：结果还没回来，补注册 observer 等 DONE；
     * - `done`：结果已经落库（常见于冷启动自动同步先跑），直接走补插；
     * - `failed`：未生成合格稿，仍然等待同步冷却后补投。
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
                RewriteStatus.FAILED -> watching.add(row.wordId)
                // 未知/空 status 仍然带 needs_reinsert：不能放回普通队列，
                // 否则旧稿会被当成正常卡作答，等于绕过“合格才插回”。按等待处理，fail-closed。
                else -> watching.add(row.wordId)
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
     * （用户标记重写后照常答完，结果晚到不该让他重背一遍）。
     *
     * 1.4.7.2 W2 起插回改走 [WaitingReinsertPlacement] 的稳定槽位，ViewModel 里只保留
     * 「今天已答过就结清」这一条兜底判断；本函数保留给恢复计划器的判定语义与单测。
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

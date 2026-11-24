package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.model.ApproachReview
import com.kaoyan.wordhelper.data.model.ApproachRevision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class RewriteMemoryReader(
    private val bookMemoryDao: BookMemoryDao,
    private val wordMemoryDao: WordMemoryDao
) {
    /** 当前词在库里最新的思路，用于同步完原地刷新卡背。bookId=0 时退回全局那份。 */
    suspend fun approachOf(wordId: Long, bookId: Long = 0L): String? {
        if (bookId > 0L) {
            val own = bookMemoryDao.getForBook(wordId, bookId)?.approach.orEmpty()
            if (own.isNotBlank()) return own
        }
        return wordMemoryDao.getByWordId(wordId)?.approach
    }

    /**
     * 卡背直接订阅库里的记忆思路：电脑端结果一旦落库就换文，
     * 不必依赖「同步那一刻正好刷新了词对象」这样的时序。
     * 这本书有自己的版本就用它，没有就用全局（老数据不用回填）。
     */
    fun observeApproach(wordId: Long, bookId: Long = 0L): Flow<String?> {
        val global = wordMemoryDao.observeApproach(wordId)
        if (bookId <= 0L) return global
        return bookMemoryDao.observeApproachForBook(wordId, bookId)
            .combine(global) { own, fallback -> own?.takeIf { it.isNotBlank() } ?: fallback }
    }

    /**
     * 1.4.6.1：当前展示的那一版思路的版本号，和 [observeApproach] 用同一套回退规则。
     * 反馈按它落库 / 回显：换过思路后旧评价不会再亮在新版上。
     */
    fun observeApproachRev(wordId: Long, bookId: Long = 0L): Flow<Int> {
        val globalApproach = wordMemoryDao.observeApproach(wordId)
        val globalRev = wordMemoryDao.observeRev(wordId)
        if (bookId <= 0L) {
            return combine(globalApproach, globalRev) { approach, rev ->
                ApproachRevision.resolve(null, 0, approach, rev ?: 0)
            }
        }
        return combine(
            bookMemoryDao.observeApproachForBook(wordId, bookId),
            bookMemoryDao.observeRevForBook(wordId, bookId),
            globalApproach,
            globalRev
        ) { own, ownRev, fallback, fallbackRev ->
            ApproachRevision.resolve(own, ownRev ?: 0, fallback, fallbackRev ?: 0)
        }
    }

    /** 一次性读当前版本号（提交反馈时用，避免 StateFlow 的值比点击晚一帧）。 */
    suspend fun approachRevOf(wordId: Long, bookId: Long = 0L): Int {
        if (bookId > 0L) {
            val own = bookMemoryDao.getForBook(wordId, bookId)
            if (own != null && own.approach.isNotBlank()) return own.rev.coerceAtLeast(0)
        }
        val global = wordMemoryDao.getByWordId(wordId)
        return if (global != null && global.approach.isNotBlank()) {
            global.rev.coerceAtLeast(0)
        } else {
            0
        }
    }

    /** 这本书当前生效的方法标签做成的流；卡背胶囊读它，重写落地立刻换标签。 */
    fun observeMethod(wordId: Long, bookId: Long = 0L): Flow<String?> {
        val global = wordMemoryDao.observeMethod(wordId)
        if (bookId <= 0L) return global
        return bookMemoryDao.observeMethodForBook(wordId, bookId)
            .combine(global) { own, fallback -> own?.takeIf { it.isNotBlank() } ?: fallback }
    }

    /** 1.5.3v2：上一版思路（手改前保留）。bookId=0 的老路径没有按书副本，恒为 null。 */
    fun observePrevApproach(wordId: Long, bookId: Long = 0L): Flow<String?> {
        if (bookId <= 0L) return flowOf(null)
        return bookMemoryDao.observePrevApproachForBook(wordId, bookId)
    }

    /**
     * 1.4.7.1：卡背「未复核」角标读的流。
     *
     * 回退规则和 [observeApproach] 完全一致：这本书有非空正文就用它，否则用全局那份。
     * 角标必须跟着正文走，否则会错位成「正文是全局的、标记却是这本书上一版的」。
     * 没有正文 / 老数据 / 老服务端不带字段 → unverified=false，界面就不显示角标。
     */
    fun observeApproachReview(wordId: Long, bookId: Long = 0L): Flow<ApproachReview> {
        val global = wordMemoryDao.observeApproachReview(wordId)
        if (bookId <= 0L) {
            return global.map { ApproachReview.resolve(null, it) }
        }
        return combine(
            bookMemoryDao.observeApproachReviewForBook(wordId, bookId),
            global
        ) { own, fallback ->
            ApproachReview.resolve(own, fallback)
        }
    }

    /** 这本书当前生效的方法标签（用于重写完后原地换胶囊）。 */
    suspend fun methodOf(wordId: Long, bookId: Long = 0L): String {
        if (bookId > 0L) {
            bookMemoryDao.methodForBook(wordId, bookId)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return wordMemoryDao.getByWordId(wordId)?.method.orEmpty()
    }
}

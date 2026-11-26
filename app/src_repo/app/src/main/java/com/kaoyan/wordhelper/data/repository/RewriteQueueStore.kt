package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueBreakdown
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.network.RetryPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RewriteQueueStore(
    private val queueDao: RewriteQueueDao,
    private val wordMemoryDao: WordMemoryDao,
    private val bookMemoryDao: BookMemoryDao,
    private val syncMutex: Mutex,
    private val syncEngine: RewriteSyncEngine,
    private val transport: RewriteTransport,
    private val gson: Gson
) {
    private val queueWriteMutex = Mutex()

    fun observeMark(wordId: Long, bookId: Long): Flow<RewriteQueueItem?> =
        queueDao.observeForBook(wordId, bookId)

    fun observeOpenCount(): Flow<Int> = queueDao.observeOpenCount()

    /** 1.5.2：待上传 / 电脑处理中分开显示，别再给一个糊在一起的数字。 */
    fun observeQueueBreakdown(): Flow<RewriteQueueBreakdown> = queueDao.observeQueueBreakdown()

    /** 1.4.6.1：当前这本书还有几条重写在排队/等结果（完成判定以它为准）。 */
    fun observeOpenCountForBook(bookId: Long): Flow<Int> = queueDao.observeOpenCountForBook(bookId)

    /**
     * 1.4.6.1 P0（DB20）：当前书里「点过跳过、但还没插回」的队列行。
     * 不看 status：done 行也要能补插，冷启动自动同步先跑也不会漏。
     */
    suspend fun needsReinsertForBook(bookId: Long): List<RewriteQueueItem> =
        queueDao.getNeedsReinsertForBook(bookId)

    /**
     * 1.4.7.2 W2：当前书里「跳过之后还没被实际作答」的重写卡（flow 版）。
     * 当天完成判定、等待卡条数都读它，过滤学习日由 ViewModel 做。
     */
    fun observeNeedsReinsertForBook(bookId: Long): Flow<List<RewriteQueueItem>> =
        queueDao.observeNeedsReinsertForBook(bookId)

    /** 单条标记（取 createdAt 用：插回位置的稳定种子）。 */
    suspend fun markOf(wordId: Long, bookId: Long): RewriteQueueItem? =
        queueDao.getVisibleForBook(wordId, bookId)

    /**
     * 用户点了「跳过」：先落库，进程死掉也能在下次加载时补插回队列。
     *
     * 1.5.2v2：必须是幂等的。旧实现把 UPDATE 行数 == 1 当成成功，
     * 但 needs_reinsert 已经是 1 时 SQLite 可能返回 0；用户会看到
     * 「重写记录已变化」而卡片不动。现在只要行还在就算成功，
     * 更新本身只是把标记再次置 1。
     */
    suspend fun markNeedsReinsert(wordId: Long, bookId: Long): Boolean =
        queueWriteMutex.withLock {
            if (queueDao.getForBook(wordId, bookId) == null) {
                false
            } else {
                queueDao.markNeedsReinsert(wordId, bookId)
                true
            }
        }

    /**
     * 用户真的作答了这张重写卡（三键 / 太简单 / 拼写成功），才结清欠卡。
     * 生成完成、插回队列、退出学习页都不调用它。
     *
     * 必须带上作答发生的那本书：同一个 word_id 可以同时属于多本书，
     * 只按 word_id 清会误清另一本书的欠卡（见 RewriteQueueDao.clearNeedsReinsert）。
     */
    suspend fun clearNeedsReinsert(wordId: Long, bookId: Long) {
        queueWriteMutex.withLock { queueDao.clearNeedsReinsert(wordId, bookId) }
    }

    /**
     * 当前本词本书这条队列行的 requestId；用于区分「本轮新请求」和上一轮遗留的 DONE。
     */
    suspend fun requestIdOf(wordId: Long, bookId: Long): String? =
        queueDao.getForBook(wordId, bookId)?.requestId

    /**
     * 打标记并立刻尝试上传。返回 true 表示已发到电脑（status=queued）；
     * false 表示先存在本地，等下次同步补发。
     */
    suspend fun enqueueAndSync(
        wordId: Long,
        word: String,
        model: String,
        instruction: String,
        bookId: Long = 0L,
        priority: Int = 0,
        requestId: String = newRewriteRequestId(wordId, bookId),
        onPersisted: () -> Unit = {}
    ): Boolean {
        // 1.5.1：先落本地队列，再试发网络。
        //
        // 旧实现把 queueDao.upsert 包在 syncMutex 里，而 sync() 会整段占着 syncMutex
        // 做 probeHealth / upload / 结果分页。真机网络慢或后台同步正在跑时，用户点
        // 「重写思路」会先卡在锁上：本地一行都没写，卡背 Flow 不发射，按钮维持原样，
        // 看起来就是「点击毫无反应」。现在本地写入只受 queueWriteMutex 保护，落库后
        // UI 立刻能看到「已标记」，网络发送慢也不再挡住反馈。
        val saved = runCatching {
            queueWriteMutex.withLock {
                queueDao.upsert(
                    buildRewriteRequest(
                        existing = queueDao.getForBook(wordId, bookId),
                        wordId = wordId,
                        bookId = bookId,
                        word = word,
                        instruction = instruction,
                        model = model,
                        now = System.currentTimeMillis(),
                        newRequestId = requestId,
                        priority = priority
                    )
                )
            }
        }.isSuccess
        if (!saved) return false
        onPersisted()
        syncEngine.sync()
        val status = queueDao.getForBook(wordId, bookId)?.status
        return status == RewriteStatus.QUEUED || status == RewriteStatus.DONE
    }

    /** 取消当前请求。已跳过且仍欠一次作答的任务继续按原流程回收。 */
    suspend fun cancel(wordId: Long, bookId: Long = 0L): Boolean = syncMutex.withLock {
        queueWriteMutex.withLock write@{
            val existing = queueDao.getVisibleForBook(wordId, bookId) ?: return@write false
            if (existing.needsReinsert) return@write false
            when (existing.status) {
                RewriteStatus.PENDING -> Unit
                RewriteStatus.QUEUED -> {
                    val clientId = rewriteClientId(existing)
                    val stopped = transport.send(
                        path = "/rewrite/stop",
                        payload = gson.toJson(mapOf("clientId" to clientId)),
                        attempts = 1,
                        report = false
                    )
                    if (stopped.kind != RetryPolicy.Kind.OK) return@write false
                    val receipt = runCatching { gson.fromJson(stopped.body, StopReceipt::class.java) }.getOrNull()
                    if (receipt?.clientId != clientId || receipt.stopped <= 0) return@write false
                }
                else -> return@write false
            }
            queueDao.delete(wordId, existing.bookId)
            true
        }
    }

    /**
     * 1.4.7.2：让电脑停止重写。word 为空 = 停全部。
     * 服务端会 generation+1，晚到的结果不会再被采纳；本地等待态由 ViewModel 收尾。
     */
    suspend fun stopRewrite(word: String? = null): Boolean = syncMutex.withLock {
        stopRewriteLocked(word)
    }

    private data class StopReceipt(val clientId: String?, val stopped: Int)

    /** 锁内版本：stopAllRewrites 要「停远端 + 结清本地」整段原子，不能再抢一次锁。 */
    private suspend fun stopRewriteLocked(word: String?): Boolean {
        val payload = if (word.isNullOrBlank()) "{}" else gson.toJson(mapOf("word" to word))
        val outcome = transport.send(
            path = "/rewrite/stop",
            payload = payload,
            attempts = 1,
            report = false
        )
        return outcome.kind == RetryPolicy.Kind.OK
    }

    /**
     * 停止全部重写并结清本地队列。电脑没收到停止指令时不动本地：
     * 否则界面上停了、电脑还在跑，下一轮同步又把结果插回来。
     *
     * 1.4.7.2：整段放在 syncMutex 里。否则 stop 返回后、本地结清前，
     * 另一次 sync 会抢到锁把仍是 pending 的标记再传上去，服务端 stop 之后
     * 又建一条任务，结果回来时把卡背写回新思路（W2 复测 P2-1）。
     */
    suspend fun stopAllRewrites(): Boolean = syncMutex.withLock {
        if (!stopRewriteLocked(null)) return@withLock false
        val now = System.currentTimeMillis()
        queueDao.getOpen().forEach { item ->
            queueDao.updateStatus(item.wordId, item.bookId, RewriteStatus.FAILED, "用户停止", now)
        }
        // 已经生成完、欠一次插回的行也不能在停止后偷偷插回来。
        queueDao.clearAllNeedsReinsert()
        true
    }

    /** 撤销一次重写；返回恢复后的旧思路，没有可撤销版本时返回 null。 */
    suspend fun undo(wordId: Long, bookId: Long = 0L): String? = syncMutex.withLock {
        queueWriteMutex.withLock { undoLocked(wordId, bookId) }
    }

    private suspend fun undoLocked(wordId: Long, bookId: Long): String? {
        if (queueDao.getVisibleForBook(wordId, bookId)?.needsReinsert == true) return null
        if (bookId > 0L) {
            val own = bookMemoryDao.getForBook(wordId, bookId) ?: return null
            val prev = own.prevApproach?.takeIf { it.isNotBlank() } ?: return null
            // 1.4.6：思路和方法标签是同一版，撤销要一起退回去。
            // 迁移上来的老行没有 prev_method（NULL），那就保持当前标签，跟旧行为一致。
            val prevMethod = own.prevMethod?.takeIf { it.isNotBlank() }
            val nextRev = (own.rev - 1).coerceAtLeast(0)
            bookMemoryDao.upsert(
                own.copy(
                    approach = prev,
                    method = prevMethod ?: own.method,
                    prevApproach = null,
                    prevMethod = null,
                    rev = nextRev,
                    source = if (nextRev == 0) WordMemory.SOURCE_SEED else WordMemory.SOURCE_USER,
                    // 1.4.7.1：撤销回去的是**更早那一版**，它没复核与否无从得知。
                    // 角标只描述「当前这版」，退回去还挂着新版的「未复核」就是假消息，所以清掉。
                    unverified = false,
                    unverifiedReason = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            queueDao.delete(wordId, bookId)
            return prev
        }
        val memory = wordMemoryDao.getByWordId(wordId) ?: return null
        val prev = memory.prevApproach?.takeIf { it.isNotBlank() } ?: return null
        val prevMethod = memory.prevMethod?.takeIf { it.isNotBlank() }
        val nextRev = (memory.rev - 1).coerceAtLeast(0)
        wordMemoryDao.upsert(
            memory.copy(
                approach = prev,
                method = prevMethod ?: memory.method,
                prevApproach = null,
                prevMethod = null,
                rev = nextRev,
                // 退回到种子原文时把归属交还给种子，后续种子升级仍能覆盖它
                source = if (nextRev == 0) WordMemory.SOURCE_SEED else WordMemory.SOURCE_USER,
                // 1.4.7.1：同按书那份 —— 退回去的那版没复核与否不知道，角标不能跟着留。
                unverified = false,
                unverifiedReason = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        queueDao.delete(wordId, bookId)
        return prev
    }
}

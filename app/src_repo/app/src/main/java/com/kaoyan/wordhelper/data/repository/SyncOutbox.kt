package com.kaoyan.wordhelper.data.repository

import com.google.gson.annotations.SerializedName
import android.util.Log
import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.SyncDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.SyncOp
import com.kaoyan.wordhelper.data.entity.SyncState
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import com.kaoyan.wordhelper.data.repository.SyncContracts.CAP_SYNC_PUSH
import com.kaoyan.wordhelper.data.repository.SyncContracts.NO_ADDRESS
import com.kaoyan.wordhelper.data.repository.SyncContracts.PUSH_BATCH
import com.kaoyan.wordhelper.data.repository.SyncContracts.PUSH_PATH
import com.kaoyan.wordhelper.data.repository.SyncContracts.TAG
import com.kaoyan.wordhelper.data.repository.SyncContracts.TYPE_PROGRESS
import com.kaoyan.wordhelper.data.repository.SyncContracts.UNREACHABLE
import com.kaoyan.wordhelper.data.repository.SyncContracts.opIdOf

internal class SyncOutbox<W>(
    private val syncDao: SyncDao,
    private val wordDao: WordDao,
    private val bookDao: BookDao,
    private val gson: Gson,
    private val stateStore: SyncLocalState,
    private val endpoints: SyncEndpointResolver,
    private val http: SyncHttpTransport,
    private val factory: SyncWireFactory<W>,
    private val upcoming: suspend () -> UpcomingNewWords?
) {
    /**
     * 把 tb_progress 当前这一行（学习结果态）写成一条待同步记录。
     *
     * op_id = "p-<wordId>-<lrt>"：同一个词、同一次复习天然只有一个 id，重复调用（重试、
     * 重复点击）只会被 tb_sync_op 的主键吞掉，不会重复推送。
     *
     * payload 里的 w / b 先留空：入队时只拿得到本地 id，word_key 与书名在 [push] 时才回查
     * （词/书可能在入队之后被改名，早查早过期）。
     */
    suspend fun enqueueProgress(wordId: Long, bookId: Long, snapshot: Progress) {
        if (wordId <= 0L) return
        // lrt 是服务端比新旧的唯一依据。「标记太简单」那条写库路径不写 last_review_time（=0），
        // 用 0 入队会在对面永远输给任何一条旧记录，所以用动作发生时间兜底。
        val lrt = if (snapshot.lastReviewTime > 0L) snapshot.lastReviewTime else System.currentTimeMillis()
        val opId = opIdOf(wordId, bookId, lrt)
        val payload = LocalOp(
            opId = opId,
            type = TYPE_PROGRESS,
            wordKey = "",
            bookName = "",
            status = snapshot.status,
            repetitions = snapshot.repetitions,
            intervalDays = snapshot.intervalDays,
            nextReviewTime = snapshot.nextReviewTime,
            easeFactor = snapshot.easeFactor,
            reviewCount = snapshot.reviewCount,
            lastReviewTime = lrt,
            wordId = wordId,
            bookId = bookId,
            fsrsStability = snapshot.fsrsStability,
            fsrsDifficulty = snapshot.fsrsDifficulty,
            fsrsLapses = snapshot.fsrsLapses,
            fsrsState = snapshot.fsrsState
        )
        syncDao.enqueue(
            SyncOp(
                opId = opId,
                payload = gson.toJson(payload),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 首次同步：把手机上已有的学习进度一次性补进队列，让「以前背的词」也能备份到电脑。
     *
     * 只在「服务端 rev=0 且从没同步过」时执行，所以正常路径上只会发生一次；
     * 补队列失败（push 失败）时下次还会再补一遍，op_id 相同，INSERT IGNORE 不会重复。
     * 5000 词分批写，避免一条超大事务卡住学习页。
     */
    suspend fun bulkEnqueueIfFresh(state: SyncState): Int {
        if (state.serverRev != 0L || state.lastSyncAt != 0L) return 0
        val rows = syncDao.allLearnedProgress()
        if (rows.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val ops = rows.map { row ->
            val lrt = if (row.lastReviewTime > 0L) row.lastReviewTime else now
            val opId = opIdOf(row.wordId, row.bookId, lrt)
            SyncOp(
                opId = opId,
                payload = gson.toJson(
                    LocalOp(
                        opId = opId,
                        type = TYPE_PROGRESS,
                        wordKey = "",
                        bookName = "",
                        status = row.status,
                        repetitions = row.repetitions,
                        intervalDays = row.intervalDays,
                        nextReviewTime = row.nextReviewTime,
                        easeFactor = row.easeFactor,
                        reviewCount = row.reviewCount,
                        lastReviewTime = lrt,
                        wordId = row.wordId,
                        bookId = row.bookId,
                        fsrsStability = row.fsrsStability,
                        fsrsDifficulty = row.fsrsDifficulty,
                        fsrsLapses = row.fsrsLapses,
                        fsrsState = row.fsrsState
                    )
                ),
                createdAt = now
            )
        }
        ops.chunked(500).forEach { syncDao.enqueueAll(it) }
        Log.i(TAG, "首次同步：把 ${ops.size} 条历史进度补进队列")
        return ops.size
    }

    suspend fun pushLocked(): Result<Int> {
        val config = endpoints.config() ?: return Result.failure(SyncException(NO_ADDRESS))
        val rows = syncDao.pending(PUSH_BATCH)
        val preview = upcoming()

        val state = stateStore.ensureState()
        val wordKeyCache = HashMap<Long, String>()
        val bookNameCache = HashMap<Long, String>()
        val wireOps = ArrayList<W>(rows.size)
        val deadOpIds = ArrayList<String>()

        rows.forEach { row ->
            val local = parseLocal(row)
            val lrt = local?.lastReviewTime
            // 一条 op 必须同时有 lrt（比新旧的依据）和本地 wid/bid（回查 w/b 的依据），缺一个都推不出去。
            val localWordId = local?.wordId
            val localBookId = local?.bookId
            if (local == null || lrt == null || localWordId == null || localBookId == null) {
                // 写不进去的 payload 只可能是历史格式残留；留在队里也永远推不出去，删掉并留日志。
                Log.w(TAG, "同步队列里有一条 payload 解析不了，丢弃：${row.opId}")
                deadOpIds += row.opId
                return@forEach
            }
            val wordKey = wordKeyOf(localWordId, wordKeyCache)
            val bookName = bookNameOf(localBookId, bookNameCache)
            if (wordKey.isBlank() || bookName.isBlank()) {
                // 词已被删 / 书已不存在：这条记录没有落点，留在队里只会让「待同步 N 条」永远不清零。
                Log.w(TAG, "同步队列里的记录找不到词或书，丢弃：${row.opId} w=$wordKey b=$bookName")
                deadOpIds += row.opId
                return@forEach
            }
            wireOps += factory.create(
                opId = row.opId,
                type = local.type ?: TYPE_PROGRESS,
                wordKey = wordKey,
                bookName = bookName,
                status = local.status,
                repetitions = local.repetitions,
                intervalDays = local.intervalDays,
                nextReviewTime = local.nextReviewTime,
                easeFactor = local.easeFactor,
                reviewCount = local.reviewCount,
                lastReviewTime = lrt,
                fsrsStability = local.fsrsStability,
                fsrsDifficulty = local.fsrsDifficulty,
                fsrsLapses = local.fsrsLapses,
                fsrsState = local.fsrsState
            )
        }
        if (deadOpIds.isNotEmpty()) syncDao.deleteByOpIds(deadOpIds)
        if (wireOps.isEmpty() && preview == null) return Result.success(0)

        val pushJson = gson.toJson(pushRequest(state.deviceId, state.serverRev, wireOps, preview))
        // /health 说得很清楚「这台电脑没有 sync_push」：请求干脆不发，别拿 404 去撞（1.4.6 的 186 次）。
        if (ServerHealth.supports(endpoints.serverHealth, CAP_SYNC_PUSH) == false) {
            val message = RetryPolicy.missingCapabilityMessage(CAP_SYNC_PUSH)
            http.publish(SyncPhase.SERVER_TOO_OLD, null, message)
            return Result.failure(SyncException(message))
        }
        // 候选地址轮试；分类、退避、上报都在 call() 里做，这里只负责按类型给用户一句话。
        val outcome = http.sendToOrigins(config) { origin ->
            http.requestPost(origin + PUSH_PATH, pushJson, config.token)
        }
        if (outcome.kind != RetryPolicy.Kind.OK) {
            // 队列一条都不动：学习结果先留在手机上，等下次同步。
            return Result.failure(SyncException(pushFailureText(outcome)))
        }
        val pushBody = outcome.body
        if (pushBody.isNullOrBlank()) {
            return Result.failure(SyncException("电脑的同步回执是空的，记录先留在本地"))
        }
        val response = runCatching { gson.fromJson(pushBody, PushResponse::class.java) }.getOrNull()
        if (response == null || (response.serverRev == null && response.acked == null)) {
            return Result.failure(SyncException("电脑的同步回执读不懂，记录先留在本地"))
        }

        val acked = response.acked.orEmpty()
        if (acked.isNotEmpty()) syncDao.deleteByOpIds(acked)
        // 服务端明确不收的（缺字段/格式错）留在队里也永远不会被收，删掉并留日志，别让「待同步 N」卡住。
        val rejected = response.rejected.orEmpty()
        if (rejected.isNotEmpty()) {
            val ids = rejected.mapNotNull { it.opId?.takeIf(String::isNotBlank) }
            if (ids.isNotEmpty()) syncDao.deleteByOpIds(ids)
            Log.w(TAG, "电脑端拒收 ${rejected.size} 条：${rejected.take(3).mapNotNull { it.reason }}")
        }

        // 1.4.7.2 W5：诊断字段。冲突/清理/重放分开记，别把正常重放当冲突吓自己。
        val conflicts = response.conflicts.orEmpty()
        if (conflicts.isNotEmpty()) {
            Log.w(
                TAG,
                "电脑端判定 ${conflicts.size} 条真实冲突：" +
                    conflicts.take(3).joinToString("; ") {
                        "${it.word}/${it.reason}(old=${it.oldLrt},new=${it.newLrt})"
                    }
            )
        }
        val trimmed = response.trimmed ?: 0
        if (trimmed > 0) {
            Log.w(TAG, "电脑端为容量清理了 $trimmed 行（按书 ${response.trimmedByBook.orEmpty()}）")
        }
        val replayed = response.replayed.orEmpty()
        if (replayed.isNotEmpty()) {
            Log.i(TAG, "电脑端确认 ${replayed.size} 条幂等重放（不涨 rev、不算冲突）")
        }
        SyncStatusStore.reportSyncDiagnostics(
            epoch = response.epoch ?: state.epoch,
            conflicts = conflicts.size,
            trimmed = trimmed,
            replayed = replayed.size
        )

        var newState = state.copy(
            serverRev = if (wireOps.isEmpty()) state.serverRev else response.serverRev ?: state.serverRev,
            lastSyncAt = System.currentTimeMillis()
        )
        val serverEpoch = response.epoch ?: state.epoch
        if (serverEpoch != state.epoch) {
            // 服务端换库/坏文件重建：旧 rev 会静默漏行，必须归零重拉；
            // 本地 tb_sync_op 未发队列一条都不删（用户刚背的进度先留在手机上）。
            Log.w(TAG, "电脑端恢复代次变了：${state.epoch} -> $serverEpoch，游标归零重拉")
            newState = newState.copy(serverRev = 0L, epoch = serverEpoch)
        }
        syncDao.putState(newState)
        // ack 之后队列短了，状态页的「待同步 N 条」要立刻跟上，不能等下次进页面。
        SyncStatusStore.reportPending(syncDao.pendingCount())
        return Result.success(acked.size)
    }

    private fun parseLocal(row: SyncOp): LocalOp? =
        runCatching { gson.fromJson(row.payload, LocalOp::class.java) }.getOrNull()

    /**
     * word_id -> word_key。词表里 word_key = trim + lowercase(word)（AppDatabase 与
     * WordRepository 的 normalizeWordKey 都是这套），所以这里用同一套算法反推，
     * 不能用 Locale.ROOT：库里的 key 是按默认 locale 落下的，两边必须一致才对得上。
     */
    private suspend fun wordKeyOf(wordId: Long, cache: MutableMap<Long, String>): String {
        cache[wordId]?.let { return it }
        val raw = wordDao.getWordById(wordId)?.word.orEmpty()
        val key = if (raw.isBlank()) "" else raw.trim().lowercase()
        cache[wordId] = key
        return key
    }

    private suspend fun bookNameOf(bookId: Long, cache: MutableMap<Long, String>): String {
        cache[bookId]?.let { return it }
        val name = bookDao.getBookById(bookId)?.name?.trim().orEmpty()
        cache[bookId] = name
        return name
    }

    /** 推送失败时给用户的话：401 / 404 本身就是完整建议，别再挂尾巴。 */
    private fun pushFailureText(outcome: HttpOutcome): String {
        val base = outcome.message?.takeIf { it.isNotBlank() } ?: UNREACHABLE
        return when (outcome.kind) {
            RetryPolicy.Kind.UNAUTHORIZED, RetryPolicy.Kind.NOT_FOUND -> base
            else -> "$base，记录先留在本地"
        }
    }
}

/**
 * 队列里存的 payload。比契约多两个本地字段 wid / bid：
 * 推送时要靠它们回查 word_key 与书名；契约里的 w / b 只写最终值，所以入队时留空。
 */
private data class LocalOp(
    @SerializedName("op_id") val opId: String? = null,
    @SerializedName("t") val type: String? = null,
    @SerializedName("w") val wordKey: String? = null,
    @SerializedName("b") val bookName: String? = null,
    @SerializedName("st") val status: Int? = null,
    @SerializedName("reps") val repetitions: Int? = null,
    @SerializedName("ivl") val intervalDays: Int? = null,
    @SerializedName("nrt") val nextReviewTime: Long? = null,
    @SerializedName("ef") val easeFactor: Float? = null,
    @SerializedName("rc") val reviewCount: Int? = null,
    @SerializedName("lrt") val lastReviewTime: Long? = null,
    @SerializedName("wid") val wordId: Long? = null,
    @SerializedName("bid") val bookId: Long? = null,
    // 1.4.9-P1：FSRS-5 的 4 个状态量（可选；老数据没有这几个键时按「对面不知道」处理）。
    @SerializedName("fsrs_stability") val fsrsStability: Float? = null,
    @SerializedName("fsrs_difficulty") val fsrsDifficulty: Float? = null,
    @SerializedName("fsrs_lapses") val fsrsLapses: Int? = null,
    @SerializedName("fsrs_state") val fsrsState: Int? = null
)

internal fun <W> pushRequest(deviceId: String, rev: Long, ops: List<W>, preview: UpcomingNewWords?) =
    PushRequest(deviceId, rev, ops, preview?.words?.map { it.id }, preview?.words?.map { it.word },
        preview?.book?.name, preview?.book?.id)

internal data class PushRequest<W>(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("base_rev") val baseRev: Long,
    @SerializedName("ops") val ops: List<W>,
    @SerializedName("upcoming_new_word_ids") val ids: List<Long>?,
    @SerializedName("upcoming_new_word_keys") val keys: List<String>?,
    @SerializedName("upcoming_new_book") val book: String?,
    @SerializedName("upcoming_new_book_id") val bookId: Long?
)

private data class PushResponse(
    @SerializedName("server_rev") val serverRev: Long? = null,
    /** 1.4.7.2 W5：服务端恢复代次；缺省 = 老服务端，按 1 处理。 */
    @SerializedName("epoch") val epoch: Long? = null,
    @SerializedName("acked") val acked: List<String>? = null,
    @SerializedName("rejected") val rejected: List<RejectedOp>? = null,
    @SerializedName("conflicts") val conflicts: List<SyncConflict>? = null,
    @SerializedName("trimmed") val trimmed: Int? = null,
    @SerializedName("trimmed_by_book") val trimmedByBook: Map<String, Int>? = null,
    @SerializedName("replayed") val replayed: List<String>? = null
)

/** 服务端拒收的行：op_id + 原因（服务端字段名就是 op_id / reason）。 */
private data class RejectedOp(
    @SerializedName("op_id") val opId: String? = null,
    @SerializedName("reason") val reason: String? = null
)

/** 服务端判定的真实冲突（不是普通幂等重放）。 */
private data class SyncConflict(
    @SerializedName("op_id") val opId: String? = null,
    @SerializedName("word") val word: String? = null,
    @SerializedName("kept_op_id") val keptOpId: String? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("old_lrt") val oldLrt: Long? = null,
    @SerializedName("new_lrt") val newLrt: Long? = null
)

internal fun interface SyncWireFactory<W> {
    fun create(
        opId: String?,
        type: String?,
        wordKey: String?,
        bookName: String?,
        status: Int?,
        repetitions: Int?,
        intervalDays: Int?,
        nextReviewTime: Long?,
        easeFactor: Float?,
        reviewCount: Int?,
        lastReviewTime: Long?,
        fsrsStability: Float?,
        fsrsDifficulty: Float?,
        fsrsLapses: Int?,
        fsrsState: Int?
    ): W
}

package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 学习记录同步（手机端这一半）。
 *
 * 三条链路：
 * - [enqueueProgress]：学习结果落库后把「结果态」写进 tb_sync_op（纯本地表，不发网络）；
 * - [push]：队列里的 op 补上 word_key / 书名后 POST /sync/push，只删服务端 ack 过的；
 * - [pull]：从 server_rev 往后 GET /sync/pull，按 (词, 书) 覆盖本地 tb_progress。
 * - [syncOnce]：pull → push → pull，返回一句能直接显示给人看的结果。
 *
 * 冲突口径（与服务端一致）：每个词取 last_review_time 最新的那条。客户端不弹窗、不合并，
 * 只在「对面这条确实比本地新」时才覆盖。
 *
 * [settingsRepository] 允许为空：WordRepository 里只用到入队（本地表），不传就完全不会碰 DataStore，
 * 也就保证学习路径上不可能发出网络请求。
 */
class SyncRepository(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository? = null
) {
    private val syncDao = database.syncDao()
    private val wordDao = database.wordDao()
    private val bookDao = database.bookDao()
    private val progressDao = database.progressDao()

    private val gson = Gson()

    /** 同一时刻只允许一轮同步（pull 与 push 共用游标与队列，交叉跑会把 rev 弄乱）。 */
    private val syncMutex = Mutex()
    private val stateStore = SyncLocalState(syncDao)
    private val http = SyncHttpTransport()
    private val endpoints = SyncEndpointResolver(settingsRepository, syncDao, http)
    private val outbox = SyncOutbox(syncDao, wordDao, bookDao, gson, stateStore, endpoints, http, SyncWireFactory(::WireOp)) {
        settingsRepository?.let { readUpcomingNewWords(database, it) }
    }
    private val merger = SyncPullMerger(syncDao, wordDao, bookDao, progressDao, gson, stateStore, endpoints, http, WireOp::class.java) { row ->
        SyncProgressRow(
            type = row.type,
            wordKey = row.wordKey,
            bookName = row.bookName,
            status = row.status,
            repetitions = row.repetitions,
            intervalDays = row.intervalDays,
            nextReviewTime = row.nextReviewTime,
            easeFactor = row.easeFactor,
            reviewCount = row.reviewCount,
            lastReviewTime = row.lastReviewTime,
            fsrsStability = row.fsrsStability,
            fsrsDifficulty = row.fsrsDifficulty,
            fsrsLapses = row.fsrsLapses,
            fsrsState = row.fsrsState
        )
    }

    /**
     * 1.4.7.1：最近一次 [syncOnce] 跑完的时间戳（成功失败都算）。
     *
     * 后台定时同步（[com.kaoyan.wordhelper.sync.BackgroundSync]）读它做节流：
     * 用户刚切回前台顺手同步过一轮，后台任务就没必要隔几分钟再拉一遍同样的数据。
     */
    @Volatile
    var lastSyncAtMs: Long = 0L
        private set

    @Volatile
    private var networkRecoveryStarted = false

    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun clearRateLimit() = http.clearRateLimit()

    suspend fun enqueueProgress(wordId: Long, bookId: Long, snapshot: Progress) =
        outbox.enqueueProgress(wordId, bookId, snapshot)

    /** 队列推给电脑端，返回本次被 ack（可以安全删掉）的条数。网络失败时队列原样保留。 */
    suspend fun push(): Result<Int> = syncMutex.withLock { outbox.pushLocked() }

    /** 从本地游标往后拉，返回本次真正落到 tb_progress 的行数。 */
    suspend fun pull(): Result<Int> = syncMutex.withLock { merger.pullLocked() }

    suspend fun hasAddress(): Boolean = endpoints.hasAddress()

    /**
     * 跑一轮同步（对外唯一入口）。
     *
     * 1.4.7.1：只在这里记「最近同步时间」，给后台定时任务做节流用；
     * 真正的 pull→push→pull 在 [syncOnceLocked] 里，加锁也在那儿，行为与之前完全一样。
     */
    suspend fun syncOnce(): Result<String> = try {
        syncOnceLocked()
    } finally {
        lastSyncAtMs = System.currentTimeMillis()
    }

    /**
     * pull → push → pull。
     * 先拉：对面可能有比本地新的结果；再推：把本地刚学的发出去；最后再拉一次，
     * 把「自己刚推上去的」在别的设备上的结果是同一份，顺手把服务端补的字段收回来。
     */
    private suspend fun syncOnceLocked(): Result<String> = syncMutex.withLock {
        // 1.4.7：同步前用 /health 校验实际候选，状态页才有电脑端版本与能力可显示。
        // 探不到不阻塞：老服务端没有这个接口，真正的同步请求会拿到 404，由 call() 归档成 SERVER_TOO_OLD。
        val probeConfig = endpoints.config()
        val verifiedOrigin = if (probeConfig != null) endpoints.probeHealth(probeConfig) else null
        val knownInstance = settingsRepository?.settingsFlow?.first()?.cardBackInstanceId.orEmpty()
        // 已配对过就绝不把 token 发给没通过 /health 身份校验的候选。
        if (knownInstance.isNotBlank() && verifiedOrigin == null) {
            return@withLock Result.failure(
                SyncException("电脑身份未确认，本轮同步已停止；请确认在同一 WiFi 或检查地址/口令")
            )
        }
        // 本轮后续 pull/push 只发这个地址，避免 probe 通过后又按候选顺序回到伪服务。
        endpoints.pinnedOrigin = verifiedOrigin
        try {
            // 第一次同步前先把「升级前就背过的词」补进队列：服务端 rev=0 且从没同步过才走这里。
            outbox.bulkEnqueueIfFresh(stateStore.ensureState())
            val firstPull = merger.pullLocked()
            if (firstPull.isFailure) {
                return@withLock Result.failure<String>(firstPull.exceptionOrNull() ?: SyncException("同步失败"))
            }
            val pushed = outbox.pushLocked()
            if (pushed.isFailure) {
                return@withLock Result.failure<String>(pushed.exceptionOrNull() ?: SyncException("同步失败"))
            }
            val secondPull = merger.pullLocked()
            if (secondPull.isFailure) {
                return@withLock Result.failure<String>(secondPull.exceptionOrNull() ?: SyncException("同步失败"))
            }
            val total = firstPull.getOrDefault(0) + pushed.getOrDefault(0) + secondPull.getOrDefault(0)
            return@withLock Result.success(
                if (total == 0) "已经是最新的了" else "已同步 $total 条"
            )
        } finally {
            endpoints.pinnedOrigin = null
        }
    }

    /** 队列里还有几条没发出去（给界面显示「待同步 N 条」用，不触发网络）。 */
    suspend fun pendingCount(): Int = syncDao.pendingCount().also { SyncStatusStore.reportPending(it) }

    /**
     * 1.4.6.1 P1：网络恢复后自动补一轮学习记录同步。
     *
     * 改写链路早就在监听网络了，学习记录没有；断网时背的词会一直躺在 tb_sync_op 里，
     * 等用户下次手点同步。这里只在真正「从断到通」时触发一次，并做 30 秒节流，
     * 不和改写同步共用节流（两条链路各自的请求互不影响）。
     */
    fun startNetworkRecoverySync() {
        val settings = settingsRepository ?: return
        if (networkRecoveryStarted) return
        networkRecoveryStarted = true
        autoSyncScope.launch {
            settings.connectedNetworkFlow
                .distinctUntilChanged()
                // 订阅时读到的当前状态不算「恢复」：启动时的正常同步会处理。
                .drop(1)
                .filterNotNull()
                .collect {
                    runCatching { syncOnce() }
                }
        }
    }

    suspend fun resetCursorForEndpointChange() = stateStore.resetCursorForEndpointChange()

    /** POST /sync/push 的 ops 元素，也是 GET /sync/pull 的 rows 元素。所有字段可空：pull 的行可能缺字段。 */
    internal data class WireOp(
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
        // 1.4.9-P1：FSRS-5 状态量。全部可空 = 老服务端/老数据不会因为缺字段整行被拒。
        // 键名与 tools/cardback-editor/sync_store.py 的 OPTIONAL_TYPED 保持一致。
        @SerializedName("fsrs_stability") val fsrsStability: Float? = null,
        @SerializedName("fsrs_difficulty") val fsrsDifficulty: Float? = null,
        @SerializedName("fsrs_lapses") val fsrsLapses: Int? = null,
        @SerializedName("fsrs_state") val fsrsState: Int? = null
    )

    companion object
}

package com.kaoyan.wordhelper.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.SyncOp
import com.kaoyan.wordhelper.data.entity.SyncState
import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.LanDiscovery
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import com.kaoyan.wordhelper.data.network.TlsPinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    /** 1.4.6.1 P1：本次进程里最后一次真正连通的地址（手填地址坏了时靠它兜底）。 */
    @Volatile
    private var activeOrigin: String? = null

    /**
     * 本轮已经通过 `/health` 身份校验的地址。一旦确定，只把请求发到它；
     * 否则伪服务/陌生地址可能先拿到 2xx，后续 pull/push 仍会把 token 发过去。
     */
    @Volatile
    private var pinnedOrigin: String? = null
    private var endpointRevisionSeen = settingsRepository?.endpointRevision ?: -1L

    /** 已写进 DataStore 的 last-good；一轮同步要发多次请求，别每次都写盘。 */
    @Volatile
    private var savedLastGood: String? = null

    /** 1.4.7.2 W4：HTTPS 配置地址的 /identity 锚点最多 10 分钟拉一次（pin 轮换兜底）。 */
    @Volatile
    private var anchorFetchedAt = 0L

    @Volatile
    private var anchorFetchedKey = ""

    /** 断网恢复自动补同步的节流。 */
    @Volatile
    private var lastAutoSyncAt = 0L

    /**
     * 1.4.7.1：最近一次 [syncOnce] 跑完的时间戳（成功失败都算）。
     *
     * 后台定时同步（[com.kaoyan.wordhelper.sync.BackgroundSync]）读它做节流：
     * 用户刚切回前台顺手同步过一轮，后台任务就没必要隔几分钟再拉一遍同样的数据。
     */
    @Volatile
    var lastSyncAtMs: Long = 0L
        private set

    /**
     * 1.4.7：被 429 限流到什么时候（epoch ms）。
     *
     * 服务端的窗口是「单 IP 每分钟 20 次 / 每天 200 次」，超了要等窗口过去。
     * 这个值大于现在时，[call] 直接短路返回 RATE_LIMITED，不再往电脑上打请求 ——
     * 1.4.6 那次事故就是 App 4 秒一轮把额度打满，越试越糟。
     */
    @Volatile
    private var rateLimitedUntil = 0L

    /** 1.5.2：「立即重试」先清本地冷却，避免按钮在 429 冷却期里空转。 */
    fun clearRateLimit() {
        rateLimitedUntil = 0L
    }

    /** 上次 GET /health 的时间：60 秒最多探一次，别让状态页每次刷新都多打一个请求。 */
    @Volatile
    private var lastHealthProbeAt = 0L

    /** 上次探测的地址；地址变了要立刻重探，不能拿旧电脑的 instance 判断新电脑。 */
    @Volatile
    private var lastHealthProbeOrigin = ""

    /** 上次探测通过身份校验的地址；60 秒节流内复用，避免每轮都打 /health。 */
    @Volatile
    private var lastVerifiedOrigin: String? = null

    /**
     * 上一次上报的状态。4 秒一轮自动同步时，同一个失败会重复几十次；
     * 内容完全一样就不重复写 [SyncStatusStore]，免得状态页的时间戳一直跳。
     */
    @Volatile
    private var lastReport: Triple<SyncPhase, Int?, String?>? = null

    /**
     * 电脑端 `/health` 的自述（null = 没探到 / 老服务端没有这个接口）。
     *
     * 只用来判断**能力**：服务端版本号还停在 1.4.2，拿版本号比大小只会把新端也判成旧的。
     */
    @Volatile
    private var serverHealth: ServerHealth.Info? = null

    @Volatile
    private var networkRecoveryStarted = false

    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * OkHttpClient 自带连接池与线程池，构造一次就该复用；但只在真的要联网时才建
     * （WordRepository 里那个只入队的实例永远用不到它）。
     * 超时与池参数与 RewriteRepository 保持一致，同一个电脑端不能两套脾气。
     *
     * 1.5.1 起按候选性质分流：[lanClient] 走局域网、[publicClient] 走公网域名，
     * 这个对象只剩 [call] 的兜底默认值（谁忘了传 client 也不会拿到比这更短的超时）。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 电脑端是 python 的 HTTPServer（HTTP/1.0，回完就断连），
            // 复用空闲连接只会拿到对端已经关掉的 socket，所以不留连接。
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    /**
     * 1.5.1：局域网候选的「短痛」客户端。
     *
     * 同一个 WiFi 下电脑端要么毫秒级回话，要么压根不在线；用上面 4/12/15 秒那套
     * 去等一个已经离线的 IP，一轮同步会先白等十几秒，公网兜底永远轮不到。
     * 所以 LAN 只给 2 秒建连、8 秒整体：打不通就立刻换下一个候选。
     */
    private val lanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(LAN_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LAN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(LAN_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    /**
     * 1.5.1：公网（`settings.wordbookRemoteUrl`）候选的客户端。
     *
     * 域名走 Cloudflare Tunnel 这类中继，握手/首字节本来就更慢，用 LAN 的短超时
     * 会把「其实能通但慢一点」的出口误判成失败。这里放宽到 8/20/30 秒。
     */
    private val publicClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PUBLIC_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PUBLIC_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PUBLIC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 中继同样是「一次一连接」更稳：复用空闲连接只会拿到已关闭的 socket。
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    // ---- 入队 ----

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
    private suspend fun bulkEnqueueIfFresh(state: SyncState): Int {
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

    // ---- 推送 ----

    /** 队列推给电脑端，返回本次被 ack（可以安全删掉）的条数。网络失败时队列原样保留。 */
    suspend fun push(): Result<Int> = syncMutex.withLock { pushLocked() }

    private suspend fun pushLocked(): Result<Int> {
        val config = config() ?: return Result.failure(SyncException(NO_ADDRESS))
        val rows = syncDao.pending(PUSH_BATCH)
        if (rows.isEmpty()) return Result.success(0)

        val state = ensureState()
        val wordKeyCache = HashMap<Long, String>()
        val bookNameCache = HashMap<Long, String>()
        val wireOps = ArrayList<WireOp>(rows.size)
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
            wireOps += WireOp(
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
        if (wireOps.isEmpty()) return Result.success(0)

        val pushJson = gson.toJson(PushRequest(state.deviceId, state.serverRev, wireOps))
        // /health 说得很清楚「这台电脑没有 sync_push」：请求干脆不发，别拿 404 去撞（1.4.6 的 186 次）。
        if (ServerHealth.supports(serverHealth, CAP_SYNC_PUSH) == false) {
            val message = RetryPolicy.missingCapabilityMessage(CAP_SYNC_PUSH)
            publish(SyncPhase.SERVER_TOO_OLD, null, message)
            return Result.failure(SyncException(message))
        }
        // 候选地址轮试；分类、退避、上报都在 call() 里做，这里只负责按类型给用户一句话。
        val outcome = sendToOrigins(config) { origin ->
            requestPost(origin + PUSH_PATH, pushJson, config.token)
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
            serverRev = response.serverRev ?: state.serverRev,
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

    // ---- 拉取 ----

    /** 从本地游标往后拉，返回本次真正落到 tb_progress 的行数。 */
    suspend fun pull(): Result<Int> = syncMutex.withLock { pullLocked() }

    private suspend fun pullLocked(): Result<Int> {
        val config = config() ?: return Result.failure(SyncException(NO_ADDRESS))
        var state = ensureState()
        var applied = 0
        var rewound = false

        repeat(MAX_PULL_PAGES) {
            // 服务端只认 since_rev；device_id 只在 push 时带，pull 不需要（也不该假装能过滤自己发过的 op）。
            val path = "$PULL_PATH?since_rev=${state.serverRev}"
            if (ServerHealth.supports(serverHealth, CAP_SYNC_CURSOR) == false) {
                val message = RetryPolicy.missingCapabilityMessage(CAP_SYNC_CURSOR)
                publish(SyncPhase.SERVER_TOO_OLD, null, message)
                return Result.failure(SyncException(message))
            }
            val outcome = sendToOrigins(config) { origin -> requestGet(origin + path, config.token) }
            if (outcome.kind != RetryPolicy.Kind.OK) {
                // 游标没动，下次还是从同一处拉，不会漏也不会重复算。
                return Result.failure(SyncException(outcome.message ?: UNREACHABLE))
            }
            val pullBody = outcome.body
            if (pullBody.isNullOrBlank()) {
                return Result.failure(SyncException("电脑返回的同步数据是空的"))
            }
            val page = runCatching { gson.fromJson(pullBody, PullPage::class.java) }.getOrNull()
                ?: return Result.failure(SyncException("电脑返回的同步数据读不懂"))

            // 1.4.7.2 W5：恢复代次变了 = 服务端换库/坏文件重建。只归零游标重拉，
            // 本地待发队列原样保留；最多重来一次，第二次还变就报错让用户先别同步。
            val pageEpoch = page.epoch
            if (pageEpoch != null && pageEpoch != state.epoch) {
                if (rewound) return Result.failure(SyncException("电脑端恢复代次反复变化，先别同步"))
                rewound = true
                Log.w(TAG, "电脑端恢复代次变了：${state.epoch} -> $pageEpoch，游标归零重拉")
                state = state.copy(serverRev = 0L, epoch = pageEpoch)
                syncDao.putState(state)
                SyncStatusStore.reportSyncDiagnostics(
                    epoch = pageEpoch,
                    conflicts = 0,
                    trimmed = 0,
                    replayed = 0
                )
                return@repeat
            }

            val serverRev = page.serverRev
            if (serverRev != null && serverRev < state.serverRev) {
                // 电脑端清过库或换过电脑，rev 会从头算；本地游标比它大就归零重拉一次。
                if (rewound) return Result.failure(SyncException("电脑端版本号对不上，先别同步"))
                rewound = true
                state = state.copy(serverRev = 0L)
                syncDao.putState(state)
                return@repeat
            }

            val result = applyRows(page.rows.orEmpty())
            applied += result.applied
            if (result.skipped > 0) {
                Log.i(TAG, "本次拉取跳过 ${result.skipped} 条（词或书不在本机 / 比本地旧）")
            }
            // 游标优先用服务端给的 next_since_rev：还有下一页时它只推进到本页最后一行，
            // 一次超过 500 条也不会漏（1.4.4 验收 P0）。老服务端没这个字段就退回 server_rev。
            val nextRev = page.nextSinceRev ?: serverRev
            if (nextRev != null && nextRev != state.serverRev) {
                // rows 全部落库之后才推游标：中途失败下次会重放这一页，
                // 重放是安全的（同一条 lrt 相等的记录不会再覆盖）。
                state = state.copy(serverRev = nextRev, lastSyncAt = System.currentTimeMillis())
                syncDao.putState(state)
            }
            if (page.more != true) return Result.success(applied)
        }
        // 拉不完不能报成功：否则用户以为同步完了，其实后面还有一大截没下来。
        return Result.failure(SyncException("同步没拉完（一次最多 $MAX_PULL_PAGES 页），再点一次同步继续"))
    }

    /**
     * 把一页 rows 写进 tb_progress。返回真正覆盖了几条、跳过了几条。
     *
     * 只写契约里有的字段：本地多出来的拼写次数、连续正确、平均反应时间等一律沿用原值
     * （契约没有它们，硬覆盖会把手机上的统计抹掉）。
     */
    private suspend fun applyRows(rows: List<WireOp>): PullApply {
        if (rows.isEmpty()) return PullApply(0, 0)
        // 服务端只存书名。用户可能自己建过同名书，取 id 最小的那本，保证每次落到同一本。
        val bookIdByName: Map<String, Long> = bookDao.getAllBooksList()
            .groupBy { it.name }
            .mapValues { (_, books) -> books.minOf { it.id } }

        var applied = 0
        var skipped = 0
        // 同一页里同一个 (词, 书) 来多条时，只让 lrt 最大的那条落地。
        val appliedLrt = HashMap<Pair<Long, Long>, Long>()

        rows.forEach { row ->
            val type = row.type
            if (!type.isNullOrBlank() && type != TYPE_PROGRESS) {
                skipped++
                return@forEach
            }
            val wordKey = row.wordKey?.trim().orEmpty()
            val wordId = if (wordKey.isEmpty()) null else wordDao.getWordIdByKey(wordKey)
            val bookName = row.bookName?.trim().orEmpty()
            val bookId = if (bookName.isEmpty()) null else bookIdByName[bookName]
            val lrt = row.lastReviewTime
            if (wordId == null || bookId == null || lrt == null) {
                // 词/书不在这台手机上，或这一行没有 lrt：跳过并计数，不动本地数据。
                skipped++
                return@forEach
            }
            val key = wordId to bookId
            val alreadyApplied = appliedLrt[key]
            if (alreadyApplied != null && alreadyApplied >= lrt) {
                skipped++
                return@forEach
            }
            val existing = progressDao.getProgress(wordId, bookId)
            // 最新赢：本地这条比对面新（比如刚在这台手机上复习过），就保留本地。
            if (existing != null && existing.lastReviewTime > lrt) {
                skipped++
                return@forEach
            }
            val target = (existing ?: Progress(wordId = wordId, bookId = bookId)).copy(
                status = row.status ?: existing?.status ?: Progress.STATUS_NEW,
                repetitions = row.repetitions ?: existing?.repetitions ?: 0,
                intervalDays = row.intervalDays ?: existing?.intervalDays ?: 0,
                nextReviewTime = row.nextReviewTime ?: existing?.nextReviewTime ?: 0L,
                easeFactor = row.easeFactor ?: existing?.easeFactor ?: 2.5f,
                reviewCount = row.reviewCount ?: existing?.reviewCount ?: 0,
                lastReviewTime = lrt,
                // 1.4.9-P1：FSRS 4 字段是可选的「增量契约」。对面没带（老数据/老服务端）时
                // 必须原样保留本地值，绝不能拿 0 冲掉本机已经算出来的 S/D/lapses/state。
                fsrsStability = row.fsrsStability ?: existing?.fsrsStability ?: 0f,
                fsrsDifficulty = row.fsrsDifficulty ?: existing?.fsrsDifficulty ?: 0f,
                fsrsLapses = row.fsrsLapses ?: existing?.fsrsLapses ?: 0,
                fsrsState = row.fsrsState ?: existing?.fsrsState ?: 0
            )
            if (existing == null) {
                progressDao.insert(target)
            } else {
                // existing.copy(...) 已经带着本地主键，update 只改字段、不换行。
                progressDao.update(target)
            }
            appliedLrt[key] = lrt
            applied++
        }
        return PullApply(applied, skipped)
    }

    /**
     * 有没有配置电脑地址（手填的或上次成功过的都算）。
     *
     * 给后台定时同步判断「该不该挂这个任务」用：没地址时后台跑也只是立刻失败，白耗电。
     * 纯读，不走 [config]：那边为了轮试地址会重置换地址后的游标，不该被一句询问触发。
     */
    suspend fun hasAddress(): Boolean {
        val settings = settingsRepository?.settingsFlow?.first() ?: return false
        // 用户清空地址 = 解除配对；activeOrigin 可能还是本进程上一轮的地址，不能让它复活后台任务。
        if (settings.cardBackUrl.isBlank() && settings.cardBackLastGoodUrl.isBlank()) return false
        return CardBackAddress.candidates(
            active = activeOrigin,
            current = settings.cardBackUrl,
            lastGood = settings.cardBackLastGoodUrl
        ).isNotEmpty()
    }

    // ---- 一轮完整同步 ----

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
        // 1.4.7：同步前先问一句 /health（60 秒节流一次），状态页才有电脑端版本与能力可显示。
        // 探不到不阻塞：老服务端没有这个接口，真正的同步请求会拿到 404，由 call() 归档成 SERVER_TOO_OLD。
        val probeConfig = config()
        val verifiedOrigin = if (probeConfig != null) probeHealth(probeConfig) else null
        val knownInstance = settingsRepository?.settingsFlow?.first()?.cardBackInstanceId.orEmpty()
        // 已配对过就绝不把 token 发给没通过 /health 身份校验的候选。
        if (knownInstance.isNotBlank() && verifiedOrigin == null) {
            return@withLock Result.failure(
                SyncException("电脑身份未确认，本轮同步已停止；请确认在同一 WiFi 或检查地址/口令")
            )
        }
        // 本轮后续 pull/push 只发这个地址，避免 probe 通过后又按候选顺序回到伪服务。
        pinnedOrigin = verifiedOrigin
        try {
            // 第一次同步前先把「升级前就背过的词」补进队列：服务端 rev=0 且从没同步过才走这里。
            bulkEnqueueIfFresh(ensureState())
            val firstPull = pullLocked()
            if (firstPull.isFailure) {
                return@withLock Result.failure<String>(firstPull.exceptionOrNull() ?: SyncException("同步失败"))
            }
            val pushed = pushLocked()
            if (pushed.isFailure) {
                return@withLock Result.failure<String>(pushed.exceptionOrNull() ?: SyncException("同步失败"))
            }
            val secondPull = pullLocked()
            if (secondPull.isFailure) {
                return@withLock Result.failure<String>(secondPull.exceptionOrNull() ?: SyncException("同步失败"))
            }
            val total = firstPull.getOrDefault(0) + pushed.getOrDefault(0) + secondPull.getOrDefault(0)
            return@withLock Result.success(
                if (total == 0) "已经是最新的了" else "已同步 $total 条"
            )
        } finally {
            pinnedOrigin = null
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
            settings.networkAvailableFlow
                .distinctUntilChanged()
                // 订阅时读到的当前状态不算「恢复」：启动时的正常同步会处理。
                .drop(1)
                .filter { it }
                .collect {
                    // 已经在同步：这一轮会用到刚恢复的网络，别再排一轮。
                    if (syncMutex.isLocked) return@collect
                    val now = System.currentTimeMillis()
                    if (now - lastAutoSyncAt < AUTO_SYNC_MIN_INTERVAL_MS) return@collect
                    lastAutoSyncAt = now
                    runCatching { syncOnce() }
                }
        }
    }

    // ---- 内部工具 ----

    private suspend fun ensureState(): SyncState {
        val existing = syncDao.state()
        if (existing != null && existing.deviceId.isNotBlank()) return existing
        // 第一次同步才生成设备 id：换手机/清数据会换一个新 id，服务端据此区分来源。
        val deviceId = UUID.randomUUID().toString()
        val state = (existing ?: SyncState()).copy(deviceId = deviceId)
        syncDao.putState(state)
        return state
    }

    /** 1.4.7 验收 B2：换电脑/换地址后必须从头拉，旧游标会静默漏行；保存连接串时立刻清，进程被杀也不会漏。 */
    suspend fun resetCursorForEndpointChange() {
        syncDao.state()?.let { st ->
            if (st.serverRev != 0L) syncDao.putState(st.copy(serverRev = 0L))
        }
    }

    private suspend fun config(rotate: Boolean = true): SyncConfig? {
        // 只读一次 settingsFlow：地址与口令必须来自同一份快照。
        val settings = settingsRepository?.settingsFlow?.first() ?: return null
        val token = settings.cardBackToken.trim()
        val settingsPin = TlsPinned.normalizePin(settings.cardBackTlsPin).orEmpty()
        // 1.4.7：用户改过地址/口令就丢掉本会话缓存的 activeOrigin，否则新地址不会被优先尝试。
        // 必须先于 pinned 早退：改口令会清 pin，旧 LAN 地址不能再拿 token。
        val revision = settingsRepository?.endpointRevision ?: 0L
        if (revision != endpointRevisionSeen) {
            endpointRevisionSeen = revision
            activeOrigin = null
            pinnedOrigin = null
            // 1.4.7 验收 B2：换电脑/重装后服务端 rev 属于另一台机器，旧游标会静默漏行，必须归零从头拉。
            syncDao.state()?.let { st ->
                if (st.serverRev != 0L) syncDao.putState(st.copy(serverRev = 0L))
            }
        }
        // 1.5.1：公网兜底地址只来自设置里的 wordbookRemoteUrl（默认值由构建注入到
        // BuildConfig，仓库里是空串），**不硬编码任何域名**。没 token 时不加：
        // 没口令去敲域名只会换回一个无意义的 401，和 RewriteRepository 的约定一致。
        val publicOrigin = SyncFallbackPolicy.normalizePublicUrl(settings.wordbookRemoteUrl)
        val cloudFallback = publicOrigin.takeIf { token.isNotBlank() && it.isNotBlank() }
        // 本轮 /health 已确认身份：只认这个地址，不给伪服务/陌生候选任何拿 token 的机会。
        val pinned = pinnedOrigin
        if (!pinned.isNullOrBlank()) {
            return SyncConfig(
                origins = listOf(pinned),
                token = token,
                tlsPin = settingsPin,
                tlsPort = settings.cardBackTlsPort,
                publicOrigins = if (SyncFallbackPolicy.isPublicOrigin(pinned, publicOrigin)) {
                    setOf(pinned)
                } else {
                    emptySet()
                }
            )
        }
        // 1.4.7.2 W4：同 WiFi 只走 HTTPS + SPKI pin。pin 要么本地已有，要么先从
        // **HTTPS 配置地址**的 /identity 取锚点；取不到就不发现，绝不退回明文 HTTP。
        var lanPin = settingsPin
        var lanPort = settings.cardBackTlsPort
        // 首次配对：配对行已经给了 pin + tls_port。此时 instance_id 还是空的，
        // 不能让流程退回「先 HTTP 发 token 再学身份」——必须先走 pin 绑定的 HTTPS。
        val firstPairOrigin = firstPairPinnedOrigin(settings, lanPin, lanPort)
        if (firstPairOrigin != null) {
            activeOrigin = firstPairOrigin
            lanPort = settings.cardBackTlsPort.takeIf { it in 1..65535 } ?: DEFAULT_TLS_PORT
        }
        if (rotate && settings.cardBackLanAuto && firstPairOrigin == null) {
            val lan = resolveLan(settings)
            if (lan != null) {
                activeOrigin = lan.origin
                lanPin = lan.pin
                lanPort = lan.port
            }
        }
        // 1.4.6.1 P1：和改写链路同一套候选/上次成功，手填地址写坏时学习记录也能自愈。
        // 1.5.1：公网域名垫在 lastGood 之前 —— 局域网全灭时它是唯一还能走通的出口，
        // 而 lastGood 很可能就是那个刚刚失效的局域网地址。
        val rawOrigins = if (rotate) {
            CardBackAddress.candidates(
                active = activeOrigin,
                current = settings.cardBackUrl,
                lastGood = settings.cardBackLastGoodUrl,
                cloudFallback = cloudFallback
            )
        } else {
            listOfNotNull(
                CardBackAddress.preferred(
                    active = activeOrigin,
                    current = settings.cardBackUrl,
                    lastGood = settings.cardBackLastGoodUrl
                ),
                // 只打一发的路径也要能落到公网：局域网首选不通时，域名是最后一次机会。
                cloudFallback
            )
        }
        // 有 pin 时，同一台电脑的明文 HTTP 地址一律不进候选：token 只能发给
        // 「pin 证明过的 https://ip:tlsPort」或已受信的 HTTPS 域名。
        val kept = if (lanPin.isNotBlank()) {
            rawOrigins.filterNot { isPlainHttpIpOrigin(it) }
        } else {
            rawOrigins
        }.distinct()
        if (kept.isEmpty()) return null
        // 用户手填的地址、active、lastGood 可能就是同一个公网域名：按 host 判定，
        // 命中公网的候选要拿长超时，剩下的都按局域网短超时处理。
        val publicOrigins = kept.filter { SyncFallbackPolicy.isPublicOrigin(it, publicOrigin) }.toSet()
        // 稳定排序：局域网候选永远排在公网之前。activeOrigin 可能就是上次成功的公网域名，
        // 若照原顺序它会被顶到最前面，在家时反而先白等一次公网超时 —— 与「局域网优先」相反。
        val origins = kept.sortedBy { if (it in publicOrigins) 1 else 0 }
        return SyncConfig(
            origins = origins,
            token = token,
            tlsPin = lanPin,
            tlsPort = lanPort,
            publicOrigins = publicOrigins
        )
    }

    /**
     * 自动发现 LAN HTTPS 端点。只有本地有 expected instance_id 才继续；
     * pin 缺失时先尝试从 HTTPS 配置地址取锚点，仍没有就返回 null。
     */
    private suspend fun resolveLan(settings: UserSettings): LanEndpoint? {
        val expected = settings.cardBackInstanceId.trim()
        val token = settings.cardBackToken.trim()
        if (expected.isBlank() || token.isBlank()) return null
        val context = settingsRepository?.appContext ?: return null
        var pin = TlsPinned.normalizePin(settings.cardBackTlsPin)
        var port = settings.cardBackTlsPort.takeIf { it in 1..65535 } ?: 8443
        val now = System.currentTimeMillis()
        val anchorKey = "${settings.cardBackUrl}|$expected|${token.hashCode()}"
        val httpsConfig = TlsPinned.normalizedHttpsOrigin(settings.cardBackUrl) != null
        val shouldFetchAnchor = httpsConfig && (
            pin == null ||
                anchorFetchedKey != anchorKey ||
                now - anchorFetchedAt >= ANCHOR_REFRESH_MS
            )
        if (shouldFetchAnchor) {
            val anchor = runCatching {
                LanDiscovery.fetchAnchor(settings.cardBackUrl, token, expected)
            }.getOrNull()
            anchorFetchedAt = now
            anchorFetchedKey = anchorKey
            if (anchor != null) {
                runCatching { settingsRepository?.updateCardBackTlsAnchor(anchor.pin, anchor.port) }
                pin = TlsPinned.normalizePin(anchor.pin)
                port = anchor.port
            }
        }
        val usablePin = pin ?: return null
        val found = runCatching {
            LanDiscovery.discover(context, token, expected, usablePin, port)
        }.getOrNull() ?: return null
        if (found.instanceId != expected) return null
        return LanEndpoint(origin = found.origin, pin = usablePin, port = port)
    }

    /** 记下真正连通的地址；下次冷启动/换 Wi-Fi 还能兜底。 */
    private suspend fun rememberSuccess(origin: String) {
        SyncStatusStore.reportOrigin(origin)
        activeOrigin = origin
        if (origin == savedLastGood) return
        savedLastGood = origin
        runCatching { settingsRepository?.updateCardBackLastGoodUrl(origin) }
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

    private data class SyncConfig(
        val origins: List<String>,
        val token: String,
        val tlsPin: String = "",
        val tlsPort: Int = 8443,
        /**
         * 走公网（[UserSettings.wordbookRemoteUrl] 那个域名）的候选。
         *
         * 1.5.1：局域网优先，但出了家门局域网必然不通，所以要能落到公网。
         * 两类候选的超时预算完全不同 —— 局域网要「快失败」（2 秒内切走），
         * 公网要「耐心等」（4G/隧道首包常常 3 秒以上）—— 因此这里必须能区分。
         */
        val publicOrigins: Set<String> = emptySet()
    ) {
        /** 该 origin 是否走公网域名（决定用哪个超时档的 client）。 */
        fun isPublic(origin: String): Boolean = publicOrigins.contains(origin)
    }

    private data class LanEndpoint(val origin: String, val pin: String, val port: Int)

    private data class PullApply(val applied: Int, val skipped: Int)

    private class SyncException(message: String) : Exception(message)

    private fun requestGet(url: String, token: String): Request = Request.Builder()
        .url(url)
        // 电脑端是 HTTP/1.0 的一次性连接，明确告诉它用完就断，别让它吊着一个复用不了的空闲连接。
        .header("Connection", "close")
        // 口令头与电脑端改写链路共用 CardbackAuth：头名和 401 时给用户看的话都只有一处定义。
        .apply { CardbackAuth.headerValue(token)?.let { header(CardbackAuth.HEADER, it) } }
        .get()
        .build()

    private fun requestPost(url: String, json: String, token: String): Request = Request.Builder()
        .url(url)
        .header("Connection", "close")
        .apply { CardbackAuth.headerValue(token)?.let { header(CardbackAuth.HEADER, it) } }
        .post(json.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    /** 一次请求的结局：归类、状态码、响应体、异常、「下次可试」的时间和给用户的那句话。 */
    private data class HttpOutcome(
        val kind: RetryPolicy.Kind,
        val code: Int?,
        val body: String?,
        val error: Throwable? = null,
        val retryAfterMs: Long? = null,
        val message: String? = null,
        val retryAtEpochMs: Long? = null
    )

    /**
     * 所有联网请求的唯一出口：分类 → 决定要不要重试 → 上报状态 → 返回结局。
     *
     * 规则（与 [RetryPolicy] 一致）：
     * - 2xx 直接返回；401 / 404 / 429 / 其他 4xx **一律不重试**（重试也变不好，只会把限流额度打满）；
     * - 只有 5xx / 408 / 超时 / IO 才按指数退避就地重试，最多 [maxAttempts] 次；
     * - 429 会把「冷却到什么时候」记进 [rateLimitedUntil]：之后的请求在闸内直接短路，
     *   连包都不发（1.4.6 事故就是 App 4 秒一轮把 20 次/分的额度打满）。
     *
     * [maxAttempts] = 1 表示「只问一次结果」，[report] = false 表示这次请求不该影响状态页
     * （单词状态轮询、能力探测这类后台小请求）。
     */
    private suspend fun call(
        build: () -> Request,
        maxAttempts: Int = RetryPolicy.DEFAULT_MAX_ATTEMPTS,
        respectRateLimit: Boolean = true,
        report: Boolean = true,
        client: OkHttpClient = this.client
    ): HttpOutcome = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastCode: Int? = null
        var lastError: Throwable? = null
        var lastRetryAfter: Long? = null
        while (true) {
            val gate = rateLimitedUntil
            val now = System.currentTimeMillis()
            if (respectRateLimit && gate > now) {
                val wait = gate - now
                val message = RetryPolicy.rateLimitedMessage(wait)
                if (report) publish(SyncPhase.RATE_LIMITED, 429, message, gate)
                return@withContext HttpOutcome(
                    kind = RetryPolicy.Kind.RATE_LIMITED,
                    code = null,
                    body = null,
                    retryAfterMs = wait,
                    message = message,
                    retryAtEpochMs = gate
                )
            }
            try {
                client.newCall(build()).execute().use { response ->
                    val code = response.code
                    // 读响应体失败（例如服务端提前断流）不能吞成 null —— 那会被当成
                    // 「200 + 空正文」，既不重试也说不清原因。让它冒到外层按 IO 重试。
                    val body = response.body?.string()
                    when (RetryPolicy.classify(code)) {
                        RetryPolicy.Kind.OK -> {
                            if (report) publish(SyncPhase.OK, code)
                            return@withContext HttpOutcome(RetryPolicy.Kind.OK, code, body)
                        }
                        RetryPolicy.Kind.UNAUTHORIZED -> {
                            val message = "HTTP 401：${CardbackAuth.HINT}"
                            if (report) publish(SyncPhase.UNAUTHORIZED, code, message)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.UNAUTHORIZED, code, body, message = message
                            )
                        }
                        RetryPolicy.Kind.NOT_FOUND -> {
                            // 旧服务端没有 /sync/*：不重试，直接告诉用户「电脑端版本过旧」。
                            if (report) publish(SyncPhase.SERVER_TOO_OLD, code, RetryPolicy.MSG_SERVER_TOO_OLD)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.NOT_FOUND, code, body,
                                message = RetryPolicy.MSG_SERVER_TOO_OLD
                            )
                        }
                        RetryPolicy.Kind.RATE_LIMITED -> {
                            val retryAfter = RetryPolicy.parseRetryAfterMs(
                                raw = response.header("Retry-After"),
                                nowEpochMs = System.currentTimeMillis()
                            )
                            val wait = RetryPolicy.delayFor(RetryPolicy.Kind.RATE_LIMITED, attempt, retryAfter)
                            val until = System.currentTimeMillis() + wait
                            rateLimitedUntil = until
                            val message = RetryPolicy.rateLimitedMessage(wait)
                            if (report) publish(SyncPhase.RATE_LIMITED, code, message, until)
                            return@withContext HttpOutcome(
                                kind = RetryPolicy.Kind.RATE_LIMITED,
                                code = code,
                                body = body,
                                retryAfterMs = wait,
                                message = message,
                                retryAtEpochMs = until
                            )
                        }
                        RetryPolicy.Kind.FATAL -> {
                            val message = "电脑拒绝了请求${RetryPolicy.httpTag(code)}"
                            if (report) publish(SyncPhase.FAILED, code, message)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.FATAL, code, body, message = message
                            )
                        }
                        RetryPolicy.Kind.RETRYABLE -> {
                            // 5xx / 408：等本轮循环末尾的退避；Retry-After 有就听它的。
                            lastCode = code
                            lastError = null
                            lastRetryAfter = RetryPolicy.parseRetryAfterMs(
                                raw = response.header("Retry-After"),
                                nowEpochMs = System.currentTimeMillis()
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // 协程被取消不是「同步失败」：原样抛出去，别写进状态、别重试。
                throw cancelled
            } catch (t: Throwable) {
                lastCode = null
                lastError = t
                lastRetryAfter = null
            }

            // 走到这里一定是可重试的失败（5xx / 408 / 超时 / IO）。
            if (!RetryPolicy.shouldRetryInline(RetryPolicy.Kind.RETRYABLE, attempt, maxAttempts)) {
                val message = RetryPolicy.retryingMessage(
                    reason = RetryPolicy.describe(lastError),
                    httpCode = lastCode
                )
                if (report) publish(SyncPhase.RETRYING, lastCode, message)
                return@withContext HttpOutcome(
                    kind = RetryPolicy.Kind.RETRYABLE,
                    code = lastCode,
                    body = null,
                    error = lastError,
                    retryAfterMs = lastRetryAfter,
                    message = message
                )
            }
            // 可取消的等待（协程取消时立刻退出，不用等满 1 秒）。
            delay(RetryPolicy.delayFor(RetryPolicy.Kind.RETRYABLE, attempt, lastRetryAfter))
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        HttpOutcome(RetryPolicy.Kind.RETRYABLE, lastCode, null)
    }

    /**
     * 按候选地址依次试，谁先「回话」就用谁。
     *
     * 「回话」= 拿到了 HTTP 状态码（哪怕是 401/404/429）：这个地址是活的，换下一个只会在死地址上白等。
     * 一个都没回话（连不上 / 超时）才返回最后一个结局。
     */
    private suspend fun sendToOrigins(config: SyncConfig, build: (String) -> Request): HttpOutcome {
        var last: HttpOutcome? = null
        // 有公网域名可落时，局域网候选只打一发：地址不通（换网/路由器改 IP/电脑关机）
        // 时必须尽快把机会让给域名，而不是在死地址上重试 + 退避等上十几秒。
        val failFastLan = config.publicOrigins.isNotEmpty()
        for (origin in config.origins) {
            val isPublic = config.isPublic(origin)
            // https LAN origin 必须走 pin；没有合法 pin 就跳过，绝不回退系统信任的明文/普通 TLS。
            val originClient = httpClientFor(origin, config.tlsPin, isPublic)
            if (originClient == null) {
                Log.w(TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            val outcome = call(
                build = { build(origin) },
                maxAttempts = if (failFastLan && !isPublic) 1 else RetryPolicy.DEFAULT_MAX_ATTEMPTS,
                client = originClient
            )
            last = outcome
            // 只有 2xx 才算「这个地址能用」。但 2xx 也可能来自伪装服务：
            // 不在这里记 last-good，等 /health 解析出 instance_id 并通过身份校验后再记。
            if (outcome.code != null && outcome.code in 200..299) {
                return outcome
            }
            // 限流闸内短路：换地址也没用，直接返回（RATE_LIMITED 已经上报过）。
            // 401/404 只说明这个候选不可用（口令/版本不对），继续试下一个地址（LAN 失败走域名）。
            if (outcome.kind == RetryPolicy.Kind.RATE_LIMITED) return outcome
        }
        return last ?: HttpOutcome(RetryPolicy.Kind.RETRYABLE, null, null, message = UNREACHABLE)
    }

    /**
     * 按 origin 选择客户端：https + IP 的 LAN 必须用 pin；其余按「局域网 / 公网」分两档超时。
     * 返回 null 表示「这是 LAN HTTPS，但没有可用的 pin」，调用方必须跳过而不是降级。
     */
    private fun httpClientFor(origin: String, pin: String, isPublic: Boolean = false): OkHttpClient? {
        // pin 优先且不可让位：https://ip 只能信 pin 证明过的那台电脑，
        // 无论它被归到哪一档，都不能换成系统信任或明文。
        if (TlsPinned.isLanHttps(origin)) return TlsPinned.clientFor(pin)
        // 公网走隧道/4G，首包慢，给足预算；局域网要「快失败」好切公网。
        // 既不是设置里的公网域名、又不是内网 IP 的普通域名（例如用户自建 HTTPS）
        // 保持原来的 4/12/15，不因为「没归类」就被误伤成 2 秒超时。
        if (isPublic) return publicClient
        return if (isLanOrigin(origin)) lanClient else client
    }

    /** 1.5.1：内网地址判定（只用于挑超时档，不参与任何信任判断）。 */
    private fun isLanOrigin(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    /**
     * pin 在配对行里，地址主机是 IP 时，直接把配置地址换成
     * `https://host:tlsPort`。这不是发现，是「用用户贴进来的 pin 验证同一台
     * 电脑的 TLS 端点」，所以 instance 空不空都不影响：已配对用户重贴带 pin
     * 的配对行时也必须能升级，否则会拿不到任何候选、同步停摆。
     */
    private fun firstPairPinnedOrigin(settings: UserSettings, pin: String?, port: Int): String? {
        val normalized = TlsPinned.normalizePin(pin) ?: return null
        val origin = CardBackAddress.originOf(settings.cardBackUrl) ?: return null
        val uri = runCatching { URI(origin) }.getOrNull() ?: return null
        val host = uri.host?.trim().orEmpty()
        if (!TlsPinned.isIpLiteral(host)) return null
        val tlsPort = port.takeIf { it in 1..65535 } ?: DEFAULT_TLS_PORT
        return "https://$host:$tlsPort"
    }

    /**
     * 有 pin 时 token 只允许走 pin 证明过的 HTTPS 或受信 HTTPS 域名；
     * 任何 http:// 候选（不只是 IP 字面量）都不能再发口令。
     */
    private fun isPlainHttpIpOrigin(origin: String): Boolean =
        origin.startsWith("http://", ignoreCase = true)

    /**
     * 1.4.7：GET /health 问出电脑端版本与能力（60 秒最多一次）。
     *
     * 判旧端**不看版本号**（服务端还是 1.4.2，没升）：只看 `/health` 在不在、能力清单有没有缺。
     * 探测失败不阻塞主流程 —— 真正的同步请求会给出最终结论。
     */
    private suspend fun probeHealth(config: SyncConfig): String? {
        val now = System.currentTimeMillis()
        val firstOrigin = config.origins.firstOrNull().orEmpty()
        // 地址变了（LAN/域名/换电脑）必须立刻重探，否则 60 秒内会拿旧电脑的
        // instance_id 判断新电脑，游标不归零，拉取会静默漏行。
        val originChanged = firstOrigin != lastHealthProbeOrigin
        if (!originChanged && now - lastHealthProbeAt < HEALTH_PROBE_MIN_INTERVAL_MS) {
            return lastVerifiedOrigin
        }
        lastHealthProbeAt = now
        lastHealthProbeOrigin = firstOrigin
        lastVerifiedOrigin = null
        val knownInstance = settingsRepository?.settingsFlow?.first()?.cardBackInstanceId.orEmpty()
        for (origin in config.origins) {
            // 有 pin 时，明文 HTTP+IP 绝不能拿 token；首配也必须先走 pin 绑定的 HTTPS。
            if (config.tlsPin.isNotBlank() && origin.startsWith("http://") && isPlainHttpIpOrigin(origin)) {
                Log.w(TAG, "拒绝有 pin 时的明文 HTTP 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            // 1.4.7.2 W4-B P1-1：私网 http 候选在发 token 前先用 /identity?nonce 验身份。
            // 否则伪 LAN 服务能先拿到 X-Cardback-Token，等发现 instance 不对已经晚了。
            if (knownInstance.isNotBlank() && origin.startsWith("http://") &&
                !LanDiscovery.verifyOrigin(origin, knownInstance, config.token)
            ) {
                Log.w(TAG, "拒绝未通过 identity 校验的候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            var verifiedInstance = knownInstance
            // 首次配对（known 为空）也要证明：pin 已由配对行给出，用 pinned TLS
            // 无 token 请求 /identity?nonce，proof/proof_peer 通过才允许发 token。
            if (TlsPinned.isLanHttps(origin) && config.tlsPin.isNotBlank()) {
                val probed = TlsPinned.verifyIdentity(origin, knownInstance, config.token, config.tlsPin)
                if (probed.isNullOrBlank()) {
                    Log.w(TAG, "拒绝未通过 pinned identity 校验的 LAN HTTPS：$origin")
                    continue
                }
                verifiedInstance = probed
            }
            val originClient = httpClientFor(origin, config.tlsPin, config.isPublic(origin))
            if (originClient == null) {
                Log.w(TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            val outcome = call(
                build = { requestGet(origin + ServerHealth.PATH, config.token) },
                maxAttempts = 1,
                // /health 不参与限流：状态页不应该因为我们自己贴着 429 冷却就看不到电脑端信息。
                respectRateLimit = false,
                // 探测失败不该把状态页从 OK 翻成 RETRYING；只有 404/401 这类「确定结论」才报。
                report = false,
                client = originClient
            )
            if (outcome.code == null) continue // 连不上：换下一个地址
            when (outcome.kind) {
                RetryPolicy.Kind.OK -> {
                    val info = ServerHealth.parse(outcome.body)
                    if (info == null) {
                        Log.w(TAG, "/health 回了 200 但读不懂：${outcome.body?.take(120)}")
                        continue
                    }
                    val instanceId = info.instanceId
                    // 已知/刚由 identity 证明的电脑身份，2xx 也必须对得上；对不上可能
                    // 是伪装服务/换了电脑。换电脑请清空地址或口令重新配对。
                    if (verifiedInstance.isNotBlank() &&
                        (instanceId.isNullOrBlank() || verifiedInstance != instanceId)
                    ) {
                        Log.w(TAG, "拒绝未通过身份校验的 /health：known=$verifiedInstance got=${instanceId ?: "(none)"}")
                        continue
                    }
                    if (instanceId.isNullOrBlank()) {
                        Log.w(TAG, "/health 没给 instance_id，拒绝把 token 继续发给该地址：$origin")
                        continue
                    }
                    rememberSuccess(origin)
                    serverHealth = info
                    lastVerifiedOrigin = origin
                    if (verifiedInstance.isBlank()) {
                        settingsRepository?.updateCardBackInstanceId(instanceId)
                    }
                    SyncStatusStore.reportServer(info.version, info.startedAt, info.capabilities)
                    Log.i(
                        TAG,
                        "/health: ${info.version} protocol=${info.protocol} caps=${info.capabilities}"
                    )
                    return origin
                }
                RetryPolicy.Kind.NOT_FOUND -> {
                    // 线上 8765 现在还是旧进程：这里就是那次「电脑端版本过旧」的现实来源。
                    serverHealth = null
                    publish(SyncPhase.SERVER_TOO_OLD, outcome.code, RetryPolicy.MSG_SERVER_TOO_OLD)
                }
                RetryPolicy.Kind.UNAUTHORIZED -> {
                    publish(SyncPhase.UNAUTHORIZED, outcome.code, outcome.message)
                }
                else -> Unit // 5xx / 超时：静默，别用一个探测失败打扰用户
            }
            // 这个候选不可用/身份不对：继续试域名兜底，不能因为一个伪 LAN 候选就停在 token 发送前。
        }
        return null
    }

    /**
     * 上报一次状态。内容没变就不重复写 —— 4 秒一轮自动同步时同一句话会重复几十次，
     * 状态页的时间戳一直跳会让人以为「它一直在重试」。
     */
    private fun publish(
        phase: SyncPhase,
        httpCode: Int? = null,
        message: String? = null,
        retryAtEpochMs: Long? = null
    ) {
        val key = Triple(phase, httpCode, message)
        if (key == lastReport) {
            if (retryAtEpochMs != null) {
                SyncStatusStore.report(phase, httpCode, message, retryAtEpochMs)
            }
            return
        }
        lastReport = key
        SyncStatusStore.report(phase, httpCode, message, retryAtEpochMs)
    }

    /** 推送失败时给用户的话：401 / 404 本身就是完整建议，别再挂尾巴。 */
    private fun pushFailureText(outcome: HttpOutcome): String {
        val base = outcome.message?.takeIf { it.isNotBlank() } ?: UNREACHABLE
        return when (outcome.kind) {
            RetryPolicy.Kind.UNAUTHORIZED, RetryPolicy.Kind.NOT_FOUND -> base
            else -> "$base，记录先留在本地"
        }
    }

    // ---- 传出去的形状（字段名就是契约，改字段名等于改协议） ----

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

    private data class PushRequest(
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("base_rev") val baseRev: Long,
        @SerializedName("ops") val ops: List<WireOp>
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

    private data class PullPage(
        @SerializedName("server_rev") val serverRev: Long? = null,
        @SerializedName("epoch") val epoch: Long? = null,
        @SerializedName("next_since_rev") val nextSinceRev: Long? = null,
        @SerializedName("rows") val rows: List<WireOp>? = null,
        @SerializedName("more") val more: Boolean? = null
    )

    companion object {
        private const val TAG = "SyncRepository"
        private const val PUSH_PATH = "/sync/push"
        private const val PULL_PATH = "/sync/pull"
        private const val DEFAULT_TLS_PORT = 8443

        /** 一次最多推 100 条（契约上限口径，也免得一批太大被 python 端读超时）。 */
        private const val PUSH_BATCH = 100
        private const val MAX_PULL_PAGES = 20

        /** /health 探测节流：60 秒最多问一次，再怎么点同步也不该多打请求。 */
        private const val HEALTH_PROBE_MIN_INTERVAL_MS = 60_000L
        /** HTTPS 配置地址的 TLS 锚点刷新间隔；pin 轮换时不必等下次冷启动。 */
        private const val ANCHOR_REFRESH_MS = 10 * 60_000L

        /** /health 能力清单里学习记录这条链路真正依赖的两项。 */
        private const val CAP_SYNC_PUSH = "sync_push"
        private const val CAP_SYNC_CURSOR = "sync_cursor"

        /** 断网/重连回调常连着抖几次，30 秒内只补一轮。 */
        private const val AUTO_SYNC_MIN_INTERVAL_MS = 30_000L

        /**
         * 局域网候选的超时：建连 2 秒、整体 8 秒。
         *
         * 同一个 WiFi 下电脑端要么毫秒级回话，要么根本不在线（换网/路由器改 IP/电脑关机）。
         * 2 秒足够覆盖 ARP + TCP 三次握手，再久就是「不在线」了 —— 这时候的价值
         * 全在「赶紧让位给公网域名」，而不是继续在死地址上耗。
         */
        private const val LAN_CONNECT_TIMEOUT_SECONDS = 2L
        private const val LAN_READ_TIMEOUT_SECONDS = 5L
        private const val LAN_CALL_TIMEOUT_SECONDS = 8L

        /**
         * 公网候选的超时：建连 8 秒、读 20 秒、整体 30 秒。
         *
         * 域名后面通常是 Cloudflare Tunnel/中转，握手与外网 RTT 都比局域网慢一个量级，
         * 用 LAN 那套短超时会把它误判成失败 —— 而它恰恰是出了门唯一能用的出口。
         */
        private const val PUBLIC_CONNECT_TIMEOUT_SECONDS = 8L
        private const val PUBLIC_READ_TIMEOUT_SECONDS = 20L
        private const val PUBLIC_CALL_TIMEOUT_SECONDS = 30L

        private const val TYPE_PROGRESS = "progress"

        // 与 RewriteRepository 里那两句提示同措辞：同一个毛病不该有两种说法。
        private const val NO_ADDRESS = "先去「我的」页填电脑地址"
        private const val UNREACHABLE = "电脑没连上"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // op_id 带 bookId：同一个词在几本书里各有进度，一本书一条 op，不能互相盖掉。
        private fun opIdOf(wordId: Long, bookId: Long, lastReviewTime: Long): String =
            "p-$wordId-$bookId-$lastReviewTime"
    }
}

/**
 * 电脑端地址解析。
 *
 * 规则与 RewriteRepository.baseOrigin() 完全一致（那个是 private，本次不动它）：
 * 用户可能填的是完整文件地址 http://ip:8765/cardback.json，这里只留 scheme://host:port，
 * 因为同步接口挂在同一个端口上，路径部分是卡背文件专用的。
 */
internal object SyncEndpoint {
    fun baseOrigin(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = when {
            text.startsWith("http://", ignoreCase = true) -> text
            text.startsWith("https://", ignoreCase = true) -> text
            else -> "http://$text"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val portPart = if (uri.port > 0) ":" + uri.port else ""
        return "${uri.scheme}://$host$portPart"
    }
}

/**
 * 公网兜底的判定规则（1.5.1「学习记录同步也支持局域网优先」）。
 *
 * 学习记录同步现在跟改写链路同一套候选顺序：局域网优先，出不去再落公网域名
 * （`UserSettings.wordbookRemoteUrl`）。这里抽出两件纯判断，好在 JVM 单测里锁死：
 *  - [normalizePublicUrl]：把设置里那个地址收敛成 `https://host[:port]`，空/非法一律 ""；
 *  - [isPublicOrigin]：判断某个候选是不是那个公网域名 —— 它决定该候选走
 *    「2 秒快失败」的局域网超时，还是「8/20/30 秒」的公网超时。
 *
 * 口径复用 [RemoteUrlPolicy.normalizeBase]（改写链路同一个规则，别另造一套），
 * 域名只接受 https：token 要跟着请求发出去，明文 HTTP 域名不可接受。
 */
internal object SyncFallbackPolicy {

    /**
     * 把设置里的公网地址收敛成 `https://host[:port]`。
     *
     * 空、解析不出 host、或者不是 https（包括用户手填的明文 http 域名）都返回 ""：
     * 返回空串就等于「没有公网兜底」，调用方据此不加这个候选。
     */
    fun normalizePublicUrl(raw: String?): String {
        val base = RemoteUrlPolicy.normalizeBase(raw.orEmpty())
        if (base.isEmpty()) return ""
        if (!base.startsWith("https://", ignoreCase = true)) return ""
        return CardBackAddress.originOf(base).orEmpty()
    }

    /**
     * [origin] 是否就是公网域名 [publicUrl]（都已经过 [normalizePublicUrl] 更好）。
     *
     * 按 `scheme://host[:port]` 比，路径与大小写不参与：用户可能把带
     * `/cards/cet6_cards.json` 的完整地址填进去，也可能填大写域名。
     * publicUrl 为空时一律 false —— 没有配置公网就不存在「公网候选」。
     */
    fun isPublicOrigin(origin: String?, publicUrl: String): Boolean {
        if (publicUrl.isBlank()) return false
        val candidate = CardBackAddress.originOf(origin) ?: return false
        val public = CardBackAddress.originOf(publicUrl) ?: return false
        return candidate.equals(public, ignoreCase = true)
    }
}

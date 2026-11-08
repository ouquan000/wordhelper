package com.kaoyan.wordhelper.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.SyncOp
import com.kaoyan.wordhelper.data.entity.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
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

    /**
     * OkHttpClient 自带连接池与线程池，构造一次就该复用；但只在真的要联网时才建
     * （WordRepository 里那个只入队的实例永远用不到它）。
     * 超时与池参数与 RewriteRepository 保持一致，同一个电脑端不能两套脾气。
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
            bookId = bookId
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
                        bookId = row.bookId
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
                lastReviewTime = lrt
            )
        }
        if (deadOpIds.isNotEmpty()) syncDao.deleteByOpIds(deadOpIds)
        if (wireOps.isEmpty()) return Result.success(0)

        val reply = post(
            url = config.origin + PUSH_PATH,
            json = gson.toJson(PushRequest(state.deviceId, state.serverRev, wireOps)),
            token = config.token
        ) ?: return Result.failure(SyncException(UNREACHABLE))

        if (reply.code == 401) return Result.failure(SyncException(CardbackAuth.HINT))
        val pushBody = reply.body
        if (reply.code !in 200..299 || pushBody.isNullOrBlank()) {
            // 队列一条都不动：学习结果先留在手机上，等下次同步。
            return Result.failure(SyncException("电脑没接受同步（HTTP ${reply.code}），记录先留在本地"))
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
        syncDao.putState(
            state.copy(
                serverRev = response.serverRev ?: state.serverRev,
                lastSyncAt = System.currentTimeMillis()
            )
        )
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
            val url = "${config.origin}$PULL_PATH?since_rev=${state.serverRev}"
            val reply = get(url, config.token) ?: return Result.failure(SyncException(UNREACHABLE))

            if (reply.code == 401) return Result.failure(SyncException(CardbackAuth.HINT))
            val pullBody = reply.body
            if (reply.code !in 200..299 || pullBody.isNullOrBlank()) {
                // 游标没动，下次还是从同一处拉，不会漏也不会重复算。
                return Result.failure(SyncException("电脑没给同步数据（HTTP ${reply.code}）"))
            }
            val page = runCatching { gson.fromJson(pullBody, PullPage::class.java) }.getOrNull()
                ?: return Result.failure(SyncException("电脑返回的同步数据读不懂"))

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
                lastReviewTime = lrt
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

    // ---- 一轮完整同步 ----

    /**
     * pull → push → pull。
     * 先拉：对面可能有比本地新的结果；再推：把本地刚学的发出去；最后再拉一次，
     * 把「自己刚推上去的」在别的设备上的结果是同一份，顺手把服务端补的字段收回来。
     */
    suspend fun syncOnce(): Result<String> = syncMutex.withLock {
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
    }

    /** 队列里还有几条没发出去（给界面显示「待同步 N 条」用，不触发网络）。 */
    suspend fun pendingCount(): Int = syncDao.pendingCount()

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

    private suspend fun config(): SyncConfig? {
        // 只读一次 settingsFlow：地址与口令必须来自同一份快照。
        val settings = settingsRepository?.settingsFlow?.first() ?: return null
        val origin = SyncEndpoint.baseOrigin(settings.cardBackUrl) ?: return null
        return SyncConfig(origin = origin, token = settings.cardBackToken.trim())
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

    private data class SyncConfig(val origin: String, val token: String)

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

    private suspend fun get(url: String, token: String): HttpResult? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt < GET_ATTEMPTS) {
            attempt++
            val result = runCatching {
                client.newCall(requestGet(url, token)).execute().use { response ->
                    HttpResult(response.code, response.body?.string())
                }
            }.getOrNull()
            if (result != null) return@withContext result
            // 与 RewriteRepository.httpGet 同样的退避：python 的 HTTPServer 单线程，
            // 偶发连不上多半是上一个请求还没收尾，等 150ms 再来。
            if (attempt < GET_ATTEMPTS) Thread.sleep(RETRY_DELAY_MS)
        }
        null
    }

    private suspend fun post(url: String, json: String, token: String): HttpResult? =
        withContext(Dispatchers.IO) {
            // 只试一次：push 的幂等键在服务端（op_id），重发不会重复记账，
            // 但盲目重试会把「已经到服务端、只是回执丢了」变成一次多余的整批重传。
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Connection", "close")
                    .apply { CardbackAuth.headerValue(token)?.let { header(CardbackAuth.HEADER, it) } }
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(request).execute().use { response ->
                    HttpResult(response.code, response.body?.string())
                }
            }.getOrNull()
        }

    private data class HttpResult(val code: Int, val body: String?)

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
        @SerializedName("bid") val bookId: Long? = null
    )

    /** POST /sync/push 的 ops 元素，也是 GET /sync/pull 的 rows 元素。所有字段可空：pull 的行可能缺字段。 */
    private data class WireOp(
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
        @SerializedName("lrt") val lastReviewTime: Long? = null
    )

    private data class PushRequest(
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("base_rev") val baseRev: Long,
        @SerializedName("ops") val ops: List<WireOp>
    )

    private data class PushResponse(
        @SerializedName("server_rev") val serverRev: Long? = null,
        @SerializedName("acked") val acked: List<String>? = null,
        @SerializedName("rejected") val rejected: List<RejectedOp>? = null
    )

    /** 服务端拒收的行：op_id + 原因（服务端字段名就是 op_id / reason）。 */
    private data class RejectedOp(
        @SerializedName("op_id") val opId: String? = null,
        @SerializedName("reason") val reason: String? = null
    )

    private data class PullPage(
        @SerializedName("server_rev") val serverRev: Long? = null,
        @SerializedName("next_since_rev") val nextSinceRev: Long? = null,
        @SerializedName("rows") val rows: List<WireOp>? = null,
        @SerializedName("more") val more: Boolean? = null
    )

    companion object {
        private const val TAG = "SyncRepository"
        private const val PUSH_PATH = "/sync/push"
        private const val PULL_PATH = "/sync/pull"

        /** 一次最多推 100 条（契约上限口径，也免得一批太大被 python 端读超时）。 */
        private const val PUSH_BATCH = 100
        private const val MAX_PULL_PAGES = 20
        private const val GET_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 150L

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

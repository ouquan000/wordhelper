package com.kaoyan.wordhelper.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.MemoryLockDao
import com.kaoyan.wordhelper.data.dao.RewriteFeedbackDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueBreakdown
import com.kaoyan.wordhelper.data.dao.RewriteSkippedDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteSkippedResult
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.ApproachReview
import com.kaoyan.wordhelper.data.model.ApproachRevision
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.LanDiscovery
import com.kaoyan.wordhelper.data.network.OriginTrustPolicy
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import com.kaoyan.wordhelper.data.network.TlsPinned
import com.kaoyan.wordhelper.util.DateUtils
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 电脑端 `/rewrite/status` 里当前词的一条进度。
 *
 * 服务端目前只给 `state`（pending/running/done/failed），`stage` 是预留字段，
 * 有就用它精确映射阶段，没有就由 ViewModel 按 state 推断。
 */
data class RewriteStatusSnapshot(
    val word: String,
    val state: String,
    val stage: String?,
    val error: String?,
    /** 电脑端那条记录的更新时间，用来区分「这次的结果」和上一次的历史。 */
    val updatedAt: String? = null
)

/** 一次同步的结果，给 ViewModel 决定要不要刷新当前卡片。 */
data class RewriteSyncResult(
    val uploaded: Int = 0,
    /** 这次真正写进本地库（或标记为失败）的单词 id。 */
    val touchedWordIds: List<Long> = emptyList(),
    val failed: Int = 0,
    val error: String? = null
)

/**
 * 「重写记忆思路」的本地队列与电脑端同步（1.4.3）。
 *
 * 手机永不直接调模型：
 *   打标记 → 落 tb_rewrite_queue → 尝试 POST /rewrite/marks
 *   冷启动/回前台 → 补发未发出队列 + GET /rewrite/results?since=lastSeq → 写 tb_word_memory
 *
 * 地址复用「卡背结构地址」的 host:port，只把路径换成 /rewrite 下的接口。
 */
/** 升级前已投递的任务继续使用原 clientId。 */
internal fun rewriteClientId(item: RewriteQueueItem): String =
    item.requestId.ifBlank { "w${item.wordId}-${item.createdAt}" }

/** 老服务按词头合并任务：同词一次只投递一书，其他书的独立任务留在本地。 */
internal fun selectRewriteUploads(
    pending: List<RewriteQueueItem>,
    queued: List<RewriteQueueItem>,
    staleBefore: Long
): List<RewriteQueueItem> {
    fun key(item: RewriteQueueItem) = item.word.trim().lowercase(Locale.ROOT)
    val remoteWords = queued.mapTo(hashSetOf(), ::key)
    return (queued.filter { it.updatedAt < staleBefore } +
        pending.filter { key(it) !in remoteWords }).distinctBy(::key)
}

/** 无 clientId 的旧结果只认唯一全局旧任务，不靠词头猜词书。 */
internal fun matchRewriteResultMark(
    marks: List<RewriteQueueItem>,
    clientId: String?
): RewriteQueueItem? {
    val incoming = clientId?.takeIf { it.isNotBlank() }
    return if (incoming != null) {
        marks.singleOrNull { rewriteClientId(it) == incoming }
    } else {
        marks.singleOrNull()?.takeIf { it.requestId.isBlank() && it.bookId == 0L }
    }
}

/** 同书重写保留欠卡和原学习日；新指令用新的请求键。 */
internal fun buildRewriteRequest(
    existing: RewriteQueueItem?,
    wordId: Long,
    bookId: Long,
    word: String,
    model: String,
    instruction: String,
    now: Long,
    newRequestId: String,
    priority: Int = 0
): RewriteQueueItem {
    val prior = existing?.takeIf { it.wordId == wordId && it.bookId == bookId }
    val trimmed = instruction.trim()
    if (prior != null && prior.status == RewriteStatus.PENDING &&
        prior.instruction == trimmed && prior.model == model &&
        DateUtils.currentLearningDate(prior.createdAt) == DateUtils.currentLearningDate(now)
    ) {
        // 同一天同指令重复入队：保留原行，并保留更高优先级（数值更小者）。
        return prior.copy(updatedAt = now, priority = minOf(prior.priority, priority))
    }
    return RewriteQueueItem(
        id = prior?.id ?: 0,
        wordId = wordId,
        bookId = bookId,
        word = word,
        instruction = trimmed,
        model = model,
        needsReinsert = prior?.needsReinsert == true,
        createdAt = prior?.takeIf { it.needsReinsert }?.createdAt ?: now,
        updatedAt = now,
        requestId = newRequestId,
        priority = priority
    )
}


class RewriteRepository(
    private val settingsRepository: SettingsRepository,
    private val wordDao: WordDao,
    private val wordMemoryDao: WordMemoryDao,
    /** 1.4.5 起卡背思路按书存，读的时候先看这本书有没有自己的版本。 */
    private val bookMemoryDao: BookMemoryDao,
    private val queueDao: RewriteQueueDao,
    /** 1.4.6：卡背 👍/👎 先存本地，再（尽力）推给电脑端。 */
    private val feedbackDao: RewriteFeedbackDao,
    /** 1.4.6：同步时因认领不到/词还没导进来而跳过的结果，落在这里等补拉，不能静默丢。 */
    private val skippedDao: RewriteSkippedDao,
    /** 1.5.3：免洗金牌本地锁；1.5.3v2 起同步时补发 synced=0 的锁。 */
    private val memoryLockDao: MemoryLockDao,
    /** 只为把书名带上去：记录页按书筛，可书只在手机库里。 */
    private val bookDao: BookDao
) {
    private val gson = Gson()
    /** v8：FSRS 记忆信号与自动换招的本地去重/冷却（旁路数据，丢了最多多重报一次）。 */
    private val memorySignalPrefs by lazy {
        settingsRepository.appContext.getSharedPreferences(MEMORY_SIGNAL_PREFS, Context.MODE_PRIVATE)
    }
    private val syncMutex = Mutex()
    private val queueWriteMutex = Mutex()
    private var lastLegacyResultReplay = 0L

    @Volatile
    private var cachedModels: List<RewriteModel>? = null

    /** 电脑端回 401 了：口令不对，别把这种情况混进「电脑没连上」。 */
    @Volatile
    private var authFailed = false

    /**
     * 1.4.6.1：本次进程里最后一次真正连通的地址（scheme://host:port）。
     *
     * 手填地址会变旧 —— 路由器重新分配 IP、换了 Wi-Fi、或者用户填错。有它在，
     * 后续请求先打这个已经证明能通的地址，不用每个请求都先撞一次 4 秒超时。
     */
    @Volatile
    private var activeOrigin: String? = null
    private var endpointRevisionSeen = -1L

    /** 已经写进 DataStore 的 last-good；一次同步要发十几页请求，别每页都写盘。 */
    @Volatile
    private var savedLastGood: String? = null

    /** 1.4.7.2 W4：HTTPS 配置地址的 /identity 锚点最多 10 分钟拉一次。 */
    @Volatile
    private var anchorFetchedAt = 0L

    @Volatile
    private var anchorFetchedKey = ""

    /** 自动补发节流：断网/重连常常连着抖几个回调，不必每次都全量 sync。 */
    @Volatile
    private var lastAutoSyncAt = 0L

    /**
     * 1.4.7：被 429 限流到什么时候（epoch ms）。
     *
     * 服务端的限流只掐 POST /rewrite/ 下的接口（marks、feedback）：单 IP 每分钟 20 次、每天 200 次。
     * 这个值大于现在时，**POST** 在闸内直接短路，连包都不发 —— 1.4.6 那次事故就是
     * App 4 秒一轮把 20 次额度打满，然后 22 次 429 之后越试越糟。
     * GET（/rewrite/status 的 4 秒轮询、/rewrite/results）不占额度，不能被这个闸冻住。
     */
    @Volatile
    private var rateLimitedUntil = 0L

    /** 上次 GET /health 的时间：60 秒最多探一次。 */
    @Volatile
    private var lastHealthProbeAt = 0L

    /**
     * 电脑端 `/health` 的自述（null = 没探到 / 老服务端没有这个接口）。
     *
     * 只用来判能力，**不拿版本号比大小**：服务端版本还停在 1.4.2，比大小会把新端也判成旧的。
     */
    @Volatile
    private var serverHealth: ServerHealth.Info? = null

    /** 上一次上报的状态；同一句话不重复写，免得 4 秒轮询把状态页刷成流水账。 */
    @Volatile
    private var lastReport: Triple<SyncPhase, Int?, String?>? = null

    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** bookId → 书名；一本书查一次数据库就够，词表几千个词也不会每条都查。 */
    private val bookNames = HashMap<Long, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        // 和卡背刷新一样：调用频率低，不池化连接，避开复用已被服务端关掉的连接
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 1.5.1：局域网优先的短超时客户端。
     *
     * 校园网/热点换 IP 后，旧局域网地址常常是「能连上但一直不回」；4 秒连接 + 12 秒读
     * 会把一次点击拖到十几秒。局域网只给 2 秒连接窗口，失败就切公网。
     */
    private val lanClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    /** 1.5.1：公网走系统信任，读超时放宽，避免隧道冷启动被误判成失败。 */
    private val cloudClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        // 1.4.6.1：网络恢复后补一次同步。只跟系统回调走，不轮询；
        // App 被杀回调就没了 —— 不要求后台发送，下次打开时的启动同步会补。
        autoSyncScope.launch {
            settingsRepository.networkAvailableFlow
                .distinctUntilChanged()
                // 第一个值是订阅时读到的「当前状态」：启动同步已经会做，别重复发一次。
                .drop(1)
                .filter { it }
                .collect {
                    // 正在同步：这一轮已经会用到刚恢复的网络，别再排队来一遍。
                    if (syncMutex.isLocked) return@collect
                    val now = System.currentTimeMillis()
                    if (now - lastAutoSyncAt < AUTO_SYNC_MIN_INTERVAL_MS) return@collect
                    lastAutoSyncAt = now
                    runCatching { sync() }
                }
        }
    }

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

    /** 有没有填电脑地址（手填的或上次成功过的都算）；没填就别往队列里塞东西。 */
    suspend fun hasAddress(): Boolean = cardBackEndpoint(rotate = true) != null

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
        priority: Int = 0
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
                        newRequestId = newRewriteRequestId(wordId, bookId),
                        priority = priority
                    )
                )
            }
        }.isSuccess
        if (!saved) return false
        sync()
        val status = queueDao.getForBook(wordId, bookId)?.status
        return status == RewriteStatus.QUEUED || status == RewriteStatus.DONE
    }

    /**
     * v8：把一次真实复习的记忆信号（retained / lapsed）报给电脑端。
     *
     * 纯旁路：连不上、没地址、服务端不认识这个接口都静默吞掉，绝不能影响作答。
     * 同一个 (词, 书) 的 retained 只报一次；lapsed 24 小时内最多一条，避免每轮复习刷屏。
     */
    suspend fun reportMemorySignal(
        wordId: Long,
        bookId: Long,
        word: String,
        method: String,
        event: String,
        stability: Float,
        difficulty: Float,
        lapses: Int
    ) {
        runCatching {
            val prefs = memorySignalPrefs
            val now = System.currentTimeMillis()
            if (event == EVENT_RETAINED) {
                if (prefs.getBoolean(retainedKey(wordId, bookId), false)) return
            } else if (event == EVENT_LAPSED) {
                if (now - prefs.getLong(lapsedKey(wordId, bookId), 0L) < MEMORY_SIGNAL_MIN_INTERVAL_MS) return
            }
            // 服务端按 clientId 幂等：带按天分桶，lapsed 过 24h 冷却后才会新开一条，
            // 同一天内的重试仍然会被吞成同一条，不会刷屏。
            val bucket = now / 86_400_000L
            val payload = gson.toJson(
                mapOf(
                    "clientId" to "fsrs-$wordId-$bookId-$event-$bucket",
                    "word" to word,
                    "bookId" to bookId,
                    "method" to method,
                    "event" to event,
                    "stability" to stability,
                    "difficulty" to difficulty,
                    "lapses" to lapses
                )
            )
            val outcome = send(
                path = "/rewrite/memory-signal",
                payload = payload,
                attempts = 1,
                respectRateLimit = true,
                report = false
            )
            if (outcome.kind == RetryPolicy.Kind.OK) {
                val editor = prefs.edit()
                if (event == EVENT_RETAINED) {
                    editor.putBoolean(retainedKey(wordId, bookId), true)
                } else if (event == EVENT_LAPSED) {
                    editor.putLong(lapsedKey(wordId, bookId), now)
                }
                editor.apply()
            }
        }
    }

    /**
     * v8：多次遗忘 + 难度过高时，自动给这个词重排一版更直白的记忆思路。
     *
     * 已经在队列里 / 24 小时内换过招就跳过；入队失败同样静默。优先级 20 让这条
     * 自动任务排在手点重写之后、普通补写之前（由 DAO 按 priority 升序读取）。
     */
    suspend fun maybeAutoRewriteAfterLapse(word: Word, book: Book, progress: Progress) {
        runCatching {
            val queued = queueDao.getVisibleForBook(word.id, book.id)
            if (queued != null &&
                (queued.status == RewriteStatus.PENDING || queued.status == RewriteStatus.QUEUED)
            ) {
                return
            }
            val prefs = memorySignalPrefs
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(autoKey(word.id, book.id), 0L) < AUTO_REWRITE_COOLDOWN_MS) return
            Log.d(
                TAG,
                "FSRS lapse trigger: word=${word.word} lapses=${progress.fsrsLapses} " +
                    "difficulty=${progress.fsrsDifficulty}"
            )
            val model = runCatching { settingsRepository.rewriteModel() }.getOrDefault("")
            val sent = enqueueAndSync(
                wordId = word.id,
                word = word.word,
                model = model,
                instruction = FSRS_LAPSE_INSTRUCTION,
                bookId = book.id,
                priority = AUTO_REWRITE_PRIORITY
            )
            if (sent) prefs.edit().putLong(autoKey(word.id, book.id), now).apply()
        }
    }

    private fun retainedKey(wordId: Long, bookId: Long) = "retained_${wordId}_${bookId}"

    private fun lapsedKey(wordId: Long, bookId: Long) = "lapsed_${wordId}_${bookId}"

    private fun autoKey(wordId: Long, bookId: Long) = "auto_${wordId}_${bookId}"

    /** 已投递或还欠作答的任务不能通过取消删掉。 */
    suspend fun cancel(wordId: Long, bookId: Long = 0L): Boolean = syncMutex.withLock {
        queueWriteMutex.withLock write@{
            val existing = queueDao.getVisibleForBook(wordId, bookId) ?: return@write false
            if (existing.status != RewriteStatus.PENDING || existing.needsReinsert) return@write false
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

    /** 锁内版本：stopAllRewrites 要「停远端 + 结清本地」整段原子，不能再抢一次锁。 */
    private suspend fun stopRewriteLocked(word: String?): Boolean {
        val payload = if (word.isNullOrBlank()) "{}" else gson.toJson(mapOf("word" to word))
        val outcome = send(
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

    /**
     * 补发未发出的标记 + 增量拉结果。
     * 任何一步失败都只返回 error，不抛异常，调用方静默处理。
     */
    suspend fun sync(): RewriteSyncResult {
        return syncMutex.withLock {
        val address = hasAddress()
        if (!address) return@withLock RewriteSyncResult(error = NO_ADDRESS)
        // 先问一句「你是谁、会什么」：老服务端没有 /health（回 404）就是「电脑端版本过旧」，
        // 这一句比等后面某个接口 404 更早、更准。探测本身失败不阻塞主流程。
        probeHealth()
        var uploaded = 0
        val touched = ArrayList<Long>()
        var failed = 0

        val retryAt = System.currentTimeMillis()
        queueDao.getFailedDebtsBefore(retryAt - FAILED_DEBT_RETRY_MILLIS).forEach { item ->
            queueDao.retryFailedDebt(item.id, newRewriteRequestId(item.wordId, item.bookId), retryAt)
        }
        // 电脑端仍按词头合并任务；等前一书有结果再传下一书，避免远端覆盖。
        // 半小时无回音时优先重投原请求，不能让候补任务抢先覆盖它。
        val staleBefore = System.currentTimeMillis() - STALE_QUEUED_MILLIS
        val open = queueDao.getOpen()
        val pending = if (ServerHealth.supports(serverHealth, CAP_REWRITE_SCOPED) == true) {
            open.filter { it.status == RewriteStatus.PENDING || it.updatedAt < staleBefore }
        } else {
            selectRewriteUploads(
                open.filter { it.status == RewriteStatus.PENDING },
                open.filter { it.status == RewriteStatus.QUEUED },
                staleBefore
            )
        }
        if (pending.isNotEmpty()) {
            val result = upload(pending)
            val accepted = result.accepted
            if (accepted == null) {
                // 1.4.7：不再一律说「电脑没连上」—— 404 要说「电脑端版本过旧」，401 要说口令。
                return@withLock RewriteSyncResult(error = result.error ?: UNREACHABLE)
            }
            val now = System.currentTimeMillis()
            pending.forEach { item ->
                if (accepted.contains(rewriteClientId(item))) {
                    queueDao.updateStatus(item.wordId, item.bookId, RewriteStatus.QUEUED, null, now)
                    uploaded += 1
                }
            }
        }

        // 1.4.6：攒下来的 👍/👎 顺手补发；它坏了不该把整轮判死。
        // 1.4.7：但也不能静默 —— 404（电脑端没有 /rewrite/feedback）之类会带一句话进本次结果，
        // 状态页据此显示「电脑端版本过旧」，而不是每次同步都悄悄丢一批反馈。
        var feedbackIssue: String? = null
        runCatching { pushFeedback(feedbackDao.getUnsynced(FEEDBACK_BATCH)) }
            .getOrNull()?.let { feedbackIssue = it }

        // 1.5.3v2 C5：离线点的「免洗金牌」补发。锁先落本地 synced=0，这里逐条推给电脑端，
        // 成功才置 synced=1；失败留给下一次 sync 再试，绝不丢锁、也不阻塞主流程。
        runCatching {
            memoryLockDao.unsynced().forEach { lock ->
                val ok = runCatching { setMemoryLock(lock.wordId, lock.bookId, lock.locked) }
                    .getOrDefault(false)
                if (ok) {
                    memoryLockDao.markSynced(lock.id)
                }
            }
        }

        var cursor = settingsRepository.rewriteResultCursor()
        // 服务端在“写结果”和“抬 highWater”之间存在一瞬窗口：游标可能已经停在
        // 最新 seq，但对应结果还没落盘，下一次 since=最新 seq 就再也拉不到它。
        // 本地还有未结清的排队行时，第一页故意回看 1 条，补上这个边界。
        val replayFrom = if (queueDao.getOpen().isNotEmpty() && cursor.seq > 0) {
            cursor.seq - 1
        } else {
            cursor.seq
        }
        var firstPage = true
        var seqRewound = false
        var skippedChecked = false
        val legacyRewoundSeq = settingsRepository.rewriteLegacyRewoundSeq()
        val skippedAttempts = HashMap<Long, Int>()
        repeat(MAX_PAGES) {
            val since = if (firstPage) replayFrom else cursor.seq
            firstPage = false
            val outcome = send("/rewrite/results?since=$since&limit=50")
            if (outcome.kind != RetryPolicy.Kind.OK) {
                // 404 → 版本过旧；429 → 限流（带剩余秒数）；连不上 → 电脑没连上。
                return@withLock RewriteSyncResult(
                    uploaded, touched, failed, outcome.message ?: UNREACHABLE
                )
            }
            val body = outcome.body
            if (body.isNullOrBlank()) {
                return@withLock RewriteSyncResult(uploaded, touched, failed, "电脑返回的结果是空的")
            }
            val page = runCatching { gson.fromJson(body, ResultsPage::class.java) }.getOrNull()
                ?: return@withLock RewriteSyncResult(uploaded, touched, failed, "电脑返回的结果读不懂")
            val binding = cursor.bind(page.streamId, page.highWater, page.nextSince)
            val now = System.currentTimeMillis()
            val legacyReplay = !seqRewound && page.streamId.isNullOrBlank() &&
                since > 0 && page.nextSince == since && page.results.isNullOrEmpty() &&
                now - lastLegacyResultReplay >= LEGACY_REPLAY_INTERVAL_MS &&
                since != legacyRewoundSeq
            if (binding.rewind && seqRewound) {
                return@withLock RewriteSyncResult(uploaded, touched, failed, "重写结果流正在切换，请稍后重试")
            }
            if (binding.replaceSkipped) {
                // 旧流的 seq 可与新结果重号；只清补拉缓存，待发任务与用户卡片不动。
                skippedDao.clearForReplacedStream()
                skippedAttempts.clear()
                skippedChecked = false
            }
            val nextCursor = if (legacyReplay) binding.cursor.copy(seq = 0L) else binding.cursor
            if (nextCursor != cursor) {
                cursor = nextCursor
                settingsRepository.updateRewriteResultCursor(cursor)
            }
            if (binding.rewind || legacyReplay) {
                if (legacyReplay) {
                    lastLegacyResultReplay = now
                    settingsRepository.updateRewriteLegacyRewoundSeq(since)
                }
                seqRewound = true
                return@repeat
            }
            if (!skippedChecked) {
                // 先核对结果流，再补上轮未认领的行。
                retrySkipped()
                skippedDao.getPending(SKIPPED_RETRY_LIMIT, RewriteSkippedResult.MAX_ATTEMPTS).forEach {
                    skippedAttempts[it.seq] = it.attempts
                }
                skippedChecked = true
            }
            // 刚装好/刚清数据时，种子还没导完，词表里查不到这个词 —— 这条结果先别丢：
            // 不推进 since，下次同步还会再拉一遍。否则「换机恢复」会静默地一条都恢复不了。
            // 词表已经导完还查不到，说明这个词确实不在库里（删词/换词表/脏数据），
            // 那就跳过它继续往后走，不能让它把后面所有结果永远堵住。
            var unknownWord = false
            val skipped = ArrayList<Pair<RewriteResultDto, String>>()
            page.results.orEmpty().forEach { dto ->
                val wordId = applyResult(dto)
                if (wordId != null) {
                    touched.add(wordId)
                    if (dto.status == STATUS_FAILED) failed += 1
                } else {
                    val key = dto.word?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    if (key.isNotEmpty() && wordDao.getWordIdByKey(key) == null) unknownWord = true
                    // 1.4.6：跳过不等于丢掉 —— 记住这一行，下次同步先补它。
                    if (dto.seq != null) skipped.add(dto to skipReasonOf(dto))
                }
            }
            if (unknownWord && wordDao.countAll() < SEED_READY_MIN_WORDS) {
                // 种子还没导完：游标不推进，整页下次重来。这里不能落「跳过」，
                // 否则同一行每拉一遍就烧一次 attempts，种子一到就被当成废行。
                return@withLock RewriteSyncResult(uploaded, touched, failed, null)
            }
            skipped.forEach { (dto, reason) ->
                val seq = dto.seq ?: return@forEach
                val attempts = (skippedAttempts[seq] ?: 0) + 1
                skippedAttempts[seq] = attempts
                skippedDao.upsert(
                    RewriteSkippedResult(
                        seq = seq,
                        word = dto.word?.trim().orEmpty(),
                        payload = gson.toJson(dto),
                        reason = reason,
                        attempts = attempts,
                        lastTryAt = System.currentTimeMillis()
                    )
                )
            }
            val next = page.nextSince ?: since
            if (next > since) {
                cursor = cursor.copy(seq = next)
                settingsRepository.updateRewriteResultCursor(cursor)
            }
            if (page.more != true) {
                return@withLock RewriteSyncResult(uploaded, touched, failed, feedbackIssue)
            }
        }
        RewriteSyncResult(uploaded, touched, failed, feedbackIssue)
        }
    }


    /**
     * 补拉上次同步里被跳过的结果（1.4.6）。
     *
     * 游标已经走过这些 seq，不补就是永久丢。只有「应用成功 / payload 读不懂」才从待办里删；
     * 否则 attempts+1，等下次再试 —— 到顶的行不再被 getPending 取到，但留在表里当证据。
     * 种子还没导完时直接返回：这类跳过本来就是「词表没准备好」，补也是白烧次数。
     */
    private suspend fun retrySkipped() {
        if (wordDao.countAll() < SEED_READY_MIN_WORDS) return
        val rows = skippedDao.getPending(SKIPPED_RETRY_LIMIT, RewriteSkippedResult.MAX_ATTEMPTS)
        if (rows.isEmpty()) return
        rows.forEach { row ->
            val dto = runCatching { gson.fromJson(row.payload, RewriteResultDto::class.java) }
                .getOrNull()
            if (dto == null) {
                skippedDao.delete(row.seq)
                return@forEach
            }
            if (applyResult(dto) != null) {
                skippedDao.delete(row.seq)
                return@forEach
            }
            skippedDao.upsert(
                row.copy(attempts = row.attempts + 1, lastTryAt = System.currentTimeMillis())
            )
        }
    }

    /** 给跳过行留个能查的原因（不是业务分支，只是可读性）。 */
    private suspend fun skipReasonOf(dto: RewriteResultDto): String {
        val key = dto.word?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (key.isEmpty()) return SKIP_NO_WORD
        val wordId = wordDao.getWordIdByKey(key) ?: return SKIP_WORD_MISSING
        if (queueDao.getAllForWord(wordId).isEmpty()) return SKIP_NO_MARK
        if (dto.approach.isNullOrEmpty()) return SKIP_EMPTY_APPROACH
        return SKIP_CLIENT_MISMATCH
    }

    /** 卡片上的 👍/👎（1.4.6）：当前词在这本书里的反馈，用来把按钮显示成已选状态。 */
    fun observeFeedback(wordId: Long, bookId: Long): Flow<List<RewriteFeedback>> =
        feedbackDao.observeForWord(wordId, bookId)

    /**
     * 1.5.3：把「免洗金牌」同步给电脑端，作为闲时洗词的绝对保护。
     * 本地锁定表先落库；这里只是尽力同步，失败不阻塞用户。
     */
    suspend fun setMemoryLock(wordId: Long, bookId: Long, locked: Boolean): Boolean {
        val payload = gson.toJson(
            mapOf(
                "wordId" to wordId,
                "bookId" to bookId,
                "locked" to locked
            )
        )
        val outcome = send("/rewrite/lock", payload, attempts = 2, rotate = true)
        return outcome.kind == RetryPolicy.Kind.OK
    }

    /** 1.5.2：当前这一版思路的累计赞数，用来显示「👍 5」。 */
    fun observeLikeCount(wordId: Long, bookId: Long, approachRev: Int): Flow<Int> =
        feedbackDao.observeLikeCount(wordId, bookId, approachRev.coerceAtLeast(0))

    /** 只取「当前这一版思路」上的态度；换版后返回 null，允许重新评价。 */
    fun observeFeedbackRating(wordId: Long, bookId: Long, approachRev: Int): Flow<String?> =
        feedbackDao.observeRatingForRevision(wordId, bookId, approachRev)

    /**
     * 记一条 👍/👎。先落本地（离线也不丢），再尽力推一次；推不出去的留给 sync 补发。
     * 同一条（词/书/评价/版本）重复点不会重复入库，也不会重复打扰电脑端。
     * 永远返回 true（除非 rating 非法）—— 反馈是旁路，不能因为网络把 UI 卡住。
     */
    suspend fun submitFeedback(
        wordId: Long,
        rating: String,
        word: String,
        approach: String,
        method: String,
        model: String,
        instruction: String,
        bookId: Long = 0L,
        approachRev: Int = 0,
        /** 1.5.2 点赞++：本次合并进来的连击次数（1..50）。 */
        count: Int = 1,
        /** 1.5.3：点踩原因（谐音不准/词根太生僻…），可空。 */
        reasons: List<String> = emptyList(),
        /** 1.5.3：点踩吐槽，可空。 */
        comment: String = ""
    ): Boolean {
        if (!RewriteFeedbackRating.isValid(rating)) return false
        val safeRev = approachRev.coerceAtLeast(0)
        val safeCount = count.coerceIn(1, MAX_FEEDBACK_BURST)
        val now = System.currentTimeMillis()
        val safeReasons = reasons.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_FEEDBACK_REASONS)
        val reasonsText = safeReasons.joinToString(REASON_SEPARATOR).take(MAX_FEEDBACK_CHARS)
        val commentText = comment.trim().take(MAX_FEEDBACK_CHARS)

        // 👍 是累加：同一版思路只保留一行，连击加进 count。
        // 👎 1.5.3v2 起同样累加连踩次数（1..50，只记差评不重写），重复点不重复入库。
        if (rating == RewriteFeedbackRating.UP) {
            val existing = feedbackDao.find(wordId, bookId, rating, safeRev)
            if (existing != null) {
                // 累计值封顶 50，避免历史累计超过后端单条 1..50 的校验。
                val nextCount = (existing.count + safeCount).coerceAtMost(MAX_FEEDBACK_BURST)
                val added = nextCount - existing.count
                if (added > 0) {
                    feedbackDao.addCount(existing.id, added, now)
                    runCatching { pushFeedback(feedbackDao.getUnsynced(FEEDBACK_BATCH)) }
                }
                return true
            }
        } else {
            val existing = feedbackDao.find(wordId, bookId, rating, safeRev)
            if (existing != null) {
                val nextCount = (existing.count + safeCount).coerceAtMost(MAX_FEEDBACK_BURST)
                val added = nextCount - existing.count
                if (added > 0) {
                    feedbackDao.addCount(existing.id, added, now)
                }
                feedbackDao.updateReasons(existing.id, reasonsText, commentText, now)
                runCatching { pushFeedback(feedbackDao.getUnsynced(FEEDBACK_BATCH)) }
                return true
            }
        }

        val inserted = feedbackDao.insert(
            RewriteFeedback(
                wordId = wordId,
                bookId = bookId,
                word = word,
                rating = rating,
                approach = approach.take(MAX_FEEDBACK_CHARS),
                method = method,
                model = model,
                instruction = instruction.take(MAX_FEEDBACK_CHARS),
                approachRev = safeRev,
                createdAt = now,
                count = safeCount,
                reasons = reasonsText,
                comment = commentText
            )
        )
        // -1 = 唯一索引挡下了（之前已经表过态），不用再发一次。
        if (inserted <= 0L) return true
        runCatching { pushFeedback(feedbackDao.getUnsynced(FEEDBACK_BATCH)) }
        return true
    }

    /**
     * 把没发出去的反馈推给电脑端。
     *
     * 返回 null = 成功（或本来就没东西要发）；非 null = 给用户看的一句失败原因。
     * 服务端按 clientId 幂等去重，所以整批重推是安全的；但 429 之后不会再硬怼，
     * 而是等冷却窗口（见 [rateLimitedUntil]），这期间同一批留在本地等下一轮。
     */
    private suspend fun pushFeedback(items: List<RewriteFeedback>): String? {
        if (items.isEmpty()) return null
        // /health 明说「这台电脑没有 feedback 能力」：请求干脆不发（1.4.6 的 186 次 404 就是这么来的）。
        if (ServerHealth.supports(serverHealth, CAP_FEEDBACK) == false) {
            val message = RetryPolicy.missingCapabilityMessage(CAP_FEEDBACK)
            publish(SyncPhase.SERVER_TOO_OLD, null, message)
            return message
        }
        val payload = FeedbackPayload(
            items = items.map { item ->
                FeedbackDto(
                    clientId = feedbackClientId(item.wordId, item.bookId, item.rating, item.approachRev),
                    wordId = item.wordId,
                    bookId = item.bookId,
                    word = item.word,
                    rating = item.rating,
                    approach = item.approach,
                    method = item.method,
                    model = item.model,
                    instruction = item.instruction,
                    approachRev = item.approachRev,
                    count = item.count,
                    reasons = item.reasons.split(REASON_SEPARATOR).filter { it.isNotBlank() },
                    comment = item.comment,
                    createdAt = isoTime(item.createdAt)
                )
            }
        )
        val outcome = send(
            path = "/rewrite/feedback",
            payload = gson.toJson(payload),
            attempts = POST_ATTEMPTS,
            respectRateLimit = true
        )
        if (outcome.kind != RetryPolicy.Kind.OK) return outcome.message ?: UNREACHABLE
        val response = runCatching { gson.fromJson(outcome.body, FeedbackPage::class.java) }
            .getOrNull() ?: return "电脑返回的回执读不懂，反馈先留在本地"
        if (response.ok != true) return "电脑没接受这批判注，反馈先留在本地"
        feedbackDao.markSynced(items.map { it.id })
        return null
    }

    /** 电脑端模型列表；连不上就用内置 5 个兜底。成功一次后缓存到进程结束。 */
    suspend fun models(): List<RewriteModel> {
        cachedModels?.let { return it }
        // 后台小请求：只问一次、失败静默（内置兜底模型永远可用，不该为它弹任何提示）。
        val outcome = send("/models", attempts = 1, report = false)
        if (outcome.kind != RetryPolicy.Kind.OK) return RewriteModels.DEFAULT
        val body = outcome.body
        if (body.isNullOrBlank()) return RewriteModels.DEFAULT
        val parsed = runCatching { gson.fromJson(body, ModelsPage::class.java) }.getOrNull()
        val list = parsed?.models.orEmpty().mapNotNull { dto ->
            val id = dto.id ?: return@mapNotNull null
            RewriteModel(
                id = id,
                name = dto.name?.takeIf { it.isNotBlank() } ?: id,
                provider = dto.provider.orEmpty(),
                reasoning = dto.reasoning.orEmpty()
            )
        }
        if (list.isNotEmpty()) cachedModels = list
        return list.ifEmpty { RewriteModels.DEFAULT }
    }

    /**
     * 当前词在电脑端的进度；连不上/回包读不懂返回 null。
     *
     * 只发一次请求：这个方法被 4 秒一次的轮询调用，失败时重试三次会把一个轮询周期拖到几十秒，
     * 用户看到的进度反而更卡。断网就静默返回 null，界面上显示「等电脑上线」。
     */
    suspend fun statusOf(mark: RewriteQueueItem): RewriteStatusSnapshot? {
        val word = mark.word
        // /health 已经说过「这台电脑没有 rewrite 能力」：别再每 4 秒撞一次 404。
        if (ServerHealth.supports(serverHealth, CAP_REWRITE) == false) return null
        val outcome = send("/rewrite/status", attempts = 1, rotate = false, report = false)
        if (outcome.kind != RetryPolicy.Kind.OK) return null
        val body = outcome.body
        if (body.isNullOrBlank()) return null
        val page = runCatching { gson.fromJson(body, StatusPage::class.java) }.getOrNull() ?: return null
        val key = word.trim().lowercase(Locale.ROOT)
        val forWord = page.items.orEmpty().filter { it.word?.trim()?.lowercase(Locale.ROOT) == key }
        val exact = forWord.filter { it.clientId == rewriteClientId(mark) }
        val matches = exact.ifEmpty {
            // 老服务器没有 clientId；只在本地确认这是唯一已发送任务时展示推测进度。
            if (mark.status == RewriteStatus.QUEUED &&
                queueDao.getAllForWord(mark.wordId).count { it.status == RewriteStatus.QUEUED } == 1
            ) forWord.filter { it.clientId.isNullOrBlank() } else emptyList()
        }.filterNot { item ->
            // 服务端留着同一词的历史：上一条 done/failed 可能比这次标记还早。
            // 不滤掉的话，刚点完重写第一轮就会读到旧的 100%，进度条先冲满再退回来。
            val terminal = item.state == "done" || item.state == STATUS_FAILED
            terminal && mark.createdAt > 0L &&
                (parseServerMillis(item.updatedAt) ?: Long.MAX_VALUE) +
                TERMINAL_STALE_TOLERANCE_MS < mark.createdAt
        }
        // 同一个词可能有多条历史（上一条 done/failed + 这次 running），正在跑的优先
        val item = matches.firstOrNull { it.state == "pending" || it.state == "running" }
            ?: matches.firstOrNull()
            ?: return RewriteStatusSnapshot(word = word, state = STATE_MISSING, stage = null, error = null)
        return RewriteStatusSnapshot(
            word = item.word.orEmpty(),
            state = item.state.orEmpty(),
            stage = item.stage?.takeIf { it.isNotBlank() },
            error = item.error?.takeIf { it.isNotBlank() },
            updatedAt = item.updatedAt
        )
    }

    /** 电脑端时间形如 2026-11-07T21:57:19+08:00；解析不了就当没有。 */
    private fun parseServerMillis(raw: String?): Long? {
        val text = raw ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    // ---- 内部 ----

    private suspend fun applyResult(dto: RewriteResultDto): Long? {
        val word = dto.word?.trim().orEmpty()
        if (word.isEmpty()) return null
        val wordId = wordDao.getWordIdByKey(word.lowercase(Locale.ROOT)) ?: return null
        val now = System.currentTimeMillis()

        // 认领守卫：只认「本地还有这条标记」的结果。
        // - 没有队列行 = 已撤销 / 已应用完 / 换机清过队列：一律丢弃。
        //   旧逻辑在这里接受合法 clientId，然后把 bookId 当 0 写进全局那份，
        //   结果就是「撤销后迟到的结果穿透到主书」（1.4.5 验收 P0）。
        // - 有队列行：精确匹配 clientId，不靠词头猜词书。
        val allMarks = queueDao.getAllForWord(wordId)
        val mark = matchRewriteResultMark(allMarks, dto.clientId) ?: return null
        // 1.5.3v2 C5：worker 闲时洗词的结果不能覆盖用户锁定的「免洗金牌」。
        // 本地锁是用户的最终决定，哪怕后端在锁同步到之前已经开洗，结果落地时也直接丢弃
        // （仍然返回 wordId 认领结果、推进游标，只是不写卡背、不改状态）。
        if (shouldSkipWorkerResult(dto.source, memoryLockDao.isLocked(wordId, mark.bookId))) {
            return wordId
        }
        // 已完成的请求被回放，不覆盖当前卡背，也不让迟到的失败倒退状态。
        if (mark.status == RewriteStatus.DONE) return wordId
        if (mark.status == RewriteStatus.FAILED && mark.error == "用户停止") return null

        if (dto.status == STATUS_FAILED || (mark.needsReinsert && dto.unverified == true)) {
            queueDao.updateStatus(
                wordId,
                mark.bookId,
                RewriteStatus.FAILED,
                dto.error?.takeIf { it.isNotBlank() } ?: "电脑端没生成出合格思路",
                now
            )
            return wordId
        }

        val approach = dto.approach.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (approach.isEmpty()) return null

        val existing = wordMemoryDao.getByWordId(wordId)
        val prev = dto.prev.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { existing?.approach.orEmpty() }

        val method = dto.method.orEmpty().trim().lowercase()
            .takeIf { it.isNotEmpty() }
            ?: existing?.method.orEmpty()

        // 1.4.7.1：这版思路是不是没复核。老服务端不带字段 → dto.unverified == null → false，
        // 行为与升级前一模一样，不会凭空冒出角标。原因只在「确实未复核」时才有意义。
        val unverified = dto.unverified == true
        val unverifiedReason = if (unverified) {
            dto.reason?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        // 1.4.5：这条标记是在哪本书里点的，就只改哪本书的卡背。
        // bookId=0 是老队列/换机恢复，沿用旧行为写全局那份。
        val bookId = mark.bookId
        if (bookId > 0L) {
            val own = bookMemoryDao.getForBook(wordId, bookId)
            // 1.4.6 幂等：同一行结果被重拉一遍（游标没推进 / 服务端重发）时，
            // 不能把「上一版」覆盖成刚写进去的这版 —— 那样撤销只会退到一模一样的文字。
            // 1.4.7.1：文字没变也要把未复核标记刷新一下，否则先到的旧结果会让角标一直不出现。
            if (own?.approach == approach) {
                if (own != null && (own.unverified != unverified ||
                        own.unverifiedReason != unverifiedReason)
                ) {
                    bookMemoryDao.upsert(
                        own.copy(unverified = unverified, unverifiedReason = unverifiedReason)
                    )
                }
                queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
                return wordId
            }
            // 这一版之前生效的方法标签：撤销时跟思路一起退回去。
            val previousMethod = own?.method?.takeIf { it.isNotBlank() }
                ?: existing?.method?.takeIf { it.isNotBlank() }
            bookMemoryDao.upsert(
                BookMemory(
                    wordId = wordId,
                    bookId = bookId,
                    approach = approach,
                    collocations = own?.collocations?.takeIf { it.isNotBlank() }
                        ?: existing?.collocations.orEmpty(),
                    method = method,
                    updatedAt = now,
                    source = WordMemory.SOURCE_USER,
                    rev = dto.rev ?: ((own?.rev ?: 0) + 1),
                    prevApproach = (own?.approach?.takeIf { it.isNotBlank() } ?: prev)
                        .takeIf { it.isNotBlank() },
                    prevMethod = previousMethod,
                    unverified = unverified,
                    unverifiedReason = unverifiedReason
                )
            )
            queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
            return wordId
        }

        if (existing?.approach == approach) {
            if (existing != null && (existing.unverified != unverified ||
                    existing.unverifiedReason != unverifiedReason)
            ) {
                wordMemoryDao.upsert(
                    existing.copy(unverified = unverified, unverifiedReason = unverifiedReason)
                )
            }
            queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
            return wordId
        }
        wordMemoryDao.upsert(
            WordMemory(
                wordId = wordId,
                approach = approach,
                collocations = existing?.collocations.orEmpty(),
                updatedAt = now,
                source = WordMemory.SOURCE_USER,
                rev = dto.rev ?: ((existing?.rev ?: 0) + 1),
                prevApproach = prev.takeIf { it.isNotBlank() },
                // 重写出来的是「哪一招」也一起换掉；电脑端没给就保留原标签，别把卡背标签弄空。
                method = method,
                prevMethod = existing?.method?.takeIf { it.isNotBlank() },
                unverified = unverified,
                unverifiedReason = unverifiedReason
            )
        )
        queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
        return wordId
    }

    /** 推送标记的结果：成功给出「电脑端收下了哪些 clientId」，失败给一句能直接给用户看的话。 */
    private suspend fun upload(items: List<RewriteQueueItem>): UploadResult {
        if (ServerHealth.supports(serverHealth, CAP_REWRITE) == false) {
            val message = RetryPolicy.missingCapabilityMessage(CAP_REWRITE)
            publish(SyncPhase.SERVER_TOO_OLD, null, message)
            return UploadResult(null, message)
        }
        // 词头要给全：只有单词的话，模型分不清是哪个词义、什么词性，重生质量会明显掉。
        val marks = items.map { item ->
            val word = wordDao.getWordById(item.wordId)
            val memory = wordMemoryDao.getByWordId(item.wordId)
            // 按书重写时，给模型看的「旧思路」和记录页显示的书名都必须是这本书的；
            // 词本身只记得自己出生的那本书，不能拿 word.bookId 冒充当前书。
            val scopedMemory = if (item.bookId > 0L) {
                bookMemoryDao.getForBook(item.wordId, item.bookId)
            } else {
                null
            }
            MarkDto(
                clientId = rewriteClientId(item),
                bookId = item.bookId.takeIf { it > 0L },
                word = item.word,
                phonetic = word?.phonetic.orEmpty(),
                pos = posOf(word?.meaning.orEmpty()),
                meaning = word?.meaning.orEmpty(),
                model = item.model,
                instruction = item.instruction,
                book = bookNameOf(if (item.bookId > 0L) item.bookId else (word?.bookId ?: 0L)),
                markedAt = isoTime(item.updatedAt),
                currentApproach = (scopedMemory?.approach ?: memory?.approach).orEmpty()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { null }
            )
        }
        val payload = MarksPayload(marks = marks)
        val outcome = send(
            path = "/rewrite/marks",
            payload = gson.toJson(payload),
            attempts = POST_ATTEMPTS,
            respectRateLimit = true
        )
        if (outcome.kind != RetryPolicy.Kind.OK) {
            return UploadResult(null, outcome.message ?: UNREACHABLE)
        }
        val response = runCatching { gson.fromJson(outcome.body, MarksPage::class.java) }.getOrNull()
            ?: return UploadResult(null, "电脑返回的回执读不懂，标记先留在本地")
        return UploadResult(response.accepted.orEmpty().toSet(), null)
    }

    private fun newRewriteRequestId(wordId: Long, bookId: Long): String =
        "w$wordId-b$bookId-${UUID.randomUUID()}"

    /** 反馈的幂等键：同一本书、同一个词、同一版思路、同一个态度只产生一行。 */
    private fun feedbackClientId(
        wordId: Long,
        bookId: Long,
        rating: String,
        approachRev: Int
    ): String = "f$wordId-$bookId-$rating-r$approachRev"

    /** 书名（空 = 查不到/没绑书）；电脑端只转存这个字符串给记录页筛选用。 */
    private suspend fun bookNameOf(bookId: Long): String {
        if (bookId <= 0L) return ""
        bookNames[bookId]?.let { return it }
        val name = runCatching { bookDao.getBookById(bookId)?.name }.getOrNull().orEmpty()
        bookNames[bookId] = name
        return name
    }

    /** 释义里自带词性前缀（"adj. 恰当的…"），抽出来当词性，别把整条释义都塞给模型。 */
    private fun posOf(meaning: String): String {
        val m = POS_PREFIX.find(meaning.trim()) ?: return ""
        return m.groupValues[1]
    }

    private fun isoTime(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(millis))
    }

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

    /** upload() 的结果：要么给回「电脑端收下了哪些 clientId」，要么给一句失败原因。 */
    private data class UploadResult(val accepted: Set<String>?, val error: String?)

    /**
     * 所有联网请求的唯一出口（与 SyncRepository.call 同一套规则；两个仓库各自持有自己的限流闸）。
     *
     * - 2xx 直接成功；401 / 404 / 429 / 其他 4xx 一律不重试（重试也变不好，只会把限流额度打满）；
     * - 只有 5xx / 408 / 超时 / IO 才按指数退避就地重试，最多 [maxAttempts] 次；
     * - 429 把冷却写进 [rateLimitedUntil]，之后 **respectRateLimit = true** 的请求在闸内直接短路，
     *   连包都不发；
     * - 协程取消原样抛出，不记成失败、不重试。
     */
    private suspend fun call(
        build: () -> Request,
        maxAttempts: Int = RetryPolicy.DEFAULT_MAX_ATTEMPTS,
        respectRateLimit: Boolean = false,
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
                            // 调用方只能拿到「没成功」，分不清「电脑没开」和「口令错」——
                            // 这个标记就是给 consumeAuthFailure() 用的（界面据此显示粘连接串）。
                            authFailed = true
                            val message = "HTTP 401：${CardbackAuth.HINT}"
                            if (report) publish(SyncPhase.UNAUTHORIZED, code, message)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.UNAUTHORIZED, code, body, message = message
                            )
                        }
                        RetryPolicy.Kind.NOT_FOUND -> {
                            // 旧服务端没有这个接口（1.4.6 的 /rewrite/feedback 就是这么 404 的 186 次）：
                            // 不重试、不静默，直接说「电脑端版本过旧」。
                            if (report) publish(SyncPhase.SERVER_TOO_OLD, code, RetryPolicy.MSG_SERVER_TOO_OLD)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.NOT_FOUND, code, body,
                                message = RetryPolicy.MSG_SERVER_TOO_OLD
                            )
                        }
                        RetryPolicy.Kind.RATE_LIMITED -> {
                            // 服务端 60 秒 20 次 / 每天 200 次，超了就是这个分支。
                            // 不就地重试：算好冷却时间、记进闸里，这一轮到此为止。
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
                            // 5xx / 408：交给循环末尾的退避；有 Retry-After 就听它的。
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
            // 可取消的等待（协程取消时立刻退出），替代原来的 Thread.sleep。
            delay(RetryPolicy.delayFor(RetryPolicy.Kind.RETRYABLE, attempt, lastRetryAfter))
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        HttpOutcome(RetryPolicy.Kind.RETRYABLE, lastCode, null)
    }

    /**
     * 按候选顺序发一次请求（1.4.6.1 的地址轮试保留）：当前地址 → 手填地址 → 上次成功地址，
     * 前一个连不上才试下一个，成功后把这个地址记成「上次成功」。
     *
     * 谁先「回话」（拿到任何 HTTP 状态码）就用谁 —— 偶发连不上多半是 Python 的 HTTPServer
     * 单线程、上一个请求还没收尾，不应该因为一个地址慢就把整轮同步拖成几个连接超时。
     * `rotate=false` 只打最可信的那一个（4 秒一次的进度轮询不能拖成两个连接超时）。
     */
    private suspend fun send(
        path: String,
        payload: String? = null,
        attempts: Int = RetryPolicy.DEFAULT_MAX_ATTEMPTS,
        rotate: Boolean = true,
        respectRateLimit: Boolean = false,
        report: Boolean = true
    ): HttpOutcome {
        val endpoint = cardBackEndpoint(rotate)
            ?: return HttpOutcome(RetryPolicy.Kind.FATAL, null, null, message = NO_ADDRESS)
        val knownInstance = knownInstanceId()
        var last: HttpOutcome? = null
        for (origin in endpoint.origins) {
            // 已配对过：私网 http 候选先做无口令 /identity?nonce 校验，
            // 验不过就不把口令发给它（防同 WiFi 伪造地址/中继）。
            if (!originAllowed(origin, knownInstance, endpoint.token)) continue
            val originClient = httpClientFor(origin, endpoint.tlsPin)
            if (originClient == null) {
                Log.w(TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            if (!httpsLanIdentityOk(origin, knownInstance, endpoint.token, endpoint.tlsPin)) continue
            val outcome = call(
                build = {
                    val builder = Request.Builder()
                        .url(origin + path)
                        .header("Connection", "close")
                    val bodyText = payload
                    if (bodyText == null) {
                        builder.get()
                    } else {
                        builder.post(bodyText.toRequestBody(JSON_MEDIA_TYPE))
                    }
                    withToken(builder, endpoint.token).build()
                },
                maxAttempts = attempts,
                respectRateLimit = respectRateLimit,
                report = report,
                client = originClient
            )
            last = outcome
            if (OriginTrustPolicy.canRememberSuccess(outcome.code)) {
                rememberSuccess(origin)
                return outcome
            }
            // 限流闸内短路：换地址也没用（限流按 IP 算），直接返回（已经上报过）。
            if (outcome.kind == RetryPolicy.Kind.RATE_LIMITED) return outcome
            if (outcome.code != null) continue
        }
        return last ?: HttpOutcome(RetryPolicy.Kind.FATAL, null, null, message = NO_ADDRESS)
    }

    /**
     * 1.4.7：GET /health 问出电脑端版本与能力（60 秒最多一次）。
     *
     * 判旧端**不看版本号**（服务端还是 1.4.2，没升）：只看 `/health` 在不在、能力清单缺不缺。
     * 探测失败不阻塞主流程 —— 后面真正的请求会给出最终结论。
     */
    private suspend fun probeHealth() {
        val now = System.currentTimeMillis()
        if (now - lastHealthProbeAt < HEALTH_PROBE_MIN_INTERVAL_MS) return
        val endpoint = cardBackEndpoint(rotate = true) ?: return
        lastHealthProbeAt = now
        val knownInstance = knownInstanceId()
        for (origin in endpoint.origins) {
            if (!originAllowed(origin, knownInstance, endpoint.token)) continue
            val originClient = httpClientFor(origin, endpoint.tlsPin)
            if (originClient == null) {
                Log.w(TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            if (!httpsLanIdentityOk(origin, knownInstance, endpoint.token, endpoint.tlsPin)) continue
            val outcome = call(
                build = {
                    withToken(
                        Request.Builder()
                            .url(origin + ServerHealth.PATH)
                            .header("Connection", "close")
                            .get(),
                        endpoint.token
                    ).build()
                },
                maxAttempts = 1,
                // /health 不参与限流：状态页不该因为我们自己贴着 429 冷却就看不到电脑端信息。
                respectRateLimit = false,
                // 探测失败不该把状态页从 OK 翻成 RETRYING；只有 404 / 401 这种「确定结论」才报。
                report = false,
                client = originClient
            )
            if (outcome.code == null) continue
            if (OriginTrustPolicy.canRememberSuccess(outcome.code)) rememberSuccess(origin)
            when (outcome.kind) {
                RetryPolicy.Kind.OK -> {
                    val info = ServerHealth.parse(outcome.body)
                    if (info == null) {
                        Log.w(TAG, "/health 回了 200 但读不懂：${outcome.body?.take(120)}")
                    } else {
                        serverHealth = info
                        SyncStatusStore.reportServer(info.version, info.startedAt, info.capabilities)
                        Log.i(TAG, "/health: ${info.version} protocol=${info.protocol} caps=${info.capabilities}")
                    }
                }
                RetryPolicy.Kind.NOT_FOUND -> {
                    // 老服务端没有 /health：之后每个 /sync、/rewrite/* 都会 404，早点说清楚。
                    serverHealth = null
                    publish(SyncPhase.SERVER_TOO_OLD, outcome.code, RetryPolicy.MSG_SERVER_TOO_OLD)
                }
                RetryPolicy.Kind.UNAUTHORIZED -> {
                    publish(SyncPhase.UNAUTHORIZED, outcome.code, outcome.message)
                }
                else -> Unit // 5xx / 超时：静默，别用一个探测失败打扰用户
            }
            return
        }
    }

    /** 上报一次状态；同一句话不重复写（4 秒轮询下重复写会把时间戳刷成假的「一直在重试」）。 */
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

    /**
     * 地址候选 + 口令，一次读够。
     * DataStore 读失败按「没配地址」处理，不把异常抛给调用方。
     */
    private suspend fun cardBackEndpoint(rotate: Boolean): CardBackEndpoint? {
        val settings = runCatching { settingsRepository.settingsFlow.first() }.getOrNull()
            ?: return null
        // 1.4.7：用户改过地址/口令就丢掉本会话缓存的 activeOrigin，否则新地址不会被优先尝试。
        val revision = settingsRepository.endpointRevision
        if (revision != endpointRevisionSeen) {
            endpointRevisionSeen = revision
            activeOrigin = null
        }
        // 1.4.7.2 W4：同 WiFi 只走 HTTPS + SPKI pin。没有 pin 时先向 HTTPS 配置地址
        // 取锚点；拿不到就不发现，绝不退回明文 HTTP。
        var lanPin = TlsPinned.normalizePin(settings.cardBackTlsPin).orEmpty()
        var lanPort = settings.cardBackTlsPort
        // 首次配对：配对行已经给了 pin + tls_port，但 instance_id 还空。
        // 此时必须先走 pin 绑定的 HTTPS，不能让 token 先发明文 HTTP。
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
        // 1.4.8：只有已配对（有 token）才把公网域名作为兜底候选。
        // 没 token 时强行请求域名只会换回一个无意义的 401，不如直接报「没配置地址」。
        // 1.5.1：域名不再硬编码，统一读设置里的公网同步地址（可由本地 keystore.properties
        // 注入 BuildConfig.DEFAULT_REMOTE_URL 后自动预填）。这样私人域名不会进 Git。
        // 域名走系统信任，不拿 LAN 的 SPKI pin 校验证书（httpClientFor 按 origin 区分）。
        val cloudFallback = settings.wordbookRemoteUrl
            .takeIf { settings.cardBackToken.isNotBlank() }
            ?.let(::normalizeCloudOrigin)
        val origins = if (rotate) {
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
                )
            )
        }
        // 有 pin 时，明文 HTTP+IP 一律不进候选：token 只能发给 pin 证明过的 HTTPS
        // 或已受信 HTTPS 域名。没有 pin 的老配置保持原行为。
        // 只有「带口令」时才必须把明文 HTTP+IP 排除在候选外；
        // 未配对（token 为空）时没有秘密可泄露，显式填的 http://10.0.2.2:8765
        // 必须能作为 HTTPS 自发现失败后的兜底，否则模拟器/首次配对会出现
        // 「有地址但一个请求都发不出去」的假死。
        val safeOrigins = if (lanPin.isNotBlank() && settings.cardBackToken.isNotBlank()) {
            origins.filterNot { isPlainHttpIpOrigin(it) }
        } else {
            origins
        }
        if (safeOrigins.isEmpty()) {
            return null
        }
        return CardBackEndpoint(
            origins = safeOrigins,
            token = settings.cardBackToken,
            tlsPin = lanPin,
            tlsPort = lanPort
        )
    }

    /**
     * 配对行给了 pin/tls_port、地址主机是 IP 时，把 http://IP:8765 升成
     * https://IP:tlsPort。instance 空不空都允许升级：已配对用户重贴带 pin
     * 的配对行也必须能走这条路，否则候选会被明文过滤清空、同步停摆。
     */
    private fun firstPairPinnedOrigin(settings: UserSettings, pin: String, port: Int): String? {
        val normalized = TlsPinned.normalizePin(pin) ?: return null
        val origin = CardBackAddress.originOf(settings.cardBackUrl) ?: return null
        val host = runCatching { URI(origin).host }.getOrNull()?.trim().orEmpty()
        if (!TlsPinned.isIpLiteral(host)) return null
        val tlsPort = port.takeIf { it in 1..65535 } ?: DEFAULT_TLS_PORT
        return "https://$host:$tlsPort"
    }

    /** 有 pin 时任何明文 http 候选都不能再发口令，不只是 IP 字面量。 */
    private fun isPlainHttpIpOrigin(origin: String): Boolean =
        origin.startsWith("http://", ignoreCase = true)

    /**
     * 自动发现 LAN HTTPS 端点；pin 缺失时只允许从 HTTPS 配置地址的 /identity 取锚点。
     */
    private suspend fun resolveLan(settings: UserSettings): LanEndpoint? {
        val expected = settings.cardBackInstanceId.trim()
        val token = settings.cardBackToken.trim()
        if (expected.isBlank() || token.isBlank()) return null
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
                runCatching { settingsRepository.updateCardBackTlsAnchor(anchor.pin, anchor.port) }
                pin = TlsPinned.normalizePin(anchor.pin)
                port = anchor.port
            }
        }
        val usablePin = pin ?: return null
        val found = runCatching {
            LanDiscovery.discover(settingsRepository.appContext, token, expected, usablePin, port)
        }.getOrNull() ?: return null
        if (found.instanceId != expected) return null
        return LanEndpoint(origin = found.origin, pin = usablePin, port = port)
    }

    /**
     * 记下这次真正连上的地址：内存里立刻生效（后续请求先打它），
     * 同时写进 DataStore，下次冷启动 / 换 Wi-Fi 还能兜底。
     */
    private suspend fun rememberSuccess(origin: String) {
        SyncStatusStore.reportOrigin(origin)
        activeOrigin = origin
        if (origin == savedLastGood) return
        savedLastGood = origin
        runCatching { settingsRepository.updateCardBackLastGoodUrl(origin) }
    }

    private data class CardBackEndpoint(
        val origins: List<String>,
        val token: String,
        val tlsPin: String = "",
        val tlsPort: Int = 8443
    )

    private data class LanEndpoint(val origin: String, val pin: String, val port: Int)

    private suspend fun knownInstanceId(): String =
        runCatching { settingsRepository.settingsFlow.first().cardBackInstanceId }.getOrDefault("")

    /**
     * 已配对电脑的私网 http 地址必须先证明身份，才允许把口令发过去。
     * 未配对 / HTTPS 域名保持原行为（首次配对靠用户手填地址）。
     */
    private suspend fun originAllowed(origin: String, knownInstance: String, token: String): Boolean {
        if (!OriginTrustPolicy.needsProof(origin, knownInstance)) return true
        return LanDiscovery.verifyOrigin(origin, knownInstance, token)
    }

    /**
     * https + IP 的 LAN 候选：TLS pin 只证明「这张证书」，还要再证明「这个 instance」。
     * 已配对时先做无口令 `/identity?nonce`，验不过就不发 token；未配对保持原行为。
     */
    private suspend fun httpsLanIdentityOk(
        origin: String,
        knownInstance: String,
        token: String,
        pin: String
    ): Boolean {
        if (!TlsPinned.isLanHttps(origin)) return true
        // instance 空也照样做一次无口令 /identity?nonce：pin 只证明证书，
        // proof/proof_peer 才证明「这台电脑知道口令」；首配不能跳过。
        return TlsPinned.verifyIdentity(origin, knownInstance, token, pin) != null
    }

    /**
     * 按 origin 选择客户端：
     * - 域名/普通公网地址用系统信任 + 较长超时；
     * - 局域网 IP 用 2 秒短超时，连不上就快速切公网；
     * - https + IP 的 LAN 必须用 pin。返回 null 表示「这是 LAN HTTPS，但没有可用的 pin」，
     *   调用方必须跳过而不是降级。
     */
    private fun httpClientFor(origin: String, pin: String): OkHttpClient? {
        if (TlsPinned.isLanHttps(origin)) return TlsPinned.clientFor(pin)
        return if (isLanOrigin(origin)) lanClient else cloudClient
    }

    /** 1.5.1：判断一个 origin 是不是内网地址（只用于选超时，不参与信任判断）。 */
    private fun isLanOrigin(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull()?.lowercase(Locale.ROOT)
            ?: return false
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
     * 公网地址归一成 origin：补 https、去掉卡片路径和结尾斜杠。
     * 解析失败返回 null，调用方按「没配置」处理，绝不拿半截字符串去请求。
     */
    private fun normalizeCloudOrigin(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            text
        } else {
            "https://$text"
        }
        val origin = CardBackAddress.originOf(withScheme) ?: return null
        if (!origin.startsWith("https://", ignoreCase = true) && !isLanOrigin(origin)) return null
        return origin
    }

    /** 电脑端不在本机时，每个请求都得带口令；没填就照发（本机浏览器编辑卡背免票）。 */
    private fun withToken(builder: Request.Builder, token: String): Request.Builder {
        CardbackAuth.headerValue(token)?.let { builder.header(CardbackAuth.HEADER, it) }
        return builder
    }

    /** 上一次请求是不是被电脑端的口令拦住了；读一次就清掉，免得同一句提示反复弹。 */
    fun consumeAuthFailure(): Boolean {
        if (!authFailed) return false
        authFailed = false
        return true
    }

    // 1.4.6.1：原来这里只有一个 baseOrigin()（只读手填地址）。
    // 地址解析与候选顺序挪到 CardBackAddress（纯函数，单测覆盖），
    // 失败轮试 / 成功回写在 cardBackEndpoint() 与 send() 里。

    private data class MarksPayload(val marks: List<MarkDto>)

    private data class MarkDto(
        val clientId: String,
        val bookId: Long?,
        val word: String,
        val phonetic: String,
        val pos: String,
        val meaning: String,
        val model: String,
        val instruction: String,
        /** 书名供记录页显示；旧服务忽略独立的 bookId。 */
        val book: String = "",
        val markedAt: String,
        val currentApproach: List<String>? = null
    )

    private data class MarksPage(val accepted: List<String>?, val rejected: List<Any>?)

    private data class ResultsPage(
        val results: List<RewriteResultDto>?,
        val nextSince: Long?,
        val more: Boolean?,
        val streamId: String?,
        val highWater: Long?
    )

    private data class RewriteResultDto(
        val seq: Long?,
        val word: String?,
        val clientId: String?,
        val rev: Int?,
        val approach: List<String>?,
        val prev: List<String>?,
        val method: String?,
        val status: String?,
        val error: String?,
        /**
         * 1.4.7.1：这版思路「没复核」（自检没跑完 / 兜底产出）。老服务端不带这个字段，
         * Gson 反序列化出来是 null —— 判定一律写 `== true`，绝不当成「未复核」误报。
         */
        val unverified: Boolean? = null,
        /** 未复核的原因（服务端 reason），可能没有；空白按没有处理。 */
        val reason: String? = null,
        /** 1.5.3v2：结果来源。worker=后端闲时洗词主动产出（不得覆盖本地锁）；空=用户点重写。 */
        val source: String = ""
    )

    private data class StatusPage(val items: List<StatusItem>?)

    private data class StatusItem(
        val word: String?,
        val state: String?,
        val stage: String?,
        val error: String?,
        val updatedAt: String?,
        val clientId: String?
    )

    private data class ModelsPage(val models: List<ModelDto>?)

    private data class ModelDto(
        val id: String?,
        val name: String?,
        val provider: String?,
        val reasoning: String?
    )

    private data class FeedbackPayload(val items: List<FeedbackDto>)

    /**
     * 一条反馈。clientId 由（词、书、评价）拼出来 —— 服务端据此幂等去重，
     * 手机端断了网重推同一批也不会在 data/feedback.jsonl 里堆重复行。
     */
    internal data class FeedbackDto(
        val clientId: String,
        val wordId: Long,
        val bookId: Long,
        val word: String,
        val rating: String,
        val approach: String,
        val method: String,
        val model: String,
        val instruction: String,
        /** 这一版思路的版本号；电脑端只做记录，不参与生成。 */
        val approachRev: Int = 0,
        /** 1.5.2：连击合并后的次数；旧服务端会忽略未知字段，不影响兼容。 */
        val count: Int = 1,
        /** 1.5.3：结构化点踩原因；旧服务端会忽略。 */
        val reasons: List<String> = emptyList(),
        /** 1.5.3：点踩吐槽；旧服务端会忽略。 */
        val comment: String = "",
        val createdAt: String?
    )

    private data class FeedbackPage(
        val ok: Boolean? = null,
        val accepted: List<String>? = null
    )

    companion object {
        private const val TAG = "RewriteRepository"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_PAGES = 20
        private const val STATUS_FAILED = "failed"

        /**
         * POST 的重试次数：标记/反馈在服务端按 clientId 幂等，重发安全；
         * 但只给一次重试 —— 限流额度按请求数算，不能把一个 POST 拖成长尾。
         */
        private const val POST_ATTEMPTS = 2

        /** /health 探测节流：60 秒最多问一次。 */
        private const val HEALTH_PROBE_MIN_INTERVAL_MS = 60_000L

        /** HTTPS 配置地址的 TLS 锚点刷新间隔；pin 轮换时不必等下次冷启动。 */
        private const val ANCHOR_REFRESH_MS = 10 * 60_000L
        private const val DEFAULT_TLS_PORT = 8443

        /** /health 能力清单里重写链路真正依赖的两项。 */
        private const val CAP_REWRITE = "rewrite"
        private const val CAP_REWRITE_SCOPED = "rewrite_scoped"
        private const val LEGACY_REPLAY_INTERVAL_MS = 60_000L
        private const val CAP_FEEDBACK = "feedback"

        /** 网络恢复自动补发的最小间隔：连重连抖动几下也只发一次。 */
        private const val AUTO_SYNC_MIN_INTERVAL_MS = 30_000L

        /** /rewrite/status 连上了，但列表里没有这个词（电脑端清过数据）。 */
        const val STATE_MISSING = "missing"
        private const val STALE_QUEUED_MILLIS = 30 * 60 * 1000L
        private const val FAILED_DEBT_RETRY_MILLIS = 60_000L

        /** 判断历史终态是否比本次标记还早时，允许的时钟误差。 */
        private const val TERMINAL_STALE_TOLERANCE_MS = 2_000L

        /**
         * 词表「算导完了」的最低词数。低于这个数时，拉回的结果里查不到词一律当成「还没导完」，
         * 保留结果、不推进游标；高于这个数还查不到，就当这个词真的不在库里，跳过它继续走。
         */
        private const val SEED_READY_MIN_WORDS = 1000

        /** 一次补发/补拉多少条，别把一页请求撑太大。 */
        private const val FEEDBACK_BATCH = 50

        /** 1.5.3：单次连击合并的上限，和后端 1..50 的校验对齐。 */
        private const val MAX_FEEDBACK_BURST = FeedbackBurstPolicy.MAX_COUNT

        /** 1.5.3：点踩原因最多几条，避免脏输入撑爆反馈行。 */
        private const val MAX_FEEDBACK_REASONS = 8
        private const val REASON_SEPARATOR = "|"
        private const val SKIPPED_RETRY_LIMIT = 20

        /** 反馈正文最多带这么多字（思路可能很长，电脑端只需要够判断就行）。 */
        private const val MAX_FEEDBACK_CHARS = 2000

        /** 跳过原因，方便对着 tb_rewrite_skipped.reason 查。 */
        private const val SKIP_NO_WORD = "no_word"
        private const val SKIP_WORD_MISSING = "word_missing"
        private const val SKIP_NO_MARK = "no_mark"
        private const val SKIP_EMPTY_APPROACH = "empty_approach"
        private const val SKIP_CLIENT_MISMATCH = "client_mismatch"

        /** 本 App 发出的 clientId 形状：w{wordId}-{createdAt}。 */

        /** 释义开头的词性前缀：adj. / v. / n. / adv. / vt. … */
        private val POS_PREFIX = Regex("^([a-zA-Z]{1,5}(?:\\.[a-zA-Z]{1,5})?)\\.")
        const val NO_ADDRESS = "先去「我的」页填电脑地址"
        const val UNREACHABLE = "电脑没连上"

        /** v8：记忆信号与遗忘自动换招的本地账本（只存本机，不与后端共享）。 */
        private const val MEMORY_SIGNAL_PREFS = "fsrs_memory_signal"
        private const val EVENT_RETAINED = "retained"
        private const val EVENT_LAPSED = "lapsed"

        /** retained 只报一次；lapsed 用 24 小时节流，避免每轮复习都发一条。 */
        private const val MEMORY_SIGNAL_MIN_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** 自动换招的 24 小时冷却：同一词一天最多自动重排一次。 */
        private const val AUTO_REWRITE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

        /** 自动换招的队列优先级：数值越小越先做；20 排在用户手点重写之后。 */
        private const val AUTO_REWRITE_PRIORITY = 20

        private const val FSRS_LAPSE_INSTRUCTION =
            "FSRS 监测到该词真实复习多次遗忘（难度过高），请彻底废弃当前思路与方法，" +
                "改用更直白、基于初高中熟词或一跳谐音的聪明室友小抄写法。"

        /** 1.5.3v2 C5：worker 闲时洗词的结果遇到本地「免洗金牌」锁时，必须跳过覆盖。 */
        fun shouldSkipWorkerResult(source: String?, isLocked: Boolean): Boolean =
            source == "worker" && isLocked
    }
}

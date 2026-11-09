package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.RewriteFeedbackDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteSkippedDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteSkippedResult
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.ApproachRevision
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.network.CardBackAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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
import okhttp3.Response
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
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
    /** 只为把书名带上去：记录页按书筛，可书只在手机库里。 */
    private val bookDao: BookDao
) {
    private val gson = Gson()
    private val syncMutex = Mutex()

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

    /** 已经写进 DataStore 的 last-good；一次同步要发十几页请求，别每页都写盘。 */
    @Volatile
    private var savedLastGood: String? = null

    /** 自动补发节流：断网/重连常常连着抖几个回调，不必每次都全量 sync。 */
    @Volatile
    private var lastAutoSyncAt = 0L

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

    fun observeMark(wordId: Long): Flow<RewriteQueueItem?> = queueDao.observeByWordId(wordId)

    fun observeOpenCount(): Flow<Int> = queueDao.observeOpenCount()

    /** 1.4.6.1：当前这本书还有几条重写在排队/等结果（完成判定以它为准）。 */
    fun observeOpenCountForBook(bookId: Long): Flow<Int> = queueDao.observeOpenCountForBook(bookId)

    /**
     * 1.4.6.1 P0（DB20）：当前书里「点过跳过、但还没插回」的队列行。
     * 不看 status：done 行也要能补插，冷启动自动同步先跑也不会漏。
     */
    suspend fun needsReinsertForBook(bookId: Long): List<RewriteQueueItem> =
        queueDao.getNeedsReinsertForBook(bookId)

    /** 用户点了「跳过」：先落库，进程死掉也能在下次加载时补插回队列。 */
    suspend fun markNeedsReinsert(wordId: Long, bookId: Long) {
        queueDao.markNeedsReinsert(wordId, bookId)
    }

    /** 插回成功 / 发现已在队列 / 今天已答过 / 重新重写或撤销时清掉待插回标记。 */
    suspend fun clearNeedsReinsert(wordId: Long) {
        queueDao.clearNeedsReinsert(wordId)
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

    /** 这本书当前生效的方法标签（用于重写完后原地换胶囊）。 */
    suspend fun methodOf(wordId: Long, bookId: Long = 0L): String {
        if (bookId > 0L) {
            bookMemoryDao.methodForBook(wordId, bookId)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return wordMemoryDao.getByWordId(wordId)?.method.orEmpty()
    }

    /**
     * 打标记并立刻尝试上传。返回 true 表示已发到电脑（status=queued）；
     * false 表示先存在本地，等下次同步补发。
     */
    suspend fun enqueueAndSync(
        wordId: Long,
        word: String,
        model: String,
        instruction: String,
        bookId: Long = 0L
    ): Boolean {
        val now = System.currentTimeMillis()
        val existing = queueDao.getByWordId(wordId)
        // 同一条未发出的标记反复点，沿用原来的 clientId，电脑端可以按幂等去重
        val createdAt = if (existing != null && existing.status == RewriteStatus.PENDING) {
            existing.createdAt
        } else {
            now
        }
        queueDao.upsert(
            RewriteQueueItem(
                wordId = wordId,
                bookId = bookId,
                word = word,
                instruction = instruction.trim(),
                model = model,
                status = RewriteStatus.PENDING,
                error = null,
                createdAt = createdAt,
                updatedAt = now
            )
        )
        sync()
        // 以队列里的真实状态为准：上传成功但拉结果失败时，也不能骗用户说没发出去。
        return queueDao.getByWordId(wordId)?.status == RewriteStatus.QUEUED
    }

    /** 取消标记。已经发给电脑的（queued）不在这里删，等结果回来。 */
    suspend fun cancel(wordId: Long) {
        val existing = queueDao.getByWordId(wordId) ?: return
        if (existing.status == RewriteStatus.QUEUED) return
        queueDao.delete(wordId)
    }

    /** 撤销一次重写；返回恢复后的旧思路，没有可撤销版本时返回 null。 */
    suspend fun undo(wordId: Long, bookId: Long = 0L): String? {
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
                    updatedAt = System.currentTimeMillis()
                )
            )
            queueDao.delete(wordId)
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
                updatedAt = System.currentTimeMillis()
            )
        )
        queueDao.delete(wordId)
        return prev
    }

    /**
     * 补发未发出的标记 + 增量拉结果。
     * 任何一步失败都只返回 error，不抛异常，调用方静默处理。
     */
    suspend fun sync(): RewriteSyncResult {
        return syncMutex.withLock {
        if (!hasAddress()) return@withLock RewriteSyncResult(error = NO_ADDRESS)
        var uploaded = 0
        val touched = ArrayList<Long>()
        var failed = 0

        // 已送出去但半小时没回音的，重新挂回待发：电脑端清过数据、或服务重启丢了任务时靠它自愈。
        val staleBefore = System.currentTimeMillis() - STALE_QUEUED_MILLIS
        queueDao.getStaleQueued(staleBefore).forEach { item ->
            queueDao.updateStatus(item.wordId, RewriteStatus.PENDING, item.error, System.currentTimeMillis())
        }

        val pending = queueDao.getPending()
        if (pending.isNotEmpty()) {            val accepted = upload(pending)
            if (accepted == null) return@withLock RewriteSyncResult(error = UNREACHABLE)
            val now = System.currentTimeMillis()
            pending.forEach { item ->
                if (accepted.contains(clientIdOf(item))) {
                    queueDao.updateStatus(item.wordId, RewriteStatus.QUEUED, null, now)
                    uploaded += 1
                }
            }
        }

        // 1.4.6：上次同步里「认领不到 / 词还没导进来」被跳过的结果，这里补一次。
        // 游标早就走过它们了，不补就是永久丢。
        retrySkipped()

        // 1.4.6：攒下来的 👍/👎 顺手补发；发不出去不影响这次同步的结果。
        runCatching { pushFeedback(feedbackDao.getUnsynced(FEEDBACK_BATCH)) }

        var since = settingsRepository.rewriteLastSeq()
        var seqRewound = false
        // 补拉表里已有的行：同一行再次被跳过时 attempts 要累加，不能从 0 重新数。
        val skippedAttempts = HashMap<Long, Int>()
        skippedDao.getPending(SKIPPED_RETRY_LIMIT, RewriteSkippedResult.MAX_ATTEMPTS).forEach {
            skippedAttempts[it.seq] = it.attempts
        }
        repeat(MAX_PAGES) {
            val body = httpGet("/rewrite/results?since=$since&limit=50")
            if (body == null) {
                return@withLock RewriteSyncResult(uploaded, touched, failed, UNREACHABLE)
            }
            val page = runCatching { gson.fromJson(body, ResultsPage::class.java) }.getOrNull()
                ?: return@withLock RewriteSyncResult(uploaded, touched, failed, "电脑返回的结果读不懂")
            // 电脑端清过 data/ 之后 seq 会从 1 重新数，本地 since 还停在旧值 —— 那样会一直拉到空页。
            // 发现服务端游标回退，就把本地游标归零重拉一次。
            val serverNext = page.nextSince
            if (!seqRewound && serverNext != null && since > 0 && serverNext < since) {
                seqRewound = true
                since = 0L
                settingsRepository.updateRewriteLastSeq(0L)
                return@repeat
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
                since = next
                settingsRepository.updateRewriteLastSeq(since)
            }
            if (page.more != true) return@withLock RewriteSyncResult(uploaded, touched, failed, null)
        }
        RewriteSyncResult(uploaded, touched, failed, null)
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
        if (queueDao.getByWordId(wordId) == null) return SKIP_NO_MARK
        if (dto.approach.isNullOrEmpty()) return SKIP_EMPTY_APPROACH
        return SKIP_CLIENT_MISMATCH
    }

    /** 卡片上的 👍/👎（1.4.6）：当前词在这本书里的反馈，用来把按钮显示成已选状态。 */
    fun observeFeedback(wordId: Long, bookId: Long): Flow<List<RewriteFeedback>> =
        feedbackDao.observeForWord(wordId, bookId)

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
        approachRev: Int = 0
    ): Boolean {
        if (!RewriteFeedbackRating.isValid(rating)) return false
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
                approachRev = approachRev.coerceAtLeast(0),
                createdAt = System.currentTimeMillis()
            )
        )
        // -1 = 唯一索引挡下了（之前已经表过态），不用再发一次。
        if (inserted <= 0L) return true
        runCatching { pushFeedback(feedbackDao.getUnsynced(FEEDBACK_BATCH)) }
        return true
    }

    /**
     * 把没发出去的反馈推给电脑端。失败只返回 false，不抛异常；
     * 服务端按 clientId 幂等去重，所以整批重推是安全的。
     */
    private suspend fun pushFeedback(items: List<RewriteFeedback>): Boolean {
        if (items.isEmpty()) return true
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
                    createdAt = isoTime(item.createdAt)
                )
            }
        )
        val body = httpPost("/rewrite/feedback", gson.toJson(payload)) ?: return false
        val response = runCatching { gson.fromJson(body, FeedbackPage::class.java) }
            .getOrNull() ?: return false
        if (response.ok != true) return false
        feedbackDao.markSynced(items.map { it.id })
        return true
    }

    /** 电脑端模型列表；连不上就用内置 5 个兜底。成功一次后缓存到进程结束。 */
    suspend fun models(): List<RewriteModel> {
        cachedModels?.let { return it }
        val body = httpGet("/models") ?: return RewriteModels.DEFAULT
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
    suspend fun statusOf(word: String, sinceMillis: Long = 0L): RewriteStatusSnapshot? {
        val body = httpGet("/rewrite/status", attempts = 1, rotate = false) ?: return null
        val page = runCatching { gson.fromJson(body, StatusPage::class.java) }.getOrNull() ?: return null
        val key = word.trim().lowercase(Locale.ROOT)
        val matches = page.items.orEmpty().filter {
            it.word?.trim()?.lowercase(Locale.ROOT) == key
        }.filterNot { item ->
            // 服务端留着同一词的历史：上一条 done/failed 可能比这次标记还早。
            // 不滤掉的话，刚点完重写第一轮就会读到旧的 100%，进度条先冲满再退回来。
            val terminal = item.state == "done" || item.state == STATUS_FAILED
            terminal && sinceMillis > 0L &&
                (parseServerMillis(item.updatedAt) ?: Long.MAX_VALUE) + TERMINAL_STALE_TOLERANCE_MS < sinceMillis
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
        // - 有队列行：带 clientId 时必须和这条标记完全一致；不带 clientId 的老结果也接受。
        val mark = queueDao.getByWordId(wordId) ?: return null
        val incoming = dto.clientId?.takeIf { it.isNotBlank() }
        if (incoming != null) {
            val owner = CLIENT_ID.matchEntire(incoming)?.groupValues?.get(1)?.toLongOrNull()
            if (owner == null || owner != wordId) return null
            if (incoming != clientIdOf(mark)) return null
        }

        if (dto.status == STATUS_FAILED) {
            queueDao.updateStatus(
                wordId,
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

        // 1.4.5：这条标记是在哪本书里点的，就只改哪本书的卡背。
        // bookId=0 是老队列/换机恢复，沿用旧行为写全局那份。
        val bookId = mark?.bookId ?: 0L
        if (bookId > 0L) {
            val own = bookMemoryDao.getForBook(wordId, bookId)
            // 1.4.6 幂等：同一行结果被重拉一遍（游标没推进 / 服务端重发）时，
            // 不能把「上一版」覆盖成刚写进去的这版 —— 那样撤销只会退到一模一样的文字。
            if (own?.approach == approach) {
                queueDao.updateStatus(wordId, RewriteStatus.DONE, null, now)
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
                    prevMethod = previousMethod
                )
            )
            queueDao.updateStatus(wordId, RewriteStatus.DONE, null, now)
            return wordId
        }

        if (existing?.approach == approach) {
            queueDao.updateStatus(wordId, RewriteStatus.DONE, null, now)
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
                prevMethod = existing?.method?.takeIf { it.isNotBlank() }
            )
        )
        queueDao.updateStatus(wordId, RewriteStatus.DONE, null, now)
        return wordId
    }

    private suspend fun upload(items: List<RewriteQueueItem>): Set<String>? {
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
                clientId = clientIdOf(item),
                word = item.word,
                phonetic = word?.phonetic.orEmpty(),
                pos = posOf(word?.meaning.orEmpty()),
                meaning = word?.meaning.orEmpty(),
                model = item.model,
                instruction = item.instruction,
                book = bookNameOf(if (item.bookId > 0L) item.bookId else (word?.bookId ?: 0L)),
                markedAt = isoTime(item.createdAt),
                currentApproach = (scopedMemory?.approach ?: memory?.approach).orEmpty()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { null }
            )
        }
        val payload = MarksPayload(marks = marks)
        val body = httpPost("/rewrite/marks", gson.toJson(payload)) ?: return null
        val response = runCatching { gson.fromJson(body, MarksPage::class.java) }.getOrNull() ?: return null
        return response.accepted.orEmpty().toSet()
    }

    private fun clientIdOf(item: RewriteQueueItem): String = "w${item.wordId}-${item.createdAt}"

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

    private suspend fun httpGet(path: String, attempts: Int = 3, rotate: Boolean = true): String? =
        request(path, attempts, rotate) { url, token ->
            client.newCall(withToken(Request.Builder().url(url).get(), token).build())
                .execute().use { response -> readBody(response) }
        }

    private suspend fun httpPost(path: String, json: String): String? =
        request(path, attempts = 1, rotate = true) { url, token ->
            val body = json.toRequestBody(JSON_MEDIA_TYPE)
            client.newCall(withToken(Request.Builder().url(url).post(body), token).build())
                .execute().use { response -> readBody(response) }
        }

    /**
     * 按候选顺序发请求（1.4.6.1）：当前地址 → 手填地址 → 上次成功地址，
     * 前一个连不上才试下一个，成功后把这个地址记成「上次成功」。
     *
     * attempts 是对同一个地址的重试次数；`rotate=false` 时只打最可信的那一个
     * （4 秒一次的进度轮询不能把一次轮询拖成两个连接超时）。
     * 全部候选都失败返回 null —— 调用方原本就把 null 当「没连上」，判断不用改。
     */
    private suspend fun <T : Any> request(
        path: String,
        attempts: Int,
        rotate: Boolean,
        call: (url: String, token: String) -> T?
    ): T? = withContext(Dispatchers.IO) {
        val endpoint = cardBackEndpoint(rotate) ?: return@withContext null
        endpoint.origins.forEach { origin ->
            repeat(attempts) {
                val result = runCatching { call(origin + path, endpoint.token) }
                    .onFailure { Thread.sleep(RETRY_BACKOFF_MS) }
                    .getOrNull()
                if (result != null) {
                    rememberSuccess(origin)
                    return@withContext result
                }
            }
        }
        null
    }

    /**
     * 地址候选 + 口令，一次读够。
     * DataStore 读失败按「没配地址」处理，不把异常抛给调用方。
     */
    private suspend fun cardBackEndpoint(rotate: Boolean): CardBackEndpoint? {
        val settings = runCatching { settingsRepository.settingsFlow.first() }.getOrNull()
            ?: return null
        val origins = if (rotate) {
            CardBackAddress.candidates(
                active = activeOrigin,
                current = settings.cardBackUrl,
                lastGood = settings.cardBackLastGoodUrl
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
        if (origins.isEmpty()) return null
        return CardBackEndpoint(origins = origins, token = settings.cardBackToken)
    }

    /**
     * 记下这次真正连上的地址：内存里立刻生效（后续请求先打它），
     * 同时写进 DataStore，下次冷启动 / 换 Wi-Fi 还能兜底。
     */
    private suspend fun rememberSuccess(origin: String) {
        activeOrigin = origin
        if (origin == savedLastGood) return
        savedLastGood = origin
        runCatching { settingsRepository.updateCardBackLastGoodUrl(origin) }
    }

    private data class CardBackEndpoint(val origins: List<String>, val token: String)

    /** 电脑端不在本机时，每个请求都得带口令；没填就照发（本机浏览器编辑卡背免票）。 */
    private fun withToken(builder: Request.Builder, token: String): Request.Builder {
        CardbackAuth.headerValue(token)?.let { builder.header(CardbackAuth.HEADER, it) }
        return builder
    }

    /**
     * 401 要单独记一笔：调用方只能拿到 null，分不清「电脑没开」和「口令错」，
     * 界面就会把口令问题说成「等电脑连上」，用户照着做一辈子都连不上。
     */
    private fun readBody(response: Response): String? {
        if (response.code == HTTP_UNAUTHORIZED) {
            authFailed = true
            return null
        }
        if (!response.isSuccessful) return null
        return response.body?.string()
    }

    /** 上一次请求是不是被电脑端的口令拦住了；读一次就清掉，免得同一句提示反复弹。 */
    fun consumeAuthFailure(): Boolean {
        if (!authFailed) return false
        authFailed = false
        return true
    }

    // 1.4.6.1：原来这里只有一个 baseOrigin()（只读手填地址）。
    // 地址解析与候选顺序挪到 CardBackAddress（纯函数，单测覆盖），
    // 失败轮试 / 成功回写在 cardBackEndpoint() 与 request() 里。

    private data class MarksPayload(val marks: List<MarkDto>)

    private data class MarkDto(
        val clientId: String,
        val word: String,
        val phonetic: String,
        val pos: String,
        val meaning: String,
        val model: String,
        val instruction: String,
        /** 书名；记录页按书筛，电脑端只存这个字符串。老版本不传，默认空。 */
        val book: String = "",
        val markedAt: String,
        val currentApproach: List<String>? = null
    )

    private data class MarksPage(val accepted: List<String>?, val rejected: List<Any>?)

    private data class ResultsPage(
        val results: List<RewriteResultDto>?,
        val nextSince: Long?,
        val more: Boolean?
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
        val error: String?
    )

    private data class StatusPage(val items: List<StatusItem>?)

    private data class StatusItem(
        val word: String?,
        val state: String?,
        val stage: String?,
        val error: String?,
        val updatedAt: String?
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
    private data class FeedbackDto(
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
        val createdAt: String?
    )

    private data class FeedbackPage(
        val ok: Boolean? = null,
        val accepted: List<String>? = null
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_PAGES = 20
        private const val STATUS_FAILED = "failed"
        private const val HTTP_UNAUTHORIZED = 401

        /** 同一个地址失败后的退避，别把重试打成忙等。 */
        private const val RETRY_BACKOFF_MS = 150L

        /** 网络恢复自动补发的最小间隔：连重连抖动几下也只发一次。 */
        private const val AUTO_SYNC_MIN_INTERVAL_MS = 30_000L

        /** /rewrite/status 连上了，但列表里没有这个词（电脑端清过数据）。 */
        const val STATE_MISSING = "missing"
        private const val STALE_QUEUED_MILLIS = 30 * 60 * 1000L

        /** 判断历史终态是否比本次标记还早时，允许的时钟误差。 */
        private const val TERMINAL_STALE_TOLERANCE_MS = 2_000L

        /**
         * 词表「算导完了」的最低词数。低于这个数时，拉回的结果里查不到词一律当成「还没导完」，
         * 保留结果、不推进游标；高于这个数还查不到，就当这个词真的不在库里，跳过它继续走。
         */
        private const val SEED_READY_MIN_WORDS = 1000

        /** 一次补发/补拉多少条，别把一页请求撑太大。 */
        private const val FEEDBACK_BATCH = 50
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
        private val CLIENT_ID = Regex("^w(\\d+)-(\\d+)$")

        /** 释义开头的词性前缀：adj. / v. / n. / adv. / vt. … */
        private val POS_PREFIX = Regex("^([a-zA-Z]{1,5}(?:\\.[a-zA-Z]{1,5})?)\\.")
        const val NO_ADDRESS = "先去「我的」页填电脑地址"
        const val UNREACHABLE = "电脑没连上"
    }
}

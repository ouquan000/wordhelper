package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URI
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

    fun observeMark(wordId: Long): Flow<RewriteQueueItem?> = queueDao.observeByWordId(wordId)

    fun observeOpenCount(): Flow<Int> = queueDao.observeOpenCount()

    /** 有没有填电脑地址；没填就别往队列里塞东西。 */
    suspend fun hasAddress(): Boolean = baseOrigin() != null

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
            val nextRev = (own.rev - 1).coerceAtLeast(0)
            bookMemoryDao.upsert(
                own.copy(
                    approach = prev,
                    prevApproach = null,
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
        val nextRev = (memory.rev - 1).coerceAtLeast(0)
        wordMemoryDao.upsert(
            memory.copy(
                approach = prev,
                prevApproach = null,
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
        val base = baseOrigin() ?: return@withLock RewriteSyncResult(error = NO_ADDRESS)
        var uploaded = 0
        val touched = ArrayList<Long>()
        var failed = 0

        // 已送出去但半小时没回音的，重新挂回待发：电脑端清过数据、或服务重启丢了任务时靠它自愈。
        val staleBefore = System.currentTimeMillis() - STALE_QUEUED_MILLIS
        queueDao.getStaleQueued(staleBefore).forEach { item ->
            queueDao.updateStatus(item.wordId, RewriteStatus.PENDING, item.error, System.currentTimeMillis())
        }

        val pending = queueDao.getPending()
        if (pending.isNotEmpty()) {            val accepted = upload(base, pending)
            if (accepted == null) return@withLock RewriteSyncResult(error = UNREACHABLE)
            val now = System.currentTimeMillis()
            pending.forEach { item ->
                if (accepted.contains(clientIdOf(item))) {
                    queueDao.updateStatus(item.wordId, RewriteStatus.QUEUED, null, now)
                    uploaded += 1
                }
            }
        }

        var since = settingsRepository.rewriteLastSeq()
        var seqRewound = false
        repeat(MAX_PAGES) {
            val body = httpGet("$base/rewrite/results?since=$since&limit=50")
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
            page.results.orEmpty().forEach { dto ->
                val wordId = applyResult(dto)
                if (wordId != null) {
                    touched.add(wordId)
                    if (dto.status == STATUS_FAILED) failed += 1
                } else {
                    val key = dto.word?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    if (key.isNotEmpty() && wordDao.getWordIdByKey(key) == null) unknownWord = true
                }
            }
            if (unknownWord && wordDao.countAll() < SEED_READY_MIN_WORDS) {
                return@withLock RewriteSyncResult(uploaded, touched, failed, null)
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


    /** 电脑端模型列表；连不上就用内置 5 个兜底。成功一次后缓存到进程结束。 */
    suspend fun models(): List<RewriteModel> {
        cachedModels?.let { return it }
        val base = baseOrigin() ?: return RewriteModels.DEFAULT
        val body = httpGet("$base/models") ?: return RewriteModels.DEFAULT
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
        val base = baseOrigin() ?: return null
        val body = httpGet("$base/rewrite/status", attempts = 1) ?: return null
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
                        .takeIf { it.isNotBlank() }
                )
            )
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
                method = method
            )
        )
        queueDao.updateStatus(wordId, RewriteStatus.DONE, null, now)
        return wordId
    }

    private suspend fun upload(base: String, items: List<RewriteQueueItem>): Set<String>? {
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
        val body = httpPost("$base/rewrite/marks", gson.toJson(payload)) ?: return null
        val response = runCatching { gson.fromJson(body, MarksPage::class.java) }.getOrNull() ?: return null
        return response.accepted.orEmpty().toSet()
    }

    private fun clientIdOf(item: RewriteQueueItem): String = "w${item.wordId}-${item.createdAt}"

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

    private suspend fun httpGet(url: String, attempts: Int = 3): String? = withContext(Dispatchers.IO) {
        val token = currentToken()
        // python 的 HTTPServer 默认 HTTP/1.0，回完就断连接；声明 Connection: close 避免复用旧 socket。
        repeat(attempts) {
            val body = runCatching {
                client.newCall(
                    withToken(Request.Builder().url(url).get(), token).build()
                ).execute().use { response -> readBody(response) }
            }.onFailure { Thread.sleep(150) }.getOrNull()
            if (body != null) return@withContext body
        }
        null
    }

    private suspend fun httpPost(url: String, json: String): String? = withContext(Dispatchers.IO) {
        val token = currentToken()
        runCatching {
            val body = json.toRequestBody(JSON_MEDIA_TYPE)
            client.newCall(
                withToken(Request.Builder().url(url).post(body), token).build()
            ).execute().use { response -> readBody(response) }
        }.getOrNull()
    }

    /** 电脑端不在本机时，每个请求都得带口令；没填就照发（本机浏览器编辑卡背免票）。 */
    private fun withToken(builder: Request.Builder, token: String): Request.Builder {
        CardbackAuth.headerValue(token)?.let { builder.header(CardbackAuth.HEADER, it) }
        return builder
    }

    private suspend fun currentToken(): String {
        return runCatching { settingsRepository.settingsFlow.first().cardBackToken }.getOrDefault("")
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

    /** 从卡背地址里取 scheme://host:port；没填或填错返回 null。 */
    private suspend fun baseOrigin(): String? {
        val raw = settingsRepository.settingsFlow.first().cardBackUrl.trim()
        if (raw.isEmpty()) return null
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "http://$raw"
        }
        return runCatching {
            val uri = URI(withScheme)
            val host = uri.host ?: return@runCatching null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://$host$port"
        }.getOrNull()
    }

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

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_PAGES = 20
        private const val STATUS_FAILED = "failed"
        private const val HTTP_UNAUTHORIZED = 401

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

        /** 本 App 发出的 clientId 形状：w{wordId}-{createdAt}。 */
        private val CLIENT_ID = Regex("^w(\\d+)-(\\d+)$")

        /** 释义开头的词性前缀：adj. / v. / n. / adv. / vt. … */
        private val POS_PREFIX = Regex("^([a-zA-Z]{1,5}(?:\\.[a-zA-Z]{1,5})?)\\.")
        const val NO_ADDRESS = "先去「我的」页填电脑地址"
        const val UNREACHABLE = "电脑没连上"
    }
}

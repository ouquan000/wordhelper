package com.kaoyan.wordhelper.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookWordContent
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.CardSeedLoader
import com.kaoyan.wordhelper.data.model.CardSeedPayload
import com.kaoyan.wordhelper.data.model.CardSeedWord
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.TlsPinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 词卡热更的结果。失败时 message 直接给用户看，不要吞掉原因。 */
sealed interface WordbookSyncResult {
    data class Success(
        val updatedWords: Int,
        val version: Int,
        val unchanged: Boolean,
        val message: String
    ) : WordbookSyncResult

    data class Failure(val message: String) : WordbookSyncResult
}

/**
 * 词卡热更失败时给用户看的人话（1.5.1）。
 *
 * 为什么单拎出来：1.5.0 把 `SocketTimeoutException` / `ConnectException` 的英文 message
 * 原样塞进 `Failure.message`，用户在「我的」页看到的就是一句
 * `failed to connect to /192.168.1.5:8765 (port 8765) after 2000ms`。
 * 这里只做「异常 → 短句中文」的纯映射：不碰网络、不碰状态、不读时钟，可直接 JVM 单测。
 */
object WordbookStatusPolicy {

    /** 连不上 / 超时：最常见的失败，文案必须直接给出下一步动作。 */
    const val MSG_UNREACHABLE =
        "连不上电脑：请检查电脑地址和网络，或在「我的」页填写公网地址"

    const val MSG_HOST_UNRESOLVED = "公网地址解析失败，请检查地址"

    const val MSG_TLS = "HTTPS 证书校验失败"

    const val MSG_UNAUTHORIZED = "口令不对，请重新粘贴配对行"

    /** 兜底原因的最大长度：异常 message 可能是一整段 HTML，别把一行提示撑爆。 */
    private const val MAX_REASON_CHARS = 40

    /** 顺着 cause 链最多看这么多层：包两三层 IOException 是常态，再深就是噪音了。 */
    private const val MAX_CAUSE_DEPTH = 5

    /**
     * 异常自带 HTTP 状态码时实现它。
     *
     * 401 本身是「响应」不是「异常」，正常请求走不到这里；实现这个接口只是为了让
     * 调用点能把状态码和异常一起交出来（单测也用它造「假 401 异常」）。
     */
    interface HttpStatusFailure {
        val httpCode: Int
    }

    /**
     * 把一次失败翻成一句中文。
     *
     * [httpCode] 显式给出时优先于异常类型（401 与网络无关）；不给则从异常链里的
     * [HttpStatusFailure] 取。
     */
    fun describeFailure(error: Throwable?, httpCode: Int? = null): String {
        val code = httpCode?.takeIf { it > 0 } ?: httpCodeOf(error)
        if (code == 401) return MSG_UNAUTHORIZED

        // 只看最外层不够：OkHttp/协程常把真正的 IOException 包一层。
        var node = error
        var depth = 0
        while (node != null && depth < MAX_CAUSE_DEPTH) {
            when (node) {
                is SSLException -> return MSG_TLS
                is UnknownHostException -> return MSG_HOST_UNRESOLVED
                // SocketTimeoutException 是 InterruptedIOException 的子类，二者都算「超时」。
                is SocketTimeoutException -> return MSG_UNREACHABLE
                is InterruptedIOException -> return MSG_UNREACHABLE
                is ConnectException -> return MSG_UNREACHABLE
                is NoRouteToHostException -> return MSG_UNREACHABLE
            }
            node = node.cause
            depth++
        }
        return "同步失败：${reasonOf(error)}"
    }

    private fun httpCodeOf(error: Throwable?): Int? {
        var node = error
        var depth = 0
        while (node != null && depth < MAX_CAUSE_DEPTH) {
            if (node is HttpStatusFailure) {
                return node.httpCode.takeIf { it > 0 }
            }
            node = node.cause
            depth++
        }
        return null
    }

    /**
     * 兜底原因：优先复用 [RetryPolicy.describe] 的中文描述，
     * 压成一行、截到 [MAX_REASON_CHARS]，宁可短也别糊一屏英文堆栈。
     */
    private fun reasonOf(error: Throwable?): String {
        // 带类型但没 message 的裸异常（如 RuntimeException()）不该把类名糊到用户脸上。
        if (error != null && error.message.isNullOrBlank() && error.cause == null) return "未知错误"
        val raw = if (error == null) "" else RetryPolicy.describe(error)
        val text = raw.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return "未知错误"
        return if (text.length <= MAX_REASON_CHARS) text else text.take(MAX_REASON_CHARS) + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}

/** 给设置页看的轻量状态；持久化的字段仍以 SettingsRepository 为准。 */
data class WordbookUpdateState(
    val syncing: Boolean = false,
    val lastMessage: String? = null,
    val appliedSha: String = "",
    val lastSyncAt: Long = 0L,
    val lastWordCount: Int = 0
)

/**
 * 1.5.0：词卡 JSON 热更新（免重装 APK）。
 *
 * 双通道：
 *  - 局域网：复用「卡背结构地址」的 origin，连接 2 秒超时，连不上立刻换公网；
 *  - 公网：用户在设置里填的 HTTPS 地址（Cloudflare Tunnel 等）。
 *
 * 安全边界：
 *  - manifest 与词卡都必须过 SHA-256 校验；book 必须是「六级记忆卡」，拒绝改名建新书；
 *  - 只写 filesDir/cet6_cards.json，先写 .tmp 再原子替换，旧文件留 .bak；
 *  - 数据库只更新词义/例句和非用户手改的助记，**绝不触碰 tb_progress**；
 *  - 用户改过（source=user / rev!=0 / 有撤销记录 / unverified）的助记一律保留。
 */
class WordbookUpdateRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(WordbookUpdateState())
    val state: StateFlow<WordbookUpdateState> = _state.asStateFlow()

    /**
     * 只给「冷启动回读」用的作用域。
     *
     * 仓库本身是 Application 级单例，没有调用方会给它协程作用域，
     * 所以自带一个：SupervisorJob 保证一次回读失败不会连坐后面的任务，IO 线程读写 DataStore。
     */
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 1.5.1 冷启动回读：杀后台重开后 `_state` 是全新默认值（appliedSha="" / lastSyncAt=0），
        // 「我的」页就会一直显示「还没同步过」，尽管 settings 里明明记着上次同步。
        // 这里把三个持久化字段灌回去；失败静默（读不到就是「还没同步过」，不影响主流程）。
        stateScope.launch {
            runCatching {
                val settings = settingsRepository.settingsFlow.first()
                _state.update { current ->
                    // 不能覆盖正在进行的同步；也不覆盖刚拿到的本次同步结果（lastMessage 非空）。
                    if (current.syncing || current.lastMessage != null) {
                        current
                    } else {
                        current.copy(
                            appliedSha = settings.wordbookAppliedSha,
                            lastSyncAt = settings.wordbookLastSyncAt,
                            lastWordCount = settings.wordbookLastWordCount
                        )
                    }
                }
            }
        }
    }

    /** 点按钮 / 打开页面时调用。同一时刻只允许一个同步任务。 */
    suspend fun sync(): WordbookSyncResult {
        if (!syncMutex.tryLock()) {
            return WordbookSyncResult.Failure("正在同步，请稍等一下")
        }
        try {
            _state.value = _state.value.copy(syncing = true, lastMessage = null)
            val result = withContext(Dispatchers.IO) { syncLocked() }
            val latest = settingsRepository.settingsFlow.first()
            _state.value = WordbookUpdateState(
                syncing = false,
                lastMessage = result.messageOf(),
                appliedSha = latest.wordbookAppliedSha,
                lastSyncAt = latest.wordbookLastSyncAt,
                lastWordCount = latest.wordbookLastWordCount
            )
            return result
        } catch (cancellation: CancellationException) {
            // 取消不是失败：原样抛出去，别把「页面关了」说成「同步失败」。
            throw cancellation
        } catch (t: Throwable) {
            // 1.5.1：任何漏出来的底层异常（连接/超时/DNS/证书）都在这里翻成人话再交给 UI。
            val message = WordbookStatusPolicy.describeFailure(t)
            _state.value = _state.value.copy(lastMessage = message)
            return WordbookSyncResult.Failure(message)
        } finally {
            _state.value = _state.value.copy(syncing = false)
            syncMutex.unlock()
        }
    }

    private suspend fun syncLocked(): WordbookSyncResult {
        val settings = settingsRepository.settingsFlow.first()
        val endpoints = buildEndpoints(settings)
        if (endpoints.isEmpty()) {
            return WordbookSyncResult.Failure(
                "还没配置电脑地址：先在「卡背结构地址」填电脑地址，或在下面填公网同步地址"
            )
        }

        var lastError = ""
        for (endpoint in endpoints) {
            val manifestFetch = fetch("${endpoint.origin}/cards/manifest.json", endpoint, MAX_MANIFEST_BYTES)
            when (manifestFetch) {
                is Fetch.Failed -> {
                    lastError = endpointFailureText(endpoint, "连不上：", manifestFetch)
                    continue
                }
                is Fetch.Http -> {
                    lastError = when (manifestFetch.code) {
                        401, 403 -> CardbackAuth.HINT
                        404 -> "${endpoint.label}电脑端还是旧版（没有 /cards/manifest.json），请先把电脑端服务升到 1.5.0"
                        else -> "${endpoint.label}返回 HTTP ${manifestFetch.code}"
                    }
                    continue
                }
                is Fetch.Ok -> Unit
            }

            val manifest = runCatching {
                gson.fromJson(String(manifestFetch.bytes, Charsets.UTF_8), CardManifest::class.java)
            }.getOrNull()
            if (manifest == null || manifest.sha256.isBlank()) {
                lastError = "${endpoint.label}发布清单不是合法 JSON"
                continue
            }
            if (!WordbookSyncPolicy.isExpectedBook(manifest.book)) {
                lastError = "发布清单的 book 是「${manifest.book}」，不是「$BOOK_NAME」，已拒绝（不允许改名建新书）"
                continue
            }
            val expectedSha = manifest.sha256.trim().lowercase(Locale.ROOT)
            if (!WordbookSyncPolicy.isSha256Hex(expectedSha)) {
                lastError = "${endpoint.label}发布清单的 sha256 格式不对"
                continue
            }

            // 已经应用过同一份发布包：只刷新「上次检查时间」，不重复下载 5MB。
            if (expectedSha == settings.wordbookAppliedSha.trim().lowercase(Locale.ROOT)) {
                val now = System.currentTimeMillis()
                settingsRepository.updateWordbookApplied(
                    expectedSha,
                    now,
                    settings.wordbookLastWordCount.takeIf { it > 0 } ?: manifest.count
                )
                return WordbookSyncResult.Success(
                    updatedWords = 0,
                    version = manifest.version,
                    unchanged = true,
                    message = "已是最新（v${manifest.version} · ${manifest.count} 词）"
                )
            }

            val cardsFetch = fetch("${endpoint.origin}/cards/cet6_cards.json", endpoint, MAX_CARDS_BYTES)
            val bytes = when (cardsFetch) {
                is Fetch.Ok -> cardsFetch.bytes
                is Fetch.Http -> {
                    lastError = "${endpoint.label}下载词卡失败：HTTP ${cardsFetch.code}"
                    continue
                }
                is Fetch.Failed -> {
                    lastError = endpointFailureText(endpoint, "下载词卡失败：", cardsFetch)
                    continue
                }
            }
            val actualSha = sha256(bytes)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                lastError = "${endpoint.label}词卡文件校验失败（SHA-256 对不上），已拒绝写入"
                continue
            }

            val payload = runCatching {
                gson.fromJson(String(bytes, Charsets.UTF_8), CardSeedPayload::class.java)
            }.getOrNull()
            if (payload == null || payload.words.isEmpty()) {
                lastError = "${endpoint.label}词卡内容为空或不是合法 JSON"
                continue
            }
            if (!WordbookSyncPolicy.isExpectedBook(payload.book)) {
                lastError = "词卡里的 book 是「${payload.book}」，不是「$BOOK_NAME」，已拒绝"
                continue
            }
            if (manifest.count > 0 && payload.words.size != manifest.count) {
                lastError = "${endpoint.label}词卡词数（${payload.words.size}）与清单（${manifest.count}）不一致"
                continue
            }

            if (!writeCardsAtomically(bytes)) {
                return WordbookSyncResult.Failure("写入本地词卡文件失败，旧文件已保留，请检查手机存储空间")
            }

            val outcome = applyToDatabase(payload)
            if (outcome.bookMissing) {
                return WordbookSyncResult.Failure(
                    "本地找不到「$BOOK_NAME」这本词书，请先到词书页恢复内置词书再同步"
                )
            }

            val now = System.currentTimeMillis()
            settingsRepository.updateWordbookApplied(expectedSha, now, payload.words.size)
            return WordbookSyncResult.Success(
                updatedWords = outcome.contentUpdated,
                version = manifest.version,
                unchanged = false,
                message = buildString {
                    append("已更新 ${outcome.contentUpdated} 个词条")
                    if (outcome.memoryUpdated > 0) append(" · 助记 ${outcome.memoryUpdated} 条")
                    if (outcome.userProtected > 0) append(" · 保留你手改的 ${outcome.userProtected} 条")
                    if (outcome.unknownWords > 0) append(" · ${outcome.unknownWords} 个词本地没有跳过")
                }
            )
        }
        return WordbookSyncResult.Failure(lastError.ifBlank { "同步失败：没有可用的电脑地址" })
    }

    // ---- 地址 / 通道 ----

    private fun buildEndpoints(settings: UserSettings): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        val token = settings.cardBackToken
        val rawPin = TlsPinned.normalizePin(settings.cardBackTlsPin).orEmpty()

        val lanUrls = listOfNotNull(
            CardBackRepository.normalizeUrl(settings.cardBackUrl),
            CardBackRepository.normalizeUrl(settings.cardBackLastGoodUrl)
        ).distinct()
        lanUrls.forEach { url ->
            val origin = originOf(url) ?: return@forEach
            val host = runCatching { Uri.parse(origin).host }.getOrNull()
            var base = origin
            var pin = ""
            if (rawPin.isNotEmpty() && host != null && TlsPinned.isIpLiteral(host)) {
                pin = rawPin
                if (base.startsWith("http://", ignoreCase = true)) {
                    val port = settings.cardBackTlsPort.takeIf { it > 0 } ?: 8443
                    base = "https://$host:$port"
                }
            }
            out.add(Endpoint("局域网", base, token, pin, lan = true))
        }

        val remote = normalizeRemote(settings.wordbookRemoteUrl)
        if (remote != null) {
            out.add(Endpoint("公网", remote, token, "", lan = false))
        }
        return out.distinctBy { it.origin }
    }

    /** 用户可能把整条 manifest 地址贴进来；这里统一削成 origin。 */
    private fun normalizeRemote(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "https://$text"
        }
        text = text
            .substringBefore("?")
            .removeSuffix("/cards/manifest.json")
            .removeSuffix("/cards/cet6_cards.json")
            .trimEnd('/')
        return originOf(text)
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    // ---- 网络 ----

    /**
     * 一个通道上的传输失败，给用户看的那一句。
     *
     * 有底层异常时 [Fetch.Failed.message] 已经是完整人话（「局域网连不上，且未配置公网地址；…」），
     * 再拼「局域网连不上：」就重复了；没有原始异常（服务端返回空内容 / 包太大）时
     * 才需要「通道 + 原因」的前缀。
     */
    private fun endpointFailureText(endpoint: Endpoint, prefix: String, failure: Fetch.Failed): String =
        if (failure.error != null) failure.message else "${endpoint.label}$prefix${failure.message}"

    private sealed interface Fetch {
        data class Ok(val bytes: ByteArray) : Fetch
        data class Http(val code: Int) : Fetch

        /**
         * 传输层失败。[error] 是原始异常（连不上/超时/DNS/证书），
         * [message] 已经是 [WordbookStatusPolicy] 翻好的人话；没有原始异常时才是自己写的短句。
         */
        data class Failed(val message: String, val error: Throwable? = null) : Fetch
    }

    private fun fetch(url: String, endpoint: Endpoint, maxBytes: Int): Fetch {
        val base = if (endpoint.pin.isNotEmpty()) {
            TlsPinned.clientFor(endpoint.pin) ?: DEFAULT_CLIENT
        } else {
            DEFAULT_CLIENT
        }
        val client = base.newBuilder()
            .connectTimeout(if (endpoint.lan) 2 else 8, TimeUnit.SECONDS)
            .readTimeout(if (endpoint.lan) 5 else 30, TimeUnit.SECONDS)
            .callTimeout(if (endpoint.lan) 7 else 45, TimeUnit.SECONDS)
            .build()
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Connection", "close")
        CardbackAuth.headerValue(endpoint.token)?.let { builder.header(CardbackAuth.HEADER, it) }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return Fetch.Http(response.code)
                val body = response.body ?: return Fetch.Failed("服务器返回空内容")
                val declared = body.contentLength()
                if (declared > maxBytes) {
                    return Fetch.Failed("发布包异常大（${declared / 1024 / 1024} MB），已拒绝")
                }
                val bytes = body.bytes()
                if (bytes.size > maxBytes) {
                    Fetch.Failed("发布包异常大（${bytes.size / 1024 / 1024} MB），已拒绝")
                } else {
                    Fetch.Ok(bytes)
                }
            }
        } catch (e: Exception) {
            // 1.5.1：别把 `failed to connect to /192.168.x.x:8765 ...` 这种原文交出去。
            Fetch.Failed(WordbookStatusPolicy.describeFailure(e), e)
        }
    }

    // ---- 本地文件 ----

    private fun writeCardsAtomically(bytes: ByteArray): Boolean {
        val target = File(context.filesDir, CardSeedLoader.ASSET_NAME)
        val temp = File(context.filesDir, "${CardSeedLoader.ASSET_NAME}.tmp")
        val backup = File(context.filesDir, "${CardSeedLoader.ASSET_NAME}.bak")
        return runCatching {
            if (target.isFile && target.length() > 0L) {
                target.copyTo(backup, overwrite = true)
            }
            temp.writeBytes(bytes)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrElse {
            runCatching { temp.delete() }
            false
        }
    }

    // ---- 数据库 ----

    private data class ApplyOutcome(
        val contentUpdated: Int = 0,
        val memoryUpdated: Int = 0,
        val userProtected: Int = 0,
        val unknownWords: Int = 0,
        val bookMissing: Boolean = false
    )

    private suspend fun applyToDatabase(payload: CardSeedPayload): ApplyOutcome = database.withTransaction {
        val book = database.bookDao().findByNameAndType(BOOK_NAME, Book.TYPE_IMPORTED)
            ?: return@withTransaction ApplyOutcome(bookMissing = true)

        val words = LinkedHashMap<String, CardSeedWord>()
        payload.words.forEach { word ->
            val key = word.word.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty() && !words.containsKey(key)) words[key] = word
        }

        val wordIds = HashMap<String, Long>()
        words.keys.toList().chunked(QUERY_CHUNK).forEach { chunk ->
            database.wordDao().getWordEntitiesByKeys(chunk).forEach { entity ->
                wordIds[entity.wordKey] = entity.id
            }
        }
        val existingContentIds = database.wordDao().getContentWordIds(book.id).toHashSet()
        val memories = HashMap<Long, WordMemory>()
        wordIds.values.chunked(QUERY_CHUNK).forEach { chunk ->
            database.wordMemoryDao().getByWordIds(chunk).forEach { memory ->
                memories[memory.wordId] = memory
            }
        }

        val contentsToInsert = ArrayList<BookWordContent>()
        val memoriesToInsert = ArrayList<WordMemory>()
        var contentUpdated = 0
        var memoryUpdated = 0
        var userProtected = 0
        var unknownWords = 0
        val now = System.currentTimeMillis()

        words.forEach { (key, word) ->
            val wordId = wordIds[key]
            if (wordId == null) {
                unknownWords++
                return@forEach
            }
            if (existingContentIds.contains(wordId)) {
                database.wordDao().updateMeaningExample(wordId, book.id, word.meaning, word.example)
            } else {
                contentsToInsert.add(
                    BookWordContent(
                        wordId = wordId,
                        bookId = book.id,
                        meaning = word.meaning,
                        example = word.example
                    )
                )
            }
            contentUpdated++

            val approach = word.approach.joinToString("\n") { it.trim() }.trim()
            val collocations = word.collocations.joinToString("\n") { it.trim() }.trim()
            if (approach.isEmpty() && collocations.isEmpty()) return@forEach
            // 1.5.3：用户锁定的「免洗金牌」绝不参与云端词卡热更。
            if (database.memoryLockDao().isLocked(wordId, book.id)) {
                userProtected++
                return@forEach
            }
            val incomingMethod = word.method.trim()

            val existing = memories[wordId]
            when {
                existing == null -> {
                    memoriesToInsert.add(
                        WordMemory(
                            wordId = wordId,
                            approach = approach,
                            collocations = collocations,
                            updatedAt = now,
                            source = WordMemory.SOURCE_SEED,
                            method = incomingMethod
                        )
                    )
                    memoryUpdated++
                }
                WordbookSyncPolicy.canReplaceFromOta(existing) -> {
                    database.wordMemoryDao().updateContentAndMethod(
                        wordId = wordId,
                        approach = approach,
                        collocations = collocations,
                        method = incomingMethod.ifEmpty { existing.method },
                        source = WordMemory.SOURCE_SEED,
                        updatedAt = now
                    )
                    memoryUpdated++
                }
                else -> userProtected++
            }
        }

        if (contentsToInsert.isNotEmpty()) {
            database.wordDao().upsertBookWordContents(contentsToInsert)
        }
        if (memoriesToInsert.isNotEmpty()) {
            database.wordMemoryDao().upsertAll(memoriesToInsert)
        }
        database.bookDao().updateTotalCount(book.id, database.wordDao().getWordCount(book.id))

        ApplyOutcome(
            contentUpdated = contentUpdated,
            memoryUpdated = memoryUpdated,
            userProtected = userProtected,
            unknownWords = unknownWords
        )
    }

    // ---- 工具 ----

    private data class Endpoint(
        val label: String,
        val origin: String,
        val token: String,
        val pin: String,
        val lan: Boolean
    )

    private data class CardManifest(
        val version: Int = 0,
        val book: String = "",
        val count: Int = 0,
        val sha256: String = "",
        val updatedAt: String = ""
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun WordbookSyncResult.messageOf(): String = when (this) {
        is WordbookSyncResult.Success -> message
        is WordbookSyncResult.Failure -> message
    }

    companion object {
        const val BOOK_NAME = WordbookSyncPolicy.BOOK_NAME
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_CARDS_BYTES = 32 * 1024 * 1024
        private const val QUERY_CHUNK = 500

        private val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

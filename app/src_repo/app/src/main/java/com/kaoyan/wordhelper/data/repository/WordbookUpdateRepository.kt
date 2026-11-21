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
import com.kaoyan.wordhelper.data.network.TlsPinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

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
                    lastError = "${endpoint.label}连不上：${manifestFetch.message}"
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
                    lastError = "${endpoint.label}下载词卡失败：${cardsFetch.message}"
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

    private sealed interface Fetch {
        data class Ok(val bytes: ByteArray) : Fetch
        data class Http(val code: Int) : Fetch
        data class Failed(val message: String) : Fetch
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
            Fetch.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
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

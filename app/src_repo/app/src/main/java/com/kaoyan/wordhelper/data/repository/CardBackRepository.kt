package com.kaoyan.wordhelper.data.repository

import android.content.Context
import com.kaoyan.wordhelper.data.model.CardBackCodec
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.CardBackDecodeResult
import com.kaoyan.wordhelper.data.model.CardBackDefaults
import com.kaoyan.wordhelper.data.network.TlsPinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** 一次刷新的结果，用来给用户一句人话。 */
sealed interface CardBackRefreshResult {
    data class Success(
        val revision: Int,
        val blockCount: Int,
        val unknownTypes: List<String>,
        /** 内容与 revision 都没变时不再写盘、不重渲染（契约 §6）。 */
        val unchanged: Boolean = false
    ) : CardBackRefreshResult

    data class Failure(val message: String) : CardBackRefreshResult
}

/**
 * 卡背结构清单的读取与缓存。
 *
 * 取值顺序（见 docs/cardback-schema-1.4.2.md §4）：
 *   HTTP 拉取 → 本地缓存 → 上一份成功的 → 内置默认
 * 任何一步失败都静默往下掉，永远不出现空卡背。
 *
 * 注意：这里只碰文件，不碰数据库，复习进度完全不受影响。
 */
class CardBackRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val _config = MutableStateFlow(CardBackDefaults.config)
    val config: StateFlow<CardBackConfig> = _config.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    /** 电脑端回 401 了：口令不对，别把这种情况说成「电脑没连上」。 */
    @Volatile
    private var authFailed = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        // 手动刷新频率极低，不池化连接：每次都用新连接，避开「复用到已被服务端关掉的连接」这类偶发失败
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()

    private val cacheFile: File
        get() = File(context.filesDir, CardBackCodec.FILENAME)

    private val lastGoodFile: File
        get() = File(context.filesDir, CardBackCodec.LAST_GOOD_FILENAME)

    /** App 启动时调用：只读本地，不发网络请求。 */
    suspend fun bootstrap() {
        withContext(Dispatchers.IO) {
            val fromCache = readAndDecode(cacheFile)
            if (fromCache != null) {
                _config.value = fromCache
                return@withContext
            }
            val fromLastGood = readAndDecode(lastGoodFile)
            if (fromLastGood != null) {
                _config.value = fromLastGood
            }
            // 都读不到就保持内置默认，什么都不用做
        }
    }

    /**
     * 用户点「刷新卡背结构」时调用。
     * @return 给人看的结果；失败时当前卡背保持不变。
     */
    suspend fun refresh(): CardBackRefreshResult {
        if (_refreshing.value) {
            return CardBackRefreshResult.Failure("正在刷新，稍等一下")
        }
        _refreshing.value = true
        authFailed = false
        try {
            val settings = settingsRepository.settingsFlow.first()
            val rawUrl = settings.cardBackUrl
            val url = normalizeUrl(rawUrl)
                ?: return CardBackRefreshResult.Failure("还没填卡背结构地址（我的 → 设置 → 卡背结构地址）")

            // 1.4.7.2 W4：配对行带 pin 时，卡背刷新也必须走 pin 绑定的 HTTPS，
            // 不能把 X-Cardback-Token 明文发给 http://IP:8765。
            val rawPin = settings.cardBackTlsPin.trim()
            val normalizedPin = TlsPinned.normalizePin(rawPin)
            if (rawPin.isNotEmpty() && normalizedPin == null) {
                return CardBackRefreshResult.Failure(
                    "配对行里的 pin 格式不对（应为 64 位十六进制）；请重新粘贴电脑上的整行"
                )
            }
            val pin = normalizedPin.orEmpty()
            val port = settings.cardBackTlsPort.takeIf { it in 1..65535 } ?: 8443
            val requestUrl = pinnedHttpsUrl(url, pin, port)
            if (requestUrl == null) {
                return CardBackRefreshResult.Failure(
                    "这台电脑要求安全连接，但当前地址没有可用的 pin；请重新粘贴电脑上的配对行"
                )
            }
            val body = withContext(Dispatchers.IO) { fetch(requestUrl, settings.cardBackToken, pin) }
                ?: return CardBackRefreshResult.Failure(
                    if (authFailed) {
                        CardbackAuth.HINT
                    } else {
                        "电脑没连上：先确认电脑上的服务开着、手机和电脑在同一个 WiFi；" +
                            "如果电脑换过网络，IP 会变，去电脑上重新复制「手机：粘贴这一行」整行贴过来"
                    }
                )

            return applyRemoteBody(body)
        } finally {
            _refreshing.value = false
        }
    }

    /** 直接用一段 JSON 覆盖当前清单（调试/导入用）。 */
    suspend fun applyRemoteBody(body: String): CardBackRefreshResult {
        val decoded = CardBackCodec.decode(body)
        if (decoded is CardBackDecodeResult.Rejected) {
            return CardBackRefreshResult.Failure("这份结构不能用：${decoded.reason}（已保持原样）")
        }
        val config = (decoded as CardBackDecodeResult.Ok).config

        // 契约 §6：revision 没变大、内容也没变，就什么都不做（不写盘、不重渲染、不谎报已更新）。
        // 内容变了但 revision 没涨（手改 published 文件之类）仍然放行，免得用户改完白改。
        val current = _config.value
        if (config.revision <= current.revision && sameContent(config, current)) {
            return CardBackRefreshResult.Success(
                revision = current.revision,
                blockCount = current.renderableBlocks.size,
                unknownTypes = config.unknownTypes,
                unchanged = true
            )
        }

        withContext(Dispatchers.IO) {
            // 旧的缓存先转成「上一份成功的」，再写新的，写失败也能回退
            if (cacheFile.isFile && cacheFile.length() > 0L) {
                runCatching { cacheFile.copyTo(lastGoodFile, overwrite = true) }
            }
            runCatching { cacheFile.writeText(body) }
        }

        _config.value = config
        val message = buildString {
            append("已更新到第 ${config.revision} 版，共 ${config.renderableBlocks.size} 块")
            if (config.unknownTypes.isNotEmpty()) {
                append("；跳过不认识的块：")
                append(config.unknownTypes.joinToString("、"))
            }
        }
        _lastMessage.value = message
        return CardBackRefreshResult.Success(
            revision = config.revision,
            blockCount = config.renderableBlocks.size,
            unknownTypes = config.unknownTypes
        )
    }

    /** 回到 1.4.1 的原样。 */
    suspend fun restoreDefault() {
        withContext(Dispatchers.IO) {
            if (cacheFile.isFile && cacheFile.length() > 0L) {
                runCatching { cacheFile.copyTo(lastGoodFile, overwrite = true) }
            }
            runCatching { cacheFile.delete() }
        }
        _config.value = CardBackDefaults.config.copy(revision = _config.value.revision + 1)
        _lastMessage.value = "已恢复默认卡背结构"
    }

    fun clearMessage() {
        _lastMessage.value = null
    }

    private fun readAndDecode(file: File): CardBackConfig? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { file.readText() }
            .getOrNull()
            ?.let { CardBackCodec.decode(it) }
            ?.let { if (it is CardBackDecodeResult.Ok) it.config else null }
    }

    private fun fetch(url: String, token: String, pin: String): String? {
        // 偶发连接失效（实测约 7%，重试即好）—— 手动刷新这么低频，重试一次完全划算。
        repeat(2) { attempt ->
            val body = runCatching { fetchOnce(url, token, pin) }.getOrNull()
            if (body != null) return body
            if (attempt == 0) runCatching { Thread.sleep(150) }
        }
        return null
    }

    private fun fetchOnce(url: String, token: String, pin: String): String? {
        val requestClient = if (pin.isNotBlank() && url.startsWith("https://", ignoreCase = true)) {
            TlsPinned.clientFor(pin) ?: return null
        } else {
            client
        }
        val builder = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Connection", "close")
        // 电脑端不在本机时，每个请求都要带口令；没填就照发（本机 127.0.0.1 免票）。
        CardbackAuth.headerValue(token)?.let { builder.header(CardbackAuth.HEADER, it) }
        return requestClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) {
                authFailed = true
                return@use null
            }
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }

    /**
     * 有 pin 时，把明文 HTTP+IP 的卡背地址升级成同主机的 HTTPS:pin 端口。
     * 域名/公网 HTTPS 原样返回；有 pin 但地址不是 IP 也不强行改。
     */
    private fun pinnedHttpsUrl(url: String, pin: String, port: Int): String? {
        if (pin.isBlank()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.trim().orEmpty()
        // 有 pin 且已是 HTTPS：保留原地址（域名由系统信任，IP 由 pin 客户端）。
        if (uri.scheme.equals("https", ignoreCase = true)) return url
        // 有 pin 但地址不是 IP：无法安全升级，也不能退回明文发 token。
        if (host.isEmpty() || !TlsPinned.isIpLiteral(host)) return null
        val path = uri.rawPath.orEmpty().ifBlank { "/${CardBackCodec.FILENAME}" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$host:$port$path$query"
    }

    /** 两份清单「画出来是不是一样」——只看影响渲染的字段，revision/updatedAt 不算。 */
    private fun sameContent(a: CardBackConfig, b: CardBackConfig): Boolean =
        a.schemaVersion == b.schemaVersion &&
            a.book == b.book &&
            a.spacingDp == b.spacingDp &&
            a.motion == b.motion &&
            a.blocks == b.blocks

    companion object {
        /**
         * 把用户填的东西尽量变成一个能用的 URL：
         * - 只填了 `192.168.1.5:8765` → 补 http:// 和 /cardback.json
         * - 只填了 `http://192.168.1.5:8765` → 补 /cardback.json
         * - 填了完整地址 → 原样用（这就是「留接口」：任何公网/隧道地址都能直接用）
         */
        fun normalizeUrl(raw: String?): String? {
            var text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (!text.startsWith("http://", ignoreCase = true) &&
                !text.startsWith("https://", ignoreCase = true)
            ) {
                text = "http://$text"
            }
            val afterScheme = text.substringAfter("://")
            if (afterScheme.isEmpty()) return null
            // 拼接完如果没有路径，补默认文件名
            val pathStart = afterScheme.indexOf('/')
            if (pathStart < 0) {
                text = "$text/${CardBackCodec.FILENAME}"
            }
            return text
        }
    }
}

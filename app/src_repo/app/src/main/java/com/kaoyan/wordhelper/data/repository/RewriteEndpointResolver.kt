package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.LanDiscovery
import com.kaoyan.wordhelper.data.network.OriginTrustPolicy
import com.kaoyan.wordhelper.data.network.TlsPinned
import java.net.URI
import kotlinx.coroutines.flow.first
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** HTTPS 配置地址的 TLS 锚点刷新间隔；pin 轮换时不必等下次冷启动。 */
private const val ANCHOR_REFRESH_MS = 10 * 60_000L
private const val DEFAULT_TLS_PORT = 8443

internal class RewriteEndpointResolver(
    private val settingsRepository: SettingsRepository
) {
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        // 和卡背刷新一样：调用频率低，不池化连接，避开复用已被服务端关掉的连接
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
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

    /**
     * 地址候选 + 口令，一次读够。
     * DataStore 读失败按「没配地址」处理，不把异常抛给调用方。
     */
    suspend fun cardBackEndpoint(rotate: Boolean): CardBackEndpoint? {
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
        val configured = firstPairPinnedOrigin(settings, lanPin, lanPort) ?: settings.cardBackUrl
        val lan = if (rotate && settings.cardBackLanAuto) resolveLan(settings) else null
        if (lan != null) {
            lanPin = lan.pin
            lanPort = lan.port
        }
        val cloudFallback = SyncFallbackPolicy.normalizePublicUrl(settings.wordbookRemoteUrl)
            .takeIf { settings.cardBackToken.isNotBlank() && it.isNotBlank() }
        val safeOrigins = SyncFallbackPolicy.preferredOrigins(
            discovered = lan?.origin,
            active = activeOrigin,
            configured = configured,
            lastGood = settings.cardBackLastGoodUrl,
            cloud = cloudFallback,
            pinned = lanPin.isNotBlank() && settings.cardBackToken.isNotBlank()
        )
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
        TlsPinned.normalizePin(pin) ?: return null
        val origin = CardBackAddress.originOf(settings.cardBackUrl) ?: return null
        val host = runCatching { URI(origin).host }.getOrNull()?.trim().orEmpty()
        if (!TlsPinned.isIpLiteral(host)) return null
        val tlsPort = port.takeIf { it in 1..65535 } ?: DEFAULT_TLS_PORT
        return "https://$host:$tlsPort"
    }

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
        val anchorUrl = sequenceOf(settings.cardBackUrl, settings.wordbookRemoteUrl)
            .mapNotNull { TlsPinned.normalizedHttpsOrigin(it) }
            .firstOrNull { !TlsPinned.isLanHttps(it) }
        val anchorKey = "${anchorUrl}|$expected|${token.hashCode()}"
        if (anchorUrl != null && (
            pin == null ||
                anchorFetchedKey != anchorKey ||
                now - anchorFetchedAt >= ANCHOR_REFRESH_MS
            )
        ) {
            val anchor = runCatching {
                LanDiscovery.fetchAnchor(anchorUrl, token, expected)
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
    suspend fun rememberSuccess(origin: String) {
        SyncStatusStore.reportOrigin(origin)
        activeOrigin = origin
        if (origin == savedLastGood) return
        savedLastGood = origin
        runCatching { settingsRepository.updateCardBackLastGoodUrl(origin) }
    }

    data class CardBackEndpoint(
        val origins: List<String>,
        val token: String,
        val tlsPin: String = "",
        val tlsPort: Int = 8443
    )

    private data class LanEndpoint(val origin: String, val pin: String, val port: Int)

    suspend fun knownInstanceId(): String =
        runCatching { settingsRepository.settingsFlow.first().cardBackInstanceId }.getOrDefault("")

    /**
     * 已配对电脑的私网 http 地址必须先证明身份，才允许把口令发过去。
     * 未配对 / HTTPS 域名保持原行为（首次配对靠用户手填地址）。
     */
    suspend fun originAllowed(origin: String, knownInstance: String, token: String): Boolean {
        if (!OriginTrustPolicy.needsProof(origin, knownInstance)) return true
        return LanDiscovery.verifyOrigin(origin, knownInstance, token)
    }

    /**
     * https + IP 的 LAN 候选：TLS pin 只证明「这张证书」，还要再证明「这个 instance」。
     * 已配对时先做无口令 `/identity?nonce`，验不过就不发 token；未配对保持原行为。
     */
    suspend fun httpsLanIdentityOk(
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
    fun httpClientFor(origin: String, pin: String): OkHttpClient? {
        if (TlsPinned.isLanHttps(origin)) return TlsPinned.clientFor(pin)
        return if (CardBackAddress.isLanOrigin(origin)) lanClient else cloudClient
    }
}

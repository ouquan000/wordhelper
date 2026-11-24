package com.kaoyan.wordhelper.data.repository

import android.util.Log
import com.kaoyan.wordhelper.data.dao.SyncDao
import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.LanDiscovery
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import com.kaoyan.wordhelper.data.network.TlsPinned
import com.kaoyan.wordhelper.data.repository.SyncContracts.ANCHOR_REFRESH_MS
import com.kaoyan.wordhelper.data.repository.SyncContracts.DEFAULT_TLS_PORT
import com.kaoyan.wordhelper.data.repository.SyncContracts.TAG
import java.net.URI
import kotlinx.coroutines.flow.first

internal class SyncEndpointResolver(
    private val settingsRepository: SettingsRepository?,
    private val syncDao: SyncDao,
    private val http: SyncHttpTransport
) {
    /** 1.4.6.1 P1：本次进程里最后一次真正连通的地址（手填地址坏了时靠它兜底）。 */
    @Volatile
    private var activeOrigin: String? = null

    /**
     * 本轮已经通过 `/health` 身份校验的地址。一旦确定，只把请求发到它；
     * 否则伪服务/陌生地址可能先拿到 2xx，后续 pull/push 仍会把 token 发过去。
     */
    @Volatile
    var pinnedOrigin: String? = null
    private var endpointRevisionSeen = settingsRepository?.endpointRevision ?: -1L

    /** 已写进 DataStore 的 last-good；一轮同步要发多次请求，别每次都写盘。 */
    @Volatile
    private var savedLastGood: String? = null

    /** 1.4.7.2 W4：HTTPS 配置地址的 /identity 锚点最多 10 分钟拉一次（pin 轮换兜底）。 */
    @Volatile
    private var anchorFetchedAt = 0L

    @Volatile
    private var anchorFetchedKey = ""

    /**
     * 电脑端 `/health` 的自述（null = 没探到 / 老服务端没有这个接口）。
     *
     * 只用来判断**能力**：服务端版本号还停在 1.4.2，拿版本号比大小只会把新端也判成旧的。
     */
    @Volatile
    var serverHealth: ServerHealth.Info? = null
        private set

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

    suspend fun config(rotate: Boolean = true): SyncConfig? {
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
        val configured = firstPairPinnedOrigin(settings, lanPin, lanPort) ?: settings.cardBackUrl
        val lan = if (rotate && settings.cardBackLanAuto) resolveLan(settings) else null
        if (lan != null) {
            lanPin = lan.pin
            lanPort = lan.port
        }
        val origins = SyncFallbackPolicy.preferredOrigins(
            discovered = lan?.origin,
            active = activeOrigin,
            configured = configured,
            lastGood = settings.cardBackLastGoodUrl,
            cloud = cloudFallback,
            pinned = lanPin.isNotBlank()
        )
        if (origins.isEmpty()) return null
        val publicOrigins = origins.filter { SyncFallbackPolicy.isPublicOrigin(it, publicOrigin) }.toSet()
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

    /**
     * pin 在配对行里，地址主机是 IP 时，直接把配置地址换成
     * `https://host:tlsPort`。这不是发现，是「用用户贴进来的 pin 验证同一台
     * 电脑的 TLS 端点」，所以 instance 空不空都不影响：已配对用户重贴带 pin
     * 的配对行时也必须能升级，否则会拿不到任何候选、同步停摆。
     */
    private fun firstPairPinnedOrigin(settings: UserSettings, pin: String?, port: Int): String? {
        TlsPinned.normalizePin(pin) ?: return null
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
    suspend fun probeHealth(config: SyncConfig): String? {
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
            val originClient = http.httpClientFor(origin, config.tlsPin, config.isPublic(origin))
            if (originClient == null) {
                Log.w(TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            val outcome = http.call(
                build = { http.requestGet(origin + ServerHealth.PATH, config.token) },
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
                    http.publish(SyncPhase.SERVER_TOO_OLD, outcome.code, RetryPolicy.MSG_SERVER_TOO_OLD)
                }
                RetryPolicy.Kind.UNAUTHORIZED -> {
                    http.publish(SyncPhase.UNAUTHORIZED, outcome.code, outcome.message)
                }
                else -> Unit // 5xx / 超时：静默，别用一个探测失败打扰用户
            }
            // 这个候选不可用/身份不对：继续试域名兜底，不能因为一个伪 LAN 候选就停在 token 发送前。
        }
        return null
    }
}

private data class LanEndpoint(val origin: String, val pin: String, val port: Int)

package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.network.RetryPolicy
import okhttp3.MediaType.Companion.toMediaType

internal object SyncContracts {
    internal const val TAG = "SyncRepository"
    internal const val PUSH_PATH = "/sync/push"
    internal const val PULL_PATH = "/sync/pull"
    internal const val DEFAULT_TLS_PORT = 8443

    /** 一次最多推 100 条（契约上限口径，也免得一批太大被 python 端读超时）。 */
    internal const val PUSH_BATCH = 100
    internal const val MAX_PULL_PAGES = 20

    /** HTTPS 配置地址的 TLS 锚点刷新间隔；pin 轮换时不必等下次冷启动。 */
    internal const val ANCHOR_REFRESH_MS = 10 * 60_000L

    /** /health 能力清单里学习记录这条链路真正依赖的两项。 */
    internal const val CAP_SYNC_PUSH = "sync_push"
    internal const val CAP_SYNC_CURSOR = "sync_cursor"

    /** 断网/重连回调常连着抖几次，30 秒内只补一轮。 */

    /**
     * 局域网候选的超时：建连 2 秒、整体 8 秒。
     *
     * 同一个 WiFi 下电脑端要么毫秒级回话，要么根本不在线（换网/路由器改 IP/电脑关机）。
     * 2 秒足够覆盖 ARP + TCP 三次握手，再久就是「不在线」了 —— 这时候的价值
     * 全在「赶紧让位给公网域名」，而不是继续在死地址上耗。
     */
    internal const val LAN_CONNECT_TIMEOUT_SECONDS = 2L
    internal const val LAN_READ_TIMEOUT_SECONDS = 5L
    internal const val LAN_CALL_TIMEOUT_SECONDS = 8L

    /**
     * 公网候选的超时：建连 8 秒、读 20 秒、整体 30 秒。
     *
     * 域名后面通常是 Cloudflare Tunnel/中转，握手与外网 RTT 都比局域网慢一个量级，
     * 用 LAN 那套短超时会把它误判成失败 —— 而它恰恰是出了门唯一能用的出口。
     */
    internal const val PUBLIC_CONNECT_TIMEOUT_SECONDS = 8L
    internal const val PUBLIC_READ_TIMEOUT_SECONDS = 20L
    internal const val PUBLIC_CALL_TIMEOUT_SECONDS = 30L

    internal const val TYPE_PROGRESS = "progress"

    // 与 RewriteRepository 里那两句提示同措辞：同一个毛病不该有两种说法。
    internal const val NO_ADDRESS = "先去「我的」页填电脑地址"
    internal const val UNREACHABLE = "电脑没连上"

    internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // op_id 带 bookId：同一个词在几本书里各有进度，一本书一条 op，不能互相盖掉。
    internal fun opIdOf(wordId: Long, bookId: Long, lastReviewTime: Long): String =
        "p-$wordId-$bookId-$lastReviewTime"
}

internal data class SyncConfig(
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

internal class SyncException(message: String) : Exception(message)

/** 一次请求的结局：归类、状态码、响应体、异常、「下次可试」的时间和给用户的那句话。 */
internal data class HttpOutcome(
    val kind: RetryPolicy.Kind,
    val code: Int?,
    val body: String?,
    val error: Throwable? = null,
    val retryAfterMs: Long? = null,
    val message: String? = null,
    val retryAtEpochMs: Long? = null
)

internal data class SyncProgressRow(
    val type: String?,
    val wordKey: String?,
    val bookName: String?,
    val status: Int?,
    val repetitions: Int?,
    val intervalDays: Int?,
    val nextReviewTime: Long?,
    val easeFactor: Float?,
    val reviewCount: Int?,
    val lastReviewTime: Long?,
    val fsrsStability: Float?,
    val fsrsDifficulty: Float?,
    val fsrsLapses: Int?,
    val fsrsState: Int?
)

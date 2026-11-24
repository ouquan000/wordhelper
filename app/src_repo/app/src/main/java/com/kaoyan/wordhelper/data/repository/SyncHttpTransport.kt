package com.kaoyan.wordhelper.data.repository

import android.util.Log
import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.TlsPinned
import com.kaoyan.wordhelper.data.repository.SyncContracts.JSON_MEDIA_TYPE
import com.kaoyan.wordhelper.data.repository.SyncContracts.LAN_CALL_TIMEOUT_SECONDS
import com.kaoyan.wordhelper.data.repository.SyncContracts.LAN_CONNECT_TIMEOUT_SECONDS
import com.kaoyan.wordhelper.data.repository.SyncContracts.LAN_READ_TIMEOUT_SECONDS
import com.kaoyan.wordhelper.data.repository.SyncContracts.PUBLIC_CALL_TIMEOUT_SECONDS
import com.kaoyan.wordhelper.data.repository.SyncContracts.PUBLIC_CONNECT_TIMEOUT_SECONDS
import com.kaoyan.wordhelper.data.repository.SyncContracts.PUBLIC_READ_TIMEOUT_SECONDS
import com.kaoyan.wordhelper.data.repository.SyncContracts.TAG
import com.kaoyan.wordhelper.data.repository.SyncContracts.UNREACHABLE
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class SyncHttpTransport {
    /**
     * 1.4.7：被 429 限流到什么时候（epoch ms）。
     *
     * 服务端的窗口是「单 IP 每分钟 20 次 / 每天 200 次」，超了要等窗口过去。
     * 这个值大于现在时，[call] 直接短路返回 RATE_LIMITED，不再往电脑上打请求 ——
     * 1.4.6 那次事故就是 App 4 秒一轮把额度打满，越试越糟。
     */
    @Volatile
    private var rateLimitedUntil = 0L

    /** 1.5.2：「立即重试」先清本地冷却，避免按钮在 429 冷却期里空转。 */
    fun clearRateLimit() {
        rateLimitedUntil = 0L
    }

    /**
     * 上一次上报的状态。4 秒一轮自动同步时，同一个失败会重复几十次；
     * 内容完全一样就不重复写 [SyncStatusStore]，免得状态页的时间戳一直跳。
     */
    @Volatile
    private var lastReport: Triple<SyncPhase, Int?, String?>? = null

    /**
     * OkHttpClient 自带连接池与线程池，构造一次就该复用；但只在真的要联网时才建
     * （WordRepository 里那个只入队的实例永远用不到它）。
     * 超时与池参数与 RewriteRepository 保持一致，同一个电脑端不能两套脾气。
     *
     * 1.5.1 起按候选性质分流：[lanClient] 走局域网、[publicClient] 走公网域名，
     * 这个对象只剩 [call] 的兜底默认值（谁忘了传 client 也不会拿到比这更短的超时）。
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

    /**
     * 1.5.1：局域网候选的「短痛」客户端。
     *
     * 同一个 WiFi 下电脑端要么毫秒级回话，要么压根不在线；用上面 4/12/15 秒那套
     * 去等一个已经离线的 IP，一轮同步会先白等十几秒，公网兜底永远轮不到。
     * 所以 LAN 只给 2 秒建连、8 秒整体：打不通就立刻换下一个候选。
     */
    private val lanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(LAN_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LAN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(LAN_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    /**
     * 1.5.1：公网（`settings.wordbookRemoteUrl`）候选的客户端。
     *
     * 域名走 Cloudflare Tunnel 这类中继，握手/首字节本来就更慢，用 LAN 的短超时
     * 会把「其实能通但慢一点」的出口误判成失败。这里放宽到 8/20/30 秒。
     */
    private val publicClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PUBLIC_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PUBLIC_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PUBLIC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 中继同样是「一次一连接」更稳：复用空闲连接只会拿到已关闭的 socket。
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    fun requestGet(url: String, token: String): Request = Request.Builder()
        .url(url)
        // 电脑端是 HTTP/1.0 的一次性连接，明确告诉它用完就断，别让它吊着一个复用不了的空闲连接。
        .header("Connection", "close")
        // 口令头与电脑端改写链路共用 CardbackAuth：头名和 401 时给用户看的话都只有一处定义。
        .apply { CardbackAuth.headerValue(token)?.let { header(CardbackAuth.HEADER, it) } }
        .get()
        .build()

    fun requestPost(url: String, json: String, token: String): Request = Request.Builder()
        .url(url)
        .header("Connection", "close")
        .apply { CardbackAuth.headerValue(token)?.let { header(CardbackAuth.HEADER, it) } }
        .post(json.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    /**
     * 所有联网请求的唯一出口：分类 → 决定要不要重试 → 上报状态 → 返回结局。
     *
     * 规则（与 [RetryPolicy] 一致）：
     * - 2xx 直接返回；401 / 404 / 429 / 其他 4xx **一律不重试**（重试也变不好，只会把限流额度打满）；
     * - 只有 5xx / 408 / 超时 / IO 才按指数退避就地重试，最多 [maxAttempts] 次；
     * - 429 会把「冷却到什么时候」记进 [rateLimitedUntil]：之后的请求在闸内直接短路，
     *   连包都不发（1.4.6 事故就是 App 4 秒一轮把 20 次/分的额度打满）。
     *
     * [maxAttempts] = 1 表示「只问一次结果」，[report] = false 表示这次请求不该影响状态页
     * （单词状态轮询、能力探测这类后台小请求）。
     */
    suspend fun call(
        build: () -> Request,
        maxAttempts: Int = RetryPolicy.DEFAULT_MAX_ATTEMPTS,
        respectRateLimit: Boolean = true,
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
            if (SyncRules.isCoolingDown(respectRateLimit, gate, now)) {
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
                            val message = "HTTP 401：${CardbackAuth.HINT}"
                            if (report) publish(SyncPhase.UNAUTHORIZED, code, message)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.UNAUTHORIZED, code, body, message = message
                            )
                        }
                        RetryPolicy.Kind.NOT_FOUND -> {
                            // 旧服务端没有 /sync/*：不重试，直接告诉用户「电脑端版本过旧」。
                            if (report) publish(SyncPhase.SERVER_TOO_OLD, code, RetryPolicy.MSG_SERVER_TOO_OLD)
                            return@withContext HttpOutcome(
                                RetryPolicy.Kind.NOT_FOUND, code, body,
                                message = RetryPolicy.MSG_SERVER_TOO_OLD
                            )
                        }
                        RetryPolicy.Kind.RATE_LIMITED -> {
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
                            // 5xx / 408：等本轮循环末尾的退避；Retry-After 有就听它的。
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

            // 走到这里一定是可重试的失败（5xx / 408 / 超时 / IO）。
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
            // 可取消的等待（协程取消时立刻退出，不用等满 1 秒）。
            delay(RetryPolicy.delayFor(RetryPolicy.Kind.RETRYABLE, attempt, lastRetryAfter))
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        HttpOutcome(RetryPolicy.Kind.RETRYABLE, lastCode, null)
    }

    /**
     * 按候选地址依次试，谁先「回话」就用谁。
     *
     * 「回话」= 拿到了 HTTP 状态码（哪怕是 401/404/429）：这个地址是活的，换下一个只会在死地址上白等。
     * 一个都没回话（连不上 / 超时）才返回最后一个结局。
     */
    suspend fun sendToOrigins(config: SyncConfig, build: (String) -> Request): HttpOutcome {
        var last: HttpOutcome? = null
        // 有公网域名可落时，局域网候选只打一发：地址不通（换网/路由器改 IP/电脑关机）
        // 时必须尽快把机会让给域名，而不是在死地址上重试 + 退避等上十几秒。
        val failFastLan = config.publicOrigins.isNotEmpty()
        for (origin in config.origins) {
            val isPublic = config.isPublic(origin)
            // https LAN origin 必须走 pin；没有合法 pin 就跳过，绝不回退系统信任的明文/普通 TLS。
            val originClient = httpClientFor(origin, config.tlsPin, isPublic)
            if (originClient == null) {
                Log.w(TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            val outcome = call(
                build = { build(origin) },
                maxAttempts = if (failFastLan && !isPublic) 1 else RetryPolicy.DEFAULT_MAX_ATTEMPTS,
                client = originClient
            )
            last = outcome
            // 只有 2xx 才算「这个地址能用」。但 2xx 也可能来自伪装服务：
            // 不在这里记 last-good，等 /health 解析出 instance_id 并通过身份校验后再记。
            // 限流闸内短路：换地址也没用，直接返回（RATE_LIMITED 已经上报过）。
            // 401/404 只说明这个候选不可用（口令/版本不对），继续试下一个地址（LAN 失败走域名）。
            if (SyncRules.stopAtOrigin(outcome.code, outcome.kind)) return outcome
        }
        return last ?: HttpOutcome(RetryPolicy.Kind.RETRYABLE, null, null, message = UNREACHABLE)
    }

    /**
     * 按 origin 选择客户端：https + IP 的 LAN 必须用 pin；其余按「局域网 / 公网」分两档超时。
     * 返回 null 表示「这是 LAN HTTPS，但没有可用的 pin」，调用方必须跳过而不是降级。
     */
    fun httpClientFor(origin: String, pin: String, isPublic: Boolean = false): OkHttpClient? {
        // pin 优先且不可让位：https://ip 只能信 pin 证明过的那台电脑，
        // 无论它被归到哪一档，都不能换成系统信任或明文。
        if (TlsPinned.isLanHttps(origin)) return TlsPinned.clientFor(pin)
        // 公网走隧道/4G，首包慢，给足预算；局域网要「快失败」好切公网。
        // 既不是设置里的公网域名、又不是内网 IP 的普通域名（例如用户自建 HTTPS）
        // 保持原来的 4/12/15，不因为「没归类」就被误伤成 2 秒超时。
        if (isPublic) return publicClient
        return if (SyncRules.isLanOrigin(origin)) lanClient else client
    }

    /**
     * 上报一次状态。内容没变就不重复写 —— 4 秒一轮自动同步时同一句话会重复几十次，
     * 状态页的时间戳一直跳会让人以为「它一直在重试」。
     */
    fun publish(
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
}

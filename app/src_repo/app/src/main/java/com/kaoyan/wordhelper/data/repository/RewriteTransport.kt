package com.kaoyan.wordhelper.data.repository

import android.util.Log
import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.OriginTrustPolicy
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import com.kaoyan.wordhelper.data.network.TlsPinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
/** /health 探测节流：60 秒最多问一次。 */
private const val HEALTH_PROBE_MIN_INTERVAL_MS = 60_000L

/** 一次请求的结局：归类、状态码、响应体、异常、「下次可试」的时间和给用户的那句话。 */
internal data class RewriteHttpOutcome(
    val kind: RetryPolicy.Kind,
    val code: Int?,
    val body: String?,
    val error: Throwable? = null,
    val retryAfterMs: Long? = null,
    val message: String? = null,
    val retryAtEpochMs: Long? = null
)

internal class RewriteTransport(
    private val endpoints: RewriteEndpointResolver
) {
    /** 电脑端回 401 了：口令不对，别把这种情况混进「电脑没连上」。 */
    @Volatile
    private var authFailed = false

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
    var serverHealth: ServerHealth.Info? = null
        private set

    /** 上一次上报的状态；同一句话不重复写，免得 4 秒轮询把状态页刷成流水账。 */
    @Volatile
    private var lastReport: Triple<SyncPhase, Int?, String?>? = null

    /** 有没有填电脑地址（手填的或上次成功过的都算）；没填就别往队列里塞东西。 */
    suspend fun hasAddress(): Boolean = endpoints.cardBackEndpoint(rotate = true) != null

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
        client: OkHttpClient
    ): RewriteHttpOutcome = withContext(Dispatchers.IO) {
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
                return@withContext RewriteHttpOutcome(
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
                            return@withContext RewriteHttpOutcome(RetryPolicy.Kind.OK, code, body)
                        }
                        RetryPolicy.Kind.UNAUTHORIZED -> {
                            // 调用方只能拿到「没成功」，分不清「电脑没开」和「口令错」——
                            // 这个标记就是给 consumeAuthFailure() 用的（界面据此显示粘连接串）。
                            authFailed = true
                            val message = "HTTP 401：${CardbackAuth.HINT}"
                            if (report) publish(SyncPhase.UNAUTHORIZED, code, message)
                            return@withContext RewriteHttpOutcome(
                                RetryPolicy.Kind.UNAUTHORIZED, code, body, message = message
                            )
                        }
                        RetryPolicy.Kind.NOT_FOUND -> {
                            // 旧服务端没有这个接口（1.4.6 的 /rewrite/feedback 就是这么 404 的 186 次）：
                            // 不重试、不静默，直接说「电脑端版本过旧」。
                            if (report) publish(SyncPhase.SERVER_TOO_OLD, code, RetryPolicy.MSG_SERVER_TOO_OLD)
                            return@withContext RewriteHttpOutcome(
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
                            return@withContext RewriteHttpOutcome(
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
                            return@withContext RewriteHttpOutcome(
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
                return@withContext RewriteHttpOutcome(
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
        RewriteHttpOutcome(RetryPolicy.Kind.RETRYABLE, lastCode, null)
    }

    /**
     * 按候选顺序发一次请求（1.4.6.1 的地址轮试保留）：当前地址 → 手填地址 → 上次成功地址，
     * 前一个连不上才试下一个，成功后把这个地址记成「上次成功」。
     *
     * 谁先「回话」（拿到任何 HTTP 状态码）就用谁 —— 偶发连不上多半是 Python 的 HTTPServer
     * 单线程、上一个请求还没收尾，不应该因为一个地址慢就把整轮同步拖成几个连接超时。
     * `rotate=false` 只打最可信的那一个（4 秒一次的进度轮询不能拖成两个连接超时）。
     */
    suspend fun send(
        path: String,
        payload: String? = null,
        attempts: Int = RetryPolicy.DEFAULT_MAX_ATTEMPTS,
        rotate: Boolean = true,
        respectRateLimit: Boolean = false,
        report: Boolean = true
    ): RewriteHttpOutcome {
        val endpoint = endpoints.cardBackEndpoint(rotate)
            ?: return RewriteHttpOutcome(RetryPolicy.Kind.FATAL, null, null, message = RewriteProtocol.NO_ADDRESS)
        val knownInstance = endpoints.knownInstanceId()
        var last: RewriteHttpOutcome? = null
        for (origin in endpoint.origins) {
            // 已配对过：私网 http 候选先做无口令 /identity?nonce 校验，
            // 验不过就不把口令发给它（防同 WiFi 伪造地址/中继）。
            if (!endpoints.originAllowed(origin, knownInstance, endpoint.token)) continue
            val originClient = endpoints.httpClientFor(origin, endpoint.tlsPin)
            if (originClient == null) {
                Log.w(RewriteProtocol.TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            if (!endpoints.httpsLanIdentityOk(origin, knownInstance, endpoint.token, endpoint.tlsPin)) continue
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
                client = if (path == RewriteProtocol.PROGRESS_PATH && (CardBackAddress.isLanOrigin(origin) || TlsPinned.isLanHttps(origin))) {
                    originClient.newBuilder()
                        .readTimeout(12, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                } else {
                    originClient
                }
            )
            last = outcome
            if (OriginTrustPolicy.canRememberSuccess(outcome.code)) {
                endpoints.rememberSuccess(origin)
                return outcome
            }
            // 限流闸内短路：换地址也没用（限流按 IP 算），直接返回（已经上报过）。
            if (outcome.kind == RetryPolicy.Kind.RATE_LIMITED) return outcome
            if (outcome.code != null) continue
        }
        return last ?: RewriteHttpOutcome(RetryPolicy.Kind.FATAL, null, null, message = RewriteProtocol.NO_ADDRESS)
    }

    /**
     * 1.4.7：GET /health 问出电脑端版本与能力（60 秒最多一次）。
     *
     * 判旧端**不看版本号**（服务端还是 1.4.2，没升）：只看 `/health` 在不在、能力清单缺不缺。
     * 探测失败不阻塞主流程 —— 后面真正的请求会给出最终结论。
     */
    suspend fun probeHealth() {
        val now = System.currentTimeMillis()
        if (now - lastHealthProbeAt < HEALTH_PROBE_MIN_INTERVAL_MS) return
        val endpoint = endpoints.cardBackEndpoint(rotate = true) ?: return
        lastHealthProbeAt = now
        val knownInstance = endpoints.knownInstanceId()
        for (origin in endpoint.origins) {
            if (!endpoints.originAllowed(origin, knownInstance, endpoint.token)) continue
            val originClient = endpoints.httpClientFor(origin, endpoint.tlsPin)
            if (originClient == null) {
                Log.w(RewriteProtocol.TAG, "跳过无 pin 的 LAN HTTPS 候选：${CardBackAddress.originOf(origin) ?: origin}")
                continue
            }
            if (!endpoints.httpsLanIdentityOk(origin, knownInstance, endpoint.token, endpoint.tlsPin)) continue
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
            if (OriginTrustPolicy.canRememberSuccess(outcome.code)) endpoints.rememberSuccess(origin)
            when (outcome.kind) {
                RetryPolicy.Kind.OK -> {
                    val info = ServerHealth.parse(outcome.body)
                    if (info == null) {
                        Log.w(RewriteProtocol.TAG, "/health 回了 200 但读不懂：${outcome.body?.take(120)}")
                    } else {
                        serverHealth = info
                        SyncStatusStore.reportServer(info.version, info.startedAt, info.capabilities)
                        Log.i(RewriteProtocol.TAG, "/health: ${info.version} protocol=${info.protocol} caps=${info.capabilities}")
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
}

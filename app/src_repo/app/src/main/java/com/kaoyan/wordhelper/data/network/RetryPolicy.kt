package com.kaoyan.wordhelper.data.network

import com.google.gson.JsonParser
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlin.random.Random

/**
 * 同步/重写链路的统一重试规则（1.4.7）。
 *
 * 背景：1.4.6 的 App 每隔 4 秒就重打一次，旧服务端没有 `/rewrite/feedback` 时连吃 186 次 404，
 * 18 次一组的 429 又把「每分钟 20 次 / 每天 200 次」的额度打满，用户看到的只是一句「同步失败」。
 *
 * 这里只放**纯函数**：怎么分类、退避多久、下次什么时候可以再试、Retry-After 怎么解。
 * 不碰网络、不碰状态存储、不读系统时钟之外的任何东西 —— 全部可单测。
 *
 * 三个硬规则：
 * 1. 401 不重试（重试一万次口令也是错的）；
 * 2. 404 不重试（电脑端没有这个接口，重试只会把日志刷满，还得告诉用户版本过旧）；
 * 3. 429 至少等 [RATE_LIMIT_MIN_DELAY_MS]，宁可晚同步，不可把额度打满。
 */
object RetryPolicy {

    /** 退避基数：第 1 次重试约 1 秒后。 */
    const val BASE_DELAY_MS = 1_000L

    /** 单次退避上限：5 分钟。再久就该让用户主动点「立即重试」了。 */
    const val MAX_DELAY_MS = 300_000L

    /** 429 之后的最短等待：服务端窗口是 60 秒 20 次，30 秒以内的紧循环毫无意义。 */
    const val RATE_LIMIT_MIN_DELAY_MS = 30_000L

    /** Retry-After 的合理上限（1 天）：再长基本是服务端算错了或被人手改过，不陪它等。 */
    const val MAX_RETRY_AFTER_MS = 24L * 60 * 60 * 1000

    /** 普通失败（5xx / 超时 / IO）就地重试几次：1 次原调用 + 2 次重试。 */
    const val DEFAULT_MAX_ATTEMPTS = 3

    /** 404 唯一该说的一句话：手机端要能一眼区分「连不上」和「电脑端太旧」。 */
    const val MSG_SERVER_TOO_OLD =
        "电脑端版本过旧：没有这个同步接口（HTTP 404）。在电脑上重启 cardback-editor（1.4.7 或更新）后再试"

    /**
     * `/health` 明确回了 200、但能力清单里没有我们要的这项。
     *
     * 比「等它回 404」体面：请求干脆不发，队列原样留在手机上，状态页说清楚缺什么。
     * 注意只有**拿到过能力清单**才能这么说 —— 清单为空一律当「不知道」，不能当「没有」。
     */
    fun missingCapabilityMessage(capability: String): String =
        "电脑端版本过旧：没有「$capability」能力（/health 里没报）。在电脑上重启 cardback-editor（1.4.7 或更新）后再试"

    /** 一次请求的归类。 */
    enum class Kind {
        /** 2xx：成功。 */
        OK,

        /** 401：口令不对，要重新粘贴连接串。不自动重试。 */
        UNAUTHORIZED,

        /** 404：电脑端没有这个接口（多半还是旧进程）。不自动重试，提示版本过旧。 */
        NOT_FOUND,

        /** 429：被限流。按 Retry-After / 退避等待，且**不得**小于 [RATE_LIMIT_MIN_DELAY_MS]。 */
        RATE_LIMITED,

        /** 5xx / 408 / 超时 / IO / 连不上：按指数退避就地重试。 */
        RETRYABLE,

        /** 400 / 403 / 其他 4xx：重试也不会变好，直接失败。 */
        FATAL
    }

    /**
     * 按 HTTP 状态码归类。
     *
     * [httpCode] 为 null 或 ≤0 表示「压根没拿到响应」（连不上、DNS 失败、超时），归 [Kind.RETRYABLE]。
     * 403 不并进 401：服务端的 403 是「跨站 POST 被拒」，换口令没用，别误导用户。
     */
    fun classify(httpCode: Int?): Kind {
        val code = httpCode ?: return Kind.RETRYABLE
        if (code <= 0) return Kind.RETRYABLE
        return when {
            code in 200..299 -> Kind.OK
            code == 401 -> Kind.UNAUTHORIZED
            code == 404 -> Kind.NOT_FOUND
            code == 429 -> Kind.RATE_LIMITED
            code == 408 || code in 500..599 -> Kind.RETRYABLE
            else -> Kind.FATAL
        }
    }

    /**
     * 第 [attempt] 次重试（从 0 开始）的纯指数退避：1s、2s、4s … 封顶 5 分钟。
     *
     * 加抖动请用 [jitteredBackoffMs]：这一层刻意保持确定性，方便断言。
     */
    fun backoffMs(attempt: Int): Long {
        if (attempt <= 0) return BASE_DELAY_MS
        // 用移位而不是 Math.pow：Math.pow 在 2^31 之后会溢出成奇怪的东西。
        val shift = attempt.coerceAtMost(20)
        val delay = BASE_DELAY_MS shl shift
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * equal jitter：`base/2 + random(base/2)`。
     *
     * 只加一半抖动，保证「至少等 base/2」—— 4 秒一轮的紧循环正是上一版的事故来源。
     */
    fun jitteredBackoffMs(attempt: Int, random: Random = Random.Default): Long {
        val base = backoffMs(attempt)
        val half = base / 2
        if (half <= 0L) return base
        return half + random.nextLong(half + 1)
    }

    /**
     * 解析 `Retry-After`（秒数或 HTTP-date，两种都要）。
     *
     * - 纯数字（"30" / " 30 "）→ 秒 × 1000；
     * - HTTP-date → 目标时刻减 [nowEpochMs]，过期的返回 0，超长截到 [MAX_RETRY_AFTER_MS]；
     * - 空串 / 垃圾 / 负数 → null（交给调用方用退避兜底）。
     *
     * HTTP-date 三种历史格式都认（RFC 1123 / RFC 850 / asctime）；日期串里可能出现的
     * `GMT` / `UTC` / `Z` / `+0800` 四种时区写法也都认 —— 服务端用什么写法我们控制不了。
     */
    fun parseRetryAfterMs(raw: String?, nowEpochMs: Long): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 1) 秒数优先：数字串不可能是日期（HTTP-date 至少 20 个字符）。
        if (text.all { it.isDigit() }) {
            val seconds = text.toLongOrNull() ?: return null
            return (seconds * 1_000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }

        // 2) HTTP-date。
        val target = parseHttpDateEpochMs(text) ?: return null
        return (target - nowEpochMs).coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    /**
     * 这一次失败之后应该等多久（毫秒）。
     *
     * - [Kind.RATE_LIMITED]：`max(30s, Retry-After ?: 抖动退避)` —— 服务端说了算，但至少 30 秒；
     * - [Kind.RETRYABLE]：有 Retry-After 就听它的，否则抖动退避；
     * - 其他（401/404/FATAL/OK）：0，等也没用。
     */
    fun delayFor(
        kind: Kind,
        attempt: Int,
        retryAfterMs: Long? = null,
        random: Random = Random.Default
    ): Long = when (kind) {
        Kind.RATE_LIMITED -> maxOf(
            RATE_LIMIT_MIN_DELAY_MS,
            retryAfterMs?.takeIf { it > 0L } ?: jitteredBackoffMs(attempt, random)
        )
        Kind.RETRYABLE -> retryAfterMs?.takeIf { it > 0L } ?: jitteredBackoffMs(attempt, random)
        else -> 0L
    }

    /** 「下次可以再试」的绝对时刻；不值得等的归类返回 null。 */
    fun nextAttemptAtEpochMs(
        nowEpochMs: Long,
        kind: Kind,
        attempt: Int,
        retryAfterMs: Long? = null,
        random: Random = Random.Default
    ): Long? {
        val delay = delayFor(kind, attempt, retryAfterMs, random)
        return if (delay > 0L) nowEpochMs + delay else null
    }

    /** 这次失败是否值得「就地」再打一次（不阻塞调用方太久）。401/404/429 一律不就地重试。 */
    fun shouldRetryInline(kind: Kind, attempt: Int, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): Boolean =
        kind == Kind.RETRYABLE && attempt + 1 < maxAttempts.coerceAtLeast(1)

    /** 给用户看的人话：「约 42 秒后重试」。不足 1 秒按 1 秒说，绝不出现「0 秒后重试」。 */
    fun waitSeconds(delayMs: Long): Long = ((delayMs + 999L) / 1_000L).coerceAtLeast(1L)

    /** 「（HTTP 429）」这种尾巴；没有状态码（连不上）时返回空串。 */
    fun httpTag(httpCode: Int?): String =
        if (httpCode == null || httpCode <= 0) "" else "（HTTP $httpCode）"

    /** 429 的统一文案：含「HTTP 429」字样，状态页靠它归类。 */
    fun rateLimitedMessage(delayMs: Long): String =
        "电脑在限流（HTTP 429）：单 IP 每分钟 20 次 / 每天 200 次，约 ${waitSeconds(delayMs)} 秒后自动重试"

    /**
     * 普通可重试失败（5xx / 超时 / IO）的统一文案。
     *
     * 故意不说「N 秒后重试」：同步的触发点是学习动作、进设置页、网络恢复，
     * 没有一条按秒计时的调度，承诺具体秒数只会让人在状态页干等。
     */
    fun retryingMessage(reason: String, httpCode: Int?): String =
        "$reason${httpTag(httpCode)}，稍后自动重试"

    /**
     * 把异常翻成一句人话（同步/重写两条链路共用）。
     *
     * 只做「能不能一眼看懂」的判断，不猜根因；协程取消必须原样抛出去（不是失败）。
     */
    fun describe(error: Throwable?): String = when (error) {
        null -> "未知错误"
        is SocketTimeoutException -> "电脑响应超时"
        is InterruptedIOException -> "电脑响应超时"
        is UnknownHostException -> "地址解析不了"
        is ConnectException -> "电脑没连上"
        is NoRouteToHostException -> "电脑没连上"
        is SSLException -> "HTTPS 证书有问题"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    /** 一次请求的完整失败描述（给日志/状态页用）。 */
    fun failureReason(kind: Kind, httpCode: Int?, error: Throwable?): String = when (kind) {
        Kind.NOT_FOUND -> MSG_SERVER_TOO_OLD
        Kind.RATE_LIMITED -> rateLimitedMessage(RATE_LIMIT_MIN_DELAY_MS)
        else -> describe(error) + httpTag(httpCode)
    }

    private fun parseHttpDateEpochMs(text: String): Long? {
        // asctime（"Sun Nov  6 08:49:37 1994"）里日期部分是两格空格：先折叠，两种写法都成立。
        val squeezed = text.replace(Regex("\\s+"), " ").trim()

        // 时区：先剥掉尾巴，剩下的按 LocalDateTime 解析，最后统一换到 UTC。
        val zone = when {
            squeezed.endsWith(" GMT", ignoreCase = true) ||
                squeezed.endsWith(" UTC", ignoreCase = true) -> ZoneOffset.UTC
            squeezed.endsWith("Z") -> ZoneOffset.UTC
            else -> offsetOf(squeezed) ?: ZoneOffset.UTC
        }
        val body = squeezed
            .removeSuffix("Z")
            .replace(Regex("\\s+(GMT|UTC)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[+-]\\d{4}$"), "")
            .trim()

        for (formatter in DATE_FORMATTERS) {
            try {
                return LocalDateTime.parse(body, formatter)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                // 换下一种格式。
            }
        }
        // 最后试一次带时区的完整解析（"2026-11-09T16:29:50+08:00" 这种 ISO 串）。
        return try {
            ZonedDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun offsetOf(text: String): ZoneOffset? {
        val match = Regex("([+-])(\\d{2})(\\d{2})$").find(text) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        val minutes = match.groupValues[3].toIntOrNull() ?: return null
        return try {
            ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
        } catch (_: Exception) {
            null
        }
    }

    private val DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
        // RFC 1123：Sun, 06 Nov 1994 08:49:37 GMT
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss", java.util.Locale.US),
        // RFC 850：Sunday, 06-Nov-94 08:49:37 GMT（两位年份，java.time 按 2000 前后就近解释）
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss", java.util.Locale.US),
        // asctime：Sun Nov  6 08:49:37 1994
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US),
        // 去掉星期的手写串（有些代理会这么发）
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", java.util.Locale.US)
    )

    /** 供单测/日志用：把毫秒换成本地时区的人话时间，避免各调用方各写一份。 */
    fun formatLocal(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}

/**
 * 电脑端 `/health` 的自报信息（1.4.7 服务端新增，老进程没有这个接口 → 404）。
 *
 * 解析必须容错：状态页靠它显示「电脑端 1.4.7 / 有 feedback 能力」，
 * 而不是因为多了一个我们不认识的字段就整段丢掉。
 */
object ServerHealth {

    const val PATH = "/health"

    /** 一台电脑的自述。字段全部可空 —— 老服务端可能只有 `ok`。 */
    data class Info(
        val version: String? = null,
        val startedAt: String? = null,
        val capabilities: Set<String> = emptySet(),
        /** 握手协议号（服务端 `protocol`）。只用来看/记，不做「小于多少就报错」的判断。 */
        val protocol: Int? = null
    )

    /**
     * 能力是否可用。
     *
     * 三态：true = 清单里有；false = 拿到了清单但里面没有；null = 没探到 / 清单为空（不知道）。
     * 调用方**只能**在 false 时才走「缺能力」分支 —— 老服务端没有 /health，清单永远是空的。
     */
    fun supports(info: Info?, capability: String): Boolean? {
        if (info == null) return null
        if (info.capabilities.isEmpty()) return null
        return capability in info.capabilities
    }

    /**
     * 解析 `/health` 的 JSON；不是 JSON、`ok` 明确为 false、根不是对象时返回 null。
     *
     * 兼容两种 capabilities 写法：`["rewrite","feedback"]` 和 `{"rewrite": true}`。
     */
    fun parse(body: String?): Info? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty() || text.first() != '{') return null
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            if (obj.has("ok") && !obj.get("ok").isJsonNull) {
                val ok = obj.get("ok")
                if (ok.isJsonPrimitive) {
                    val primitive = ok.asJsonPrimitive
                    if (primitive.isBoolean && !primitive.asBoolean) return null
                    if (primitive.isString && primitive.asString.equals("false", ignoreCase = true)) {
                        return null
                    }
                }
            }
            Info(
                version = str(obj, "version") ?: str(obj, "server"),
                startedAt = str(obj, "started_at") ?: str(obj, "startedAt"),
                capabilities = capabilitiesOf(obj),
                protocol = int(obj, "protocol") ?: int(obj, "health_protocol")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun int(obj: com.google.gson.JsonObject, key: String): Int? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try {
            val primitive = obj.get(key).asJsonPrimitive
            if (primitive.isNumber) primitive.asInt else primitive.asString.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun str(obj: com.google.gson.JsonObject, key: String): String? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try {
            obj.get(key).asString.trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun capabilitiesOf(obj: com.google.gson.JsonObject): Set<String> {
        val node = obj.get("capabilities") ?: return emptySet()
        if (node.isJsonNull) return emptySet()
        val out = LinkedHashSet<String>()
        try {
            if (node.isJsonArray) {
                node.asJsonArray.forEach { element ->
                    val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                        ?: return@forEach
                    if (primitive.isString) {
                        primitive.asString.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    }
                    // 数组里混布尔值/数字：没见过，忽略。
                }
            } else if (node.isJsonObject) {
                node.asJsonObject.entrySet().forEach { entry ->
                    val value = entry.value
                    val on = when {
                        value.isJsonNull -> false
                        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                            value.asJsonPrimitive.asBoolean
                        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                            !value.asJsonPrimitive.asString.equals("false", ignoreCase = true)
                        else -> true
                    }
                    if (on) entry.key.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            } else if (node.isJsonPrimitive && node.asJsonPrimitive.isString) {
                // 某些轻量服务端会写成 "rewrite,feedback"。
                node.asJsonPrimitive.asString.split(',', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { out.add(it) }
            }
        } catch (_: Exception) {
            // 能力清单坏了不影响主流程。
        }
        return out
    }
}

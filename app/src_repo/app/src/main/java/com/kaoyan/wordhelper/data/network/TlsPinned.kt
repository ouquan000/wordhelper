package com.kaoyan.wordhelper.data.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.NetworkInterface
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 局域网 HTTPS 的证书固定（SPKI pin，1.4.7.2 W4）。
 *
 * 局域网里没有可信 CA，IP 地址也不能靠域名证书证明身份。这里把「电脑端证书公钥的
 * SHA-256」当作唯一身份：TLS 握手时只接受叶子证书 SPKI 与 pin 完全相等的链，
 * 因此中间人即使能劫持同网段流量，也拿不出一张同 SPKI 的证书。
 *
 * pin 必须来自受信任的配置地址（HTTPS `/identity` 的 `tls_spki_sha256`），
 * 绝不能从 UDP 广播或明文 HTTP 里取；本类只负责「拿到 pin 之后」的校验与连接。
 *
 * 纯 JVM 实现，不依赖 Android；单测可直接覆盖 SPKI 计算与 pin 规范化。
 */
object TlsPinned {

    private const val CONNECT_TIMEOUT_SECONDS = 4L
    private const val READ_TIMEOUT_SECONDS = 12L
    private const val CALL_TIMEOUT_SECONDS = 15L
    private const val TOKEN_HEADER = "X-Cardback-Token"

    private val HEX_64 = Regex("^[0-9a-f]{64}$")
    private val IPV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

    /** pin -> client。证书固定按 pin 缓存，避免每次请求都新建 SSLContext/连接池。 */
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * pin 绑定的是证书身份，hostname 只是路由信息：局域网只能填 IP，而 IP 不在证书
     * SAN 里是常态。所以接受任意 host（包括 IPv4/IPv6 字面量），真正的身份校验在
     * [SpkiTrustManager] 里完成。
     */
    internal val pinBoundHostnameVerifier = HostnameVerifier { _, _ -> true }

    /** 规范化 pin：去空白、转小写；必须是 64 位 hex，否则返回 null。 */
    fun normalizePin(raw: String?): String? {
        val pin = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return pin.takeIf { HEX_64.matches(it) }
    }

    fun isValidPin(pin: String?): Boolean = normalizePin(pin) != null

    /** 叶子证书公钥的 DER SubjectPublicKeyInfo 的 SHA-256（小写 hex）。 */
    fun spkiSha256(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 按 pin 取（并缓存）OkHttpClient。pin 非法时返回 null —— 调用方必须把
     * null 当成「不能连接」，绝不能退回系统信任的 client。
     */
    fun clientFor(pin: String): OkHttpClient? {
        val normalized = normalizePin(pin) ?: return null
        return clients.getOrPut(normalized) { buildClient(normalized) }
    }

    internal fun buildClient(pin: String): OkHttpClient {
        val trustManager = SpkiTrustManager(pin)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<X509TrustManager>(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(pinBoundHostnameVerifier)
            // 不接受重定向：否则 30x 可能把带 token 的请求降级到 http://。
            .followRedirects(false)
            .followSslRedirects(false)
            // 局域网服务端是短连接；复用已被服务端关掉的 keep-alive 只会偶发失败。
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 只对 https + IP 字面量的 LAN origin 使用 pin；域名仍走系统信任。 */
    fun isLanHttps(origin: String): Boolean {
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        return isIpLiteral(uri.host)
    }

    internal fun isIpLiteral(host: String?): Boolean {
        val value = host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        if (value.isBlank()) return false
        if (IPV4.matches(value)) {
            return value.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return value.contains(':')
    }

    /** 从受信 HTTPS 配置地址取到的局域网锚点。 */
    data class Anchor(val pin: String, val port: Int, val instanceId: String)

    /**
     * 从受信 HTTPS 配置地址的 `/identity?nonce=` 取局域网 TLS 锚点。
     *
     * 先不带 token：私网直连时可走无口令 nonce 分支；公网域名会 401，此时再带
     * token 重试——HTTPS 域名证书已经证明对端主机，且只把口令发给用户配置的地址。
     * 无论哪条路，都要用本地口令验 proof / proof_peer，并比对 instance 后才信 pin。
     */
    suspend fun fetchAnchor(
        anchorUrl: String,
        expectedId: String,
        token: String,
        client: OkHttpClient
    ): Anchor? = withContext(Dispatchers.IO) {
        if (token.isBlank() || expectedId.isBlank()) return@withContext null
        val base = normalizedHttpsOrigin(anchorUrl) ?: return@withContext null
        val nonce = UUID.randomUUID().toString().replace("-", "")
        try {
            var response = client.newCall(identityRequest(base, nonce)).execute()
            if (response.code == 401) {
                response.close()
                response = client.newCall(identityRequestWithToken(base, nonce, token)).execute()
            }
            response.use { reply ->
                if (reply.code != 200) return@use null
                val body = reply.body?.string() ?: return@use null
                val json = parseObject(body) ?: return@use null
                if (json.string("service") != "cardback-editor") return@use null
                val instanceId = json.string("instance_id")
                if (instanceId != expectedId) return@use null
                val proof = json.string("proof")
                val proofPeer = json.string("proof_peer")
                if (proof.isNullOrBlank() || proofPeer.isNullOrBlank()) return@use null
                if (!constantTimeEquals(proof, hmacHex(token, nonce))) return@use null
                val peerOk = localAddresses().any { ip ->
                    constantTimeEquals(proofPeer, hmacHex(token, "$nonce|$ip"))
                }
                if (!peerOk) return@use null
                val pin = normalizePin(json.string("tls_spki_sha256")) ?: return@use null
                val port = json.int("tls_port") ?: return@use null
                if (port !in 1..65535) return@use null
                Anchor(pin, port, instanceId)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 用 pin 校验一个 https LAN origin 的 `/identity?nonce=`。
     *
     * 注意：nonce 请求**不带 token**。只有服务端返回的 proof（HMAC(token, nonce)）
     * 与 proof_peer（HMAC(token, "nonce|本机IP")）都核对通过，才返回 instance_id；
     * 任何一步失败返回 null。proof_peer 绑定服务端看到的直连 IP，中继转发会失效。
     */
    suspend fun verifyIdentity(
        origin: String,
        expectedId: String,
        token: String,
        pin: String
    ): String? = withContext(Dispatchers.IO) {
        // expectedId 为空 = 首次配对：pin 是从可信一次性配对行拿到的，HTTPS 握手本身
        // 已证明对端持有这张证书；这里只补 proof/proof_peer，并把 instance_id 学回来。
        if (token.isBlank()) return@withContext null
        val normalizedPin = normalizePin(pin) ?: return@withContext null
        val base = normalizedHttpsOrigin(origin) ?: return@withContext null
        val client = clientFor(normalizedPin) ?: return@withContext null
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val request = identityRequest(base, nonce)
        try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return@use null
                val body = response.body?.string() ?: return@use null
                val json = parseObject(body) ?: return@use null
                if (json.string("service") != "cardback-editor") return@use null
                val instanceId = json.string("instance_id")
                if (instanceId.isNullOrBlank()) return@use null
                if (expectedId.isNotBlank() && instanceId != expectedId) return@use null
                val proof = json.string("proof")
                val proofPeer = json.string("proof_peer")
                if (proof.isNullOrBlank() || proofPeer.isNullOrBlank()) return@use null
                if (!constantTimeEquals(proof, hmacHex(token, nonce))) return@use null
                val peerOk = localAddresses().any { ip ->
                    constantTimeEquals(proofPeer, hmacHex(token, "$nonce|$ip"))
                }
                if (peerOk) instanceId else null
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun normalizedHttpsOrigin(raw: String): String? {
        val origin = CardBackAddress.originOf(raw) ?: return null
        return origin.takeIf { it.startsWith("https://", ignoreCase = true) }
    }

    /**
     * 身份探测请求本身**不带 token**：先证明对端知道口令，再把口令交出去。
     * 单测直接断言这个请求没有 [TOKEN_HEADER]，避免以后有人顺手加上。
     */
    internal fun identityRequest(base: String, nonce: String): Request = Request.Builder()
        .url("$base/identity?nonce=$nonce")
        .header("Connection", "close")
        .get()
        .build()

    /**
     * 公网 HTTPS 域名的 `/identity` 仍要求口令；只有无口令请求 401 后才用它重试。
     * 目标必须是用户配置的 HTTPS 地址，且不跟随重定向。
     */
    private fun identityRequestWithToken(base: String, nonce: String, token: String): Request =
        Request.Builder()
            .url("$base/identity?nonce=$nonce")
            .header("Connection", "close")
            .header(TOKEN_HEADER, token.trim())
            .get()
            .build()

    private fun parseObject(body: String): JsonObject? {
        val text = body.trim()
        if (!text.startsWith("{")) return null
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString.trim()
    }

    private fun JsonObject.int(name: String): Int? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return runCatching { element.asInt }.getOrNull()
    }

    /** TLS 层唯一的信任来源：叶子证书 SPKI 必须与 pin 完全一致。 */
    internal class SpkiTrustManager(private val expectedPin: String) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not used")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            // 固定的是公钥，不豁免有效期：过期证书仍然拒绝。
            leaf.checkValidity()
            val actual = spkiSha256(leaf)
            if (!constantTimeEquals(actual, expectedPin.lowercase(Locale.ROOT))) {
                throw CertificateException("TLS SPKI pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    internal fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    /** 本机直连时服务端可能看到的地址；含 loopback 以兼容模拟器/本机调试。 */
    internal fun localAddresses(): List<String> {
        val out = linkedSetOf("127.0.0.1", "::1")
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .forEach { addr ->
                    val host = addr.hostAddress ?: return@forEach
                    if (host.isNotBlank()) out += host
                }
        }
        return out.toList()
    }

    private fun hmacHex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

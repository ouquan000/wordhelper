package com.kaoyan.wordhelper.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 同 WiFi 下找电脑（1.4.7.2 W4）。
 *
 * 电脑端在 UDP 8767 上应答 [MAGIC]；应答里只有 service/tls_port/instance_id，不含口令，
 * 也不含 TLS pin。UDP 只负责给出候选 IP，**不能作为身份依据**：
 * 手机必须用本地已保存的 SPKI pin 建立 HTTPS，再要求 /identity?nonce= 返回
 * HMAC(token, nonce) 与 HMAC(token, "nonce|本机IP") 证明对方同时持有口令并看到直连地址。
 *
 * 只对已经配对过（本地有 cardBackInstanceId + cardBackTlsPin）的电脑做自动 HTTPS 发现；
 * 第一次配对仍走手填地址/口令。没有 pin 时直接返回 null，**绝不退回明文 HTTP**。
 */
object LanDiscovery {

    private const val TAG = "LanDiscovery"
    private const val MAGIC = "CARDBACK_EDITOR_DISCOVER_V1"
    private const val DISCOVERY_PORT = 8767
    private const val WAIT_MS = 900
    private const val HTTP_TIMEOUT_MS = 1500
    private const val CACHE_MS = 30_000L

    /** 与 CardbackAuth.HEADER 一致；network 层不反向依赖 repository，故在这里再声明一次。 */
    private const val TOKEN_HEADER = "X-Cardback-Token"

    data class Found(val origin: String, val instanceId: String)

    /** 从受信任的 HTTPS 配置地址拿到的局域网 TLS 锚点。 */
    data class TlsAnchor(val pin: String, val port: Int, val instanceId: String)

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedKey = ""

    @Volatile
    private var cached: Found? = null

    private val anchorClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 锚点请求带 token，绝不能跟随 30x 到 http:// 或陌生地址。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 只在 WiFi 下试；蜂窝网络没有广播域，直接返回 null。
     *
     * [tlsPin] 为空/非法时**不广播**：没有 pin 就无法证明 HTTPS 对端身份，
     * 不能为了兼容旧端退回明文 HTTP。发现结果按 pin+端口 缓存 30 秒。
     */
    suspend fun discover(
        context: Context,
        token: String,
        expectedInstanceId: String,
        tlsPin: String,
        tlsPort: Int = 8443
    ): Found? = withContext(Dispatchers.IO) {
        val pin = TlsPinned.normalizePin(tlsPin) ?: return@withContext null
        if (token.isBlank() || expectedInstanceId.isBlank()) return@withContext null
        val fallbackPort = tlsPort.takeIf { it in 1..65535 } ?: 8443
        val now = System.currentTimeMillis()
        val cacheKey =
            "$expectedInstanceId|${token.hashCode()}|$pin|$fallbackPort|${networkKey(context)}"
        if (now - cachedAt < CACHE_MS && cachedKey == cacheKey) return@withContext cached

        val targets = broadcastTargets(context)
        if (targets.isEmpty()) {
            cache(null, cacheKey, now)
            return@withContext null
        }

        val replies = mutableListOf<Reply>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = WAIT_MS
                val payload = MAGIC.toByteArray(Charsets.UTF_8)
                targets.forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                    }
                }
                val deadline = System.currentTimeMillis() + WAIT_MS
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val body = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(body)
                        val port = json.optInt("port", 0)
                        val tlsReplyPort = json.optInt("tls_port", 0)
                        val addr = packet.address
                        val id = json.optString("instance_id")
                        if (json.optString("service") == "cardback-editor" &&
                            port in 1..65535 &&
                            addr is Inet4Address &&
                            id.startsWith("cb-")
                        ) {
                            replies += Reply(addr, port, tlsReplyPort, id)
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {
                        // 非本服务的包 / 坏包：继续等下一个
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UDP discover failed", t)
            return@withContext null
        }

        // 只认真实配对过的 instance_id；UDP 里报的 id 只是候选，后面还要 TLS pin + HMAC 证明。
        for (reply in replies.distinctBy { it.addr.hostAddress to it.port }) {
            if (reply.instanceId != expectedInstanceId) continue
            val port = reply.tlsPort.takeIf { it in 1..65535 } ?: fallbackPort
            val origin = "https://${reply.addr.hostAddress}:$port"
            val instanceId = TlsPinned.verifyIdentity(origin, expectedInstanceId, token, pin)
                ?: continue
            val found = Found(origin, instanceId)
            cache(found, cacheKey, System.currentTimeMillis())
            Log.i(TAG, "found $origin instance=$instanceId")
            return@withContext found
        }
        cache(null, cacheKey, System.currentTimeMillis())
        null
    }

    /**
     * 从用户配置的地址拉局域网 TLS 锚点（1.4.7.2 W4）。
     *
     * 只接受 `https://` 的 [anchorUrl]，使用系统信任的 OkHttp（域名证书）。
     * 请求**不带 token**：`/identity?nonce=` 返回 proof/proof_peer，本机用已有口令
     * 验 HMAC，确认是已配对电脑后才信任响应里的 pin/port。这样即使地址填错到陌生
     * 主机，也不会先把口令交出去。绝不从 UDP 广播或明文 HTTP 取 pin。
     */
    suspend fun fetchAnchor(
        anchorUrl: String,
        token: String,
        expectedInstanceId: String
    ): TlsAnchor? = withContext(Dispatchers.IO) {
        val anchor = TlsPinned.fetchAnchor(anchorUrl, expectedInstanceId, token, anchorClient)
            ?: return@withContext null
        TlsAnchor(anchor.pin, anchor.port, anchor.instanceId)
    }

    /** 纯解析：便于单测覆盖「instance 不匹配 / pin 非法 / 端口越界」等拒绝路径。 */
    internal fun parseAnchor(body: String, expectedInstanceId: String): TlsAnchor? {
        val text = body.trim()
        if (!text.startsWith("{")) return null
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        if (json.string("instance_id") != expectedInstanceId) return null
        val pin = TlsPinned.normalizePin(json.string("tls_spki_sha256")) ?: return null
        val port = json.int("tls_port") ?: return null
        if (port !in 1..65535) return null
        return TlsAnchor(pin, port, expectedInstanceId)
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

    private fun cache(found: Found?, key: String, at: Long) {
        cachedAt = at
        cachedKey = key
        cached = found
    }

    private fun networkKey(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        return cm.activeNetwork?.toString().orEmpty().ifBlank { "none" }
    }

    private fun broadcastTargets(context: Context): List<Inet4Address> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = cm.activeNetwork ?: return emptyList()
        val props = cm.getLinkProperties(active) ?: return emptyList()
        val hasWifi = props.interfaceName?.startsWith("wlan") == true ||
            props.interfaceName?.startsWith("ap") == true
        if (!hasWifi) return emptyList()
        val result = LinkedHashSet<Inet4Address>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .filterIsInstance<Inet4Address>()
                .forEach(result::add)
        }
        // 有些 ROM 不给 interfaceAddresses.broadcast，补一个全局广播兜底。
        if (result.isEmpty()) {
            runCatching { result += InetAddress.getByName("255.255.255.255") as Inet4Address }
        }
        return result.toList()
    }

    /**
     * 在发口令前核对一个候选地址的电脑身份。
     *
     * 仅保留给**旧版私网 http:// 候选**的兼容路径：先不带 token 走 `/identity?nonce`，
     * 验证 proof_peer。新发现的 LAN 一律走 [TlsPinned.verifyIdentity] 的 HTTPS pin 路径。
     */
    suspend fun verifyOrigin(origin: String, expectedId: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            if (token.isBlank() || expectedId.isBlank()) return@withContext false
            verify(origin, expectedId, token) != null
        }

    /**
     * 先不带口令打 `/identity?nonce=`，用本地口令验 HMAC；验过才认为这个 IP
     * 是已配对的那台电脑。整个过程口令不出本机。
     */
    private fun verify(origin: String, expectedId: String, token: String): String? {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$origin/identity?nonce=$nonce").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val id = json.optString("instance_id")
            val proofPeer = json.optString("proof_peer")
            if (json.optString("service") != "cardback-editor" || id != expectedId) return null
            if (proofPeer.isEmpty()) return null
            // proof_peer 绑定的是「服务端看到的直连 IP」：攻击者转发 nonce 时，
            // 真电脑看到的是攻击者 IP，手机用自己 IP 算不出同一个值，中继即失效。
            val verified = localAddresses().any { ip ->
                val want = hmacHex(token, "$nonce|$ip")
                MessageDigest.isEqual(
                    want.toByteArray(Charsets.UTF_8),
                    proofPeer.toByteArray(Charsets.UTF_8)
                )
            }
            if (!verified) return null
            id
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 本机直连时服务端可能看到的地址；含 loopback 以兼容模拟器/本机调试。 */
    private fun localAddresses(): List<String> {
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

    private data class Reply(
        val addr: Inet4Address,
        val port: Int,
        val tlsPort: Int,
        val instanceId: String
    )
}

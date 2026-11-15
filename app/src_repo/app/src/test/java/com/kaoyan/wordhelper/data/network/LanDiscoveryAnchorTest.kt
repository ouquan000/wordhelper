package com.kaoyan.wordhelper.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点与发现边界的纯 JVM 单测：不联网、不装 Android。
 *
 * 重点证明三件事：
 * 1. 非 HTTPS 地址永远拿不到 pin；
 * 2. instance_id 对不上时整个锚点被拒；
 * 3. 身份探测请求不带 token（错误 pin 也拿不到口令）。
 */
class LanDiscoveryAnchorTest {

    private val pin = "9a9310cefb6781f5e8be6c6a1daae801675a602a390315e9c8234d536740aa3b"

    @Test
    fun parseAnchor_acceptsValidPayloadAndNormalizesPin() {
        val anchor = LanDiscovery.parseAnchor(
            """{"instance_id":"cb-abc","tls_spki_sha256":"${pin.uppercase()}","tls_port":9443}""",
            "cb-abc"
        )
        assertEquals(pin, anchor?.pin)
        assertEquals(9443, anchor?.port)
        assertEquals("cb-abc", anchor?.instanceId)
    }

    @Test
    fun parseAnchor_rejectsInstanceMismatch() {
        val body = """{"instance_id":"cb-other","tls_spki_sha256":"$pin","tls_port":8443}"""
        assertNull(LanDiscovery.parseAnchor(body, "cb-abc"))
    }

    @Test
    fun parseAnchor_rejectsMalformedPinAndPort() {
        assertNull(
            LanDiscovery.parseAnchor(
                """{"instance_id":"cb-abc","tls_spki_sha256":"${pin.dropLast(1)}","tls_port":8443}""",
                "cb-abc"
            )
        )
        assertNull(
            LanDiscovery.parseAnchor(
                """{"instance_id":"cb-abc","tls_spki_sha256":"$pin","tls_port":0}""",
                "cb-abc"
            )
        )
        assertNull(
            LanDiscovery.parseAnchor(
                """{"instance_id":"cb-abc","tls_spki_sha256":"$pin","tls_port":65536}""",
                "cb-abc"
            )
        )
    }

    @Test
    fun fetchAnchor_neverTakesPinFromPlainHttp() = runBlocking {
        assertNull(LanDiscovery.fetchAnchor("http://192.168.1.20:8080", "token", "cb-abc"))
        assertNull(LanDiscovery.fetchAnchor("192.168.1.20:8080", "token", "cb-abc"))
    }

    @Test
    fun noPinMeansNoLanDiscovery() {
        // discover 在广播之前就归一化 pin；非法/空 pin 直接 null，不会退回明文 HTTP。
        assertNull(TlsPinned.normalizePin(""))
        assertNull(TlsPinned.normalizePin("not-a-pin"))
        assertTrue(TlsPinned.normalizePin(pin) != null)
    }

    @Test
    fun wrongPinCannotObtainToken() {
        // 身份探测请求不带 token；TLS 层由 SPKI 精确匹配，pin 不对在发 HTTP 之前就握手失败。
        val request = TlsPinned.identityRequest("https://192.168.1.20:8443", "nonce")
        assertNull(request.header("X-Cardback-Token"))
        assertEquals("GET", request.method)
    }
}

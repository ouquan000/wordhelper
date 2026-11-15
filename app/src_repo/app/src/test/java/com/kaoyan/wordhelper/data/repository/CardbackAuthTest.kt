package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 1.4.7.2 W4：电脑端配对行可以带局域网 TLS 锚点。
 *
 * pin 是证书公钥摘要，不是口令；放在 `#` 片段里，手机只粘一次。
 * 这里锁住解析，防止以后把 `&pin=...` 误当成口令的一部分。
 */
class CardbackAuthTest {

    private val pin = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun parsesUrlTokenPinAndPort() {
        val info = CardbackAuth.parsePairInfo(
            "http://192.168.1.20:8765/cardback.json#tok123&pin=$pin&tls_port=8443"
        )
        assertEquals("http://192.168.1.20:8765", info.url)
        assertEquals("tok123", info.token)
        assertEquals(pin, info.tlsPin)
        assertEquals(8443, info.tlsPort)
    }

    @Test
    fun keepsLegacyLineWithoutPin() {
        val info = CardbackAuth.parsePairInfo("http://192.168.1.20:8765#tok123")
        assertEquals("tok123", info.token)
        assertNull(info.tlsPin)
        assertNull(info.tlsPort)
    }

    @Test
    fun rejectsBadPinAndPortButKeepsToken() {
        val info = CardbackAuth.parsePairInfo(
            "http://192.168.1.20:8765#tok123&pin=nothex&tls_port=70000"
        )
        assertEquals("tok123", info.token)
        assertNull(info.tlsPin)
        assertNull(info.tlsPort)
    }

    @Test
    fun firstFragmentPartIsStillToken() {
        val info = CardbackAuth.parsePairInfo("http://ip:8765#tok123&pin=$pin")
        assertEquals("tok123", info.token)
        assertEquals(pin, info.tlsPin)
    }
}

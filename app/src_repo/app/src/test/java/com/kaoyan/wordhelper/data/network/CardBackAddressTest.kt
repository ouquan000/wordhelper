package com.kaoyan.wordhelper.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.6.1 地址兜底的纯逻辑：归一化、候选顺序、单发请求选哪个。
 *
 * 这些规则决定「失败后先试哪个地址、成功后把什么写回 last-good」，
 * 写错会让用户以为地址丢了（或者更糟：拿全局/旧地址冒充）。
 */
class CardBackAddressTest {

    @Test
    fun `originOf keeps scheme host and port`() {
        assertEquals("http://192.168.1.5:8765", CardBackAddress.originOf("http://192.168.1.5:8765"))
        assertEquals("https://example.com", CardBackAddress.originOf("https://example.com"))
    }

    @Test
    fun `originOf adds http scheme when missing`() {
        assertEquals("http://192.168.1.5:8765", CardBackAddress.originOf("192.168.1.5:8765"))
        assertEquals("http://192.168.1.5:8765", CardBackAddress.originOf("  192.168.1.5:8765  "))
    }

    @Test
    fun `originOf drops path and file name`() {
        assertEquals(
            "http://192.168.1.5:8765",
            CardBackAddress.originOf("http://192.168.1.5:8765/cardback.json")
        )
        assertEquals(
            "http://192.168.1.5:8765",
            CardBackAddress.originOf("192.168.1.5:8765/rewrite/status")
        )
    }

    @Test
    fun `originOf lowercases scheme`() {
        assertEquals(
            "http://192.168.1.5:8765",
            CardBackAddress.originOf("HTTP://192.168.1.5:8765")
        )
    }

    @Test
    fun `originOf rejects blank and unparsable input`() {
        assertNull(CardBackAddress.originOf(null))
        assertNull(CardBackAddress.originOf(""))
        assertNull(CardBackAddress.originOf("   "))
        assertNull(CardBackAddress.originOf("http://"))
        assertNull(CardBackAddress.originOf("http://not a url:8765"))
    }

    @Test
    fun `candidates try session address then manual then last good`() {
        val result = CardBackAddress.candidates(
            active = "192.168.1.9:8765",
            current = "192.168.1.5:8765",
            lastGood = "192.168.1.7:8765"
        )
        assertEquals(
            listOf(
                "http://192.168.1.9:8765",
                "http://192.168.1.5:8765",
                "http://192.168.1.7:8765"
            ),
            result
        )
    }

    @Test
    fun `candidates try cloud fallback after manual and before last good`() {
        val result = CardBackAddress.candidates(
            active = "192.168.1.9:8765",
            current = "192.168.1.5:8765",
            lastGood = "192.168.1.7:8765",
            cloudFallback = "https://sync.ouquansdiary.cn"
        )
        assertEquals(
            listOf(
                "http://192.168.1.9:8765",
                "http://192.168.1.5:8765",
                "https://sync.ouquansdiary.cn",
                "http://192.168.1.7:8765"
            ),
            result
        )
    }

    @Test
    fun `candidates dedupe cloud fallback against the same manual address`() {
        val result = CardBackAddress.candidates(
            active = null,
            current = "https://sync.ouquansdiary.cn/cardback.json",
            lastGood = null,
            cloudFallback = "https://sync.ouquansdiary.cn/"
        )
        assertEquals(listOf("https://sync.ouquansdiary.cn"), result)
    }

    @Test
    fun `candidates skip cloud fallback when it is null or blank`() {
        // 未配对（没有 token）时调用方传 null：候选里不能凭空多出域名。
        assertEquals(
            listOf("http://192.168.1.5:8765"),
            CardBackAddress.candidates(
                active = null,
                current = "192.168.1.5:8765",
                lastGood = null,
                cloudFallback = null
            )
        )
        assertTrue(
            CardBackAddress.candidates(
                active = null,
                current = null,
                lastGood = null,
                cloudFallback = ""
            ).isEmpty()
        )
    }

    @Test
    fun `candidates dedupe the same address written differently`() {
        val result = CardBackAddress.candidates(
            active = null,
            current = "http://192.168.1.5:8765/cardback.json",
            lastGood = "192.168.1.5:8765"
        )
        assertEquals(listOf("http://192.168.1.5:8765"), result)
    }

    @Test
    fun `candidates skip blanks and stay empty when nothing configured`() {
        assertTrue(CardBackAddress.candidates(null, null, null).isEmpty())
        assertTrue(CardBackAddress.candidates("", "  ", null).isEmpty())
        assertEquals(
            listOf("http://192.168.1.7:8765"),
            CardBackAddress.candidates(null, "", "192.168.1.7:8765")
        )
    }

    @Test
    fun `preferred trusts a verified address before the manual one`() {
        // 本次会话连通过 → 直接用它。
        assertEquals(
            "http://192.168.1.9:8765",
            CardBackAddress.preferred("192.168.1.9:8765", "192.168.1.5:8765", "192.168.1.7:8765")
        )
        // 本次会话还没成功过：手填的是用户最新意图，优先；last-good 可能正是被换掉的旧地址。
        assertEquals(
            "http://192.168.1.5:8765",
            CardBackAddress.preferred(null, "192.168.1.5:8765", "192.168.1.7:8765")
        )
        // 手填被清空时记住的地址顶上；什么都没有就是 null（调用方据此提示去填地址）。
        assertEquals("http://192.168.1.7:8765", CardBackAddress.preferred(null, "", "192.168.1.7:8765"))
        assertNull(CardBackAddress.preferred(null, null, null))
    }
}

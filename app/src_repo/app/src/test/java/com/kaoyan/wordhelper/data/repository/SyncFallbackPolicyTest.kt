package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5.1「学习记录同步局域网优先 → 自动切公网」的两条纯规则。
 *
 * 这两件事错了不会崩，只会「默默不同步」：公网地址没被认出来，出了门就一轮轮
 * 打不通局域网；或者把局域网 IP 当成公网域名塞进长超时档，在家反而先白等 8 秒。
 * 所以把规范化与判定拿出来单测锁死。
 */
class SyncFallbackPolicyTest {

    @Test
    fun `空地址与无效地址一律规范化为空串`() {
        // 空串就是「没有公网兜底」，调用方据此不加这个候选。
        assertEquals("", SyncFallbackPolicy.normalizePublicUrl(null))
        assertEquals("", SyncFallbackPolicy.normalizePublicUrl(""))
        assertEquals("", SyncFallbackPolicy.normalizePublicUrl("   "))
        // 只有协议没有主机：解析不出 host，等于没配。
        assertEquals("", SyncFallbackPolicy.normalizePublicUrl("https://"))
    }

    @Test
    fun `大小写与尾部斜杠归一成 origin`() {
        // 用户手填的域名大小写、结尾斜杠都不该影响后面的 host 比对。
        assertEquals(
            "https://sync.example.cn",
            SyncFallbackPolicy.normalizePublicUrl("  HTTPS://Sync.Example.CN/  ")
        )
        assertEquals(
            "https://sync.example.cn",
            SyncFallbackPolicy.normalizePublicUrl("sync.example.cn")
        )
    }

    @Test
    fun `填的是完整卡片文件地址时只留 origin`() {
        // 设置页那个输入框允许贴完整 cardback 地址，同步接口挂在同一个 origin 上。
        assertEquals(
            "https://sync.example.cn",
            SyncFallbackPolicy.normalizePublicUrl("https://sync.example.cn/cards/cet6_cards.json")
        )
        assertEquals(
            "https://sync.example.cn:8443",
            SyncFallbackPolicy.normalizePublicUrl("https://sync.example.cn:8443")
        )
    }

    @Test
    fun `公网域名只接受 https`() {
        // token 要跟着请求发出去，明文 HTTP 域名不可接受 —— 直接当作没配。
        assertEquals("", SyncFallbackPolicy.normalizePublicUrl("http://sync.example.cn"))
        assertEquals("https://sync.example.cn", SyncFallbackPolicy.normalizePublicUrl("https://sync.example.cn"))
    }

    @Test
    fun `按 origin 判定公网候选 大小写不敏感且不认局域网 IP`() {
        val public = SyncFallbackPolicy.normalizePublicUrl("https://sync.example.cn")

        // 大小写/路径不影响判定。
        assertTrue(SyncFallbackPolicy.isPublicOrigin("https://SYNC.example.cn", public))
        assertTrue(SyncFallbackPolicy.isPublicOrigin("https://sync.example.cn/cards/manifest.json", public))
        // 局域网 IP、明文地址都不是公网域名。
        assertFalse(SyncFallbackPolicy.isPublicOrigin("http://192.168.1.7:8765", public))
        assertFalse(SyncFallbackPolicy.isPublicOrigin("https://192.168.1.7:8443", public))
        // 端口不同就是另一个源，不能当成公网兜底。
        assertFalse(SyncFallbackPolicy.isPublicOrigin("https://sync.example.cn:8443", public))
        // 没配公网时不存在「公网候选」。
        assertFalse(SyncFallbackPolicy.isPublicOrigin("https://sync.example.cn", ""))
    }
}

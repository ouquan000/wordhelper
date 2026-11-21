package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.5.1：默认公网同步地址的策略单测。
 *
 * 覆盖两件事：
 * 1) [RemoteUrlPolicy.resolve] —— 「用户主动清空」不能被默认值填回；
 * 2) [RemoteUrlPolicy.normalizeBase] —— 各种粘贴写法的归一，以及非法输入必须为空。
 */
class RemoteUrlPolicyTest {

    // ---------- resolve：存过 vs 没存过 ----------

    @Test
    fun resolve_storedEmptyString_doesNotFallBackToBuildDefault() {
        // 空串是「用户主动关掉公网地址」的显式标记，绝不能被默认值重新填回。
        assertEquals("", RemoteUrlPolicy.resolve("", "https://default.example.cn"))
    }

    @Test
    fun resolve_storedNull_fallsBackToBuildDefault() {
        assertEquals(
            "https://default.example.cn",
            RemoteUrlPolicy.resolve(null, "https://default.example.cn")
        )
    }

    @Test
    fun resolve_storedNull_normalizesBuildDefault() {
        // 注入的默认值也走同一套归一：多打的斜杠/误贴的卡片文件都要被修掉。
        assertEquals(
            "https://default.example.cn",
            RemoteUrlPolicy.resolve(null, "default.example.cn/cards/manifest.json")
        )
    }

    @Test
    fun resolve_storedNullAndEmptyBuildDefault_returnsEmpty() {
        assertEquals("", RemoteUrlPolicy.resolve(null, ""))
    }

    @Test
    fun resolve_storedNullAndBlankBuildDefault_returnsEmpty() {
        assertEquals("", RemoteUrlPolicy.resolve(null, "   "))
    }

    @Test
    fun resolve_storedValue_returnedVerbatim() {
        // 存过的值原样返回（不再二次归一），避免每次读设置都改写用户数据。
        assertEquals("https://a.cn/x/", RemoteUrlPolicy.resolve("https://a.cn/x/", "https://b.cn"))
    }

    @Test
    fun resolve_storedNullAndInvalidBuildDefault_returnsEmpty() {
        assertEquals("", RemoteUrlPolicy.resolve(null, "not a url"))
    }

    @Test
    fun resolve_storedEmptyString_beatsEvenValidBuildDefault() {
        assertEquals("", RemoteUrlPolicy.resolve("", "a.cn"))
    }

    // ---------- normalizeBase：空白与非法 ----------

    @Test
    fun normalize_emptyInput_returnsEmpty() {
        assertEquals("", RemoteUrlPolicy.normalizeBase(""))
        assertEquals("", RemoteUrlPolicy.normalizeBase("   "))
        assertEquals("", RemoteUrlPolicy.normalizeBase("\t\n"))
    }

    @Test
    fun normalize_invalidInput_returnsEmpty() {
        // 解析不出 host 一律丢弃：脏值进设置会让同步永远失败。
        assertEquals("", RemoteUrlPolicy.normalizeBase("http://"))
        assertEquals("", RemoteUrlPolicy.normalizeBase("https://"))
        assertEquals("", RemoteUrlPolicy.normalizeBase("not a url"))
        assertEquals("", RemoteUrlPolicy.normalizeBase("://foo"))
        assertEquals("", RemoteUrlPolicy.normalizeBase("/cards/manifest.json"))
    }

    // ---------- normalizeBase：补 scheme ----------

    @Test
    fun normalize_bareHost_addsHttpsScheme() {
        assertEquals("https://x.cn", RemoteUrlPolicy.normalizeBase("x.cn"))
    }

    @Test
    fun normalize_keepsExplicitHttpScheme() {
        assertEquals("http://x.cn", RemoteUrlPolicy.normalizeBase("http://x.cn"))
    }

    @Test
    fun normalize_trimsSurroundingWhitespace() {
        assertEquals("https://x.cn", RemoteUrlPolicy.normalizeBase("  https://x.cn  "))
    }

    // ---------- normalizeBase：尾部斜杠 ----------

    @Test
    fun normalize_trimsTrailingSlash() {
        assertEquals("https://x.cn", RemoteUrlPolicy.normalizeBase("https://x.cn/"))
        assertEquals("https://x.cn", RemoteUrlPolicy.normalizeBase("https://x.cn//"))
        assertEquals("https://x.cn/foo", RemoteUrlPolicy.normalizeBase("https://x.cn/foo/"))
    }

    // ---------- normalizeBase：误贴卡片文件路径 ----------

    @Test
    fun normalize_cutsManifestJsonPath() {
        assertEquals(
            "https://x.cn",
            RemoteUrlPolicy.normalizeBase("https://x.cn/cards/manifest.json")
        )
    }

    @Test
    fun normalize_cutsCet6CardsJsonPath() {
        assertEquals(
            "https://x.cn",
            RemoteUrlPolicy.normalizeBase("https://x.cn/cards/cet6_cards.json")
        )
    }

    @Test
    fun normalize_cutsCardPathButKeepsOriginPrefix() {
        // 站点挂在子路径下（Cloudflare Tunnel 指到 https://x.cn/foo）：前缀必须留着。
        assertEquals(
            "https://x.cn/foo",
            RemoteUrlPolicy.normalizeBase("https://x.cn/foo/cards/cet6_cards.json")
        )
    }

    @Test
    fun normalize_cutIsCaseInsensitive() {
        assertEquals(
            "https://x.cn",
            RemoteUrlPolicy.normalizeBase("https://x.cn/CARDS/MANIFEST.JSON")
        )
    }

    @Test
    fun normalize_otherJsonUnderCards_isKept() {
        // 只认那两个具体文件，别的路径不动（未来换文件名时不要误砍）。
        assertEquals(
            "https://x.cn/cards/other.json",
            RemoteUrlPolicy.normalizeBase("https://x.cn/cards/other.json")
        )
    }

    // ---------- normalizeBase：路径前缀 / 端口 / 大小写 ----------

    @Test
    fun normalize_keepsPathPrefix() {
        assertEquals("https://x.cn/foo", RemoteUrlPolicy.normalizeBase("https://x.cn/foo"))
        assertEquals("https://x.cn/foo", RemoteUrlPolicy.normalizeBase("x.cn/foo"))
    }

    @Test
    fun normalize_keepsPort() {
        assertEquals("https://x.cn:8443/foo", RemoteUrlPolicy.normalizeBase("x.cn:8443/foo"))
    }

    @Test
    fun normalize_lowercasesSchemeAndHostButKeepsPathCase() {
        assertEquals("https://x.cn/Foo", RemoteUrlPolicy.normalizeBase("HTTPS://X.CN/Foo"))
    }

    @Test
    fun normalize_dropsQueryAndFragment() {
        assertEquals("https://x.cn/foo", RemoteUrlPolicy.normalizeBase("https://x.cn/foo?q=1#frag"))
    }
}

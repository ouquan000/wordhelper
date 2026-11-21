package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 词卡热更「错误人话化」的纯 JVM 单测（1.5.1）。
 *
 * 1.5.0 的事故是：底层英文异常（`failed to connect to /192.168.1.5:8765 ...`）
 * 被原样塞进 `Failure.message`，用户在「我的」页看到的是一句看不懂的英文。
 * 这里逐条钉住映射结果 —— 文案变了测试就会红，避免又退回英文。
 */
class WordbookStatusPolicyTest {

    /** 用假 HTTP 状态异常覆盖 401：真实请求里 401 是响应不是异常。 */
    private class FakeHttpStatusFailure(
        override val httpCode: Int,
        message: String
    ) : IOException(message), WordbookStatusPolicy.HttpStatusFailure

    @Test
    fun `socket timeout is explained as unreachable`() {
        val text = WordbookStatusPolicy.describeFailure(SocketTimeoutException("timeout"))
        assertEquals(WordbookStatusPolicy.MSG_UNREACHABLE, text)
        assertTrue("不应该把英文异常原文透给用户", !text.contains("timeout"))
    }

    @Test
    fun `connection refused is explained as unreachable`() {
        val text = WordbookStatusPolicy.describeFailure(
            ConnectException("failed to connect to /192.168.1.5 (port 8765) after 2000ms")
        )
        assertEquals(WordbookStatusPolicy.MSG_UNREACHABLE, text)
        assertTrue(!text.contains("192.168.1.5"))
    }

    @Test
    fun `unknown host points at the public address`() {
        assertEquals(
            WordbookStatusPolicy.MSG_HOST_UNRESOLVED,
            WordbookStatusPolicy.describeFailure(UnknownHostException("xxx.trycloudflare.com"))
        )
    }

    @Test
    fun `ssl handshake failure is reported as certificate problem`() {
        assertEquals(
            WordbookStatusPolicy.MSG_TLS,
            WordbookStatusPolicy.describeFailure(
                SSLHandshakeException("java.security.cert.CertPathValidatorException")
            )
        )
    }

    @Test
    fun `http 401 asks to paste the pairing line again`() {
        // 两条路径都要通：显式传码，以及异常自己带码。
        assertEquals(
            WordbookStatusPolicy.MSG_UNAUTHORIZED,
            WordbookStatusPolicy.describeFailure(null, httpCode = 401)
        )
        assertEquals(
            WordbookStatusPolicy.MSG_UNAUTHORIZED,
            WordbookStatusPolicy.describeFailure(FakeHttpStatusFailure(401, "Unauthorized"))
        )
        assertEquals(
            WordbookStatusPolicy.MSG_UNAUTHORIZED,
            WordbookStatusPolicy.describeFailure(
                IOException("wrapped", FakeHttpStatusFailure(401, "Unauthorized"))
            )
        )
    }

    @Test
    fun `wrapped network exception is still recognised`() {
        val wrapped = IOException("call failed", SocketTimeoutException("Read timed out"))
        assertEquals(WordbookStatusPolicy.MSG_UNREACHABLE, WordbookStatusPolicy.describeFailure(wrapped))
    }

    @Test
    fun `unknown failure is prefixed and shortened`() {
        val text = WordbookStatusPolicy.describeFailure(
            IllegalStateException("something went wrong " + "x".repeat(200))
        )
        assertTrue("应以「同步失败：」开头", text.startsWith("同步失败："))
        assertTrue("原因要截断，不能糊一屏", text.length <= "同步失败：".length + 41)
        // 多行 message 要压成一行，否则在「我的」页会被撑出奇怪的空白。
        val multiline = WordbookStatusPolicy.describeFailure(IllegalStateException("a\nb\nc"))
        assertTrue(!multiline.contains("\n"))
    }

    @Test
    fun `null or blank error still gives a readable sentence`() {
        assertEquals("同步失败：未知错误", WordbookStatusPolicy.describeFailure(null))
        assertEquals("同步失败：未知错误", WordbookStatusPolicy.describeFailure(RuntimeException()))
    }
}

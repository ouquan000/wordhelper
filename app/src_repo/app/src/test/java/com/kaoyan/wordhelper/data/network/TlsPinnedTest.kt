package com.kaoyan.wordhelper.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 纯 JVM 单测：不联网、不依赖 Android。
 *
 * 证书是仓库内固定测试证书（仅测试用），其 SPKI SHA-256 见 [PIN]。
 */
class TlsPinnedTest {

    private val certificate: X509Certificate by lazy {
        val factory = CertificateFactory.getInstance("X.509")
        factory.generateCertificate(ByteArrayInputStream(CERT_PEM.toByteArray(Charsets.UTF_8)))
            as X509Certificate
    }

    @Test
    fun spkiSha256_matchesKnownPin() {
        assertEquals(PIN, TlsPinned.spkiSha256(certificate))
    }

    @Test
    fun normalizePin_acceptsMixedCaseAndWhitespace() {
        assertEquals(PIN, TlsPinned.normalizePin("  ${PIN.uppercase()}  "))
        assertTrue(TlsPinned.isValidPin(PIN))
    }

    @Test
    fun normalizePin_rejectsNon64Hex() {
        assertNull(TlsPinned.normalizePin(null))
        assertNull(TlsPinned.normalizePin(""))
        assertNull(TlsPinned.normalizePin(PIN.dropLast(1)))
        assertNull(TlsPinned.normalizePin(PIN + "0"))
        assertNull(TlsPinned.normalizePin("z".repeat(64)))
    }

    @Test
    fun trustManager_acceptsOnlyExactSpki() {
        TlsPinned.SpkiTrustManager(PIN).checkServerTrusted(arrayOf(certificate), "RSA")

        var rejected = false
        try {
            TlsPinned.SpkiTrustManager("00".repeat(32))
                .checkServerTrusted(arrayOf(certificate), "RSA")
        } catch (_: CertificateException) {
            rejected = true
        }
        assertTrue("SPKI 不匹配必须拒绝握手", rejected)
    }

    @Test
    fun hostnameVerifier_allowsIpLiteralBecausePinBindsIdentity() {
        assertTrue(TlsPinned.pinBoundHostnameVerifier.verify("192.168.1.20", null))
        assertTrue(TlsPinned.pinBoundHostnameVerifier.verify("fe80::1", null))
    }

    @Test
    fun isLanHttps_onlyIpLiteralHttps() {
        assertTrue(TlsPinned.isLanHttps("https://192.168.1.20:8443"))
        assertTrue(TlsPinned.isLanHttps("https://[fe80::1]:8443"))
        assertFalse(TlsPinned.isLanHttps("http://192.168.1.20:8443"))
        assertFalse(TlsPinned.isLanHttps("https://card.example.com:8443"))
        assertFalse(TlsPinned.isLanHttps("https://999.1.1.1:8443"))
    }

    @Test
    fun clientFor_cachesByNormalizedPinAndRejectsInvalid() {
        val first = TlsPinned.clientFor(PIN)
        assertNotNull(first)
        assertSame(first, TlsPinned.clientFor(PIN.uppercase()))
        assertNull(TlsPinned.clientFor("not-a-pin"))
    }

    @Test
    fun identityProbe_neverCarriesToken() {
        // 先用 pin 证明对端身份，之后才允许把口令交给它；探测请求本身绝不能带 token。
        val request = TlsPinned.identityRequest("https://10.0.0.2:8443", "abc123")
        assertNull(request.header("X-Cardback-Token"))
        assertEquals("https://10.0.0.2:8443/identity?nonce=abc123", request.url.toString())
    }

    private companion object {
        const val PIN = "9a9310cefb6781f5e8be6c6a1daae801675a602a390315e9c8234d536740aa3b"

        val CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDETCCAfmgAwIBAgIUCQJZ+0q+1EvRv6H890+UGF6s1rwwDQYJKoZIhvcNAQEL
            BQAwGDEWMBQGA1UEAwwNY2FyZGJhY2stdGVzdDAeFw0yNjExMTAxNTQ3NDdaFw0z
            NjExMDcxNTQ3NDdaMBgxFjAUBgNVBAMMDWNhcmRiYWNrLXRlc3QwggEiMA0GCSqG
            SIb3DQEBAQUAA4IBDwAwggEKAoIBAQCiqQVD0/4IFDuYpZQ7iqMlDAH6156MNuY8
            iNzs4IzXRamLlfL+AbKAHFNtaNAiIwS9cLv71o/5UQR0t48dzwlFLLkcvFi1fqJm
            DIGbp//7uDhD+k+ZY/Je5QyHzSkfHcXRxYRWzcorqyuzyTMuCOG09LJZTW8EZt/x
            pT3VdWzUoAV9QIP3KAzwHGDlkVXyFdhds1yXaORLti8NEz3a/9VD3XWcV+0boh8E
            yxcT8aPRPefQ4fSmcYOvZcVZAGzTMLNcpIW0BbYzzXh8oYFNzVU2++S7WQh3nuvX
            3wBb1MJh7OGDQCFpH62PY+trrz+TWNgXvHNwMJwsrEQkCwzJIaMPAgMBAAGjUzBR
            MB0GA1UdDgQWBBS/4yK/sfT6PqnRm7AgZQwXIKlzjDAfBgNVHSMEGDAWgBS/4yK/
            sfT6PqnRm7AgZQwXIKlzjDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUA
            A4IBAQCDsEwcY3ASPlLppmTRASymT9kOoiCzZlxrJIA/Q98O9RLqyZpBY7AUFfte
            TSpTnYtNdal4KcIdakKbsScHoQH0mCxozMLeSG4Oc3EEGwNco+xW39HuNofxqQBz
            MXPVpNYDb8kVYOW+SFY0bEwiSub9fGHoKXExx8GMmkH0kfe20C8h9/7ZLazMRydV
            eucj1P3VBPt+4UEB7S4fHRJG1NeEyzPiOfo8/g1pJXkYN753X4ninLWqNxJYdl72
            iNq48OlLGFoeAzqMs2Ds7YNMquGsMcd7ZH8cR291vkpkEBk0aNZSl4a1GNF0Acdu
            LtrWGIbS97IlDFXdxurKznBadC8L
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}

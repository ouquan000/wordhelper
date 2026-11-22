package com.kaoyan.wordhelper.data.network

import java.net.URI

/**
 * 同步地址的信任规则：只有「已配对电脑 + 私网明文 http」才需要先做
 * /identity?nonce 挑战；HTTPS 域名、首次配对（没有 instance id）和本机回环保持原行为。
 *
 * 单独抽出来是为了能在 JVM 单测里锁住这两条容易写反的规则。
 */
internal object OriginTrustPolicy {
    fun needsProof(origin: String, knownInstanceId: String): Boolean =
        knownInstanceId.isNotBlank() && origin.startsWith("http://") && !isLoopback(origin)

    /**
     * 回环地址的请求天然只到本机，服务端也按本机免票处理；
     * 这里再要求 /identity 挑战，会让「模拟器 + adb reverse」在
     * instance_id 落库后突然一个请求都发不出去。
     */
    private fun isLoopback(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull()?.lowercase().orEmpty()
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }

    /** 只有真正的 2xx 才能写 lastGood；401/404/5xx 都不能。 */
    fun canRememberSuccess(code: Int?): Boolean = code != null && code in 200..299
}

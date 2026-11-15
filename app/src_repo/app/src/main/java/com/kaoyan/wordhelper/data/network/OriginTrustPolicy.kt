package com.kaoyan.wordhelper.data.network

/**
 * 同步地址的信任规则：只有「已配对电脑 + 私网明文 http」才需要先做
 * /identity?nonce 挑战；HTTPS 域名和首次配对（没有 instance id）保持原行为。
 *
 * 单独抽出来是为了能在 JVM 单测里锁住这两条容易写反的规则。
 */
internal object OriginTrustPolicy {
    fun needsProof(origin: String, knownInstanceId: String): Boolean =
        knownInstanceId.isNotBlank() && origin.startsWith("http://")

    /** 只有真正的 2xx 才能写 lastGood；401/404/5xx 都不能。 */
    fun canRememberSuccess(code: Int?): Boolean = code != null && code in 200..299
}

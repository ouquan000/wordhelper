package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.model.CardBackCodec

/**
 * 电脑端（卡背编辑器 / 重写服务，默认 8765）的鉴权约定。
 *
 * 电脑端首次启动生成一串口令存在 `tools/cardback-editor/.cardback_token`，现在把它和地址
 * 一起打印成一行 `http://192.168.1.5:8765#<口令>`，手机只粘这一行（见 [parsePair]）。
 * 手机和电脑不在同一台机器上时，所有请求都带 `X-Cardback-Token`，否则回 401。
 * 电脑本机（127.0.0.1）的浏览器编辑卡背免票，所以这里对没填口令的情况不做拦截。
 */
object CardbackAuth {
    const val HEADER = "X-Cardback-Token"

    /** 电脑端 401 时给用户看的话（要照着做，别含糊成「电脑没连上」）。 */
    const val HINT = "电脑没认这串口令：把电脑上「手机：粘贴这一行」整行重新粘到「卡背结构地址」再试"

    fun headerValue(token: String): String? = token.trim().ifEmpty { null }

    /**
     * 电脑端给的一整行现在可能带局域网 TLS 锚点：
     * `http://192.168.1.5:8765#aBcD1234&pin=<64hex>&tls_port=8443`。
     *
     * `pin` 是电脑端证书公钥的 SHA-256，属于可信一次性配对输入，只能从这行
     * 或受信 HTTPS 的 `/identity` 得到；`#` 后面整段不会进请求行/日志。
     */
    data class PairInfo(
        val url: String,
        val token: String?,
        val tlsPin: String?,
        val tlsPort: Int?
    )

    /**
     * 拆电脑端给的那一整行：`http://192.168.1.5:8765#aBcD1234`。
     *
     * 返回「地址 to 口令」：
     * - 地址 = `#` 之前的部分，去掉 `/cardback.json` 后缀（读取端会自己补），trim；
     * - 口令 = `#` 之后的第一段（`&` 之前）；没有 `#`，或 `#` 后面是空的，就是 null。
     *
     * 口令为 null 的含义是「这次没带口令」，**不是「把口令清空」** ——
     * 老用户单独填过口令的，只改地址不能把它弄丢。
     */
    fun parsePair(raw: String): Pair<String, String?> {
        val info = parsePairInfo(raw)
        return info.url to info.token
    }

    fun parsePairInfo(raw: String): PairInfo {
        // 地址框能接受粘贴：多行文本（例如整段诊断信息）只取第一行。
        // 否则带换行的地址解析失败，会静默回退到上次成功地址，用户却以为新地址生效了。
        val text = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return PairInfo("", null, null, null)
        val cut = text.indexOf('#')
        val rawUrl = if (cut >= 0) text.substring(0, cut) else text
        val fragment = if (cut >= 0) text.substring(cut + 1) else ""
        val parts = fragment.split('&')
        val token = parts.firstOrNull()?.trim()?.ifEmpty { null }
        var pin: String? = null
        var port: Int? = null
        parts.drop(1).forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val key = part.substring(0, eq).trim().lowercase()
            val value = part.substring(eq + 1).trim()
            when (key) {
                "pin" -> pin = value.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }
                    ?.lowercase()
                "tls_port", "tlsport" -> port = value.toIntOrNull()?.takeIf { it in 1..65535 }
            }
        }
        return PairInfo(stripFileName(rawUrl.trim()), token, pin, port)
    }

    /** `http://ip:8765/cardback.json` → `http://ip:8765`；末尾斜杠也去掉，其余原样。 */
    private fun stripFileName(url: String): String {
        val suffix = "/" + CardBackCodec.FILENAME
        val withoutFile =
            if (url.endsWith(suffix, ignoreCase = true)) url.dropLast(suffix.length) else url
        return withoutFile.trimEnd('/')
    }
}

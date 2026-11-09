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
     * 拆电脑端给的那一整行：`http://192.168.1.5:8765#aBcD1234`。
     *
     * 返回「地址 to 口令」：
     * - 地址 = `#` 之前的部分，去掉 `/cardback.json` 后缀（读取端会自己补），trim；
     * - 口令 = `#` 之后的部分；没有 `#`，或 `#` 后面是空的，就是 null。
     *
     * 口令为 null 的含义是「这次没带口令」，**不是「把口令清空」** ——
     * 老用户单独填过口令的，只改地址不能把它弄丢。
     */
    fun parsePair(raw: String): Pair<String, String?> {
        // 地址框能接受粘贴：多行文本（例如整段诊断信息）只取第一行。
        // 否则带换行的地址解析失败，会静默回退到上次成功地址，用户却以为新地址生效了。
        val text = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return "" to null
        val cut = text.indexOf('#')
        val rawUrl = if (cut >= 0) text.substring(0, cut) else text
        val rawToken = if (cut >= 0) text.substring(cut + 1) else ""
        return stripFileName(rawUrl.trim()) to rawToken.trim().ifEmpty { null }
    }

    /** `http://ip:8765/cardback.json` → `http://ip:8765`；末尾斜杠也去掉，其余原样。 */
    private fun stripFileName(url: String): String {
        val suffix = "/" + CardBackCodec.FILENAME
        val withoutFile =
            if (url.endsWith(suffix, ignoreCase = true)) url.dropLast(suffix.length) else url
        return withoutFile.trimEnd('/')
    }
}

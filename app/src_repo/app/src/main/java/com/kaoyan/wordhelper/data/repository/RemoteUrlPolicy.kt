package com.kaoyan.wordhelper.data.repository

import java.net.URI

/**
 * 1.5.1：词卡热更「公网/远程地址」的默认值与归一规则。
 *
 * 纯 Kotlin（只依赖 `java.net.URI`），不碰 Android —— 这样 JVM 单测能直接覆盖，
 * 同步/热更/改写三个仓库也不用各自再写一份归一逻辑。
 *
 * 默认值来自 `BuildConfig.DEFAULT_REMOTE_URL`（由本地 keystore.properties 注入），
 * 仓库里的默认是空串：私人口令类信息不进 Git 追踪文件。
 */
object RemoteUrlPolicy {

    /** 误贴的「具体卡片文件」路径：砍回它前面的站点/前缀，否则请求必然 404。 */
    private val CARD_FILE_SUFFIXES = listOf("/cards/manifest.json", "/cards/cet6_cards.json")

    /**
     * 决定最终生效的远程地址。
     *
     * - [stored] 非 null 就原样返回 —— 空串也算「用户主动清空」，
     *   绝不能被默认值重新填回（否则用户永远关不掉公网地址）；
     * - 只有从没存过（null）时才用编译期注入的 [buildDefault]，并先做一次归一；
     * - 默认值本身为空 = 没有公网地址，只用局域网地址。
     */
    fun resolve(stored: String?, buildDefault: String): String {
        if (stored != null) return stored
        if (buildDefault.isBlank()) return ""
        return normalizeBase(buildDefault)
    }

    /**
     * 把用户可能粘贴的各种写法归一成「可用的站点基址」：
     *
     * - trim；空返回 ""；
     * - 没写 scheme 就补 `https://`；
     * - 误贴 `/cards/manifest.json`、`/cards/cet6_cards.json`（大小写不敏感）时砍到它前面；
     * - 去掉末尾 `/`，但保留路径前缀（`https://x.cn/foo` 仍是 `/foo`）；
     * - 丢掉 query / fragment、host 统一小写（路径大小写原样保留）；
     * - 解析不出 host 的一律返回 ""，脏值绝不写进设置。
     */
    fun normalizeBase(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            text = "https://$text"
        }

        val lower = text.lowercase()
        val cut = CARD_FILE_SUFFIXES
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (cut != null) text = text.substring(0, cut)
        text = text.trimEnd('/')
        if (text.isEmpty()) return ""

        val uri = runCatching { URI(text) }.getOrNull() ?: return ""
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return ""

        val scheme = uri.scheme?.lowercase() ?: "https"
        val builder = StringBuilder(scheme).append("://").append(host.lowercase())
        if (uri.port > 0) builder.append(':').append(uri.port)
        return builder.append(uri.rawPath.orEmpty().trimEnd('/')).toString()
    }
}

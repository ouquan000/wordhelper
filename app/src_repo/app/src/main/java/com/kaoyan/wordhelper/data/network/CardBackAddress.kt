package com.kaoyan.wordhelper.data.network

import java.net.URI
import java.util.Locale

/**
 * 电脑端地址（卡背 / 重写服务共用一套 host:port）的解析与候选顺序（1.4.6.1）。
 *
 * 用户填的是一整行「地址」，可能是 `192.168.1.5:8765`、也可能带着
 * `http://…/cardback.json`。真正请求时只需要 `scheme://host:port`，
 * 路径由调用方自己拼，所以这里统一归一成 origin。
 *
 * 全是纯函数：不碰 DataStore、不碰网络，单元测试直接覆盖（CardBackAddressTest）。
 * 规则必须和原来的 RewriteRepository.baseOrigin / SyncEndpoint.baseOrigin 一致，
 * 否则同一个地址在不同入口会解析成两个 origin。
 */
object CardBackAddress {

    /**
     * `192.168.1.5:8765` / `http://192.168.1.5:8765/cardback.json` → `http://192.168.1.5:8765`。
     * 空、只有空白、解析不出 host 一律返回 null（调用方按「没配地址」处理）。
     */
    fun originOf(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        return runCatching {
            val uri = URI(withScheme)
            val host = uri.host
            if (host.isNullOrBlank()) return@runCatching null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull()
    }

    /**
     * 轮试顺序：本次会话里连通过的那个 → 手填的地址 → 上次成功的地址。
     *
     * 同一个 origin 只留一次（用户手填的和上次成功的经常是同一个）；
     * 空值直接跳过，全空时返回空列表 = 没配地址。
     */
    fun candidates(active: String?, current: String?, lastGood: String?): List<String> {
        val result = LinkedHashSet<String>(3)
        listOf(active, current, lastGood).forEach { raw ->
            originOf(raw)?.let(result::add)
        }
        return result.toList()
    }

    /**
     * 只打一发请求时（例如 4 秒一次的进度轮询）选哪个地址。
     *
     * 优先「本次会话已经连通过的」，其次手填的，最后才是上次成功的。
     * 手填的是用户最近一次明确意图；last-good 只有在本次会话没连通过、手填又为空/不可解析时
     * 才顶上（例如清空了输入框，仍然记得上次能用的地址）。
     * 轮试路径不受影响：那边三个候选都会走到。
     */
    fun preferred(active: String?, current: String?, lastGood: String?): String? {
        return originOf(active) ?: originOf(current) ?: originOf(lastGood)
    }
}

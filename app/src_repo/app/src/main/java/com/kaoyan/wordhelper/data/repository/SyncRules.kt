package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.RetryPolicy
import java.net.URI
import java.util.Locale

/**
 * 电脑端地址解析。
 *
 * 规则与 RewriteRepository.baseOrigin() 完全一致（那个是 private，本次不动它）：
 * 用户可能填的是完整文件地址 http://ip:8765/cardback.json，这里只留 scheme://host:port，
 * 因为同步接口挂在同一个端口上，路径部分是卡背文件专用的。
 */
internal object SyncEndpoint {
    fun baseOrigin(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = when {
            text.startsWith("http://", ignoreCase = true) -> text
            text.startsWith("https://", ignoreCase = true) -> text
            else -> "http://$text"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val portPart = if (uri.port > 0) ":" + uri.port else ""
        return "${uri.scheme}://$host$portPart"
    }
}

/**
 * 公网兜底的判定规则（1.5.1「学习记录同步也支持局域网优先」）。
 *
 * 学习记录同步现在跟改写链路同一套候选顺序：局域网优先，出不去再落公网域名
 * （`UserSettings.wordbookRemoteUrl`）。这里抽出两件纯判断，好在 JVM 单测里锁死：
 *  - [normalizePublicUrl]：把设置里那个地址收敛成 `https://host[:port]`，空/非法一律 ""；
 *  - [isPublicOrigin]：判断某个候选是不是那个公网域名 —— 它决定该候选走
 *    「2 秒快失败」的局域网超时，还是「8/20/30 秒」的公网超时。
 *
 * 口径复用 [RemoteUrlPolicy.normalizeBase]（改写链路同一个规则，别另造一套），
 * 域名只接受 https：token 要跟着请求发出去，明文 HTTP 域名不可接受。
 */
internal object SyncFallbackPolicy {

    /**
     * 把设置里的公网地址收敛成 `https://host[:port]`。
     *
     * 空、解析不出 host、或者不是 https（包括用户手填的明文 http 域名）都返回 ""：
     * 返回空串就等于「没有公网兜底」，调用方据此不加这个候选。
     */
    fun normalizePublicUrl(raw: String?): String {
        val base = RemoteUrlPolicy.normalizeBase(raw.orEmpty())
        if (base.isEmpty()) return ""
        if (!base.startsWith("https://", ignoreCase = true)) return ""
        return CardBackAddress.originOf(base).orEmpty()
    }

    /** 已发现的 LAN 优先，显式配置和已成功地址有序去重，公网作为退路。 */
    fun preferredOrigins(
        discovered: String?,
        active: String?,
        configured: String?,
        lastGood: String?,
        cloud: String?,
        pinned: Boolean
    ): List<String> = (listOfNotNull(CardBackAddress.originOf(discovered)) +
        CardBackAddress.candidates(active, configured, lastGood, cloud))
        .distinct()
        .filterNot { pinned && it.startsWith("http://") }
        .sortedBy { if (isPublicOrigin(it, cloud.orEmpty())) 1 else 0 }

    /**
     * [origin] 是否就是公网域名 [publicUrl]（都已经过 [normalizePublicUrl] 更好）。
     *
     * 按 `scheme://host[:port]` 比，路径与大小写不参与：用户可能把带
     * `/cards/cet6_cards.json` 的完整地址填进去，也可能填大写域名。
     * publicUrl 为空时一律 false —— 没有配置公网就不存在「公网候选」。
     */
    fun isPublicOrigin(origin: String?, publicUrl: String): Boolean {
        if (publicUrl.isBlank()) return false
        val candidate = CardBackAddress.originOf(origin) ?: return false
        val public = CardBackAddress.originOf(publicUrl) ?: return false
        return candidate.equals(public, ignoreCase = true)
    }
}

internal object SyncRules {
    fun isCoolingDown(respectRateLimit: Boolean, gate: Long, now: Long): Boolean =
        respectRateLimit && gate > now

    fun stopAtOrigin(code: Int?, kind: RetryPolicy.Kind): Boolean =
        (code != null && code in 200..299) || kind == RetryPolicy.Kind.RATE_LIMITED

    fun epochChanged(incoming: Long, current: Long): Boolean = incoming != current

    fun revisionRegressed(incoming: Long?, current: Long): Boolean =
        incoming != null && incoming < current

    fun nextRevision(nextSinceRev: Long?, serverRev: Long?): Long? = nextSinceRev ?: serverRev

    fun alreadyApplied(pageTime: Long?, incoming: Long): Boolean =
        pageTime != null && pageTime >= incoming

    fun localIsNewer(localTime: Long?, incoming: Long): Boolean =
        localTime != null && localTime > incoming

    /** 1.5.1：内网地址判定（只用于挑超时档，不参与任何信任判断）。 */
    fun isLanOrigin(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }
}

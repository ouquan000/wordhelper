package com.kaoyan.wordhelper.data.model

import com.kaoyan.wordhelper.data.entity.RewriteFeedback

/**
 * 当前卡背展示的「那一版思路」的版本号（1.4.6.1）。
 *
 * 反馈必须绑在用户当时看到的那一版上，否则换过思路之后，旧评价会错误地亮在新思路旁边。
 * 取用规则与 [com.kaoyan.wordhelper.data.repository.RewriteRepository.observeApproach] 一致：
 * 这本书有自己的思路就用这本书的 rev；否则退回全局那份；都没有就是 0（种子思路）。
 */
object ApproachRevision {
    fun resolve(
        bookApproach: String?,
        bookRev: Int,
        globalApproach: String?,
        globalRev: Int
    ): Int {
        if (!bookApproach.isNullOrBlank()) return bookRev.coerceAtLeast(0)
        if (!globalApproach.isNullOrBlank()) return globalRev.coerceAtLeast(0)
        return 0
    }
}

/**
 * 从当前词/书的所有反馈里挑出属于 [approachRev] 的那条态度。
 * 换版后自然挑不到，UI 回到「👍 有帮助 / 👎 没帮助」，允许按新版重新评价。
 */
internal fun feedbackRatingForRevision(
    items: List<RewriteFeedback>,
    approachRev: Int
): String? = items.firstOrNull { it.approachRev == approachRev }?.rating

package com.kaoyan.wordhelper.data.model

/**
 * 卡背「未复核」标记（1.4.7.1 / DB 21）。
 *
 * 电脑端重写有时交出的是一版没走完自检的思路，服务端会在结果上带 `unverified: true` + `reason`。
 * 落库之后卡背挂一个中性角标，点开能看到原因 —— 只是让人知道「这版还没复核过」，不是错误。
 *
 * 读取优先级必须和 [com.kaoyan.wordhelper.data.repository.RewriteRepository.observeApproach] 一致：
 * 这本书有自己非空的思路就用它的标记，否则退回全局那份。角标永远跟着正文走 ——
 * 正文回退到全局时，标记也必须一起回退，不能把上一本书的「未复核」挂到别的书上。
 *
 * 老服务端 / 老数据没有这个字段：一律 false，界面不显示角标，行为与升级前一致。
 */
data class ApproachReview(
    val approach: String? = null,
    val unverified: Boolean = false,
    val reason: String? = null
) {
    /** 这一行有没有「真正生效的正文」，只看正文，不看标记。 */
    val hasApproach: Boolean
        get() = !approach.isNullOrBlank()

    companion object {
        val EMPTY = ApproachReview()

        /**
         * 按书那份有正文就用它，否则用全局那份；两处都没有就是空。
         * 标记和原因只来自选中的那一行，空白原因按「没有说明」处理。
         */
        fun resolve(own: ApproachReview?, global: ApproachReview?): ApproachReview {
            val active = own?.takeIf { it.hasApproach }
                ?: global?.takeIf { it.hasApproach }
                ?: return EMPTY
            return ApproachReview(
                approach = active.approach,
                unverified = active.unverified,
                reason = active.reason?.takeIf { it.isNotBlank() }
            )
        }
    }
}

package com.kaoyan.wordhelper.data.repository

/**
 * 「重写思路」点击后的即时文案策略（1.5.1）。
 *
 * 抽成纯函数是为了能单测：真正的根因在 RewriteRepository 的锁顺序（本地落库不能再等
 * syncMutex），但用户能感知到的是「点完有没有一句话」。这里把三种结果固定下来，
 * 避免以后又在 ViewModel 里写散落的 when。
 */
object RewriteMarkPolicy {

    /** 点下去、本地已经落库，但网络发送还没结束。 */
    const val MARKING = "已标记，正在发送…"

    /** 已经发到电脑端（status=QUEUED/DONE）。 */
    const val SENT = "已发给电脑，等它重写"

    /** 本地已记下，等下次同步补发。 */
    const val DEFERRED = "已记下，等电脑上线后自动重写"

    /**
     * @param sent 是否已发到电脑
     * @param authFailed 本次是否被 401 拦过
     * @param hasToken 用户是否填过口令
     * @param authHint 口令错误的提示文案（由调用方注入，避免这里依赖 Android 资源）
     */
    fun result(sent: Boolean, authFailed: Boolean, hasToken: Boolean, authHint: String): String {
        if (sent) return SENT
        return if (authFailed && hasToken) authHint else DEFERRED
    }
}

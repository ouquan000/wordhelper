package com.kaoyan.wordhelper.data.repository

/**
 * 电脑端（卡背编辑器 / 重写服务，默认 8765）的鉴权约定。
 *
 * 电脑端首次启动生成一串口令存在 `tools/cardback-editor/.cardback_token`，并在启动日志里打印；
 * 手机和电脑不在同一台机器上时，所有请求都带 `X-Cardback-Token`，否则回 401。
 * 电脑本机（127.0.0.1）的浏览器编辑卡背免票，所以这里对没填口令的情况不做拦截。
 */
object CardbackAuth {
    const val HEADER = "X-Cardback-Token"

    /** 电脑端 401 时给用户看的话（要照着填，别含糊成「电脑没连上」）。 */
    const val HINT = "口令不对，请核对电脑端启动窗口里「手机口令」那一串"

    fun headerValue(token: String): String? = token.trim().ifEmpty { null }
}

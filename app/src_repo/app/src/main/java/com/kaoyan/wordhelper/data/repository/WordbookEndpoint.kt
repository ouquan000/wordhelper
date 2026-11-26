package com.kaoyan.wordhelper.data.repository

internal class WordbookEndpoint(val origin: String, val token: String, val pin: String) {
    private val lan = SyncRules.isLanOrigin(origin)
    val label = if (lan) "局域网" else "公网"
    val connectSeconds = if (lan) 2L else 8L
    val readSeconds = if (lan) 5L else 30L
    val callSeconds = if (lan) 7L else 45L
}

package com.kaoyan.wordhelper.data.repository

import android.content.Context
import com.kaoyan.wordhelper.data.model.CardBackCodec
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.CardBackDecodeResult
import com.kaoyan.wordhelper.data.model.CardBackDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 一次刷新的结果，用来给用户一句人话。 */
sealed interface CardBackRefreshResult {
    data class Success(
        val revision: Int,
        val blockCount: Int,
        val unknownTypes: List<String>
    ) : CardBackRefreshResult

    data class Failure(val message: String) : CardBackRefreshResult
}

/**
 * 卡背结构清单的读取与缓存。
 *
 * 取值顺序（见 docs/cardback-schema-1.4.2.md §4）：
 *   HTTP 拉取 → 本地缓存 → 上一份成功的 → 内置默认
 * 任何一步失败都静默往下掉，永远不出现空卡背。
 *
 * 注意：这里只碰文件，不碰数据库，复习进度完全不受影响。
 */
class CardBackRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val _config = MutableStateFlow(CardBackDefaults.config)
    val config: StateFlow<CardBackConfig> = _config.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File
        get() = File(context.filesDir, CardBackCodec.FILENAME)

    private val lastGoodFile: File
        get() = File(context.filesDir, CardBackCodec.LAST_GOOD_FILENAME)

    /** App 启动时调用：只读本地，不发网络请求。 */
    suspend fun bootstrap() {
        withContext(Dispatchers.IO) {
            val fromCache = readAndDecode(cacheFile)
            if (fromCache != null) {
                _config.value = fromCache
                return@withContext
            }
            val fromLastGood = readAndDecode(lastGoodFile)
            if (fromLastGood != null) {
                _config.value = fromLastGood
            }
            // 都读不到就保持内置默认，什么都不用做
        }
    }

    /**
     * 用户点「刷新卡背结构」时调用。
     * @return 给人看的结果；失败时当前卡背保持不变。
     */
    suspend fun refresh(): CardBackRefreshResult {
        if (_refreshing.value) {
            return CardBackRefreshResult.Failure("正在刷新，稍等一下")
        }
        _refreshing.value = true
        try {
            val rawUrl = settingsRepository.settingsFlow.first().cardBackUrl
            val url = normalizeUrl(rawUrl)
                ?: return CardBackRefreshResult.Failure("还没填卡背结构地址（我的 → 设置 → 卡背结构地址）")

            val body = withContext(Dispatchers.IO) { fetch(url) }
                ?: return CardBackRefreshResult.Failure("连不上电脑，检查电脑上的服务开着没、手机和电脑是不是同一个网")

            return applyRemoteBody(body)
        } finally {
            _refreshing.value = false
        }
    }

    /** 直接用一段 JSON 覆盖当前清单（调试/导入用）。 */
    suspend fun applyRemoteBody(body: String): CardBackRefreshResult {
        val decoded = CardBackCodec.decode(body)
        if (decoded is CardBackDecodeResult.Rejected) {
            return CardBackRefreshResult.Failure("这份结构不能用：${decoded.reason}（已保持原样）")
        }
        val config = (decoded as CardBackDecodeResult.Ok).config

        withContext(Dispatchers.IO) {
            // 旧的缓存先转成「上一份成功的」，再写新的，写失败也能回退
            if (cacheFile.isFile && cacheFile.length() > 0L) {
                runCatching { cacheFile.copyTo(lastGoodFile, overwrite = true) }
            }
            runCatching { cacheFile.writeText(body) }
        }

        _config.value = config
        val message = buildString {
            append("已更新到第 ${config.revision} 版，共 ${config.renderableBlocks.size} 块")
            if (config.unknownTypes.isNotEmpty()) {
                append("；跳过不认识的块：")
                append(config.unknownTypes.joinToString("、"))
            }
        }
        _lastMessage.value = message
        return CardBackRefreshResult.Success(
            revision = config.revision,
            blockCount = config.renderableBlocks.size,
            unknownTypes = config.unknownTypes
        )
    }

    /** 回到 1.4.1 的原样。 */
    suspend fun restoreDefault() {
        withContext(Dispatchers.IO) {
            if (cacheFile.isFile && cacheFile.length() > 0L) {
                runCatching { cacheFile.copyTo(lastGoodFile, overwrite = true) }
            }
            runCatching { cacheFile.delete() }
        }
        _config.value = CardBackDefaults.config.copy(revision = _config.value.revision + 1)
        _lastMessage.value = "已恢复默认卡背结构"
    }

    fun clearMessage() {
        _lastMessage.value = null
    }

    private fun readAndDecode(file: File): CardBackConfig? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { file.readText() }
            .getOrNull()
            ?.let { CardBackCodec.decode(it) }
            ?.let { if (it is CardBackDecodeResult.Ok) it.config else null }
    }

    private fun fetch(url: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        /**
         * 把用户填的东西尽量变成一个能用的 URL：
         * - 只填了 `192.168.1.5:8765` → 补 http:// 和 /cardback.json
         * - 只填了 `http://192.168.1.5:8765` → 补 /cardback.json
         * - 填了完整地址 → 原样用（这就是「留接口」：任何公网/隧道地址都能直接用）
         */
        fun normalizeUrl(raw: String?): String? {
            var text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (!text.startsWith("http://", ignoreCase = true) &&
                !text.startsWith("https://", ignoreCase = true)
            ) {
                text = "http://$text"
            }
            val afterScheme = text.substringAfter("://")
            if (afterScheme.isEmpty()) return null
            // 拼接完如果没有路径，补默认文件名
            val pathStart = afterScheme.indexOf('/')
            if (pathStart < 0) {
                text = "$text/${CardBackCodec.FILENAME}"
            }
            return text
        }
    }
}

package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.network.RetryPolicy
import java.time.OffsetDateTime
import java.util.Locale

/** 判断历史终态是否比本次标记还早时，允许的时钟误差。 */
private const val TERMINAL_STALE_TOLERANCE_MS = 2_000L

internal class RewriteStatusReader(
    private val queueDao: RewriteQueueDao,
    private val transport: RewriteTransport,
    private val gson: Gson
) {
    @Volatile
    private var cachedModels: List<RewriteModel>? = null

    /** 电脑端模型列表；连不上就用内置模型兜底。成功一次后缓存到进程结束。 */
    suspend fun models(): List<RewriteModel> {
        cachedModels?.let { return it }
        // 后台小请求：只问一次、失败静默（内置兜底模型永远可用，不该为它弹任何提示）。
        val outcome = transport.send("/models", attempts = 1, report = false)
        if (outcome.kind != RetryPolicy.Kind.OK) return RewriteModels.DEFAULT
        val body = outcome.body
        if (body.isNullOrBlank()) return RewriteModels.DEFAULT
        val parsed = runCatching { gson.fromJson(body, ModelsPage::class.java) }.getOrNull()
        val list = parsed?.models.orEmpty().mapNotNull { dto ->
            val id = dto.id ?: return@mapNotNull null
            RewriteModel(
                id = id,
                name = dto.name?.takeIf { it.isNotBlank() } ?: id,
                provider = dto.provider.orEmpty(),
                reasoning = dto.reasoning.orEmpty()
            )
        }
        if (list.isNotEmpty()) cachedModels = list
        return list.ifEmpty { RewriteModels.DEFAULT }
    }

    /**
     * 当前词在电脑端的进度；连不上/回包读不懂返回 null。
     *
     * 只发一次请求：这个方法被 4 秒一次的轮询调用，失败时重试三次会把一个轮询周期拖到几十秒，
     * 用户看到的进度反而更卡。断网就静默返回 null，界面上显示「等电脑上线」。
     */
    suspend fun statusOf(mark: RewriteQueueItem): RewriteStatusSnapshot? {
        val word = mark.word
        val outcome = transport.send(RewriteProtocol.PROGRESS_PATH, attempts = 1, report = false)
        if (outcome.kind != RetryPolicy.Kind.OK) return null
        val body = outcome.body
        if (body.isNullOrBlank()) return null
        val page = runCatching { gson.fromJson(body, StatusPage::class.java) }.getOrNull() ?: return null
        val key = word.trim().lowercase(Locale.ROOT)
        val forWord = page.items.orEmpty().filter { it.word?.trim()?.lowercase(Locale.ROOT) == key }
        val exact = forWord.filter { it.clientId == rewriteClientId(mark) }
        val matches = exact.ifEmpty {
            // 老服务器没有 clientId；只在本地确认这是唯一已发送任务时展示推测进度。
            if (mark.status == RewriteStatus.QUEUED &&
                queueDao.getAllForWord(mark.wordId).count { it.status == RewriteStatus.QUEUED } == 1
            ) forWord.filter { it.clientId.isNullOrBlank() } else emptyList()
        }.filterNot { item ->
            // 服务端留着同一词的历史：上一条 done/failed 可能比这次标记还早。
            // 不滤掉的话，刚点完重写第一轮就会读到旧的 100%，进度条先冲满再退回来。
            val terminal = item.state == "done" || item.state == RewriteProtocol.STATUS_FAILED
            terminal && mark.createdAt > 0L &&
                (parseServerMillis(item.updatedAt) ?: Long.MAX_VALUE) +
                TERMINAL_STALE_TOLERANCE_MS < mark.createdAt
        }
        // 同一个词可能有多条历史（上一条 done/failed + 这次 running），正在跑的优先
        val item = matches.firstOrNull { it.state == "pending" || it.state == "running" }
            ?: matches.firstOrNull()
            ?: return RewriteStatusSnapshot(word = word, state = RewriteProtocol.STATE_MISSING, stage = null, error = null)
        return RewriteStatusSnapshot(
            word = item.word.orEmpty(),
            state = item.state.orEmpty(),
            stage = item.stage?.takeIf { it.isNotBlank() },
            error = item.error?.takeIf { it.isNotBlank() },
            updatedAt = item.updatedAt
        )
    }

    /** 电脑端时间形如 2026-11-07T21:57:19+08:00；解析不了就当没有。 */
    private fun parseServerMillis(raw: String?): Long? {
        val text = raw ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    private data class StatusPage(val items: List<StatusItem>?)

    private data class StatusItem(
        val word: String?,
        val state: String?,
        val stage: String?,
        val error: String?,
        val updatedAt: String?,
        val clientId: String?
    )

    private data class ModelsPage(val models: List<ModelDto>?)

    private data class ModelDto(
        val id: String?,
        val name: String?,
        val provider: String?,
        val reasoning: String?
    )
}

package com.kaoyan.wordhelper.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.network.RetryPolicy

/** v8：记忆信号与遗忘自动换招的本地账本（只存本机，不与后端共享）。 */
private const val MEMORY_SIGNAL_PREFS = "fsrs_memory_signal"
private const val EVENT_RETAINED = "retained"
private const val EVENT_LAPSED = "lapsed"

/** retained 只报一次；lapsed 用 24 小时节流，避免每轮复习都发一条。 */
private const val MEMORY_SIGNAL_MIN_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** 自动换招的 24 小时冷却：同一词一天最多自动重排一次。 */
private const val AUTO_REWRITE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

/** 自动换招的队列优先级：数值越小越先做；20 排在用户手点重写之后。 */
private const val AUTO_REWRITE_PRIORITY = 20

private const val FSRS_LAPSE_INSTRUCTION =
    "FSRS 监测到该词真实复习多次遗忘（难度过高），请彻底废弃当前思路与方法，" +
        "改用更直白、基于初高中熟词或一跳谐音的聪明室友小抄写法。"

internal class RewriteMemorySignals(
    private val settingsRepository: SettingsRepository,
    private val queueDao: RewriteQueueDao,
    private val queue: RewriteQueueStore,
    private val transport: RewriteTransport,
    private val gson: Gson
) {
    /** v8：FSRS 记忆信号与自动换招的本地去重/冷却（旁路数据，丢了最多多重报一次）。 */
    private val memorySignalPrefs by lazy {
        settingsRepository.appContext.getSharedPreferences(MEMORY_SIGNAL_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * v8：把一次真实复习的记忆信号（retained / lapsed）报给电脑端。
     *
     * 纯旁路：连不上、没地址、服务端不认识这个接口都静默吞掉，绝不能影响作答。
     * 同一个 (词, 书) 的 retained 只报一次；lapsed 24 小时内最多一条，避免每轮复习刷屏。
     */
    suspend fun reportMemorySignal(
        wordId: Long,
        bookId: Long,
        word: String,
        method: String,
        event: String,
        stability: Float,
        difficulty: Float,
        lapses: Int
    ) {
        runCatching {
            val prefs = memorySignalPrefs
            val now = System.currentTimeMillis()
            if (event == EVENT_RETAINED) {
                if (prefs.getBoolean(retainedKey(wordId, bookId), false)) return
            } else if (event == EVENT_LAPSED) {
                if (now - prefs.getLong(lapsedKey(wordId, bookId), 0L) < MEMORY_SIGNAL_MIN_INTERVAL_MS) return
            }
            // 服务端按 clientId 幂等：带按天分桶，lapsed 过 24h 冷却后才会新开一条，
            // 同一天内的重试仍然会被吞成同一条，不会刷屏。
            val bucket = now / 86_400_000L
            val payload = gson.toJson(
                mapOf(
                    "clientId" to "fsrs-$wordId-$bookId-$event-$bucket",
                    "word" to word,
                    "bookId" to bookId,
                    "method" to method,
                    "event" to event,
                    "stability" to stability,
                    "difficulty" to difficulty,
                    "lapses" to lapses
                )
            )
            val outcome = transport.send(
                path = "/rewrite/memory-signal",
                payload = payload,
                attempts = 1,
                respectRateLimit = true,
                report = false
            )
            if (outcome.kind == RetryPolicy.Kind.OK) {
                val editor = prefs.edit()
                if (event == EVENT_RETAINED) {
                    editor.putBoolean(retainedKey(wordId, bookId), true)
                } else if (event == EVENT_LAPSED) {
                    editor.putLong(lapsedKey(wordId, bookId), now)
                }
                editor.apply()
            }
        }
    }

    /**
     * v8：多次遗忘 + 难度过高时，自动给这个词重排一版更直白的记忆思路。
     *
     * 已经在队列里 / 24 小时内换过招就跳过；入队失败同样静默。优先级 20 让这条
     * 自动任务排在手点重写之后、普通补写之前（由 DAO 按 priority 升序读取）。
     */
    suspend fun maybeAutoRewriteAfterLapse(word: Word, book: Book, progress: Progress) {
        runCatching {
            val queued = queueDao.getVisibleForBook(word.id, book.id)
            if (queued != null &&
                (queued.status == RewriteStatus.PENDING || queued.status == RewriteStatus.QUEUED)
            ) {
                return
            }
            val prefs = memorySignalPrefs
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(autoKey(word.id, book.id), 0L) < AUTO_REWRITE_COOLDOWN_MS) return
            Log.d(
                RewriteProtocol.TAG,
                "FSRS lapse trigger: word=${word.word} lapses=${progress.fsrsLapses} " +
                    "difficulty=${progress.fsrsDifficulty}"
            )
            val model = runCatching { settingsRepository.rewriteModel() }.getOrDefault("")
            val sent = queue.enqueueAndSync(
                wordId = word.id,
                word = word.word,
                model = model,
                instruction = FSRS_LAPSE_INSTRUCTION,
                bookId = book.id,
                priority = AUTO_REWRITE_PRIORITY
            )
            if (sent) prefs.edit().putLong(autoKey(word.id, book.id), now).apply()
        }
    }

    private fun retainedKey(wordId: Long, bookId: Long) = "retained_${wordId}_${bookId}"

    private fun lapsedKey(wordId: Long, bookId: Long) = "lapsed_${wordId}_${bookId}"

    private fun autoKey(wordId: Long, bookId: Long) = "auto_${wordId}_${bookId}"
}

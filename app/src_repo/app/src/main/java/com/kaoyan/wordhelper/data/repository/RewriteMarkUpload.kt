package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth

private const val CAP_REWRITE = "rewrite"
/** 释义开头的词性前缀：adj. / v. / n. / adv. / vt. … */
private val POS_PREFIX = Regex("^([a-zA-Z]{1,5}(?:\\.[a-zA-Z]{1,5})?)\\.")

internal class RewriteMarkUpload(
    private val wordDao: WordDao,
    private val wordMemoryDao: WordMemoryDao,
    private val bookMemoryDao: BookMemoryDao,
    private val bookDao: BookDao,
    private val transport: RewriteTransport,
    private val gson: Gson
) {
    /** bookId → 书名；一本书查一次数据库就够，词表几千个词也不会每条都查。 */
    private val bookNames = HashMap<Long, String>()

    /** 推送标记的结果：成功给出「电脑端收下了哪些 clientId」，失败给一句能直接给用户看的话。 */
    suspend fun upload(items: List<RewriteQueueItem>): UploadResult {
        if (ServerHealth.supports(transport.serverHealth, CAP_REWRITE) == false) {
            val message = RetryPolicy.missingCapabilityMessage(CAP_REWRITE)
            transport.publish(SyncPhase.SERVER_TOO_OLD, null, message)
            return UploadResult(null, message)
        }
        // 词头要给全：只有单词的话，模型分不清是哪个词义、什么词性，重生质量会明显掉。
        val marks = items.map { item ->
            val word = wordDao.getWordById(item.wordId)
            val memory = wordMemoryDao.getByWordId(item.wordId)
            // 按书重写时，给模型看的「旧思路」和记录页显示的书名都必须是这本书的；
            // 词本身只记得自己出生的那本书，不能拿 word.bookId 冒充当前书。
            val scopedMemory = if (item.bookId > 0L) {
                bookMemoryDao.getForBook(item.wordId, item.bookId)
            } else {
                null
            }
            MarkDto(
                clientId = rewriteClientId(item),
                bookId = item.bookId.takeIf { it > 0L },
                word = item.word,
                phonetic = word?.phonetic.orEmpty(),
                pos = posOf(word?.meaning.orEmpty()),
                meaning = word?.meaning.orEmpty(),
                model = item.model,
                instruction = item.instruction,
                book = bookNameOf(if (item.bookId > 0L) item.bookId else (word?.bookId ?: 0L)),
                markedAt = rewriteIsoTime(item.updatedAt),
                currentApproach = (scopedMemory?.approach ?: memory?.approach).orEmpty()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { null }
            )
        }
        val payload = MarksPayload(marks = marks)
        val outcome = transport.send(
            path = "/rewrite/marks",
            payload = gson.toJson(payload),
            attempts = RewriteProtocol.POST_ATTEMPTS,
            respectRateLimit = true
        )
        if (outcome.kind != RetryPolicy.Kind.OK) {
            return UploadResult(null, outcome.message ?: RewriteProtocol.UNREACHABLE)
        }
        val response = runCatching { gson.fromJson(outcome.body, MarksPage::class.java) }.getOrNull()
            ?: return UploadResult(null, "电脑返回的回执读不懂，标记先留在本地")
        return UploadResult(response.accepted.orEmpty().toSet(), null)
    }

    /** 书名（空 = 查不到/没绑书）；电脑端只转存这个字符串给记录页筛选用。 */
    private suspend fun bookNameOf(bookId: Long): String {
        if (bookId <= 0L) return ""
        bookNames[bookId]?.let { return it }
        val name = runCatching { bookDao.getBookById(bookId)?.name }.getOrNull().orEmpty()
        bookNames[bookId] = name
        return name
    }

    /** 释义里自带词性前缀（"adj. 恰当的…"），抽出来当词性，别把整条释义都塞给模型。 */
    private fun posOf(meaning: String): String {
        val m = POS_PREFIX.find(meaning.trim()) ?: return ""
        return m.groupValues[1]
    }

    /** upload() 的结果：要么给回「电脑端收下了哪些 clientId」，要么给一句失败原因。 */
    data class UploadResult(val accepted: Set<String>?, val error: String?)

    private data class MarksPayload(val marks: List<MarkDto>)

    private data class MarkDto(
        val clientId: String,
        val bookId: Long?,
        val word: String,
        val phonetic: String,
        val pos: String,
        val meaning: String,
        val model: String,
        val instruction: String,
        /** 书名供记录页显示；旧服务忽略独立的 bookId。 */
        val book: String = "",
        val markedAt: String,
        val currentApproach: List<String>? = null
    )

    private data class MarksPage(val accepted: List<String>?, val rejected: List<Any>?)
}

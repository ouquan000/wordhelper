package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.MemoryLockDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteSkippedDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.RewriteSkippedResult
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.entity.WordMemory
import java.util.Locale

/** 跳过原因，方便对着 tb_rewrite_skipped.reason 查。 */
private const val SKIP_NO_WORD = "no_word"
private const val SKIP_WORD_MISSING = "word_missing"
private const val SKIP_NO_MARK = "no_mark"
private const val SKIP_EMPTY_APPROACH = "empty_approach"
private const val SKIP_CLIENT_MISMATCH = "client_mismatch"

internal data class RewriteResultDto(
    val seq: Long?,
    val word: String?,
    val clientId: String?,
    val rev: Int?,
    val approach: List<String>?,
    val prev: List<String>?,
    val method: String?,
    val status: String?,
    val error: String?,
    /**
     * 1.4.7.1：这版思路「没复核」（自检没跑完 / 兜底产出）。老服务端不带这个字段，
     * Gson 反序列化出来是 null —— 判定一律写 `== true`，绝不当成「未复核」误报。
     */
    val unverified: Boolean? = null,
    /** 未复核的原因（服务端 reason），可能没有；空白按没有处理。 */
    val reason: String? = null,
    /** 1.5.3v2：结果来源。worker=后端闲时洗词主动产出（不得覆盖本地锁）；空=用户点重写。 */
    val source: String = ""
)

internal class RewriteResultMerger(
    private val wordDao: WordDao,
    private val wordMemoryDao: WordMemoryDao,
    private val bookMemoryDao: BookMemoryDao,
    private val queueDao: RewriteQueueDao,
    private val memoryLockDao: MemoryLockDao,
    private val skippedDao: RewriteSkippedDao,
    private val gson: Gson,
    private val transaction: suspend (suspend () -> Long?) -> Long?
) {

    /**
     * 补拉上次同步里被跳过的结果（1.4.6）。
     *
     * 游标已经走过这些 seq，不补就是永久丢。只有「应用成功 / payload 读不懂」才从待办里删；
     * 否则 attempts+1，等下次再试 —— 到顶的行不再被 getPending 取到，但留在表里当证据。
     * 种子还没导完时直接返回：这类跳过本来就是「词表没准备好」，补也是白烧次数。
     */
    suspend fun retrySkipped() {
        if (wordDao.countAll() < RewriteProtocol.SEED_READY_MIN_WORDS) return
        val rows = skippedDao.getPending(RewriteProtocol.SKIPPED_RETRY_LIMIT, RewriteSkippedResult.MAX_ATTEMPTS)
        if (rows.isEmpty()) return
        rows.forEach { row ->
            val dto = runCatching { gson.fromJson(row.payload, RewriteResultDto::class.java) }
                .getOrNull()
            if (dto == null) {
                skippedDao.delete(row.seq)
                return@forEach
            }
            if (applyResult(dto) != null) {
                skippedDao.delete(row.seq)
                return@forEach
            }
            skippedDao.upsert(
                row.copy(attempts = row.attempts + 1, lastTryAt = System.currentTimeMillis())
            )
        }
    }

    /** 给跳过行留个能查的原因（不是业务分支，只是可读性）。 */
    suspend fun skipReasonOf(dto: RewriteResultDto): String {
        val key = dto.word?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (key.isEmpty()) return SKIP_NO_WORD
        val wordId = wordDao.getWordIdByKey(key) ?: return SKIP_WORD_MISSING
        if (queueDao.getAllForWord(wordId).isEmpty()) return SKIP_NO_MARK
        if (dto.approach.isNullOrEmpty()) return SKIP_EMPTY_APPROACH
        return SKIP_CLIENT_MISMATCH
    }

    suspend fun applyResult(dto: RewriteResultDto): Long? = transaction { applyInTransaction(dto) }

    private suspend fun applyInTransaction(dto: RewriteResultDto): Long? {
        val word = dto.word?.trim().orEmpty()
        if (word.isEmpty()) return null
        val wordId = wordDao.getWordIdByKey(word.lowercase(Locale.ROOT)) ?: return null
        val now = System.currentTimeMillis()

        // 认领守卫：只认「本地还有这条标记」的结果。
        // - 没有队列行 = 已撤销 / 已应用完 / 换机清过队列：一律丢弃。
        //   旧逻辑在这里接受合法 clientId，然后把 bookId 当 0 写进全局那份，
        //   结果就是「撤销后迟到的结果穿透到主书」（1.4.5 验收 P0）。
        // - 有队列行：精确匹配 clientId，不靠词头猜词书。
        val allMarks = queueDao.getAllForWord(wordId)
        val mark = matchRewriteResultMark(allMarks, dto.clientId) ?: return null
        // 1.5.3v2 C5：worker 闲时洗词的结果不能覆盖用户锁定的「免洗金牌」。
        // 本地锁是用户的最终决定，哪怕后端在锁同步到之前已经开洗，结果落地时也直接丢弃
        // （仍然返回 wordId 认领结果、推进游标，只是不写卡背、不改状态）。
        if (skipLockedRewrite(dto.source, memoryLockDao.isLocked(wordId, mark.bookId))) {
            return wordId
        }
        // 已完成的请求被回放，不覆盖当前卡背，也不让迟到的失败倒退状态。
        if (mark.status == RewriteStatus.DONE) return wordId
        if (mark.status == RewriteStatus.FAILED && mark.error == "用户停止") return null

        if (dto.status == RewriteProtocol.STATUS_FAILED || (mark.needsReinsert && dto.unverified == true)) {
            queueDao.updateStatus(
                wordId,
                mark.bookId,
                RewriteStatus.FAILED,
                dto.error?.takeIf { it.isNotBlank() } ?: "电脑端没生成出合格思路",
                now
            )
            return wordId
        }

        val approach = dto.approach.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (approach.isEmpty()) return null

        val existing = wordMemoryDao.getByWordId(wordId)
        val prev = dto.prev.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { existing?.approach.orEmpty() }

        val method = dto.method.orEmpty().trim().lowercase()
            .takeIf { it.isNotEmpty() }
            ?: existing?.method.orEmpty()

        // 1.4.7.1：这版思路是不是没复核。老服务端不带字段 → dto.unverified == null → false，
        // 行为与升级前一模一样，不会凭空冒出角标。原因只在「确实未复核」时才有意义。
        val unverified = dto.unverified == true
        val unverifiedReason = if (unverified) {
            dto.reason?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        // 1.4.5：这条标记是在哪本书里点的，就只改哪本书的卡背。
        // bookId=0 是老队列/换机恢复，沿用旧行为写全局那份。
        val bookId = mark.bookId
        if (bookId > 0L) {
            val own = bookMemoryDao.getForBook(wordId, bookId)
            // 1.4.6 幂等：同一行结果被重拉一遍（游标没推进 / 服务端重发）时，
            // 不能把「上一版」覆盖成刚写进去的这版 —— 那样撤销只会退到一模一样的文字。
            // 1.4.7.1：文字没变也要把未复核标记刷新一下，否则先到的旧结果会让角标一直不出现。
            if (own?.approach == approach) {
                val accepted = own.copy(
                    source = WordMemory.SOURCE_USER,
                    rev = dto.rev ?: own.rev.coerceAtLeast(1),
                    method = dto.method?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: own.method,
                    unverified = unverified,
                    unverifiedReason = unverifiedReason
                )
                if (accepted != own) bookMemoryDao.upsert(accepted.copy(updatedAt = now))
                queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
                return wordId
            }
            // 这一版之前生效的方法标签：撤销时跟思路一起退回去。
            val previousMethod = own?.method?.takeIf { it.isNotBlank() }
                ?: existing?.method?.takeIf { it.isNotBlank() }
            bookMemoryDao.upsert(
                BookMemory(
                    wordId = wordId,
                    bookId = bookId,
                    approach = approach,
                    collocations = own?.collocations?.takeIf { it.isNotBlank() }
                        ?: existing?.collocations.orEmpty(),
                    method = method,
                    updatedAt = now,
                    source = WordMemory.SOURCE_USER,
                    rev = dto.rev ?: ((own?.rev ?: 0) + 1),
                    prevApproach = (own?.approach?.takeIf { it.isNotBlank() } ?: prev)
                        .takeIf { it.isNotBlank() },
                    prevMethod = previousMethod,
                    unverified = unverified,
                    unverifiedReason = unverifiedReason
                )
            )
            queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
            return wordId
        }

        if (existing?.approach == approach) {
            val accepted = existing.copy(
                source = WordMemory.SOURCE_USER,
                rev = dto.rev ?: existing.rev.coerceAtLeast(1),
                method = dto.method?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: existing.method,
                unverified = unverified,
                unverifiedReason = unverifiedReason
            )
            if (accepted != existing) wordMemoryDao.upsert(accepted.copy(updatedAt = now))
            queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
            return wordId
        }
        wordMemoryDao.upsert(
            WordMemory(
                wordId = wordId,
                approach = approach,
                collocations = existing?.collocations.orEmpty(),
                updatedAt = now,
                source = WordMemory.SOURCE_USER,
                rev = dto.rev ?: ((existing?.rev ?: 0) + 1),
                prevApproach = prev.takeIf { it.isNotBlank() },
                // 重写出来的是「哪一招」也一起换掉；电脑端没给就保留原标签，别把卡背标签弄空。
                method = method,
                prevMethod = existing?.method?.takeIf { it.isNotBlank() },
                unverified = unverified,
                unverifiedReason = unverifiedReason
            )
        )
        queueDao.updateStatus(wordId, bookId, RewriteStatus.DONE, null, now)
        return wordId
    }
}

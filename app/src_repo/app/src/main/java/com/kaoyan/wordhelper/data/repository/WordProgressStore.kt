package com.kaoyan.wordhelper.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.MethodCount
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.Sm2Scheduler
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

internal const val MAX_DB_IN_CLAUSE_SIZE = 900

private const val TAG = "WordRepository"
private const val MASTERED_INTERVAL_DAYS_V4 = 30
private const val MASTERED_MIN_REVIEW_COUNT_V4 = 4
private const val MASTERED_MIN_EASE_V4 = 2.3f

internal class WordProgressStore(
    private val database: AppDatabase
) {
    private val progressDao = database.progressDao()
    private val earlyReviewDao = database.earlyReviewDao()
    private val forecastCacheDao = database.forecastCacheDao()
    // 学习路径只写本地同步队列，联网同步由上层触发。
    private val syncRepository = SyncRepository(database)

    /**
     * 1.4.3.2：一次性清空「学习记录」，让用户从零开始背。
     * 只清记录表，不动词书、单词、记忆思路、重写队列。
     */
    suspend fun resetStudyRecords() {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            listOf(
                "tb_progress",
                "tb_early_review_ref",
                "tb_new_word_ref",
                "tb_daily_stats",
                "tb_study_log",
                "word_ml_stats",
                "training_samples"
            ).forEach { table -> db.execSQL("DELETE FROM $table") }
        }
    }

    /**
     * 只读「这本书」的进度（1.4.6 / DB18）。
     *
     * 1.4.5 及之前这里返回的是跨书最优值（getGlobalProgress），于是同一个词在 A 书背过，
     * B 书就凭空变成已学。现在每本书各记各的，bookId 传谁就读谁。
     */
    suspend fun getProgress(wordId: Long, bookId: Long): Progress? {
        return progressDao.getProgress(wordId, bookId)
    }

    /**
     * 队列拼装用的批量版：只取这本书的进度，不再跨书取最优。
     *
     * bookId <= 0 时返回空表（等于「整本都是新词」），不偷偷回落到全局那份。
     */
    suspend fun getProgressMapForBook(
        bookId: Long,
        wordIds: Collection<Long>
    ): Map<Long, Progress> {
        if (bookId <= 0L || wordIds.isEmpty()) return emptyMap()
        val rows = getProgressByBookChunked(bookId, wordIds)
        if (rows.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, Progress>(rows.size)
        rows.forEach { result[it.wordId] = it }
        return result
    }

    fun getLearnedCount(bookId: Long): Flow<Int> = progressDao.getLearnedCount(bookId)

    fun getMasteredCount(bookId: Long): Flow<Int> = progressDao.getMasteredCount(bookId)

    fun getBookDueCount(bookId: Long, now: Long): Flow<Int> =
        progressDao.getBookDueCount(bookId, now)

    fun getBookAccuracy(bookId: Long): Flow<Float?> = progressDao.getBookAccuracy(bookId)

    fun getBookTodayTouchedCount(bookId: Long, startTime: Long, endTime: Long): Flow<Int> =
        progressDao.getBookTodayTouchedCount(bookId, startTime, endTime)

    fun getGlobalMethodCounts(): Flow<List<MethodCount>> = progressDao.getGlobalMethodCounts()

    fun getBookMethodCounts(bookId: Long): Flow<List<MethodCount>> =
        progressDao.getBookMethodCounts(bookId)

    suspend fun upsertProgress(progress: Progress) {
        val existing = progressDao.getProgress(progress.wordId, progress.bookId)
        if (existing != null) {
            progressDao.update(progress.copy(id = existing.id))
        } else {
            progressDao.insert(progress)
        }
        enqueueProgressSync(progress.wordId, progress.bookId)
    }

    /**
     * 学习结果落库之后，把这一行的结果态塞进同步队列。
     *
     * - 必须放在 withTransaction 之外：事务里只该有本地写，入队虽然也是本地表，
     *   但它是附加能力，失败不该把已经写好的学习结果一起回滚；
     * - 入队失败只留日志：同步是附加功能，不能因为队列写不进去就让「认识/不认识」报错。
     *   这里读的是刚写完那一行，所以 payload 就是最终落库状态，不用调用方再传一遍。
     */
    suspend fun enqueueProgressSync(wordId: Long, bookId: Long) {
        // 1.4.6 起进度按书独立：只推「这本书」那一行。
        // 以前这里会给所有关联书各推一份，等于把同一份进度灌进别的书，和独立性冲突。
        if (bookId <= 0L) return
        val snapshot = progressDao.getProgress(wordId, bookId) ?: return
        try {
            syncRepository.enqueueProgress(wordId, bookId, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "学习记录入同步队列失败 wordId=$wordId bookId=$bookId", e)
        }
    }

    suspend fun getDueWords(bookId: Long): List<Progress> {
        return progressDao.getProgressByBook(bookId)
            .filter { it.status != Progress.STATUS_MASTERED && it.nextReviewTime > 0L && DateUtils.isDue(it.nextReviewTime) }
            .sortedBy { it.nextReviewTime }
    }

    suspend fun disperseReviewPressure(bookId: Long, dailyCap: Int): Int {
        val safeDailyCap = dailyCap.coerceAtLeast(1)
        return database.withTransaction {
            val dueWords = progressDao.getProgressByBook(bookId)
                .filter { it.status != Progress.STATUS_MASTERED && it.nextReviewTime > 0L && DateUtils.isDue(it.nextReviewTime) }
                .sortedBy { it.nextReviewTime }
            if (dueWords.size <= safeDailyCap) return@withTransaction 0

            val overflow = dueWords.drop(safeDailyCap)
            val now = System.currentTimeMillis()
            val daySlots = ceil(overflow.size.toDouble() / safeDailyCap.toDouble())
                .toInt()
                .coerceAtLeast(1)

            overflow.forEachIndexed { index, progress ->
                val dayOffset = (index % daySlots) + 1
                val nextReviewTime = Sm2Scheduler.nextReviewTimeByDays(dayOffset, now)
                progressDao.update(progress.copy(nextReviewTime = nextReviewTime))
                earlyReviewDao.deleteByWordAndBook(progress.wordId, bookId)
            }
            invalidateForecastCacheInternal()
            overflow.size
        }
    }

    fun getProgressByBookFlow(bookId: Long): Flow<List<Progress>> = progressDao.getProgressByBookFlow(bookId)

    suspend fun getProgressByBook(bookId: Long): List<Progress> = progressDao.getProgressByBook(bookId)

    suspend fun repairMasteredStatusForV4(): Int {
        return database.withTransaction {
            val downgraded = progressDao.downgradeInvalidMasteredStatus(
                masteredStatus = Progress.STATUS_MASTERED,
                learningStatus = Progress.STATUS_LEARNING,
                minIntervalDays = MASTERED_INTERVAL_DAYS_V4,
                minReviewCount = MASTERED_MIN_REVIEW_COUNT_V4,
                minEaseFactor = MASTERED_MIN_EASE_V4
            )
            if (downgraded > 0) {
                invalidateForecastCacheInternal()
            }
            downgraded
        }
    }

    suspend fun invalidateForecastCacheInternal() {
        forecastCacheDao.clearAll()
    }

    private suspend fun getProgressByBookChunked(
        bookId: Long,
        wordIds: Collection<Long>
    ): List<Progress> {
        if (wordIds.isEmpty()) return emptyList()
        val rows = ArrayList<Progress>()
        wordIds.distinct()
            .chunked(MAX_DB_IN_CLAUSE_SIZE)
            .forEach { chunk ->
                rows += progressDao.getProgressByBookAndWordIds(bookId, chunk)
            }
        return rows
    }
}

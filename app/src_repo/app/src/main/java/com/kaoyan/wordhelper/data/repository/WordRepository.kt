package com.kaoyan.wordhelper.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookWordContent
import com.kaoyan.wordhelper.data.entity.DailyStats
import com.kaoyan.wordhelper.data.entity.EarlyReviewRef
import com.kaoyan.wordhelper.data.entity.NewWordRef
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.StudyLog
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.entity.WordEntity
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.CardSeedLoader
import com.kaoyan.wordhelper.data.model.CardSeedPayload
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.data.model.MethodCount
import com.kaoyan.wordhelper.data.model.PresetBookCatalog
import com.kaoyan.wordhelper.data.model.PresetBookSeed
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.data.model.WordDraft
import com.kaoyan.wordhelper.ml.core.SchedulingAdjustment
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.util.DateUtils
import com.kaoyan.wordhelper.util.Sm2Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

data class StudyQueueSnapshot(
    val queue: List<Word>,
    val dueCount: Int
)

enum class CheckInResult {
    SUCCESS,
    ALREADY_CHECKED_IN,
    NOT_ELIGIBLE
}

class WordRepository(private val database: AppDatabase) {
    private val bookDao = database.bookDao()
    private val wordDao = database.wordDao()
    private val progressDao = database.progressDao()
    private val newWordRefDao = database.newWordRefDao()
    private val studyLogDao = database.studyLogDao()
    private val dailyStatsDao = database.dailyStatsDao()
    private val earlyReviewDao = database.earlyReviewDao()
    private val forecastCacheDao = database.forecastCacheDao()
    private val wordMemoryDao = database.wordMemoryDao()

    /**
     * 学习记录同步队列。这里刻意不传 SettingsRepository：学习路径上只做本地入队，
     * 联网那一半由上层用带 SettingsRepository 的实例触发（见 SyncRepository 的注释）。
     */
    private val syncRepository = SyncRepository(database)

    // ---- Book ----

    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks()

    suspend fun getBookById(bookId: Long): Book? = bookDao.getBookById(bookId)

    suspend fun getActiveBook(): Book? = bookDao.getActiveBook()

    fun getActiveBookFlow(): Flow<Book?> = bookDao.getActiveBookFlow()

    suspend fun switchBook(newBookId: Long) {
        bookDao.deactivateAll()
        bookDao.activate(newBookId)
    }

    suspend fun insertBook(book: Book): Long = bookDao.insert(book)

    suspend fun deleteBook(book: Book) = bookDao.delete(book)

    suspend fun deleteBookWithData(book: Book): Boolean {
        if (book.type == Book.TYPE_NEW_WORDS) {
            return false
        }
        database.withTransaction {
            val wasActive = book.isActive
            earlyReviewDao.deleteByBook(book.id)
            progressDao.deleteByBook(book.id)
            wordDao.deleteByBook(book.id)
            wordDao.deleteOrphanWords()
            bookDao.delete(book)

            if (wasActive) {
                val remaining = bookDao.getAllBooksList().filter { it.id != book.id }
                val fallback = remaining.firstOrNull { it.type != Book.TYPE_NEW_WORDS } ?: remaining.firstOrNull()
                if (fallback != null) {
                    bookDao.deactivateAll()
                    bookDao.activate(fallback.id)
                }
            }
        }
        return true
    }

    suspend fun importBook(name: String, drafts: List<WordDraft>): Long {
        return database.withTransaction {
            val bookId = bookDao.insert(
                Book(name = name, type = Book.TYPE_IMPORTED, totalCount = 0, isActive = false)
            )
            upsertDraftsForBook(bookId, drafts)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
            bookId
        }
    }

    /**
     * 把内置种子词库落到一本词书里（没有就新建，有就原地更新），返回词书 id。
     *
     * 内容与记忆思路按 word_key 去重后 REPLACE 写入，不碰 tb_progress，
     * 所以重复执行只刷新「词义 / 例句 / 记忆思路 / 搭配」，学习进度不会重置。
     * 没有任何可用的其他活跃词书时，自动把这本书设为当前词书。
     */
    suspend fun ensureCardSeedBook(seed: CardSeedPayload): Long {
        val bookName = seed.book.trim().ifBlank { CardSeedLoader.DEFAULT_BOOK_NAME }
        val drafts = CardSeedLoader.toDrafts(seed)
        if (drafts.isEmpty()) return 0L

        var bookId = 0L
        database.withTransaction {
            val existing = bookDao.findByNameAndType(bookName, Book.TYPE_IMPORTED)
            bookId = existing?.id ?: bookDao.insert(
                Book(name = bookName, type = Book.TYPE_IMPORTED, totalCount = 0, isActive = false)
            )
            upsertDraftsForBook(bookId, drafts)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
            val active = bookDao.getActiveBook()
            if (active == null || active.type == Book.TYPE_NEW_WORDS) {
                bookDao.deactivateAll()
                bookDao.activate(bookId)
            }
        }
        return bookId
    }

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
     * 20 词测试书：只建书和内容，不切成当前学习书（用户想测时自己切）。
     * 已存在同名书时就地更新，不重复建。
     */
    suspend fun ensureTestDeck(seed: CardSeedPayload): Long {
        val bookName = seed.book.trim().ifBlank { "测试·20词" }
        val drafts = CardSeedLoader.toDrafts(seed)
        if (drafts.isEmpty()) return 0L

        var bookId = 0L
        database.withTransaction {
            val existing = bookDao.findByNameAndType(bookName, Book.TYPE_IMPORTED)
            bookId = existing?.id ?: bookDao.insert(
                Book(name = bookName, type = Book.TYPE_IMPORTED, totalCount = 0, isActive = false)
            )
            upsertDraftsForBook(bookId, drafts)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
        }
        return bookId
    }

    /**
     * 1.4.4：给种子卡片回填记忆方法标签（六脉神剑之一）。
     * 语料来自 assets/word_method_map.json（by-word 导出里本来就带 method，零模型成本）。
     * 按方法分组、分批 UPDATE，只填空值，不覆盖重写过的词。
     * 返回标注后库里已有标签的行数，方便日志核对。
     */
    suspend fun backfillWordMethods(map: Map<String, String>): Int {
        if (map.isEmpty()) return 0
        val byMethod = map.entries.groupBy({ it.value }, { it.key })
        database.withTransaction {
            byMethod.forEach { (method, keys) ->
                keys.chunked(400).forEach { chunk -> wordDao.backfillMethod(chunk, method) }
            }
        }
        return wordDao.countMarkedMethod()
    }

    suspend fun ensurePresetBooks(presetSeeds: List<PresetBookSeed> = PresetBookCatalog.presets) {
        database.withTransaction {
            val books = bookDao.getAllBooksList().toMutableList()
            if (books.none { it.type == Book.TYPE_NEW_WORDS }) {
                val newWordsId = bookDao.insert(
                    Book(
                        name = PresetBookCatalog.NEW_WORDS_BOOK_NAME,
                        type = Book.TYPE_NEW_WORDS,
                        totalCount = 0,
                        isActive = false
                    )
                )
                bookDao.getBookById(newWordsId)?.let { books.add(it) }
            }

            val presetNames = presetSeeds.map { it.name }.toSet()
            val stalePresets = books.filter { it.type == Book.TYPE_PRESET && it.name !in presetNames }
            if (stalePresets.isNotEmpty()) {
                stalePresets.forEach { preset ->
                    earlyReviewDao.deleteByBook(preset.id)
                    progressDao.deleteByBook(preset.id)
                    wordDao.deleteByBook(preset.id)
                    bookDao.delete(preset)
                }
                wordDao.deleteOrphanWords()
                val staleIds = stalePresets.map { it.id }.toSet()
                books.removeAll { it.id in staleIds }
            }

            val duplicatePresets = books
                .filter { it.type == Book.TYPE_PRESET && it.name in presetNames }
                .groupBy { it.name }
                .values
                .flatMap { sameNameBooks ->
                    sameNameBooks
                        .sortedBy { it.id }
                        .drop(1)
                }
            if (duplicatePresets.isNotEmpty()) {
                duplicatePresets.forEach { preset ->
                    earlyReviewDao.deleteByBook(preset.id)
                    progressDao.deleteByBook(preset.id)
                    wordDao.deleteByBook(preset.id)
                    bookDao.delete(preset)
                }
                wordDao.deleteOrphanWords()
                val duplicateIds = duplicatePresets.map { it.id }.toSet()
                books.removeAll { it.id in duplicateIds }
            }

            var hasActiveBook = books.any { it.isActive }
            presetSeeds.forEachIndexed { index, preset ->
                val existing = books.firstOrNull { it.type == Book.TYPE_PRESET && it.name == preset.name }
                    ?: bookDao.findByNameAndType(preset.name, Book.TYPE_PRESET)
                val targetBook = if (existing == null) {
                    val createdId = bookDao.insert(
                        Book(
                            name = preset.name,
                            type = Book.TYPE_PRESET,
                            totalCount = 0,
                            isActive = !hasActiveBook && index == 0
                        )
                    )
                    val created = bookDao.getBookById(createdId)
                    if (created != null) {
                        books.add(created)
                        if (created.isActive) {
                            hasActiveBook = true
                        }
                    }
                    created
                } else {
                    existing
                }
                if (targetBook != null) {
                    syncPresetWords(targetBook.id, preset.drafts)
                }
            }

            if (!hasActiveBook) {
                val fallbackBook = books.firstOrNull { it.type == Book.TYPE_PRESET } ?: books.firstOrNull()
                if (fallbackBook != null) {
                    bookDao.deactivateAll()
                    bookDao.activate(fallbackBook.id)
                }
            }
            updateNewWordsBookCount()
        }
    }

    // ---- Word ----

    fun getWordsByBook(bookId: Long): Flow<List<Word>> = wordDao.getWordsByBook(bookId)

    suspend fun getWordsByBookList(bookId: Long): List<Word> = wordDao.getWordsByBookList(bookId)

    suspend fun getWordsForExport(book: Book): List<Word> {
        return if (book.type == Book.TYPE_NEW_WORDS) {
            wordDao.getNewWordsList()
        } else {
            wordDao.getWordsByBookList(book.id)
        }
    }

    suspend fun getNewWordsList(): List<Word> = wordDao.getNewWordsList()

    suspend fun getWordById(wordId: Long): Word? = wordDao.getWordById(wordId)

    suspend fun searchWords(query: String, currentBookId: Long? = null): List<Word> {
        return wordDao.searchWords(query, currentBookId ?: 0L)
    }

    suspend fun insertWords(words: List<Word>) {
        if (words.isEmpty()) return
        val draftsByBook = words.groupBy { it.bookId }
        draftsByBook.forEach { (bookId, list) ->
            if (bookId <= 0L) return@forEach
            val drafts = list.map { word ->
                WordDraft(
                    word = word.word,
                    phonetic = word.phonetic,
                    meaning = word.meaning,
                    example = word.example,
                    phrases = word.phrases,
                    synonyms = word.synonyms,
                    relWords = word.relWords
                )
            }
            upsertDraftsForBook(bookId, drafts)
            bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
        }
    }

    suspend fun getWordCount(bookId: Long): Int = wordDao.getWordCount(bookId)

    // ---- Progress ----

    suspend fun getProgress(wordId: Long, bookId: Long): Progress? {
        return progressDao.getGlobalProgress(wordId)
    }

    suspend fun getGlobalProgressMap(wordIds: Collection<Long>): Map<Long, Progress> {
        if (wordIds.isEmpty()) return emptyMap()
        val grouped = getProgressByWordIdsChunked(wordIds)
            .groupBy { it.wordId }
        val result = LinkedHashMap<Long, Progress>(grouped.size)
        grouped.forEach { (wordId, progressList) ->
            val best = progressList.maxWithOrNull(PROGRESS_PRIORITY_COMPARATOR) ?: return@forEach
            result[wordId] = best
        }
        return result
    }

    suspend fun getProgressSnapshotsForWord(wordId: Long): List<Progress> {
        return progressDao.getProgressByWordIds(listOf(wordId))
            .sortedWith(compareBy<Progress> { it.bookId }.thenBy { it.id })
    }

    suspend fun restoreProgressSnapshots(
        wordId: Long,
        snapshots: List<Progress>,
        rollbackGestureEasy: Boolean = false
    ) {
        database.withTransaction {
            progressDao.deleteByWordId(wordId)
            snapshots.forEach { snapshot ->
                progressDao.insert(
                    snapshot.copy(
                        id = 0,
                        wordId = wordId
                    )
                )
            }
            if (rollbackGestureEasy) {
                updateDailyStatsInternal(gestureEasyDelta = -1)
            }
            invalidateForecastCacheInternal()
        }
    }

    fun getLearnedCount(bookId: Long): Flow<Int> = progressDao.getLearnedCount(bookId)

    fun getMasteredCount(bookId: Long): Flow<Int> = progressDao.getMasteredCount(bookId)

    // ---- 按书统计聚合（ProfileScreen 的「当前词书」视图）----
    // 全部是单行聚合结果；不要改成 getProgressByBookFlow 那种整本实体化（5000+ 词会掉帧）。

    fun getBookDueCount(bookId: Long, now: Long = System.currentTimeMillis()): Flow<Int> =
        progressDao.getBookDueCount(bookId, now)

    fun getBookAccuracy(bookId: Long): Flow<Float?> = progressDao.getBookAccuracy(bookId)

    fun getBookTodayTouchedCount(bookId: Long, startTime: Long, endTime: Long): Flow<Int> =
        progressDao.getBookTodayTouchedCount(bookId, startTime, endTime)

    // ---- 按记忆方法聚合（「按 tag 统计」）----
    // 全局按词去重（同一个词在几本书里都有进度只算一次）；按书视图只看这一本。

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
    private suspend fun enqueueProgressSync(wordId: Long, bookId: Long) {
        // 学习一个词会同时写「所有关联书」（见 applyStudyResult 里的 linkedBookIds），
        // 同步也要一本不落地入队，否则另一台只背了另一本书时，进度就永远追不上。
        val linked = runCatching {
            (wordDao.getBookIdsByWordId(wordId) + bookId).distinct()
        }.getOrElse { listOf(bookId) }
        for (linkedBookId in linked) {
            val snapshot = progressDao.getProgress(wordId, linkedBookId) ?: continue
            try {
                syncRepository.enqueueProgress(wordId, linkedBookId, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "学习记录入同步队列失败 wordId=$wordId bookId=$linkedBookId", e)
            }
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

    suspend fun applyStudyResult(
        wordId: Long,
        bookId: Long,
        rating: StudyRating,
        algorithmV4Enabled: Boolean = false,
        mlScheduler: MLEnhancedScheduler? = null,
        mlEnabled: Boolean = false,
        sessionPosition: Int = 0,
        sessionTotal: Int = 1,
        responseTimeMs: Long = 0L
    ) {
        database.withTransaction {
            val existing = progressDao.getGlobalProgress(wordId)
            val schedule = Sm2Scheduler.schedule(
                current = existing,
                rating = rating,
                algorithmV4Enabled = algorithmV4Enabled
            )

            // ML微调
            val mlAdjustment = mlScheduler?.adjust(
                baseIntervalDays = schedule.intervalDays,
                baseEaseFactor = schedule.easeFactor,
                progress = existing,
                sessionPosition = sessionPosition,
                sessionTotal = sessionTotal,
                mlEnabled = mlEnabled
            )
            val finalIntervalDays = mlAdjustment?.adjustedIntervalDays ?: schedule.intervalDays
            val finalEaseFactor = if (mlAdjustment != null && mlAdjustment.confidence > 0.1f) {
                mlAdjustment.adjustedEaseFactor
            } else {
                schedule.easeFactor
            }
            val finalNextReviewTime = if (finalIntervalDays != schedule.intervalDays && finalIntervalDays > 0) {
                Sm2Scheduler.nextReviewTimeByDays(finalIntervalDays)
            } else {
                schedule.nextReviewTime
            }

            val previousReviewCount = existing?.reviewCount ?: 0
            val qualifiesAsCompleted = rating.quality >= StudyRating.HARD.quality
            val reviewCount = previousReviewCount + if (qualifiesAsCompleted) 1 else 0
            val isNewLearningCompletion = qualifiesAsCompleted && previousReviewCount == 0
            val isReviewCompletion = qualifiesAsCompleted && previousReviewCount > 0

            // 更新ML相关字段
            val now = System.currentTimeMillis()
            val isCorrect = rating != StudyRating.AGAIN
            val newConsecutiveCorrect = if (isCorrect) {
                (existing?.consecutiveCorrect ?: 0) + 1
            } else {
                0
            }
            val oldAvgTime = existing?.avgResponseTimeMs ?: 0f
            val oldReviewCount = existing?.reviewCount ?: 0
            val newAvgResponseTime = if (responseTimeMs > 0L) {
                if (oldReviewCount > 0) {
                    (oldAvgTime * oldReviewCount + responseTimeMs) / (oldReviewCount + 1)
                } else {
                    responseTimeMs.toFloat()
                }
            } else {
                oldAvgTime
            }

            val linkedBookIds = (wordDao.getBookIdsByWordId(wordId) + bookId).distinct()
            linkedBookIds.forEach { linkedBookId ->
                val existingByBook = progressDao.getProgress(wordId, linkedBookId)
                val progress = Progress(
                    id = existingByBook?.id ?: 0,
                    wordId = wordId,
                    bookId = linkedBookId,
                    status = schedule.status,
                    repetitions = schedule.repetitions,
                    intervalDays = finalIntervalDays,
                    nextReviewTime = finalNextReviewTime,
                    easeFactor = finalEaseFactor,
                    reviewCount = reviewCount,
                    spellCorrectCount = existing?.spellCorrectCount ?: 0,
                    spellWrongCount = existing?.spellWrongCount ?: 0,
                    markedEasyCount = existing?.markedEasyCount ?: 0,
                    lastEasyTime = existing?.lastEasyTime ?: 0L,
                    consecutiveCorrect = newConsecutiveCorrect,
                    avgResponseTimeMs = newAvgResponseTime,
                    lastReviewTime = now
                )
                if (existingByBook != null) {
                    progressDao.update(progress.copy(id = existingByBook.id))
                } else {
                    progressDao.insert(progress)
                }
            }
            earlyReviewDao.deleteByWord(wordId)
            if (qualifiesAsCompleted) {
                recordStudyInternal()
            }
            updateDailyStatsInternal(
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0,
                fuzzyWordsDelta = if (rating == StudyRating.HARD) 1 else 0,
                recognizedWordsDelta = if (rating == StudyRating.GOOD) 1 else 0
            )
            invalidateForecastCacheInternal()
        }
        // 事务提交后再入同步队列：入队失败不能回滚学习结果，也绝不在事务里做任何网络事。
        enqueueProgressSync(wordId, bookId)
    }

    suspend fun applySpellingOutcome(
        wordId: Long,
        bookId: Long,
        outcome: SpellingOutcome,
        attemptCount: Int,
        durationMillis: Long = 0L,
        algorithmV4Enabled: Boolean = false,
        mlScheduler: MLEnhancedScheduler? = null,
        mlEnabled: Boolean = false,
        sessionPosition: Int = 0,
        sessionTotal: Int = 1
    ) {
        database.withTransaction {
            val existing = progressDao.getGlobalProgress(wordId)
            val schedule = Sm2Scheduler.scheduleSpelling(
                current = existing,
                outcome = outcome,
                algorithmV4Enabled = algorithmV4Enabled
            )

            // ML微调
            val mlAdjustment = mlScheduler?.adjust(
                baseIntervalDays = schedule.intervalDays,
                baseEaseFactor = schedule.easeFactor,
                progress = existing,
                sessionPosition = sessionPosition,
                sessionTotal = sessionTotal,
                mlEnabled = mlEnabled
            )
            val finalIntervalDays = mlAdjustment?.adjustedIntervalDays ?: schedule.intervalDays
            val finalEaseFactor = if (mlAdjustment != null && mlAdjustment.confidence > 0.1f) {
                mlAdjustment.adjustedEaseFactor
            } else {
                schedule.easeFactor
            }
            val finalNextReviewTime = if (finalIntervalDays != schedule.intervalDays && finalIntervalDays > 0) {
                Sm2Scheduler.nextReviewTimeByDays(finalIntervalDays)
            } else {
                schedule.nextReviewTime
            }

            val previousReviewCount = existing?.reviewCount ?: 0
            val qualifiesAsCompleted = outcome.quality >= StudyRating.HARD.quality
            val reviewCount = previousReviewCount + if (qualifiesAsCompleted) 1 else 0
            val isNewLearningCompletion = qualifiesAsCompleted && previousReviewCount == 0
            val isReviewCompletion = qualifiesAsCompleted && previousReviewCount > 0
            val spellCorrectCount = (existing?.spellCorrectCount ?: 0) + outcome.spellCorrectDelta
            val spellWrongCount = (existing?.spellWrongCount ?: 0) + outcome.spellWrongDelta

            // 更新ML相关字段
            val now = System.currentTimeMillis()
            val isCorrect = outcome != SpellingOutcome.FAILED
            val newConsecutiveCorrect = if (isCorrect) {
                (existing?.consecutiveCorrect ?: 0) + 1
            } else {
                0
            }
            val responseTimeMs = durationMillis
            val oldAvgTime = existing?.avgResponseTimeMs ?: 0f
            val oldReviewCount = existing?.reviewCount ?: 0
            val newAvgResponseTime = if (responseTimeMs > 0L) {
                if (oldReviewCount > 0) {
                    (oldAvgTime * oldReviewCount + responseTimeMs) / (oldReviewCount + 1)
                } else {
                    responseTimeMs.toFloat()
                }
            } else {
                oldAvgTime
            }

            val linkedBookIds = (wordDao.getBookIdsByWordId(wordId) + bookId).distinct()
            linkedBookIds.forEach { linkedBookId ->
                val existingByBook = progressDao.getProgress(wordId, linkedBookId)
                val progress = Progress(
                    id = existingByBook?.id ?: 0,
                    wordId = wordId,
                    bookId = linkedBookId,
                    status = schedule.status,
                    repetitions = schedule.repetitions,
                    intervalDays = finalIntervalDays,
                    nextReviewTime = finalNextReviewTime,
                    easeFactor = finalEaseFactor,
                    reviewCount = reviewCount,
                    spellCorrectCount = spellCorrectCount,
                    spellWrongCount = spellWrongCount,
                    markedEasyCount = existing?.markedEasyCount ?: 0,
                    lastEasyTime = existing?.lastEasyTime ?: 0L,
                    consecutiveCorrect = newConsecutiveCorrect,
                    avgResponseTimeMs = newAvgResponseTime,
                    lastReviewTime = now
                )
                if (existingByBook != null) {
                    progressDao.update(progress.copy(id = existingByBook.id))
                } else {
                    progressDao.insert(progress)
                }
            }
            earlyReviewDao.deleteByWord(wordId)
            if (qualifiesAsCompleted) {
                recordStudyInternal()
            }
            updateDailyStatsInternal(
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0,
                spellPracticeDelta = attemptCount.coerceAtLeast(1),
                durationMillisDelta = durationMillis.coerceAtLeast(0L)
            )
            invalidateForecastCacheInternal()
        }
        // 拼写结果也是「结果态」，同样在事务外入队（同一个 (词, 书) 后写覆盖先写）。
        enqueueProgressSync(wordId, bookId)
    }

    suspend fun getStudyQueue(
        book: Book,
        newWordLimit: Int = DEFAULT_NEW_WORDS_LIMIT,
        shuffleNewWords: Boolean = false,
        plannedNewWordIds: List<Long> = emptyList(),
        plannedModeEnabled: Boolean = false
    ): List<Word> {
        return getStudyQueueSnapshot(
            book = book,
            newWordLimit = newWordLimit,
            shuffleNewWords = shuffleNewWords,
            plannedNewWordIds = plannedNewWordIds,
            plannedModeEnabled = plannedModeEnabled
        ).queue
    }

    suspend fun getStudyQueueSnapshot(
        book: Book,
        newWordLimit: Int = DEFAULT_NEW_WORDS_LIMIT,
        shuffleNewWords: Boolean = false,
        plannedNewWordIds: List<Long> = emptyList(),
        plannedModeEnabled: Boolean = false
    ): StudyQueueSnapshot {
        val sourceWords = if (book.type == Book.TYPE_NEW_WORDS) {
            wordDao.getNewWordsList()
        } else {
            wordDao.getWordsByBookList(book.id)
        }
        if (sourceWords.isEmpty()) {
            return StudyQueueSnapshot(queue = emptyList(), dueCount = 0)
        }

        val progressByWordId = getGlobalProgressMap(sourceWords.map { it.id })
        val progressList = progressByWordId.values.toList()
        val dueProgress = progressList
            .filter { it.status != Progress.STATUS_MASTERED && it.nextReviewTime > 0 && DateUtils.isDue(it.nextReviewTime) }
            .sortedBy { it.nextReviewTime }
        val dueCount = dueProgress.size
        val dueOrder = dueProgress.mapIndexed { index, progress -> progress.wordId to index }.toMap()

        val dueWords = sourceWords
            .filter { dueOrder.containsKey(it.id) }
            .sortedBy { dueOrder.getValue(it.id) }

        val earlyReviewRefs = earlyReviewDao.getByBook(book.id)
        val earlyOrder = earlyReviewRefs.mapIndexed { index, ref -> ref.wordId to index }.toMap()
        val earlyReviewWords = sourceWords
            .filter { earlyOrder.containsKey(it.id) }
            .sortedBy { earlyOrder.getValue(it.id) }
            .filterNot { dueOrder.containsKey(it.id) }

        // Keep "new word" slots stable for words that entered learning but are not completed yet.
        // Without this, AGAIN can remove a word from new-word candidates and immediately backfill
        // another unseen word, which makes "remaining count" increase unexpectedly.
        val inLearningButUncompletedCount = progressList.count { progress ->
            progress.status != Progress.STATUS_MASTERED && progress.reviewCount <= 0
        }
        val effectiveNewWordLimit = if (book.type == Book.TYPE_NEW_WORDS) {
            Int.MAX_VALUE
        } else {
            val todayLearned = getTodayNewWordsCount()
            val remainingByDailyLimit = (newWordLimit - todayLearned).coerceAtLeast(0)
            val remainingInBook = sourceWords.count { it.id !in progressByWordId.keys }
            minOf(remainingByDailyLimit, remainingInBook)
        }
        val adjustedNewWordLimit = (effectiveNewWordLimit - inLearningButUncompletedCount).coerceAtLeast(0)
        val progressIds = progressList.map { it.wordId }.toSet()
        val excludedIds = dueWords.map { it.id }.toSet() + earlyReviewWords.map { it.id }.toSet()
        val autoNewWords = sourceWords
            .filter { it.id !in progressIds && it.id !in excludedIds }
            .take(adjustedNewWordLimit)

        val plannedCandidatesById = sourceWords
            .asSequence()
            .filter { it.id !in progressIds && it.id !in excludedIds }
            .associateBy { it.id }
        val plannedNewWords = plannedNewWordIds
            .asSequence()
            .mapNotNull { plannedId -> plannedCandidatesById[plannedId] }
            .distinctBy { it.id }
            .take(adjustedNewWordLimit)
            .toList()
        val newWords = when {
            book.type == Book.TYPE_NEW_WORDS -> autoNewWords
            plannedModeEnabled -> plannedNewWords
            plannedNewWords.isNotEmpty() -> plannedNewWords
            else -> autoNewWords
        }

        val orderedNewWords = if (shuffleNewWords) {
            newWords.shuffled()
        } else {
            newWords
        }

        val reviewWords = dueWords + earlyReviewWords
        return StudyQueueSnapshot(
            queue = mixReviewAndNewWords(reviewWords, orderedNewWords),
            dueCount = dueCount
        )
    }

    fun getEarlyReviewCountFlow(bookId: Long): Flow<Int> = earlyReviewDao.getCountFlow(bookId)

    suspend fun getEarlyReviewWordIds(bookId: Long): Set<Long> {
        return earlyReviewDao.getByBook(bookId).map { it.wordId }.toSet()
    }

    suspend fun replaceEarlyReviewWords(bookId: Long, wordIds: Collection<Long>) {
        database.withTransaction {
            earlyReviewDao.deleteByBook(bookId)
            if (wordIds.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val refs = wordIds.distinct().mapIndexed { index, wordId ->
                    EarlyReviewRef(
                        wordId = wordId,
                        bookId = bookId,
                        addTime = now + index
                    )
                }
                earlyReviewDao.insertAll(refs)
            }
        }
    }

    // ---- NewWordRef ----

    fun getAllNewWordRefs(): Flow<List<NewWordRef>> = newWordRefDao.getAll()

    fun getNewWordCount(): Flow<Int> = newWordRefDao.getCount()

    fun getNewWordIdsFlow(): Flow<Set<Long>> {
        return newWordRefDao.getAll().map { refs -> refs.map { it.wordId }.toSet() }
    }

    suspend fun addToNewWords(wordId: Long, source: AddToNewWordsSource = AddToNewWordsSource.BUTTON): Boolean {
        return database.withTransaction {
            getOrCreateNewWordsBook()
            val inserted = if (newWordRefDao.exists(wordId)) {
                false
            } else {
                newWordRefDao.insert(NewWordRef(wordId = wordId))
                updateNewWordsBookCount()
                true
            }
            if (source == AddToNewWordsSource.GESTURE) {
                updateDailyStatsInternal(gestureNotebookDelta = 1)
            }
            inserted
        }
    }

    suspend fun markWordAsTooEasy(wordId: Long, bookId: Long): Boolean {
        // 先拿事务的返回值，再决定要不要入队：linkedBookIds 为空时什么都没写，不能推一条空记录。
        val marked = database.withTransaction {
            val linkedBookIds = (wordDao.getBookIdsByWordId(wordId) + bookId)
                .distinct()
                .filter { it > 0L }
            if (linkedBookIds.isEmpty()) return@withTransaction false

            val now = System.currentTimeMillis()
            // "太简单" is a user override (hard intervention), not a standard SM-2 transition.
            val nextReviewTime = Sm2Scheduler.nextReviewTimeByDays(30, now)
            val globalProgress = progressDao.getGlobalProgress(wordId)

            linkedBookIds.forEach { linkedBookId ->
                val existingByBook = progressDao.getProgress(wordId, linkedBookId)
                val base = existingByBook ?: globalProgress
                val updated = Progress(
                    id = existingByBook?.id ?: 0L,
                    wordId = wordId,
                    bookId = linkedBookId,
                    status = Progress.STATUS_MASTERED,
                    repetitions = maxOf(base?.repetitions ?: 0, 1),
                    intervalDays = 30,
                    nextReviewTime = nextReviewTime,
                    easeFactor = base?.easeFactor ?: 2.5f,
                    reviewCount = base?.reviewCount ?: 0,
                    spellCorrectCount = base?.spellCorrectCount ?: 0,
                    spellWrongCount = base?.spellWrongCount ?: 0,
                    markedEasyCount = (base?.markedEasyCount ?: 0) + 1,
                    lastEasyTime = now
                )
                if (existingByBook != null) {
                    progressDao.update(updated.copy(id = existingByBook.id))
                } else {
                    progressDao.insert(updated)
                }
                earlyReviewDao.deleteByWordAndBook(wordId, linkedBookId)
            }

            updateDailyStatsInternal(gestureEasyDelta = 1)
            invalidateForecastCacheInternal()
            true
        }
        if (marked) {
            // 这条路径写库时不填 last_review_time（实体默认 0），入队时由 SyncRepository 用动作时间兜底，
            // 否则在「最新 lrt 赢」的口径下它永远推不倒对面。
            enqueueProgressSync(wordId, bookId)
        }
        return marked
    }

    suspend fun removeFromNewWords(wordId: Long) {
        newWordRefDao.deleteByWordId(wordId)
        updateNewWordsBookCount()
    }

    suspend fun isInNewWords(wordId: Long): Boolean = newWordRefDao.exists(wordId)

    suspend fun clearNewWords() {
        val book = getOrCreateNewWordsBook()
        newWordRefDao.deleteAll()
        progressDao.deleteByBook(book.id)
        updateNewWordsBookCount()
    }

    // ---- Study Log ----

    fun getStudyLogs(): Flow<List<StudyLog>> = studyLogDao.getAll()

    fun getDailyStats(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyStats>> {
        val start = startDate.format(DATE_FORMATTER)
        val end = endDate.format(DATE_FORMATTER)
        return dailyStatsDao.getByDateRange(start, end)
    }

    fun getDailyStatsAggregated(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyStatsAggregate>> {
        val start = startDate.format(DATE_FORMATTER)
        val end = endDate.format(DATE_FORMATTER)
        return dailyStatsDao.getAggregatedByDateRange(start, end)
    }

    suspend fun checkInToday(): CheckInResult {
        return database.withTransaction {
            val today = currentLearningDate().format(DATE_FORMATTER)
            val existing = dailyStatsDao.getOrCreate(today)
            if (existing.checkInCount > 0) {
                return@withTransaction CheckInResult.ALREADY_CHECKED_IN
            }
            val todayActivityCount = existing.newWordsCount + existing.reviewWordsCount + existing.spellPracticeCount
            if (todayActivityCount <= 0) {
                return@withTransaction CheckInResult.NOT_ELIGIBLE
            }
            dailyStatsDao.update(
                existing.copy(
                    checkInCount = 1,
                    lastCheckInTime = System.currentTimeMillis()
                )
            )
            CheckInResult.SUCCESS
        }
    }

    suspend fun recordSpellingResult(wordId: Long, bookId: Long, isCorrect: Boolean) {
        database.withTransaction {
            val existing = progressDao.getGlobalProgress(wordId)
            val spellCorrect = (existing?.spellCorrectCount ?: 0) + if (isCorrect) 1 else 0
            val spellWrong = (existing?.spellWrongCount ?: 0) + if (isCorrect) 0 else 1
            val linkedBookIds = (wordDao.getBookIdsByWordId(wordId) + bookId).distinct()
            linkedBookIds.forEach { linkedBookId ->
                val existingByBook = progressDao.getProgress(wordId, linkedBookId)
                val updated = if (existingByBook == null) {
                    Progress(
                        wordId = wordId,
                        bookId = linkedBookId,
                        spellCorrectCount = spellCorrect,
                        spellWrongCount = spellWrong,
                        markedEasyCount = existing?.markedEasyCount ?: 0,
                        lastEasyTime = existing?.lastEasyTime ?: 0L
                    )
                } else {
                    existingByBook.copy(
                        spellCorrectCount = spellCorrect,
                        spellWrongCount = spellWrong
                    )
                }
                if (existingByBook == null) {
                    progressDao.insert(updated)
                } else {
                    progressDao.update(updated.copy(id = existingByBook.id))
                }
            }
            updateDailyStatsInternal(spellPracticeDelta = 1)
        }
    }

    private suspend fun getTodayNewWordsCount(): Int {
        val today = currentLearningDate().format(DATE_FORMATTER)
        return dailyStatsDao.getByDate(today)?.newWordsCount ?: 0
    }

    // ---- Utility ----

    suspend fun getNextNewWord(bookId: Long): Word? = wordDao.getNextNewWord(bookId)

    suspend fun getOrCreateNewWordsBook(): Book {
        return bookDao.getNewWordsBook() ?: run {
            val id = bookDao.insert(
                Book(
                    name = PresetBookCatalog.NEW_WORDS_BOOK_NAME,
                    type = Book.TYPE_NEW_WORDS,
                    totalCount = 0,
                    isActive = false
                )
            )
            bookDao.getBookById(id)!!
        }
    }

    suspend fun updateBookTotalCount(bookId: Long) {
        val count = wordDao.getWordCount(bookId)
        bookDao.updateTotalCount(bookId, count)
    }

    private suspend fun syncPresetWords(bookId: Long, drafts: List<WordDraft>) {
        if (drafts.isEmpty()) {
            bookDao.updateTotalCount(bookId, 0)
            return
        }

        val currentCount = wordDao.getWordCount(bookId)
        if (currentCount == drafts.size) {
            return
        }

        val existingWordKeys = wordDao.getWordKeysByBook(bookId)
            .toSet()
        val toInsert = drafts
            .filter { normalizeWordKey(it.word) !in existingWordKeys }
        upsertDraftsForBook(bookId, toInsert)
        bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
    }

    private fun normalizeWordKey(raw: String): String {
        return raw.trim().lowercase()
    }

    private fun mixReviewAndNewWords(reviewWords: List<Word>, newWords: List<Word>): List<Word> {
        if (reviewWords.isEmpty()) return newWords
        if (newWords.isEmpty()) return reviewWords

        val mixed = ArrayList<Word>(reviewWords.size + newWords.size)
        var reviewIndex = 0
        var newIndex = 0
        var continuousReviewCount = 0

        while (reviewIndex < reviewWords.size || newIndex < newWords.size) {
            val shouldInjectNew = newIndex < newWords.size && continuousReviewCount >= 3
            if (reviewIndex < reviewWords.size && !shouldInjectNew) {
                mixed.add(reviewWords[reviewIndex])
                reviewIndex += 1
                continuousReviewCount += 1
            } else if (newIndex < newWords.size) {
                mixed.add(newWords[newIndex])
                newIndex += 1
                continuousReviewCount = 0
            } else {
                mixed.add(reviewWords[reviewIndex])
                reviewIndex += 1
                continuousReviewCount += 1
            }
        }

        return mixed
    }

    private suspend fun upsertDraftsForBook(bookId: Long, drafts: List<WordDraft>) {
        if (drafts.isEmpty()) return

        val normalizedDrafts = LinkedHashMap<String, WordDraft>(drafts.size)
        drafts.forEach { draft ->
            val key = normalizeWordKey(draft.word)
            if (key.isBlank() || normalizedDrafts.containsKey(key)) {
                return@forEach
            }
            normalizedDrafts[key] = draft
        }
        if (normalizedDrafts.isEmpty()) return

        val wordIdsByKey = HashMap<String, Long>(normalizedDrafts.size)
        normalizedDrafts.keys
            .toList()
            .chunked(MAX_DB_IN_CLAUSE_SIZE)
            .forEach { chunk ->
                wordDao.getWordEntitiesByKeys(chunk).forEach { entity ->
                    wordIdsByKey[entity.wordKey] = entity.id
                    val draft = normalizedDrafts[entity.wordKey] ?: return@forEach
                    val phonetic = draft.phonetic.trim()
                    if (entity.phonetic.isBlank() && phonetic.isNotBlank()) {
                        wordDao.updatePhonetic(entity.id, phonetic)
                    }
                }
            }

        normalizedDrafts.forEach { (key, draft) ->
            if (wordIdsByKey.containsKey(key)) {
                return@forEach
            }
            val rawWord = draft.word.trim()
            if (rawWord.isBlank()) {
                return@forEach
            }
            val insertedId = wordDao.insertWordEntity(
                WordEntity(
                    word = rawWord,
                    wordKey = key,
                    phonetic = draft.phonetic.trim()
                )
            )
            val resolvedWordId = if (insertedId > 0) {
                insertedId
            } else {
                wordDao.getWordIdByKey(key)
            }
            if (resolvedWordId != null) {
                wordIdsByKey[key] = resolvedWordId
            }
        }

        val contents = ArrayList<BookWordContent>(normalizedDrafts.size)
        val linkedWordIds = LinkedHashSet<Long>(normalizedDrafts.size)
        normalizedDrafts.forEach { (key, draft) ->
            val wordId = wordIdsByKey[key] ?: return@forEach
            linkedWordIds.add(wordId)
            contents.add(
                BookWordContent(
                    wordId = wordId,
                    bookId = bookId,
                    meaning = draft.meaning,
                    example = draft.example,
                    phrases = draft.phrases,
                    synonyms = draft.synonyms,
                    relWords = draft.relWords
                )
            )
        }
        if (contents.isNotEmpty()) {
            wordDao.upsertBookWordContents(contents)
        }

        // 记忆思路与搭配按 word_id 单独存，重新导入只覆盖它们自己，
        // 不会碰 tb_progress，所以更新内容不会重置学习进度。
        // 1.4.3：用户重写过的词（source=user）跳过，否则种子版本一涨就把重写冲掉。
        val userSourcedWordIds = wordMemoryDao.getUserSourcedWordIds().toHashSet()
        val memories = normalizedDrafts.mapNotNull { (key, draft) ->
            val wordId = wordIdsByKey[key] ?: return@mapNotNull null
            if (wordId in userSourcedWordIds) return@mapNotNull null
            if (draft.approach.isBlank() && draft.collocations.isBlank()) return@mapNotNull null
            WordMemory(
                wordId = wordId,
                approach = draft.approach.trim(),
                collocations = draft.collocations.trim(),
                updatedAt = System.currentTimeMillis()
            )
        }
        if (memories.isNotEmpty()) {
            wordMemoryDao.upsertAll(memories)
        }

        if (contents.isNotEmpty()) {
            syncBookProgressWithGlobal(bookId, linkedWordIds)
        }
    }

    private suspend fun syncBookProgressWithGlobal(bookId: Long, wordIds: Collection<Long>) {
        if (wordIds.isEmpty()) return

        val distinctWordIds = wordIds.distinct()
        val globalProgressByWordId = getProgressByWordIdsChunked(distinctWordIds)
            .groupBy { it.wordId }
            .mapValues { (_, grouped) ->
                grouped.maxWithOrNull(PROGRESS_PRIORITY_COMPARATOR)
            }
        if (globalProgressByWordId.isEmpty()) return

        val existingWordIds = HashSet<Long>()
        distinctWordIds.chunked(MAX_DB_IN_CLAUSE_SIZE).forEach { chunk ->
            existingWordIds.addAll(progressDao.getProgressWordIdsByBookAndWordIds(bookId, chunk))
        }

        val toInsert = ArrayList<Progress>(globalProgressByWordId.size)
        globalProgressByWordId.forEach { (wordId, globalProgress) ->
            val resolvedProgress = globalProgress ?: return@forEach
            if (existingWordIds.contains(wordId)) {
                return@forEach
            }
            toInsert.add(
                resolvedProgress.copy(
                    id = 0,
                    wordId = wordId,
                    bookId = bookId
                )
            )
        }
        if (toInsert.isNotEmpty()) {
            progressDao.insertAll(toInsert)
        }
    }

    private suspend fun updateNewWordsBookCount() {
        val book = getOrCreateNewWordsBook()
        val count = newWordRefDao.getCountOnce()
        bookDao.updateTotalCount(book.id, count)
    }

    private suspend fun recordStudyInternal() {
        val today = currentLearningDate().format(DATE_FORMATTER)
        val now = System.currentTimeMillis()
        val existing = studyLogDao.getByDate(today)
        val updated = if (existing == null) {
            StudyLog(date = today, count = 1, updateTime = now)
        } else {
            existing.copy(count = existing.count + 1, updateTime = now)
        }
        studyLogDao.insert(updated)
    }

    private suspend fun updateDailyStatsInternal(
        newWordsDelta: Int = 0,
        reviewWordsDelta: Int = 0,
        spellPracticeDelta: Int = 0,
        durationMillisDelta: Long = 0L,
        gestureEasyDelta: Int = 0,
        gestureNotebookDelta: Int = 0,
        fuzzyWordsDelta: Int = 0,
        recognizedWordsDelta: Int = 0
    ) {
        val today = currentLearningDate().format(DATE_FORMATTER)
        val existing = dailyStatsDao.getOrCreate(today)
        val updated = existing.copy(
            newWordsCount = (existing.newWordsCount + newWordsDelta).coerceAtLeast(0),
            reviewWordsCount = (existing.reviewWordsCount + reviewWordsDelta).coerceAtLeast(0),
            spellPracticeCount = (existing.spellPracticeCount + spellPracticeDelta).coerceAtLeast(0),
            durationMillis = (existing.durationMillis + durationMillisDelta).coerceAtLeast(0L),
            gestureEasyCount = (existing.gestureEasyCount + gestureEasyDelta).coerceAtLeast(0),
            gestureNotebookCount = (existing.gestureNotebookCount + gestureNotebookDelta).coerceAtLeast(0),
            fuzzyWordsCount = (existing.fuzzyWordsCount + fuzzyWordsDelta).coerceAtLeast(0),
            recognizedWordsCount = (existing.recognizedWordsCount + recognizedWordsDelta).coerceAtLeast(0)
        )
        dailyStatsDao.update(updated)
    }

    private suspend fun invalidateForecastCacheInternal() {
        forecastCacheDao.clearAll()
    }

    private suspend fun getProgressByWordIdsChunked(wordIds: Collection<Long>): List<Progress> {
        if (wordIds.isEmpty()) return emptyList()
        val rows = ArrayList<Progress>()
        wordIds.distinct()
            .chunked(MAX_DB_IN_CLAUSE_SIZE)
            .forEach { chunk ->
                rows += progressDao.getProgressByWordIds(chunk)
            }
        return rows
    }

    private fun currentLearningDate(now: Long = System.currentTimeMillis()): LocalDate {
        val zonedNow = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        val date = zonedNow.toLocalDate()
        return if (zonedNow.hour < DAY_REFRESH_HOUR) date.minusDays(1) else date
    }

    companion object {
        private const val TAG = "WordRepository"
        private const val DEFAULT_NEW_WORDS_LIMIT = 20
        private const val MAX_DB_IN_CLAUSE_SIZE = 900
        private const val DAY_REFRESH_HOUR = 4
        private const val MASTERED_INTERVAL_DAYS_V4 = 30
        private const val MASTERED_MIN_REVIEW_COUNT_V4 = 4
        private const val MASTERED_MIN_EASE_V4 = 2.3f
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        private val PROGRESS_PRIORITY_COMPARATOR =
            compareBy<Progress> { it.reviewCount }
                .thenBy { it.nextReviewTime }
                .thenBy { it.id }
    }
}

enum class AddToNewWordsSource {
    BUTTON,
    GESTURE
}

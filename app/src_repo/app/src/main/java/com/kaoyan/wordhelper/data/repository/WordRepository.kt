package com.kaoyan.wordhelper.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookDailyStats
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
import com.kaoyan.wordhelper.util.FsrsScheduler
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
    private val bookDailyStatsDao = database.bookDailyStatsDao()
    private val earlyReviewDao = database.earlyReviewDao()
    private val forecastCacheDao = database.forecastCacheDao()
    private val wordMemoryDao = database.wordMemoryDao()
    private val rewriteQueueDao = database.rewriteQueueDao()
    /** 1.4.6：root 标签刷新要按书那份一起改（卡背读的是 tb_book_memory）。 */
    private val bookMemoryDao = database.bookMemoryDao()

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
            // 队列行没有外键；不清理会留下 needs_reinsert=1 的孤儿，永久挡住同词。
            rewriteQueueDao.deleteByBook(book.id)
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
            upsertDraftsForBook(bookId, drafts, memorySource = WordMemory.SOURCE_IMPORT)
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
            upsertDraftsForBook(
                bookId,
                drafts,
                seedFingerprints = SeedMemoryUpgrade.previousFingerprints(seed.version, bookName),
                externalSeedOverride = seed.externalOverride
            )
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

    /**
     * 1.4.6（P0）：种子重导只更新正文，不动 method，而 WordDraft 根本没有 method 字段 ——
     * 于是新导进来的词带着默认的 "root"，老行也永远停在 "root"。
     * 这里按 word_method_map 把「种子来源、且还是 fromMethod」的行改成真正的方法标签：
     * - 只动种子行，source='user'（用户重写过）一律不碰；
     * - 候选集来自 DAO 的「仍在 root 的行」，改完 method 变了，下次启动候选集自然为空 —— 幂等。
     * 返回实际改掉的行数（tb_word_memory + tb_book_memory），方便日志核对。
     */
    suspend fun refreshRootMethods(
        map: Map<String, String>,
        fromMethod: String = DEFAULT_ROOT_METHOD
    ): Int {
        if (map.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var changed = 0
        database.withTransaction {
            val candidates = LinkedHashSet<Long>()
            candidates += wordMemoryDao.getWordIdsByMethod(fromMethod, WordMemory.SOURCE_USER)
            candidates += bookMemoryDao.getWordIdsByMethod(fromMethod, WordMemory.SOURCE_USER)
            candidates.forEach { wordId ->
                val key = wordDao.getWordKeyById(wordId) ?: return@forEach
                val method = map[key]?.trim()
                    ?.takeIf { it.isNotEmpty() && it != fromMethod }
                    ?: return@forEach
                changed += wordMemoryDao.refreshMethod(
                    wordId = wordId,
                    fromMethod = fromMethod,
                    method = method,
                    updatedAt = now,
                    userSource = WordMemory.SOURCE_USER
                )
                changed += bookMemoryDao.refreshMethod(
                    wordId = wordId,
                    fromMethod = fromMethod,
                    method = method,
                    updatedAt = now,
                    userSource = WordMemory.SOURCE_USER
                )
            }
        }
        return changed
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

    suspend fun applyStudyResult(
        wordId: Long,
        bookId: Long,
        rating: StudyRating,
        fsrsEnabled: Boolean = true,
        desiredRetention: Float = 0.90f,
        algorithmV4Enabled: Boolean = false,
        mlScheduler: MLEnhancedScheduler? = null,
        mlEnabled: Boolean = false,
        sessionPosition: Int = 0,
        sessionTotal: Int = 1,
        responseTimeMs: Long = 0L
    ) {
        database.withTransaction {
            val existing = progressDao.getProgress(wordId, bookId)
            // v8：FSRS-5 主调度；关闭时整段回落到旧的 SM-2 + ML 分支。
            val fsrs = if (fsrsEnabled) {
                FsrsScheduler.schedule(
                    current = existing,
                    rating = rating,
                    easy = false,
                    desiredRetention = desiredRetention
                )
            } else {
                null
            }
            val schedule = Sm2Scheduler.schedule(
                current = existing,
                rating = rating,
                algorithmV4Enabled = algorithmV4Enabled
            )

            // ML微调（只服务旧分支；FSRS 分支不再叠加 ML）
            val mlAdjustment = if (fsrs == null) {
                mlScheduler?.adjust(
                    baseIntervalDays = schedule.intervalDays,
                    baseEaseFactor = schedule.easeFactor,
                    progress = existing,
                    sessionPosition = sessionPosition,
                    sessionTotal = sessionTotal,
                    mlEnabled = mlEnabled
                )
            } else {
                null
            }
            val finalIntervalDays = fsrs?.intervalDays
                ?: mlAdjustment?.adjustedIntervalDays
                ?: schedule.intervalDays
            val finalEaseFactor = when {
                // FSRS 不产出 ease；保留旧值只为兼容旧字段与旧阅读逻辑。
                fsrs != null -> existing?.easeFactor ?: 2.5f
                mlAdjustment != null && mlAdjustment.confidence > 0.1f -> mlAdjustment.adjustedEaseFactor
                else -> schedule.easeFactor
            }
            val finalNextReviewTime = when {
                fsrs != null -> fsrs.nextReviewTime
                finalIntervalDays != schedule.intervalDays && finalIntervalDays > 0 ->
                    Sm2Scheduler.nextReviewTimeByDays(finalIntervalDays)
                else -> schedule.nextReviewTime
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

            // 1.4.6：只写当前这本书。同一个词在别的书里是另一份独立进度，不跟着动。
            val progress = Progress(
                id = existing?.id ?: 0,
                wordId = wordId,
                bookId = bookId,
                status = fsrs?.status ?: schedule.status,
                repetitions = fsrs?.repetitions ?: schedule.repetitions,
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
                lastReviewTime = now,
                fsrsStability = fsrs?.stability ?: existing?.fsrsStability ?: 0f,
                fsrsDifficulty = fsrs?.difficulty ?: existing?.fsrsDifficulty ?: 0f,
                fsrsLapses = fsrs?.lapses ?: existing?.fsrsLapses ?: 0,
                fsrsState = fsrs?.state ?: existing?.fsrsState ?: FsrsScheduler.STATE_NEW
            )
            if (existing != null) {
                progressDao.update(progress)
            } else {
                progressDao.insert(progress)
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
            // 1.4.5：全局那份照旧，另外给「这本书」记一笔，学习统计切到词书时才有曲线。
            updateBookDailyStatsInternal(
                bookId = bookId,
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0
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
        fsrsEnabled: Boolean = true,
        desiredRetention: Float = 0.90f,
        algorithmV4Enabled: Boolean = false,
        mlScheduler: MLEnhancedScheduler? = null,
        mlEnabled: Boolean = false,
        sessionPosition: Int = 0,
        sessionTotal: Int = 1
    ) {
        database.withTransaction {
            val existing = progressDao.getProgress(wordId, bookId)
            // v8：拼写结果折算成 FSRS rating；PERFECT 额外按 easy 处理。
            val fsrsRating = when (outcome) {
                SpellingOutcome.PERFECT -> StudyRating.GOOD
                SpellingOutcome.HINTED, SpellingOutcome.RETRY_SUCCESS -> StudyRating.HARD
                SpellingOutcome.FAILED -> StudyRating.AGAIN
            }
            val fsrs = if (fsrsEnabled) {
                FsrsScheduler.schedule(
                    current = existing,
                    rating = fsrsRating,
                    easy = outcome == SpellingOutcome.PERFECT,
                    desiredRetention = desiredRetention
                )
            } else {
                null
            }
            val schedule = Sm2Scheduler.scheduleSpelling(
                current = existing,
                outcome = outcome,
                algorithmV4Enabled = algorithmV4Enabled
            )

            // ML微调（只服务旧分支；FSRS 分支不再叠加 ML）
            val mlAdjustment = if (fsrs == null) {
                mlScheduler?.adjust(
                    baseIntervalDays = schedule.intervalDays,
                    baseEaseFactor = schedule.easeFactor,
                    progress = existing,
                    sessionPosition = sessionPosition,
                    sessionTotal = sessionTotal,
                    mlEnabled = mlEnabled
                )
            } else {
                null
            }
            val finalIntervalDays = fsrs?.intervalDays
                ?: mlAdjustment?.adjustedIntervalDays
                ?: schedule.intervalDays
            val finalEaseFactor = when {
                // FSRS 不产出 ease；保留旧值只为兼容旧字段与旧阅读逻辑。
                fsrs != null -> existing?.easeFactor ?: 2.5f
                mlAdjustment != null && mlAdjustment.confidence > 0.1f -> mlAdjustment.adjustedEaseFactor
                else -> schedule.easeFactor
            }
            val finalNextReviewTime = when {
                fsrs != null -> fsrs.nextReviewTime
                finalIntervalDays != schedule.intervalDays && finalIntervalDays > 0 ->
                    Sm2Scheduler.nextReviewTimeByDays(finalIntervalDays)
                else -> schedule.nextReviewTime
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

            // 1.4.6：拼写结果同样只落当前这本书。
            val progress = Progress(
                id = existing?.id ?: 0,
                wordId = wordId,
                bookId = bookId,
                status = fsrs?.status ?: schedule.status,
                repetitions = fsrs?.repetitions ?: schedule.repetitions,
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
                lastReviewTime = now,
                fsrsStability = fsrs?.stability ?: existing?.fsrsStability ?: 0f,
                fsrsDifficulty = fsrs?.difficulty ?: existing?.fsrsDifficulty ?: 0f,
                fsrsLapses = fsrs?.lapses ?: existing?.fsrsLapses ?: 0,
                fsrsState = fsrs?.state ?: existing?.fsrsState ?: FsrsScheduler.STATE_NEW
            )
            if (existing != null) {
                progressDao.update(progress)
            } else {
                progressDao.insert(progress)
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
            updateBookDailyStatsInternal(
                bookId = bookId,
                newWordsDelta = if (isNewLearningCompletion) 1 else 0,
                reviewWordsDelta = if (isReviewCompletion) 1 else 0,
                spellPracticeDelta = attemptCount.coerceAtLeast(1)
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

        val progressByWordId = getProgressMapForBook(book.id, sourceWords.map { it.id })
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
        if (bookId <= 0L) return
        database.withTransaction {
            // 1.4.6：拼写对错也只记当前这本书。
            val existing = progressDao.getProgress(wordId, bookId)
            val spellCorrect = (existing?.spellCorrectCount ?: 0) + if (isCorrect) 1 else 0
            val spellWrong = (existing?.spellWrongCount ?: 0) + if (isCorrect) 0 else 1
            val updated = if (existing == null) {
                Progress(
                    wordId = wordId,
                    bookId = bookId,
                    spellCorrectCount = spellCorrect,
                    spellWrongCount = spellWrong
                )
            } else {
                existing.copy(
                    spellCorrectCount = spellCorrect,
                    spellWrongCount = spellWrong
                )
            }
            if (existing == null) {
                progressDao.insert(updated)
            } else {
                progressDao.update(updated)
            }
            updateDailyStatsInternal(spellPracticeDelta = 1)
            updateBookDailyStatsInternal(bookId = bookId, spellPracticeDelta = 1)
        }
    }

    /** 1.4.5：某本书从 fromDate 起的按书记账（升级当天开始才有数据）。 */
    suspend fun getBookDailyStats(bookId: Long, fromDate: String): List<BookDailyStats> =
        bookDailyStatsDao.getRange(bookId, fromDate)

    /** 1.4.6：统计页热力图的「当前词书」口径（Room Flow，写入后自动刷新）。 */
    fun getBookDailyStatsFlow(bookId: Long, fromDate: String): Flow<List<BookDailyStats>> =
        bookDailyStatsDao.observeRange(bookId, fromDate)

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

    private suspend fun upsertDraftsForBook(
        bookId: Long,
        drafts: List<WordDraft>,
        memorySource: String = WordMemory.SOURCE_SEED,
        seedFingerprints: Map<String, String> = emptyMap(),
        externalSeedOverride: Boolean = false
    ) {
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
        normalizedDrafts.forEach { (key, draft) ->
            val wordId = wordIdsByKey[key] ?: return@forEach
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
        // 已存在的行需先确认来源，只更新正文和来源，保留标签、版本及撤销记录。
        val existingMemories = HashMap<Long, WordMemory>(normalizedDrafts.size)
        wordIdsByKey.values
            .distinct()
            .chunked(MAX_DB_IN_CLAUSE_SIZE)
            .forEach { chunk ->
                wordMemoryDao.getByWordIds(chunk).forEach { existingMemories[it.wordId] = it }
            }
        val updatedAt = System.currentTimeMillis()
        val newMemories = ArrayList<WordMemory>(normalizedDrafts.size)
        normalizedDrafts.forEach { (key, draft) ->
            val wordId = wordIdsByKey[key] ?: return@forEach
            val approach = draft.approach.trim()
            val collocations = draft.collocations.trim()
            if (approach.isBlank() && collocations.isBlank()) return@forEach
            val existingMemory = existingMemories[wordId]
            if (existingMemory != null) {
                if (SeedMemoryUpgrade.canReplace(
                        existingMemory, memorySource, seedFingerprints[key], externalSeedOverride
                    ) &&
                    (existingMemory.approach != approach || existingMemory.collocations != collocations ||
                        existingMemory.source != memorySource)
                ) {
                    wordMemoryDao.updateContent(wordId, approach, collocations, memorySource, updatedAt)
                }
            } else {
                newMemories.add(
                    WordMemory(
                        wordId = wordId,
                        approach = approach,
                        collocations = collocations,
                        updatedAt = updatedAt,
                        source = memorySource
                    )
                )
            }
        }
        if (newMemories.isNotEmpty()) {
            wordMemoryDao.upsertAll(newMemories)
        }
        // 1.4.6：导入新书不再继承别的书的进度（syncBookProgressWithGlobal 已删除）。
        // 按书独立之后，新书就该从零开始；否则「其他书从零」的口径会被一次导入破掉。
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

    /** 按书累计每日新词/复习/拼写（1.4.5）。bookId<=0 的老调用直接跳过。 */
    private suspend fun updateBookDailyStatsInternal(
        bookId: Long,
        newWordsDelta: Int = 0,
        reviewWordsDelta: Int = 0,
        spellPracticeDelta: Int = 0
    ) {
        if (bookId <= 0L) return
        if (newWordsDelta == 0 && reviewWordsDelta == 0 && spellPracticeDelta == 0) return
        val today = currentLearningDate().format(DATE_FORMATTER)
        val updated = bookDailyStatsDao.addExisting(
            date = today,
            bookId = bookId,
            newCount = newWordsDelta,
            reviewCount = reviewWordsDelta,
            spellCount = spellPracticeDelta
        )
        if (updated == 0) {
            bookDailyStatsDao.insertIfAbsent(
                BookDailyStats(
                    date = today,
                    bookId = bookId,
                    newCount = newWordsDelta.coerceAtLeast(0),
                    reviewCount = reviewWordsDelta.coerceAtLeast(0),
                    spellCount = spellPracticeDelta.coerceAtLeast(0)
                )
            )
        }
    }

    private suspend fun invalidateForecastCacheInternal() {        forecastCacheDao.clearAll()
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
        /** 种子默认方法标签；1.4.6 起按 word_method_map 刷成真正的那一招。 */
        private const val DEFAULT_ROOT_METHOD = "root"
        private const val MASTERED_INTERVAL_DAYS_V4 = 30
        private const val MASTERED_MIN_REVIEW_COUNT_V4 = 4
        private const val MASTERED_MIN_EASE_V4 = 2.3f
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

enum class AddToNewWordsSource {
    BUTTON,
    GESTURE
}

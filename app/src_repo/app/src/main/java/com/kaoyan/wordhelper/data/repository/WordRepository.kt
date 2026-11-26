package com.kaoyan.wordhelper.data.repository

import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookDailyStats
import com.kaoyan.wordhelper.data.entity.DailyStats
import com.kaoyan.wordhelper.data.entity.NewWordRef
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.StudyLog
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.CardSeedPayload
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.data.model.MethodCount
import com.kaoyan.wordhelper.data.model.PresetBookCatalog
import com.kaoyan.wordhelper.data.model.PresetBookSeed
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.data.model.WordDraft
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

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
    private val stats = WordStudyStats(database)
    private val wordContent = WordContentStore(database.bookDao(), database.wordDao(), database.wordMemoryDao())
    private val progressStore = WordProgressStore(database)
    private val notebook = WordNotebookStore(database, stats)
    private val books = WordBookStore(database, wordContent, notebook)
    private val studyCommit = WordStudyCommit(
        transaction = { write -> database.withTransaction { write() } },
        enqueue = progressStore::enqueueProgressSync
    )
    private val study = WordStudyWriter(database, stats, progressStore, studyCommit)
    private val studyQueue = WordStudyQueue(database, stats, progressStore)

    fun getAllBooks(): Flow<List<Book>> =
        books.getAllBooks()

    suspend fun getBookById(bookId: Long): Book? =
        books.getBookById(bookId)

    suspend fun getActiveBook(): Book? =
        books.getActiveBook()

    fun getActiveBookFlow(): Flow<Book?> =
        books.getActiveBookFlow()

    suspend fun switchBook(newBookId: Long) =
        books.switchBook(newBookId)

    suspend fun insertBook(book: Book): Long =
        books.insertBook(book)

    suspend fun deleteBook(book: Book) =
        books.deleteBook(book)

    suspend fun deleteBookWithData(book: Book): Boolean =
        books.deleteBookWithData(book)

    suspend fun importBook(name: String, drafts: List<WordDraft>): Long =
        books.importBook(name, drafts)

    suspend fun ensureCardSeedBook(seed: CardSeedPayload): Long =
        books.ensureCardSeedBook(seed)

    suspend fun resetStudyRecords() =
        progressStore.resetStudyRecords()

    suspend fun ensureTestDeck(seed: CardSeedPayload): Long =
        books.ensureTestDeck(seed)

    suspend fun backfillWordMethods(map: Map<String, String>): Int =
        books.backfillWordMethods(map)

    suspend fun refreshRootMethods(
        map: Map<String, String>,
        fromMethod: String = DEFAULT_ROOT_METHOD
    ): Int =
        books.refreshRootMethods(map, fromMethod)

    suspend fun ensurePresetBooks(presetSeeds: List<PresetBookSeed> = PresetBookCatalog.presets) =
        books.ensurePresetBooks(presetSeeds)

    fun getWordsByBook(bookId: Long): Flow<List<Word>> =
        wordContent.getWordsByBook(bookId)

    suspend fun getWordsByBookList(bookId: Long): List<Word> =
        wordContent.getWordsByBookList(bookId)

    suspend fun getWordsForExport(book: Book): List<Word> =
        wordContent.getWordsForExport(book)

    suspend fun getNewWordsList(): List<Word> =
        wordContent.getNewWordsList()

    suspend fun getWordForBook(wordId: Long, bookId: Long): Word? = database.wordDao().getWordForBook(wordId, bookId)

    suspend fun getWordById(wordId: Long): Word? =
        wordContent.getWordById(wordId)

    suspend fun searchWords(query: String, currentBookId: Long? = null): List<Word> =
        wordContent.searchWords(query, currentBookId)

    suspend fun insertWords(words: List<Word>) =
        wordContent.insertWords(words)

    suspend fun getWordCount(bookId: Long): Int =
        wordContent.getWordCount(bookId)

    suspend fun getProgress(wordId: Long, bookId: Long): Progress? =
        progressStore.getProgress(wordId, bookId)

    suspend fun getProgressMapForBook(
        bookId: Long,
        wordIds: Collection<Long>
    ): Map<Long, Progress> =
        progressStore.getProgressMapForBook(bookId, wordIds)

    fun getLearnedCount(bookId: Long): Flow<Int> =
        progressStore.getLearnedCount(bookId)

    fun getMasteredCount(bookId: Long): Flow<Int> =
        progressStore.getMasteredCount(bookId)

    fun getBookDueCount(bookId: Long, now: Long = System.currentTimeMillis()): Flow<Int> =
        progressStore.getBookDueCount(bookId, now)

    fun getBookAccuracy(bookId: Long): Flow<Float?> =
        progressStore.getBookAccuracy(bookId)

    fun getBookTodayTouchedCount(bookId: Long, startTime: Long, endTime: Long): Flow<Int> =
        progressStore.getBookTodayTouchedCount(bookId, startTime, endTime)

    fun getGlobalMethodCounts(): Flow<List<MethodCount>> =
        progressStore.getGlobalMethodCounts()

    fun getBookMethodCounts(bookId: Long): Flow<List<MethodCount>> =
        progressStore.getBookMethodCounts(bookId)

    suspend fun upsertProgress(progress: Progress) =
        progressStore.upsertProgress(progress)

    suspend fun getDueWords(bookId: Long): List<Progress> =
        progressStore.getDueWords(bookId)

    suspend fun disperseReviewPressure(bookId: Long, dailyCap: Int): Int =
        progressStore.disperseReviewPressure(bookId, dailyCap)

    fun getProgressByBookFlow(bookId: Long): Flow<List<Progress>> =
        progressStore.getProgressByBookFlow(bookId)

    suspend fun getProgressByBook(bookId: Long): List<Progress> =
        progressStore.getProgressByBook(bookId)

    suspend fun repairMasteredStatusForV4(): Int =
        progressStore.repairMasteredStatusForV4()

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
    ) =
        study.applyStudyResult(
            wordId,
            bookId,
            rating,
            fsrsEnabled,
            desiredRetention,
            algorithmV4Enabled,
            mlScheduler,
            mlEnabled,
            sessionPosition,
            sessionTotal,
            responseTimeMs
        )

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
    ) =
        study.applySpellingOutcome(
            wordId,
            bookId,
            outcome,
            attemptCount,
            durationMillis,
            fsrsEnabled,
            desiredRetention,
            algorithmV4Enabled,
            mlScheduler,
            mlEnabled,
            sessionPosition,
            sessionTotal
        )

    suspend fun getStudyQueue(
        book: Book,
        newWordLimit: Int = DEFAULT_NEW_WORDS_LIMIT,
        shuffleNewWords: Boolean = false,
        plannedNewWordIds: List<Long> = emptyList(),
        plannedModeEnabled: Boolean = false
    ): List<Word> =
        studyQueue.getStudyQueue(book, newWordLimit, shuffleNewWords, plannedNewWordIds, plannedModeEnabled)

    suspend fun getStudyQueueSnapshot(
        book: Book,
        newWordLimit: Int = DEFAULT_NEW_WORDS_LIMIT,
        shuffleNewWords: Boolean = false,
        plannedNewWordIds: List<Long> = emptyList(),
        plannedModeEnabled: Boolean = false
    ): StudyQueueSnapshot =
        studyQueue.getStudyQueueSnapshot(
            book,
            newWordLimit,
            shuffleNewWords,
            plannedNewWordIds,
            plannedModeEnabled
        )

    fun getEarlyReviewCountFlow(bookId: Long): Flow<Int> =
        studyQueue.getEarlyReviewCountFlow(bookId)

    suspend fun getEarlyReviewWordIds(bookId: Long): Set<Long> =
        studyQueue.getEarlyReviewWordIds(bookId)

    suspend fun replaceEarlyReviewWords(bookId: Long, wordIds: Collection<Long>) =
        studyQueue.replaceEarlyReviewWords(bookId, wordIds)

    fun getAllNewWordRefs(): Flow<List<NewWordRef>> =
        notebook.getAllNewWordRefs()

    fun getNewWordCount(): Flow<Int> =
        notebook.getNewWordCount()

    fun getNewWordIdsFlow(): Flow<Set<Long>> =
        notebook.getNewWordIdsFlow()

    suspend fun addToNewWords(wordId: Long, source: AddToNewWordsSource = AddToNewWordsSource.BUTTON): Boolean =
        notebook.addToNewWords(wordId, source)

    suspend fun removeFromNewWords(wordId: Long) =
        notebook.removeFromNewWords(wordId)

    suspend fun isInNewWords(wordId: Long): Boolean =
        notebook.isInNewWords(wordId)

    suspend fun clearNewWords() =
        notebook.clearNewWords()

    fun getStudyLogs(): Flow<List<StudyLog>> =
        stats.getStudyLogs()

    fun getDailyStats(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyStats>> =
        stats.getDailyStats(startDate, endDate)

    fun getDailyStatsAggregated(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyStatsAggregate>> =
        stats.getDailyStatsAggregated(startDate, endDate)

    suspend fun checkInToday(): CheckInResult =
        stats.checkInToday()

    suspend fun recordSpellingResult(wordId: Long, bookId: Long, isCorrect: Boolean) =
        stats.recordSpellingResult(wordId, bookId, isCorrect)

    suspend fun getBookDailyStats(bookId: Long, fromDate: String): List<BookDailyStats> =
        stats.getBookDailyStats(bookId, fromDate)

    fun getBookDailyStatsFlow(bookId: Long, fromDate: String): Flow<List<BookDailyStats>> =
        stats.getBookDailyStatsFlow(bookId, fromDate)

    suspend fun getNextNewWord(bookId: Long): Word? =
        wordContent.getNextNewWord(bookId)

    suspend fun getOrCreateNewWordsBook(): Book =
        notebook.getOrCreateNewWordsBook()

    suspend fun updateBookTotalCount(bookId: Long) =
        books.updateBookTotalCount(bookId)

    companion object {
        private const val DEFAULT_NEW_WORDS_LIMIT = 20
        private const val DEFAULT_ROOT_METHOD = "root"
    }
}

enum class AddToNewWordsSource {
    BUTTON,
    GESTURE
}

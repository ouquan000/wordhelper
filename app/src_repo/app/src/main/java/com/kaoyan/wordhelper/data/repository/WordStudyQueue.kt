package com.kaoyan.wordhelper.data.repository

import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.EarlyReviewRef
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.util.DateUtils
import kotlinx.coroutines.flow.Flow

internal class WordStudyQueue(
    private val database: AppDatabase,
    private val stats: WordStudyStats,
    private val progressStore: WordProgressStore
) {
    private val wordDao = database.wordDao()
    private val earlyReviewDao = database.earlyReviewDao()

    suspend fun getStudyQueue(
        book: Book,
        newWordLimit: Int,
        shuffleNewWords: Boolean,
        plannedNewWordIds: List<Long>,
        plannedModeEnabled: Boolean
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
        newWordLimit: Int,
        shuffleNewWords: Boolean,
        plannedNewWordIds: List<Long>,
        plannedModeEnabled: Boolean
    ): StudyQueueSnapshot {
        val sourceWords = if (book.type == Book.TYPE_NEW_WORDS) {
            wordDao.getNewWordsList()
        } else {
            wordDao.getWordsByBookList(book.id)
        }
        if (sourceWords.isEmpty()) {
            return StudyQueueSnapshot(queue = emptyList(), dueCount = 0)
        }

        val progressByWordId = progressStore.getProgressMapForBook(book.id, sourceWords.map { it.id })
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
            val todayLearned = stats.getTodayNewWordsCount()
            val remainingByDailyLimit = (newWordLimit - todayLearned).coerceAtLeast(0)
            val remainingInBook = sourceWords.count { it.id !in progressByWordId.keys }
            minOf(remainingByDailyLimit, remainingInBook)
        }
        val adjustedNewWordLimit = (effectiveNewWordLimit - inLearningButUncompletedCount).coerceAtLeast(0)
        val orderedNewWords = orderedUnlearnedWords(
            sourceWords, progressByWordId.keys + earlyOrder.keys, book, plannedNewWordIds,
            plannedModeEnabled, shuffleNewWords, DateUtils.currentLearningDate()
        ).take(adjustedNewWordLimit)

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
}

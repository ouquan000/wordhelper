package com.kaoyan.wordhelper.data.repository

import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.NewWordRef
import com.kaoyan.wordhelper.data.model.PresetBookCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class WordNotebookStore(
    private val database: AppDatabase,
    private val stats: WordStudyStats
) {
    private val bookDao = database.bookDao()
    private val progressDao = database.progressDao()
    private val newWordRefDao = database.newWordRefDao()

    fun getAllNewWordRefs(): Flow<List<NewWordRef>> = newWordRefDao.getAll()

    fun getNewWordCount(): Flow<Int> = newWordRefDao.getCount()

    fun getNewWordIdsFlow(): Flow<Set<Long>> {
        return newWordRefDao.getAll().map { refs -> refs.map { it.wordId }.toSet() }
    }

    suspend fun addToNewWords(wordId: Long, source: AddToNewWordsSource): Boolean {
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
                stats.updateDailyStatsInternal(gestureNotebookDelta = 1)
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

    suspend fun updateNewWordsBookCount() {
        val book = getOrCreateNewWordsBook()
        val count = newWordRefDao.getCountOnce()
        bookDao.updateTotalCount(book.id, count)
    }
}

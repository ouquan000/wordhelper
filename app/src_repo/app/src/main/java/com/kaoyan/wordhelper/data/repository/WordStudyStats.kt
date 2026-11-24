package com.kaoyan.wordhelper.data.repository

import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.BookDailyStats
import com.kaoyan.wordhelper.data.entity.DailyStats
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.StudyLog
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow

private const val DAY_REFRESH_HOUR = 4
private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

internal class WordStudyStats(
    private val database: AppDatabase
) {
    private val progressDao = database.progressDao()
    private val studyLogDao = database.studyLogDao()
    private val dailyStatsDao = database.dailyStatsDao()
    private val bookDailyStatsDao = database.bookDailyStatsDao()

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

    suspend fun getTodayNewWordsCount(): Int {
        val today = currentLearningDate().format(DATE_FORMATTER)
        return dailyStatsDao.getByDate(today)?.newWordsCount ?: 0
    }

    suspend fun recordStudyInternal() {
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

    suspend fun updateDailyStatsInternal(
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
    suspend fun updateBookDailyStatsInternal(
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

    private fun currentLearningDate(now: Long = System.currentTimeMillis()): LocalDate {
        val zonedNow = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        val date = zonedNow.toLocalDate()
        return if (zonedNow.hour < DAY_REFRESH_HOUR) date.minusDays(1) else date
    }
}

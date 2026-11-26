package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.util.DateUtils
import java.time.LocalDate
import kotlin.random.Random
import kotlinx.coroutines.flow.first

internal fun orderedUnlearnedWords(
    source: List<Word>, excluded: Set<Long>, book: Book, planned: List<Long>,
    plannedMode: Boolean, shuffle: Boolean, day: LocalDate
): List<Word> {
    val ordered = if (book.type != Book.TYPE_NEW_WORDS && (plannedMode || planned.isNotEmpty())) {
        val byId = source.associateBy { it.id }
        val selected = planned.distinct().mapNotNull(byId::get)
        if (!plannedMode && selected.none { it.id !in excluded }) source else selected
    } else source
    // Shuffle before removing progress so studying one word preserves the remaining order.
    val daily = if (shuffle) ordered.shuffled(Random(day.toEpochDay() xor book.id)) else ordered
    return daily.filterNot { it.id in excluded }
}

internal class UpcomingNewWords(val book: Book?, ordered: List<Word>) {
    val words = ordered.take(50)
}

internal suspend fun readUpcomingNewWords(db: AppDatabase, settings: SettingsRepository): UpcomingNewWords {
    val book = db.bookDao().getActiveBook() ?: return UpcomingNewWords(null, emptyList())
    val source = if (book.type == Book.TYPE_NEW_WORDS) db.wordDao().getNewWordsList()
        else db.wordDao().getWordsByBookList(book.id)
    val excluded = db.progressDao().getProgressByBook(book.id).mapTo(HashSet()) { it.wordId }
    db.earlyReviewDao().getByBook(book.id).mapTo(excluded) { it.wordId }
    val preferences = settings.settingsFlow.first()
    val day = DateUtils.currentLearningDate()
    val plan = settings.todayNewWordsPlanFlow.first().takeIf {
        preferences.plannedNewWordsEnabled && it.bookId == book.id && it.learningDate == day.toString()
    }
    return UpcomingNewWords(book, orderedUnlearnedWords(source, excluded, book, plan?.wordIds.orEmpty(),
        preferences.plannedNewWordsEnabled, preferences.newWordsShuffleEnabled, day))
}

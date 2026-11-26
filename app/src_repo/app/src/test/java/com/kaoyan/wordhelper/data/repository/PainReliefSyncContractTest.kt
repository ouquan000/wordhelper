package com.kaoyan.wordhelper.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.util.DateUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PainReliefSyncContractTest {
    private val day = LocalDate.of(2026, 11, 27)
    private val book = Book(7, "sample", Book.TYPE_PRESET)
    private val source = (1L..70L).map { Word(it, "word$it", bookId = book.id) }

    @Test fun pressureDismissalSurvivesDataStoreRecreation() = runBlocking {
        val dir = Files.createTempDirectory("pressure-preferences-").toFile()
        val prefs = dir.resolve("settings.preferences_pb")
        suspend fun open(update: Boolean): String? {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { prefs }
            return try {
                if (update) store.dismissPressure(day)
                store.pressureDismissedDay.first()
            } finally { job.cancelAndJoin() }
        }
        try {
            assertEquals(day.toString(), open(true))
            assertEquals(day.toString(), open(false))
        } finally { dir.deleteRecursively() }
        Unit
    }

    @Test fun pressureReturnsAtFourAfterMidnightAndColdRead() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun visible(at: String): Boolean {
            val ms = LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli()
            return pressureVisible(DateUtils.currentLearningDate(ms, zone), day.toString())
        }
        assertFalse(visible("2026-11-27T23:59"))
        assertFalse(visible("2026-11-28T03:59"))
        assertTrue(visible("2026-11-28T04:00"))
        assertTrue(visible("2026-11-28T14:00"))
    }

    @Test fun payloadMatchesQueuePrefixExcludesLearnedAndCapsAtFifty() {
        for (shuffle in listOf(false, true)) {
            val order = orderedUnlearnedWords(source, setOf(2, 8), book, emptyList(), false, shuffle, day)
            val preview = UpcomingNewWords(book, order)
            val json = JsonParser.parseString(Gson().toJson(pushRequest("device", 0, emptyList<String>(), preview))).asJsonObject
            assertEquals(order.take(50).map { it.id }, json["upcoming_new_word_ids"].asJsonArray.map { it.asLong })
            assertEquals(order.take(50).map { it.word }, json["upcoming_new_word_keys"].asJsonArray.map { it.asString })
            assertEquals(50, preview.words.size)
            assertEquals(order.drop(1), orderedUnlearnedWords(source, setOf(2, 8, order.first().id), book,
                emptyList(), false, shuffle, day))
        }
    }

    @Test fun plannedOrderAndEmptyQueueSerializeExactly() {
        val planned = orderedUnlearnedWords(source, setOf(2), book, listOf(5, 2, 1), true, false, day)
        assertEquals(listOf(5L, 1L), planned.map { it.id })
        val empty = orderedUnlearnedWords(source, emptySet(), book, emptyList(), true, false, day)
        assertEquals(emptyList<Long>(), pushRequest("device", 0, emptyList<String>(), UpcomingNewWords(book, empty)).ids)
    }

    @Test fun wordbookTimeoutsFollowOriginRegardlessOfAddressSlot() {
        fun times(origin: String) = WordbookEndpoint(origin, "", "").let {
            listOf(it.connectSeconds, it.readSeconds, it.callSeconds)
        }
        assertEquals(listOf(2L, 5L, 7L), times("http://192.168.1.2"))
        assertEquals(listOf(8L, 30L, 45L), times("https://sync.example.org"))
    }
}

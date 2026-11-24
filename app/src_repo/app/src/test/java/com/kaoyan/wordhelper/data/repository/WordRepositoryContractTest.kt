package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookWordContent
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.entity.WordEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class WordRepositoryContractTest {
    private inline fun <reified T> dao(
        crossinline handle: (String, List<Any?>) -> Any? = { name, _ -> error(name) }
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        handle(method.name, args.orEmpty().toList())
    } as T

    @Test fun searchAndExportPreserveBookSelection() = runBlocking {
        val requests = mutableListOf<Pair<String, List<Any?>>>()
        val words = listOf(Word(word = "card", bookId = 7))
        val store = WordContentStore(dao<BookDao>(), dao<WordDao> { name, args ->
            requests += name to args.dropLast(1) // suspend continuation
            words
        }, dao<WordMemoryDao>())
        assertSame(words, store.searchWords("a", null))
        assertSame(words, store.searchWords("b", 7))
        assertSame(words, store.getWordsForExport(Book(7, "A", Book.TYPE_IMPORTED)))
        assertSame(words, store.getWordsForExport(Book(8, "B", Book.TYPE_NEW_WORDS)))
        assertEquals(listOf(
            "searchWords" to listOf("a", 0L),
            "searchWords" to listOf("b", 7L),
            "getWordsByBookList" to listOf(7L),
            "getNewWordsList" to emptyList()
        ), requests)
    }

    @Test fun insertWordsNormalizesKeysKeepsFirstDraftAndSkipsInvalidBooks() = runBlocking {
        val entities = mutableListOf<WordEntity>()
        val content = mutableListOf<BookWordContent>()
        val totals = mutableListOf<Pair<Long, Int>>()
        val store = WordContentStore(
            dao<BookDao> { name, args ->
                check(name == "updateTotalCount")
                totals += args[0] as Long to args[1] as Int
                Unit
            },
            dao<WordDao> { name, args ->
                when (name) {
                    "getWordEntitiesByKeys" -> emptyList<WordEntity>()
                    "insertWordEntity" -> { entities += args.first() as WordEntity; 42L }
                    "upsertBookWordContents" -> {
                        content += (args.first() as List<*>).filterIsInstance<BookWordContent>()
                        Unit
                    }
                    "getWordCount" -> content.size
                    else -> error(name)
                }
            },
            dao<WordMemoryDao> { name, _ -> check(name == "getByWordIds"); emptyList<Any>() }
        )
        store.insertWords(listOf(
            Word(word = "  Mixed ", meaning = "first", bookId = 5),
            Word(word = "mixed", meaning = "duplicate", bookId = 5),
            Word(word = "  ", bookId = 5),
            Word(word = "ignore", bookId = 0)
        ))
        assertEquals("mixed", entities.single().wordKey)
        assertEquals("Mixed", entities.single().word)
        assertEquals(Triple(5L, 42L, "first"), content.single().let { Triple(it.bookId, it.wordId, it.meaning) })
        assertEquals(listOf(5L to 1), totals)
    }

    @Test fun syncSeesTheCommittedResultOnceForTheSubmittedBook() = runBlocking {
        val events = mutableListOf<String>()
        val commits = WordStudyCommit(
            transaction = { write -> events += "begin"; write(); events += "commit" },
            enqueue = { word, book -> events += "enqueue $word $book" }
        )
        commits.write(7, 3) { events += "progress and stats" }
        assertEquals(listOf("begin", "progress and stats", "commit", "enqueue 7 3"), events)
    }

    @Test fun failedCommitNeverEnqueues() {
        val events = mutableListOf<String>()
        val commits = WordStudyCommit(
            transaction = { write -> write(); error("commit failed") },
            enqueue = { _, _ -> events += "enqueue" }
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { commits.write(7, 3) { events += "write" } }
        }
        assertEquals(listOf("write"), events)
    }
}

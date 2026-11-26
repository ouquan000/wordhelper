package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.*
import com.kaoyan.wordhelper.data.entity.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise production result application, scoped Room SELECT and pull merge across SQLite connections. */
class RewriteColdStartTest {
    private inline fun <reified T> dao(crossinline result: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            result(method.name, args.orEmpty())
        } as T

    private fun Connection.value(sql: String) = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) }
    }

    private class Fixture(val db: Connection) {
        fun initialize() {
            db.createStatement().use { statement ->
                listOf(
                    "CREATE TABLE tb_word(id INTEGER PRIMARY KEY, word TEXT, phonetic TEXT)",
                    "CREATE TABLE tb_book_word_content(id INTEGER PRIMARY KEY, word_id, book_id, meaning, example, phrases, synonyms, rel_words)",
                    "CREATE TABLE tb_word_memory(word_id INTEGER PRIMARY KEY, approach, collocations, method)",
                    "CREATE TABLE tb_book_memory(word_id,book_id,approach,collocations,method,rev,PRIMARY KEY(word_id,book_id))",
                    "CREATE TABLE mark(status TEXT)",
                    "CREATE TABLE progress(lrt INTEGER)",
                    "INSERT INTO tb_word VALUES(1,'recall','')",
                    "INSERT INTO tb_book_word_content(id,word_id,book_id) VALUES(1,1,3),(2,1,9)",
                    "INSERT INTO tb_word_memory VALUES(1,'original','','')",
                    "INSERT INTO tb_book_memory VALUES(1,3,'other book','','',1)",
                    "INSERT INTO mark VALUES('queued')",
                    "INSERT INTO progress VALUES(100)"
                ).forEach(statement::executeUpdate)
            }
        }
    }

    private fun merger(db: Connection, failDone: Boolean = false): RewriteResultMerger {
        val mark = RewriteQueueItem(wordId = 1, bookId = 9, word = "recall", requestId = "round",
            createdAt = 1, updatedAt = 1, status = db.value("SELECT status FROM mark"))
        return RewriteResultMerger(
            dao { name, _ -> check(name == "getWordIdByKey"); 1L },
            dao { name, _ -> check(name == "getByWordId"); null },
            dao { name, args ->
                when (name) {
                    "getForBook" -> null
                    "upsert" -> {
                        val row = args[0] as BookMemory
                        db.prepareStatement("INSERT INTO tb_book_memory VALUES(?,?,?,?,?,?)").use {
                            it.setLong(1, row.wordId); it.setLong(2, row.bookId); it.setString(3, row.approach)
                            it.setString(4, row.collocations); it.setString(5, row.method); it.setInt(6, row.rev)
                            it.executeUpdate()
                        }
                        Unit
                    }
                    else -> error(name)
                }
            },
            dao { name, args ->
                when (name) {
                    "getAllForWord" -> listOf(mark)
                    "updateStatus" -> {
                        check(!failDone) { "simulated interrupted commit" }
                        db.prepareStatement("UPDATE mark SET status=?").use {
                            it.setString(1, args[2] as String); it.executeUpdate()
                        }
                        Unit
                    }
                    else -> error(name)
                }
            },
            dao { name, _ -> check(name == "isLocked"); false },
            dao { name, _ -> error(name) }, Gson()
        ) { write ->
            db.autoCommit = false
            try { write().also { db.commit() } } catch (e: Throwable) { db.rollback(); throw e }
            finally { db.autoCommit = true }
        }
    }

    @Test fun identicalGlobalSeedTextAcceptedByUserBecomesProtected() = runBlocking {
        val mark = RewriteQueueItem(wordId = 1, word = "recall", createdAt = 1, updatedAt = 1, requestId = "r")
        var memory = WordMemory(wordId = 1, approach = "same", updatedAt = 1)
        var done = false
        val repository = RewriteResultMerger(dao { _, _ -> 1L },
            dao { name, args ->
                when (name) {
                    "getByWordId" -> memory
                    "upsert" -> { memory = args[0] as WordMemory; Unit }
                    else -> error(name)
                }
            }, dao { name, _ -> error(name) },
            dao { name, _ -> if (name == "getAllForWord") listOf(mark) else { done = true; Unit } },
            dao { _, _ -> false }, dao { name, _ -> error(name) }, Gson(), { it() })
        repository.applyResult(RewriteResultDto(1L, "recall", "r", 4, listOf("same"), null, null, "ok", null))
        assertTrue(done)
        assertEquals(WordMemory.SOURCE_USER, memory.source)
        assertEquals(4, memory.rev)
        assertFalse(WordbookSyncPolicy.canReplaceFromOta(memory))
        assertFalse(SeedMemoryUpgrade.canReplace(memory, WordMemory.SOURCE_SEED, null, true))
    }

    @Test fun committedMemorySurvivesReopenAndStaleCloudSnapshot() = runBlocking {
        val path = Files.createTempFile("rewrite-reopen-", ".sqlite")
        val url = "jdbc:sqlite:$path"
        val dto = RewriteResultDto(1L, "recall", "round", 7, listOf("fresh"), null, null, "ok", null)
        try {
            DriverManager.getConnection(url).use { db ->
                Fixture(db).initialize()
                assertTrue(runCatching { merger(db, true).applyResult(dto) }.isFailure)
                assertEquals("0", db.value("SELECT count(*) FROM tb_book_memory WHERE book_id=9"))
                assertEquals("queued", db.value("SELECT status FROM mark"))
                assertEquals(1L, merger(db).applyResult(dto))
            }
            DriverManager.getConnection(url).use { db ->
                val scoped = SCOPED_WORD_BY_ID_QUERY.replace(":wordId", "1").replace(":bookId", "9")
                fun current() = db.value("SELECT approach FROM ($scoped)")
                assertEquals("fresh", current())
                val old = SyncProgressRow("progress", "recall", "book", 1, null, null, null,
                    null, null, 50L, null, null, null, null)
                val merged = applyProgressRows(listOf(old),
                    dao { _, _ -> listOf(Book(9, "book", Book.TYPE_IMPORTED)) },
                    dao { _, _ -> 1L },
                    dao { name, _ -> check(name == "getProgress")
                        Progress(wordId = 1, bookId = 9, lastReviewTime = db.value("SELECT lrt FROM progress").toLong())
                    })
                assertEquals(1, merged.skipped)
                assertEquals(1L, merger(db).applyResult(dto.copy(approach = listOf("older"), rev = 2)))
                assertEquals("fresh", current())
                assertEquals("7", db.value("SELECT rev FROM tb_book_memory WHERE book_id=9"))
                assertEquals("done", db.value("SELECT status FROM mark"))
            }
        } finally { Files.deleteIfExists(path) }
        Unit
    }
}

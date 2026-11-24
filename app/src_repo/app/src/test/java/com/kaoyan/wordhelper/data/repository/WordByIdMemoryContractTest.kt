package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.dao.WORD_BY_ID_QUERY
import org.junit.Assert.assertEquals
import org.junit.Test

/** Execute the Room query against a fresh host SQLite database for each case. */
class WordByIdMemoryContractTest {
    private fun readMemory(rows: String, wordId: Long = 1): List<String> {
        val sql = """
            CREATE TABLE tb_word(id INTEGER PRIMARY KEY, word TEXT, phonetic TEXT);
            CREATE TABLE tb_book_word_content(id INTEGER PRIMARY KEY, word_id INTEGER, book_id INTEGER,
                meaning TEXT, example TEXT, phrases TEXT, synonyms TEXT, rel_words TEXT);
            CREATE TABLE tb_word_memory(word_id INTEGER PRIMARY KEY, approach TEXT, collocations TEXT, method TEXT);
            CREATE TABLE tb_book_memory(word_id INTEGER, book_id INTEGER, approach TEXT, collocations TEXT,
                method TEXT, PRIMARY KEY(word_id, book_id));
            INSERT INTO tb_word VALUES(1, 'recall', '');
            INSERT INTO tb_book_word_content(id, word_id, book_id, meaning)
                VALUES(1, 1, 9, 'nine'), (2, 1, 3, 'three');
            $rows
            SELECT bookId, meaning, approach, collocations, method FROM ($WORD_BY_ID_QUERY);
        """.trimIndent()
        val process = ProcessBuilder("sqlite3", "-batch", "-bail", "-cmd", ".parameter set :wordId $wordId", ":memory:")
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.appendLine(sql) }
        val output = process.inputStream.bufferedReader().use { it.readLines() }
        assertEquals(output.joinToString("\n"), 0, process.waitFor())
        return output
    }

    private val global = "INSERT INTO tb_word_memory VALUES(1, 'global', 'gc', 'gm');"
    private val otherBook = "INSERT INTO tb_book_memory VALUES(1, 9, 'other', 'oc', 'om');"

    @Test fun selectedContentAndMemoryComeFromTheSameBook() {
        val rows = "$global\n$otherBook\nINSERT INTO tb_book_memory VALUES(1, 3, 'book', 'bc', 'bm');"
        assertEquals(listOf("3|three|book|bc|bm"), readMemory(rows))
    }

    @Test fun absentBookMemoryFallsBackToGlobalDespiteOtherBookOverride() {
        assertEquals(listOf("3|three|global|gc|gm"), readMemory("$global\n$otherBook"))
    }

    @Test fun emptyBookFieldsFallBackIndependently() {
        val rows = "$global\nINSERT INTO tb_book_memory VALUES(1, 3, '', 'bc', NULL);"
        assertEquals(listOf("3|three|global|bc|gm"), readMemory(rows))
    }

    @Test fun orphanWordAndUnknownWordKeepTheirLookupSemantics() {
        assertEquals(listOf("0||||"), readMemory("DELETE FROM tb_book_word_content;"))
        assertEquals(emptyList<String>(), readMemory("", wordId = 2))
    }
}

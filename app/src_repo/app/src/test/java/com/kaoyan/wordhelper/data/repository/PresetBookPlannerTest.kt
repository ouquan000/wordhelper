package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetBookPlannerTest {

    private fun preset(
        id: Long,
        name: String,
        totalCount: Int = 30,
        active: Boolean = false
    ) = Book(id = id, name = name, type = Book.TYPE_PRESET, totalCount = totalCount, isActive = active)

    @Test
    fun `stale preset is marked hidden instead of deleted`() {
        val books = listOf(preset(1, "旧词书"), preset(2, "考研核心词"))

        val toHide = PresetBookPlanner.booksToHide(books, setOf("考研核心词"))

        assertEquals(listOf(1L), toHide.map { it.id })
    }

    @Test
    fun `duplicate preset keeps the smallest id and hides the rest`() {
        val books = listOf(
            preset(7, "考研核心词"),
            preset(3, "考研核心词"),
            preset(9, "考研核心词")
        )

        val toHide = PresetBookPlanner.booksToHide(books, setOf("考研核心词"))

        assertEquals(listOf(7L, 9L), toHide.map { it.id }.sorted())
        assertTrue(toHide.none { it.id == 3L })
    }

    @Test
    fun `imported and new-word books are never hidden`() {
        val books = listOf(
            Book(id = 1, name = "我导入的词书", type = Book.TYPE_IMPORTED, totalCount = 20),
            Book(id = 2, name = "生词本", type = Book.TYPE_NEW_WORDS, totalCount = 5),
            preset(3, "考研核心词")
        )

        val toHide = PresetBookPlanner.booksToHide(books, setOf("考研核心词"))

        assertTrue(toHide.isEmpty())
    }

    @Test
    fun `already hidden preset is not processed again`() {
        val books = listOf(
            preset(1, "旧词书", totalCount = Book.HIDDEN_TOTAL_COUNT),
            preset(2, "考研核心词")
        )

        val toHide = PresetBookPlanner.booksToHide(books, setOf("考研核心词"))

        assertTrue(toHide.isEmpty())
    }

    @Test
    fun `hidden sentinel is recognized as hidden`() {
        assertTrue(preset(1, "旧词书", totalCount = Book.HIDDEN_TOTAL_COUNT).isHidden)
        assertTrue(!preset(1, "考研核心词", totalCount = 0).isHidden)
    }
}

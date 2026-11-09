package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.BookDailyStats
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.6.1：当前词书热力图的纯计算。
 *
 * 重点不是画格子，而是口径：只算这一本书、一天的量 = 新词 + 复习 + 拼写、
 * 超出窗口的日子不算进 maxCount（否则整张图会被历史峰值压暗）。
 */
class ProfileBookHeatmapTest {

    private val start = LocalDate.of(2026, 8, 17) // 周一
    private val end = LocalDate.of(2026, 8, 30) // 周日（两周窗口，方便断言）

    @Test
    fun `counts new review and spell of the same day`() {
        val cells = buildBookHeatmap(
            start,
            end,
            listOf(BookDailyStats(date = "2026-08-17", bookId = 7, newCount = 3, reviewCount = 4, spellCount = 5))
        )
        val first = cells.first { it.date == LocalDate.of(2026, 8, 17) }
        assertEquals(12, first.count)
        assertEquals(4, first.level)
    }

    @Test
    fun `only the given book rows are used`() {
        // 调用方保证 rows 已经是这本书的；这里验证不同日期的行各归各的日子，不会串。
        val cells = buildBookHeatmap(
            start,
            end,
            listOf(
                BookDailyStats(date = "2026-08-17", bookId = 7, newCount = 1),
                BookDailyStats(date = "2026-08-18", bookId = 7, newCount = 0, reviewCount = 2)
            )
        )
        assertEquals(1, cells.first { it.date == LocalDate.of(2026, 8, 17) }.count)
        assertEquals(2, cells.first { it.date == LocalDate.of(2026, 8, 18) }.count)
        assertEquals(0, cells.first { it.date == LocalDate.of(2026, 8, 19) }.count)
    }

    @Test
    fun `rows before the range are ignored`() {
        // 1.4.5 之前没有按书数据；就算表里混进窗口外的行也不能画进来。
        val cells = buildBookHeatmap(
            start,
            end,
            listOf(
                BookDailyStats(date = "2026-08-01", bookId = 7, newCount = 99),
                BookDailyStats(date = "2026-08-17", bookId = 7, newCount = 2)
            )
        )
        assertEquals(2, cells.first { it.date == LocalDate.of(2026, 8, 17) }.count)
        // 分档用的是窗口内的最大值，100 不该把 8-17 压成第 1 档。
        assertEquals(4, cells.first { it.date == LocalDate.of(2026, 8, 17) }.level)
        assertTrue(cells.none { it.date == LocalDate.of(2026, 8, 1) })
    }

    @Test
    fun `unparsable date is skipped instead of crashing`() {
        val cells = buildBookHeatmap(
            start,
            end,
            listOf(BookDailyStats(date = "not-a-date", bookId = 7, newCount = 5))
        )
        assertTrue(cells.all { it.count == 0 })
    }

    @Test
    fun `cells cover whole weeks and mark padding out of range`() {
        // end 是周日，不该有 padding；换成周三再看尾部补到周日。
        val wednesday = LocalDate.of(2026, 8, 26)
        val cells = buildBookHeatmap(start, wednesday, emptyList())
        assertEquals(0, cells.first().date.dayOfWeek.value - 1) // start 是周一
        assertTrue(cells.last().date.dayOfWeek.value == 7) // 画到周日
        assertTrue(cells.last().inRange.not())
        assertTrue(cells.first { it.date == wednesday }.inRange)
        assertEquals(0, cells.last().level)
    }

    @Test
    fun `empty rows still produce a full zero grid`() {
        val cells = buildBookHeatmap(start, end, emptyList())
        assertEquals(14, cells.size)
        assertTrue(cells.all { it.count == 0 && it.level == 0 && it.inRange })
        assertFalse(cells.any { !it.inRange })
    }
}

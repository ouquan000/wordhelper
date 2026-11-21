package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * AutoBackupManager 纯 JVM 策略测试：不依赖 Android framework / Robolectric。
 */
class AutoBackupPolicyTest {

    private val interval = AutoBackupManager.DEFAULT_INTERVAL_MS

    // ------------------------------------------------------------ shouldRun

    @Test
    fun shouldRunReturnsTrueWhenNeverBackedUp() {
        assertTrue(AutoBackupManager.shouldRun(lastSuccessAt = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun shouldRunReturnsTrueExactlyAtTwelveHours() {
        val last = 1_700_000_000_000L
        assertTrue(AutoBackupManager.shouldRun(last, last + interval))
    }

    @Test
    fun shouldRunReturnsFalseOneMillisecondBeforeTwelveHours() {
        val last = 1_700_000_000_000L
        assertFalse(AutoBackupManager.shouldRun(last, last + interval - 1L))
    }

    @Test
    fun shouldRunReturnsTrueWhenClockMovedBackwards() {
        val last = 1_700_000_000_000L
        assertTrue(AutoBackupManager.shouldRun(last, last - 60_000L))
    }

    @Test
    fun shouldRunHonoursCustomInterval() {
        val last = 1_700_000_000_000L
        assertFalse(AutoBackupManager.shouldRun(last, last + 999L, intervalMs = 1_000L))
        assertTrue(AutoBackupManager.shouldRun(last, last + 1_000L, intervalMs = 1_000L))
    }

    // -------------------------------------------------------- backupFileName

    @Test
    fun backupFileNameUsesExpectedPatternAndLocalTime() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 22, 1, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = calendar.timeInMillis
        val name = AutoBackupManager.backupFileName(now)

        assertEquals("kaoyan_words_20261122_0130.db", name)

        // 用同一 pattern 解析回读，确认时间戳没有错位。
        val parsed = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
            .parse(name.removePrefix("kaoyan_words_").removeSuffix(".db"))
        assertEquals(now, parsed!!.time)
    }

    @Test
    fun backupFileNamePadsSingleDigitFields() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 5, 3, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(
            "kaoyan_words_20260105_0304.db",
            AutoBackupManager.backupFileName(calendar.timeInMillis)
        )
    }

    // -------------------------------------------------------- expiredBackups

    @Test
    fun expiredBackupsDeletesOldestOneWhenEightExist() {
        val input = listOf(
            "kaoyan_words_20260108_0100.db",
            "kaoyan_words_20260102_0100.db",
            "kaoyan_words_20260105_0100.db",
            "kaoyan_words_20260101_0100.db",
            "kaoyan_words_20260107_0100.db",
            "kaoyan_words_20260103_0100.db",
            "kaoyan_words_20260106_0100.db",
            "kaoyan_words_20260104_0100.db"
        )
        assertEquals(
            listOf("kaoyan_words_20260101_0100.db"),
            AutoBackupManager.expiredBackups(input)
        )
    }

    @Test
    fun expiredBackupsKeepsEverythingWhenExactlySevenExist() {
        val input = listOf(
            "kaoyan_words_20260101_0100.db",
            "kaoyan_words_20260102_0100.db",
            "kaoyan_words_20260103_0100.db",
            "kaoyan_words_20260104_0100.db",
            "kaoyan_words_20260105_0100.db",
            "kaoyan_words_20260106_0100.db",
            "kaoyan_words_20260107_0100.db"
        )
        assertTrue(AutoBackupManager.expiredBackups(input).isEmpty())
    }

    @Test
    fun expiredBackupsIgnoresFilesNotCreatedByThisApp() {
        val input = listOf(
            "kaoyan_words_20260101_0100.db",
            "kaoyan_words_20260102_0100.db",
            "kaoyan_words_20260103_0100.db",
            "kaoyan_words_20260104_0100.db",
            "kaoyan_words_20260105_0100.db",
            "kaoyan_words_20260106_0100.db",
            "kaoyan_words_20260107_0100.db",
            "kaoyan_words_20260108_0100.db",
            "notes.db",
            "kaoyan_words_20260109_0100.tmp",
            "other_kaoyan_words_20260109_0100.db",
            "kaoyan_words.db"
        )
        assertEquals(
            listOf("kaoyan_words_20260101_0100.db"),
            AutoBackupManager.expiredBackups(input)
        )
    }

    @Test
    fun expiredBackupsSortsUnsortedInputAndReturnsOldestTail() {
        val input = listOf(
            "kaoyan_words_20260105_0100.db",
            "kaoyan_words_20260109_0100.db",
            "kaoyan_words_20260101_0100.db",
            "kaoyan_words_20260107_0100.db",
            "kaoyan_words_20260103_0100.db",
            "kaoyan_words_20260108_0100.db",
            "kaoyan_words_20260102_0100.db",
            "kaoyan_words_20260106_0100.db",
            "kaoyan_words_20260104_0100.db"
        )
        assertEquals(
            listOf("kaoyan_words_20260102_0100.db", "kaoyan_words_20260101_0100.db"),
            AutoBackupManager.expiredBackups(input)
        )
    }
}

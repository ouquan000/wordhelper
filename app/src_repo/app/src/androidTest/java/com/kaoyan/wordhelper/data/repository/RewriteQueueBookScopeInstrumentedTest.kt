package com.kaoyan.wordhelper.data.repository

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RewriteQueueBookScopeInstrumentedTest {
    @Test
    fun twoBooksCanQueueTheSameWordAndClearOnlyTheirOwnDebt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).build()
        try {
            val dao = db.rewriteQueueDao()
            val a = RewriteQueueItem(
                wordId = 7, bookId = 3, word = "anchor", status = RewriteStatus.QUEUED,
                needsReinsert = true, createdAt = 1, updatedAt = 1, requestId = "w7-b3-a"
            )
            val b = a.copy(bookId = 4, status = RewriteStatus.PENDING, requestId = "w7-b4-b")
            dao.upsert(a)
            dao.upsert(b)
            val storedA = requireNotNull(dao.getForBook(7, 3))
            dao.upsert(buildRewriteRequest(storedA, 7, 3, "anchor", "", "new cue", 172800001, "w7-b3-next"))
            assertEquals(2, dao.getAllForWord(7).size)
            assertTrue(requireNotNull(dao.getForBook(7, 3)).needsReinsert)
            assertEquals(storedA.createdAt, requireNotNull(dao.getForBook(7, 3)).createdAt)
            assertEquals("w7-b4-b", dao.getForBook(7, 4)?.requestId)
            assertNull(dao.getVisibleForBook(7, 5))

            dao.clearNeedsReinsert(7, 4)
            assertFalse(requireNotNull(dao.getForBook(7, 4)).needsReinsert)
            assertEquals(listOf(7L), dao.getNeedsReinsertForBook(3).map { it.wordId })
            dao.delete(7, 4)
            assertTrue(requireNotNull(dao.getForBook(7, 3)).needsReinsert)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationKeepsLegacyRowsAndAllowsSameWordInAnotherBook() {
        val callback = object : SupportSQLiteOpenHelper.Callback(22) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE tb_rewrite_queue " +
                        "(id INTEGER PRIMARY KEY, word_id INTEGER NOT NULL, book_id INTEGER NOT NULL, " +
                        "needs_reinsert INTEGER NOT NULL, created_at INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX index_tb_rewrite_queue_word_id ON tb_rewrite_queue (word_id)")
                db.execSQL("INSERT INTO tb_rewrite_queue VALUES (11, 7, 0, 1, 81)")
                db.execSQL("INSERT INTO tb_rewrite_queue VALUES (12, 8, 3, 1, 82)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .callback(callback)
                .build()
        )
        helper.use {
            val db = it.writableDatabase
            AppDatabase.MIGRATION_22_23.migrate(db)
            db.query("SELECT id, book_id, needs_reinsert, created_at, request_id FROM tb_rewrite_queue ORDER BY id")
                .use { rows ->
                    assertTrue(rows.moveToNext())
                    assertEquals(11L, rows.getLong(0))
                    assertEquals(0L, rows.getLong(1))
                    assertEquals(1, rows.getInt(2))
                    assertEquals(81L, rows.getLong(3))
                    assertEquals("", rows.getString(4))
                    assertTrue(rows.moveToNext())
                    assertEquals(12L, rows.getLong(0))
                    assertEquals(3L, rows.getLong(1))
                    assertEquals(1, rows.getInt(2))
                    assertEquals(82L, rows.getLong(3))
                    assertEquals("", rows.getString(4))
                    assertFalse(rows.moveToNext())
                }
            db.execSQL("INSERT INTO tb_rewrite_queue (id, word_id, book_id, needs_reinsert, created_at) VALUES (13, 7, 4, 0, 83)")
            db.query("SELECT COUNT(*) FROM tb_rewrite_queue WHERE word_id = 7").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(2, rows.getInt(0))
            }
        }
    }
}

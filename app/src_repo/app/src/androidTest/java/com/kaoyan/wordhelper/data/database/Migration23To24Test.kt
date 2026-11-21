package com.kaoyan.wordhelper.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DB 23 → 24 迁移：FSRS-5 四列 + 重写队列 priority。
 *
 * 这里直接建一个 v23 的原始库并调用 [AppDatabase.MIGRATION_23_24]，
 * 不走 Room 的整库打开流程：现有 MigrationTest 只覆盖到很老的版本，
 * 用 Room 打开会要求 13→24 的完整迁移链，反而测不到本次迁移本身。
 * 迁移 SQL 的列类型/默认值仍以 Room 实体为准，主脑可用 assembleDebug 的
 * Room schema 校验兜底。
 */
@RunWith(AndroidJUnit4::class)
class Migration23To24Test {

    @Test
    fun migrate23To24_backfillsLegacyProgressAndAddsPriority() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        createVersion23Database(context)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_23_24.migrate(db)
            // 幂等：再跑一次不应报错、也不应改变回填结果。
            AppDatabase.MIGRATION_23_24.migrate(db)

            // 旧进度行：repetitions=3 / interval_days=12 / ease_factor=2.2
            db.query(
                """SELECT fsrsStability, fsrsDifficulty, fsrsState, fsrsLapses
                   FROM tb_progress WHERE id = 1"""
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(12.0, cursor.getFloat(0).toDouble(), 0.001)
                assertEquals(3.25, cursor.getFloat(1).toDouble(), 0.001)
                assertEquals(2, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }

            // 未学习过的旧行：state 仍为 0（stability 给最小 1 天，避免除零）
            db.query(
                """SELECT fsrsStability, fsrsDifficulty, fsrsState, fsrsLapses
                   FROM tb_progress WHERE id = 2"""
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1.0, cursor.getFloat(0).toDouble(), 0.001)
                assertEquals(1.0, cursor.getFloat(1).toDouble(), 0.001)
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }

            // tb_rewrite_queue.priority 存在，且旧行默认 0
            var hasPriority = false
            db.query("PRAGMA table_info('tb_rewrite_queue')").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "priority") hasPriority = true
                }
            }
            assertTrue("tb_rewrite_queue 应有 priority 列", hasPriority)
            db.query("SELECT priority FROM tb_rewrite_queue WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun createVersion23Database(context: Context) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS tb_progress (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                word_id INTEGER NOT NULL,
                                book_id INTEGER NOT NULL,
                                status INTEGER NOT NULL,
                                repetitions INTEGER NOT NULL DEFAULT 0,
                                interval_days INTEGER NOT NULL DEFAULT 0,
                                next_review_time INTEGER NOT NULL,
                                ease_factor REAL NOT NULL,
                                review_count INTEGER NOT NULL DEFAULT 0,
                                spell_correct_count INTEGER NOT NULL DEFAULT 0,
                                spell_wrong_count INTEGER NOT NULL DEFAULT 0,
                                marked_easy_count INTEGER NOT NULL DEFAULT 0,
                                last_easy_time INTEGER NOT NULL DEFAULT 0,
                                consecutive_correct INTEGER NOT NULL DEFAULT 0,
                                avg_response_time_ms REAL NOT NULL DEFAULT 0,
                                last_review_time INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_progress_word_id_book_id " +
                                "ON tb_progress(word_id, book_id)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_tb_progress_next_review_time " +
                                "ON tb_progress(next_review_time)"
                        )

                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS tb_rewrite_queue (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                word_id INTEGER NOT NULL,
                                book_id INTEGER NOT NULL DEFAULT 0,
                                word TEXT NOT NULL,
                                instruction TEXT NOT NULL,
                                model TEXT NOT NULL,
                                status TEXT NOT NULL,
                                error TEXT,
                                needs_reinsert INTEGER NOT NULL DEFAULT 0,
                                created_at INTEGER NOT NULL,
                                updated_at INTEGER NOT NULL,
                                request_id TEXT NOT NULL DEFAULT ''
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_rewrite_queue_word_id_book_id " +
                                "ON tb_rewrite_queue(word_id, book_id)"
                        )

                        db.execSQL(
                            """
                            INSERT INTO tb_progress (
                                id, word_id, book_id, status, repetitions, interval_days,
                                next_review_time, ease_factor, review_count,
                                spell_correct_count, spell_wrong_count
                            ) VALUES (1, 1, 1, 1, 3, 12, 1700000000000, 2.2, 3, 0, 0)
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO tb_progress (
                                id, word_id, book_id, status, repetitions, interval_days,
                                next_review_time, ease_factor, review_count,
                                spell_correct_count, spell_wrong_count
                            ) VALUES (2, 2, 1, 0, 0, 0, 0, 2.5, 0, 0, 0)
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO tb_rewrite_queue (
                                id, word_id, book_id, word, instruction, model, status,
                                error, needs_reinsert, created_at, updated_at, request_id
                            ) VALUES (1, 1, 1, 'abandon', '', '', 'pending', NULL, 0, 1, 1, '')
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    companion object {
        private const val TEST_DB = "migration-23-24-test"
    }
}

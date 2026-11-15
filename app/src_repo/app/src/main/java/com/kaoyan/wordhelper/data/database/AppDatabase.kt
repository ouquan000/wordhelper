package com.kaoyan.wordhelper.data.database

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.BookDailyStatsDao
import com.kaoyan.wordhelper.data.dao.BookMemoryDao
import com.kaoyan.wordhelper.data.dao.DailyStatsDao
import com.kaoyan.wordhelper.data.dao.EarlyReviewDao
import com.kaoyan.wordhelper.data.dao.ForecastCacheDao
import com.kaoyan.wordhelper.data.dao.NewWordRefDao
import com.kaoyan.wordhelper.data.dao.ProgressDao
import com.kaoyan.wordhelper.data.dao.RewriteFeedbackDao
import com.kaoyan.wordhelper.data.dao.RewriteQueueDao
import com.kaoyan.wordhelper.data.dao.RewriteSkippedDao
import com.kaoyan.wordhelper.data.dao.StudyLogDao
import com.kaoyan.wordhelper.data.dao.SyncDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.dao.WordMemoryDao
import com.kaoyan.wordhelper.data.dao.AICacheDao
import com.kaoyan.wordhelper.data.dao.MLModelStateDao
import com.kaoyan.wordhelper.data.dao.TrainingSampleDao
import com.kaoyan.wordhelper.data.dao.WordMLStatsDao
import com.kaoyan.wordhelper.data.entity.AICache
import com.kaoyan.wordhelper.data.entity.MLModelState
import com.kaoyan.wordhelper.data.entity.TrainingSample
import com.kaoyan.wordhelper.data.entity.WordMLStats
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.entity.BookDailyStats
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.BookWordContent
import com.kaoyan.wordhelper.data.entity.DailyStats
import com.kaoyan.wordhelper.data.entity.EarlyReviewRef
import com.kaoyan.wordhelper.data.entity.ForecastCache
import com.kaoyan.wordhelper.data.entity.NewWordRef
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.entity.RewriteFeedback
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteSkippedResult
import com.kaoyan.wordhelper.data.entity.StudyLog
import com.kaoyan.wordhelper.data.entity.SyncOp
import com.kaoyan.wordhelper.data.entity.SyncState
import com.kaoyan.wordhelper.data.model.PresetBookCatalog
import com.kaoyan.wordhelper.data.model.WordDraft
import com.kaoyan.wordhelper.data.entity.WordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Book::class,
        WordEntity::class,
        BookWordContent::class,
        Progress::class,
        NewWordRef::class,
        StudyLog::class,
        DailyStats::class,
        EarlyReviewRef::class,
        AICache::class,
        ForecastCache::class,
        MLModelState::class,
        TrainingSample::class,
        WordMLStats::class,
        WordMemory::class,
        RewriteQueueItem::class,
        SyncOp::class,
        SyncState::class,
        BookMemory::class,
        BookDailyStats::class,
        RewriteFeedback::class,
        RewriteSkippedResult::class
    ],
    version = 23,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun wordDao(): WordDao
    abstract fun progressDao(): ProgressDao
    abstract fun newWordRefDao(): NewWordRefDao
    abstract fun studyLogDao(): StudyLogDao
    abstract fun dailyStatsDao(): DailyStatsDao
    abstract fun earlyReviewDao(): EarlyReviewDao
    abstract fun aiCacheDao(): AICacheDao
    abstract fun forecastCacheDao(): ForecastCacheDao
    abstract fun mlModelStateDao(): MLModelStateDao
    abstract fun trainingSampleDao(): TrainingSampleDao
    abstract fun wordMLStatsDao(): WordMLStatsDao
    abstract fun wordMemoryDao(): WordMemoryDao
    abstract fun rewriteQueueDao(): RewriteQueueDao
    abstract fun syncDao(): SyncDao
    abstract fun bookMemoryDao(): BookMemoryDao
    abstract fun bookDailyStatsDao(): BookDailyStatsDao
    abstract fun rewriteFeedbackDao(): RewriteFeedbackDao
    abstract fun rewriteSkippedDao(): RewriteSkippedDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kaoyan_words.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23
                )
                .build()
        }

        @VisibleForTesting
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN repetitions INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN interval_days INTEGER NOT NULL DEFAULT 0")
            }
        }

        @VisibleForTesting
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_study_log (
                        date TEXT NOT NULL PRIMARY KEY,
                        count INTEGER NOT NULL,
                        update_time INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @VisibleForTesting
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN spell_correct_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN spell_wrong_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_daily_stats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        new_words_count INTEGER NOT NULL DEFAULT 0,
                        review_words_count INTEGER NOT NULL DEFAULT 0,
                        spell_practice_count INTEGER NOT NULL DEFAULT 0,
                        duration_millis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tb_daily_stats_date ON tb_daily_stats(date)")
            }
        }

        @VisibleForTesting
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_early_review_ref (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        book_id INTEGER NOT NULL,
                        add_time INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_early_review_ref_word_id_book_id ON tb_early_review_ref(word_id, book_id)"
                )
            }
        }

        @VisibleForTesting
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tb_word RENAME TO tb_word_legacy")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_word (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word TEXT NOT NULL,
                        word_key TEXT NOT NULL,
                        phonetic TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tb_word_word_key ON tb_word(word_key)")

                db.execSQL(
                    """
                    INSERT INTO tb_word (id, word, word_key, phonetic)
                    SELECT grouped.new_id,
                           (
                               SELECT legacy.word
                               FROM tb_word_legacy legacy
                               WHERE LOWER(TRIM(legacy.word)) = grouped.word_key
                               ORDER BY legacy.id ASC
                               LIMIT 1
                           ) AS word,
                           grouped.word_key,
                           IFNULL(
                               (
                                   SELECT legacy.phonetic
                                   FROM tb_word_legacy legacy
                                   WHERE LOWER(TRIM(legacy.word)) = grouped.word_key
                                     AND TRIM(legacy.phonetic) <> ''
                                   ORDER BY legacy.id ASC
                                   LIMIT 1
                               ),
                               ''
                           ) AS phonetic
                    FROM (
                        SELECT MIN(id) AS new_id, LOWER(TRIM(word)) AS word_key
                        FROM tb_word_legacy
                        GROUP BY LOWER(TRIM(word))
                    ) AS grouped
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_book_word_content (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        book_id INTEGER NOT NULL,
                        meaning TEXT NOT NULL,
                        example TEXT NOT NULL,
                        FOREIGN KEY(word_id) REFERENCES tb_word(id) ON DELETE CASCADE,
                        FOREIGN KEY(book_id) REFERENCES tb_book(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_book_word_content_word_id_book_id ON tb_book_word_content(word_id, book_id)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tb_book_word_content_book_id ON tb_book_word_content(book_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tb_book_word_content_word_id ON tb_book_word_content(word_id)")

                db.execSQL(
                    """
                    INSERT INTO tb_book_word_content (word_id, book_id, meaning, example)
                    SELECT w.id,
                           legacy.book_id,
                           legacy.meaning,
                           legacy.example
                    FROM tb_word_legacy legacy
                    INNER JOIN tb_word w ON w.word_key = LOWER(TRIM(legacy.word))
                    WHERE legacy.id = (
                        SELECT legacy2.id
                        FROM tb_word_legacy legacy2
                        WHERE legacy2.book_id = legacy.book_id
                          AND LOWER(TRIM(legacy2.word)) = LOWER(TRIM(legacy.word))
                        ORDER BY legacy2.id DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_word_id_map (
                        old_id INTEGER PRIMARY KEY NOT NULL,
                        new_id INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tb_word_id_map (old_id, new_id)
                    SELECT legacy.id, w.id
                    FROM tb_word_legacy legacy
                    INNER JOIN tb_word w ON w.word_key = LOWER(TRIM(legacy.word))
                    """.trimIndent()
                )

                db.execSQL("ALTER TABLE tb_progress RENAME TO tb_progress_legacy")
                db.execSQL("DROP INDEX IF EXISTS index_tb_progress_word_id_book_id")
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
                        spell_wrong_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tb_progress (
                        id,
                        word_id,
                        book_id,
                        status,
                        repetitions,
                        interval_days,
                        next_review_time,
                        ease_factor,
                        review_count,
                        spell_correct_count,
                        spell_wrong_count
                    )
                    SELECT legacy.id,
                           mapped.new_id,
                           legacy.book_id,
                           legacy.status,
                           legacy.repetitions,
                           legacy.interval_days,
                           legacy.next_review_time,
                           legacy.ease_factor,
                           legacy.review_count,
                           legacy.spell_correct_count,
                           legacy.spell_wrong_count
                    FROM tb_progress_legacy legacy
                    INNER JOIN tb_word_id_map mapped ON mapped.old_id = legacy.word_id
                    WHERE legacy.id = (
                        SELECT legacy2.id
                        FROM tb_progress_legacy legacy2
                        INNER JOIN tb_word_id_map mapped2 ON mapped2.old_id = legacy2.word_id
                        WHERE mapped2.new_id = mapped.new_id
                          AND legacy2.book_id = legacy.book_id
                        ORDER BY legacy2.id DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_progress_word_id_book_id ON tb_progress(word_id, book_id)"
                )
                db.execSQL("DROP TABLE tb_progress_legacy")

                db.execSQL("ALTER TABLE tb_new_word_ref RENAME TO tb_new_word_ref_legacy")
                db.execSQL("DROP INDEX IF EXISTS index_tb_new_word_ref_word_id")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_new_word_ref (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        add_time INTEGER NOT NULL,
                        note TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tb_new_word_ref (id, word_id, add_time, note)
                    SELECT legacy.id,
                           mapped.new_id,
                           legacy.add_time,
                           legacy.note
                    FROM tb_new_word_ref_legacy legacy
                    INNER JOIN tb_word_id_map mapped ON mapped.old_id = legacy.word_id
                    WHERE legacy.id = (
                        SELECT legacy2.id
                        FROM tb_new_word_ref_legacy legacy2
                        INNER JOIN tb_word_id_map mapped2 ON mapped2.old_id = legacy2.word_id
                        WHERE mapped2.new_id = mapped.new_id
                        ORDER BY legacy2.id DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tb_new_word_ref_word_id ON tb_new_word_ref(word_id)")
                db.execSQL("DROP TABLE tb_new_word_ref_legacy")

                db.execSQL("ALTER TABLE tb_early_review_ref RENAME TO tb_early_review_ref_legacy")
                db.execSQL("DROP INDEX IF EXISTS index_tb_early_review_ref_word_id_book_id")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_early_review_ref (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        book_id INTEGER NOT NULL,
                        add_time INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tb_early_review_ref (id, word_id, book_id, add_time)
                    SELECT legacy.id,
                           mapped.new_id,
                           legacy.book_id,
                           legacy.add_time
                    FROM tb_early_review_ref_legacy legacy
                    INNER JOIN tb_word_id_map mapped ON mapped.old_id = legacy.word_id
                    WHERE legacy.id = (
                        SELECT legacy2.id
                        FROM tb_early_review_ref_legacy legacy2
                        INNER JOIN tb_word_id_map mapped2 ON mapped2.old_id = legacy2.word_id
                        WHERE mapped2.new_id = mapped.new_id
                          AND legacy2.book_id = legacy.book_id
                        ORDER BY legacy2.id DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_early_review_ref_word_id_book_id ON tb_early_review_ref(word_id, book_id)"
                )
                db.execSQL("DROP TABLE tb_early_review_ref_legacy")

                db.execSQL("DROP TABLE tb_word_id_map")
                db.execSQL("DROP TABLE tb_word_legacy")
            }
        }

        @VisibleForTesting
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_ai_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER,
                        query_content TEXT NOT NULL,
                        type TEXT NOT NULL,
                        ai_content TEXT NOT NULL,
                        model_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tb_ai_cache_word_id_type ON tb_ai_cache(word_id, type)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tb_ai_cache_query_content_type ON tb_ai_cache(query_content, type)"
                )
            }
        }

        @VisibleForTesting
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN marked_easy_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN last_easy_time INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tb_progress_next_review_time ON tb_progress(next_review_time)"
                )

                db.execSQL("ALTER TABLE tb_daily_stats ADD COLUMN gesture_easy_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE tb_daily_stats ADD COLUMN gesture_notebook_count INTEGER NOT NULL DEFAULT 0"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_forecast_cache (
                        date INTEGER NOT NULL PRIMARY KEY,
                        review_count INTEGER NOT NULL DEFAULT 0,
                        new_word_quota INTEGER NOT NULL DEFAULT 0,
                        is_calculated INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        @VisibleForTesting
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tb_daily_stats ADD COLUMN fuzzy_words_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_daily_stats ADD COLUMN recognized_words_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        @VisibleForTesting
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tb_book_word_content ADD COLUMN phrases TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tb_book_word_content ADD COLUMN synonyms TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tb_book_word_content ADD COLUMN rel_words TEXT NOT NULL DEFAULT ''")
            }
        }

        @VisibleForTesting
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 扩展 tb_progress 表
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN consecutive_correct INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN avg_response_time_ms REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tb_progress ADD COLUMN last_review_time INTEGER NOT NULL DEFAULT 0")

                // 新增 ml_model_state 表（单例）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ml_model_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        n_params_json TEXT NOT NULL DEFAULT '',
                        z_params_json TEXT NOT NULL DEFAULT '',
                        weights_json TEXT NOT NULL DEFAULT '',
                        version INTEGER NOT NULL DEFAULT 0,
                        sample_count INTEGER NOT NULL DEFAULT 0,
                        last_training_time INTEGER NOT NULL DEFAULT 0,
                        global_accuracy REAL NOT NULL DEFAULT 0,
                        user_base_retention REAL NOT NULL DEFAULT 0.85,
                        avg_response_time REAL NOT NULL DEFAULT 0,
                        std_response_time REAL NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // 新增 training_samples 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS training_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        features_json TEXT NOT NULL,
                        outcome INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        prediction_error REAL NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // 新增 word_ml_stats 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS word_ml_stats (
                        word_id INTEGER NOT NULL PRIMARY KEY,
                        predicted_difficulty REAL NOT NULL DEFAULT 0.5,
                        personal_ef REAL NOT NULL DEFAULT 2.5,
                        avg_forget_prob REAL NOT NULL DEFAULT 0.5,
                        review_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        @VisibleForTesting
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(
                    db = db,
                    table = "tb_progress",
                    column = "consecutive_correct",
                    definition = "INTEGER NOT NULL DEFAULT 0"
                )
                ensureColumnExists(
                    db = db,
                    table = "tb_progress",
                    column = "avg_response_time_ms",
                    definition = "REAL NOT NULL DEFAULT 0"
                )
                ensureColumnExists(
                    db = db,
                    table = "tb_progress",
                    column = "last_review_time",
                    definition = "INTEGER NOT NULL DEFAULT 0"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ml_model_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        n_params_json TEXT NOT NULL DEFAULT '',
                        z_params_json TEXT NOT NULL DEFAULT '',
                        weights_json TEXT NOT NULL DEFAULT '',
                        version INTEGER NOT NULL DEFAULT 0,
                        sample_count INTEGER NOT NULL DEFAULT 0,
                        last_training_time INTEGER NOT NULL DEFAULT 0,
                        global_accuracy REAL NOT NULL DEFAULT 0,
                        user_base_retention REAL NOT NULL DEFAULT 0.85,
                        avg_response_time REAL NOT NULL DEFAULT 0,
                        std_response_time REAL NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS training_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        features_json TEXT NOT NULL,
                        outcome INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        prediction_error REAL NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS word_ml_stats (
                        word_id INTEGER NOT NULL PRIMARY KEY,
                        predicted_difficulty REAL NOT NULL DEFAULT 0.5,
                        personal_ef REAL NOT NULL DEFAULT 2.5,
                        avg_forget_prob REAL NOT NULL DEFAULT 0.5,
                        review_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        @VisibleForTesting
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(
                    db = db,
                    table = "tb_daily_stats",
                    column = "check_in_count",
                    definition = "INTEGER NOT NULL DEFAULT 0"
                )
                ensureColumnExists(
                    db = db,
                    table = "tb_daily_stats",
                    column = "last_check_in_time",
                    definition = "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v14：新增 tb_word_memory（记忆思路 + 常用搭配）。
         *
         * 只做建表，不动任何既有表，所以从 v13 升级不丢学习进度。
         * 表结构必须与 Room 由 WordMemory 生成的期望 schema 完全一致，
         * 否则 Room 打开库时会抛 IllegalStateException。
         */
        @VisibleForTesting
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_word_memory (
                        word_id INTEGER NOT NULL,
                        approach TEXT NOT NULL,
                        collocations TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(word_id),
                        FOREIGN KEY(word_id) REFERENCES tb_word(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 1.4.3：记忆思路支持「来源 / 版本 / 上一版」，并新增重写标记队列。
         * 老数据全部视为 seed，rev=0，没有可撤销版本。
         */
        @VisibleForTesting
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(db, "tb_word_memory", "source", "TEXT NOT NULL DEFAULT 'seed'")
                ensureColumnExists(db, "tb_word_memory", "rev", "INTEGER NOT NULL DEFAULT 0")
                ensureColumnExists(db, "tb_word_memory", "prev_approach", "TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_rewrite_queue (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        word TEXT NOT NULL,
                        instruction TEXT NOT NULL,
                        model TEXT NOT NULL,
                        status TEXT NOT NULL,
                        error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_rewrite_queue_word_id " +
                        "ON tb_rewrite_queue (word_id)"
                )
            }
        }

        /**
         * 1.4.4：①卡背方法标签（tb_word_memory.method）②同步队列与游标（照抄墨墨的两张表）。
         */
        @VisibleForTesting
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(db, "tb_word_memory", "method", "TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_sync_op (
                        op_id TEXT NOT NULL PRIMARY KEY,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_sync_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        server_rev INTEGER NOT NULL,
                        device_id TEXT NOT NULL,
                        last_sync_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 1.4.5：①重写结果按书存（tb_book_memory）②按书每日学习量（tb_book_daily_stats）
         * ③重写队列记住这本书（tb_rewrite_queue.book_id）。
         */
        @VisibleForTesting
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_book_memory (
                        word_id INTEGER NOT NULL,
                        book_id INTEGER NOT NULL,
                        approach TEXT NOT NULL DEFAULT '',
                        collocations TEXT NOT NULL DEFAULT '',
                        method TEXT NOT NULL DEFAULT '',
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'seed',
                        rev INTEGER NOT NULL DEFAULT 0,
                        prev_approach TEXT,
                        PRIMARY KEY(word_id, book_id),
                        FOREIGN KEY(word_id) REFERENCES tb_word(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tb_book_memory_book_id ON tb_book_memory(book_id)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_book_daily_stats (
                        date TEXT NOT NULL,
                        book_id INTEGER NOT NULL,
                        new_count INTEGER NOT NULL DEFAULT 0,
                        review_count INTEGER NOT NULL DEFAULT 0,
                        spell_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(date, book_id)
                    )
                    """.trimIndent()
                )
                ensureColumnExists(db, "tb_rewrite_queue", "book_id", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 1.4.6（DB 18）：进度按书彻底独立 + 撤销可回滚方法 + 反馈/跳过行两张表。
         *
         * ① 老进度归当前词书一次：1.4.5 之前 tb_progress 其实是「一个词一份进度、复制给所有书」，
         *    所以升级时把非当前书的行删掉，其他书从零开始。当前词书 = tb_book.is_active
         *    （不是 DataStore，迁移里能直接 SQL 读），迁移只跑一次，天然幂等，
         *    不需要再加一个「启动后补跑」的一次性 flag。
         *    生词本（type=2）例外：它是收藏夹不是「背的书」，删掉会丢用户刻意收藏词的 SM-2 状态；
         *    而且它也是按书独立记账的一本书，留着不会串味。
         *    找不到 active 书时一行都不删——宁可不清，也不能把用户唯一的一份进度删光。
         * ② prev_method：撤销时和 prev_approach 一起回滚方法标签。
         * ③ tb_rewrite_skipped：sync 跳过行不再随游标蒸发。
         * ④ tb_feedback：卡背 👍/👎 本地落库，断网时等 sync 补发。
         */
        @VisibleForTesting
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(db, "tb_word_memory", "prev_method", "TEXT")
                ensureColumnExists(db, "tb_book_memory", "prev_method", "TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_rewrite_skipped (
                        seq INTEGER NOT NULL,
                        word TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        last_try_at INTEGER NOT NULL,
                        PRIMARY KEY(seq)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tb_feedback (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        book_id INTEGER NOT NULL,
                        word TEXT NOT NULL,
                        rating TEXT NOT NULL,
                        approach TEXT NOT NULL,
                        method TEXT NOT NULL,
                        model TEXT NOT NULL,
                        instruction TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        synced INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 名字必须和 Room 给 @Entity(indices=...) 生成的一致，否则启动校验会报 schema 不匹配。
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_feedback_word_id_book_id_rating " +
                        "ON tb_feedback (word_id, book_id, rating)"
                )

                cleanupForeignProgress(db)
            }
        }

        /**
         * 1.4.6.1（DB 19）：反馈绑到「思路版本」。
         *
         * 旧表只按 (word_id, book_id, rating) 去重，换过思路之后旧评价还亮着，
         * 而且没法再对新版评价。新增 approach_rev（老数据默认 0 = 种子思路），
         * 唯一索引扩成 (word_id, book_id, rating, approach_rev)：
         * 同一版同一态度仍然只记一次，换版后可以重新评。
         */
        @VisibleForTesting
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(
                    db,
                    "tb_feedback",
                    "approach_rev",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("DROP INDEX IF EXISTS index_tb_feedback_word_id_book_id_rating")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_tb_feedback_word_id_book_id_rating_approach_rev " +
                        "ON tb_feedback (word_id, book_id, rating, approach_rev)"
                )
            }
        }

        /**
         * 1.4.6.1（DB 20）：把「跳过、等结果回来再插回」的意图持久化。
         *
         * 之前只记在 ViewModel 的内存集合里，进程一死就丢；而冷启动的自动同步可能
         * 在学习页恢复等待集合之前先把队列行写成 done，恢复逻辑再查 pending/queued
         * 就是空的，于是词永远插不回去。新增 needs_reinsert 后，恢复逻辑不看状态，
         * done 行也能补插。ALTER 走 ensureColumnExists，重复迁移/已有列都安全。
         */
        @VisibleForTesting
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(
                    db,
                    "tb_rewrite_queue",
                    "needs_reinsert",
                    "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 1.4.7.1（DB 21）：记下「这版思路没复核」。
         *
         * 服务端重写结果可能带 `unverified: true` + `reason`（自检没跑完/兜底产出）。标记要跟着
         * 思路一起落库，否则关掉重开 App 就不知道这版是没复核的了。两张思路表都加，撤销时清掉。
         * 默认 0 = 已复核，老数据不受影响；ALTER 走 ensureColumnExists，重复迁移/已有列都安全。
         */
        @VisibleForTesting
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(db, "tb_word_memory", "unverified", "INTEGER NOT NULL DEFAULT 0")
                ensureColumnExists(db, "tb_word_memory", "unverified_reason", "TEXT")
                ensureColumnExists(db, "tb_book_memory", "unverified", "INTEGER NOT NULL DEFAULT 0")
                ensureColumnExists(db, "tb_book_memory", "unverified_reason", "TEXT")
            }
        }

        /**
         * 1.4.7.2（DB 22）：同步恢复代次。
         *
         * 服务端从坏文件重建时 epoch+1；客户端只归零 server_rev 重拉，
         * 不动 tb_sync_op 里还没发出去的本地进度。老库默认 1。
         */
        @VisibleForTesting
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(db, "tb_sync_state", "epoch", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** 1.4.7.2：同词不同书各有一条队列。保留旧行和在途请求的 clientId。 */
        @VisibleForTesting
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnExists(db, "tb_rewrite_queue", "request_id", "TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_tb_rewrite_queue_word_id")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tb_rewrite_queue_word_id_book_id " +
                        "ON tb_rewrite_queue (word_id, book_id)"
                )
            }
        }

        /**
         * 把 tb_progress 收敛到「当前词书 + 生词本」，其他书从零。
         * 只在 MIGRATION_17_18 里调用，所以不写成 suspend、也不看 DataStore。
         */
        private fun cleanupForeignProgress(db: SupportSQLiteDatabase) {
            val activeBookId = queryLongOrZero(db, "SELECT id FROM tb_book WHERE is_active = 1 LIMIT 1")
            if (activeBookId <= 0L) return
            val newWordsBookId = queryLongOrZero(
                db,
                "SELECT MIN(id) FROM tb_book WHERE type = ?",
                arrayOf<Any?>(Book.TYPE_NEW_WORDS)
            )
            val keep = ArrayList<Long>(2)
            keep.add(activeBookId)
            if (newWordsBookId > 0L && newWordsBookId != activeBookId) keep.add(newWordsBookId)
            // 只拼 Long，没有用户输入，不担心注入；这样也不用处理 execSQL 的 bindArgs 变体。
            db.execSQL(
                "DELETE FROM tb_progress WHERE book_id NOT IN (${keep.joinToString(", ")})"
            )
        }

        private fun queryLongOrZero(
            db: SupportSQLiteDatabase,
            sql: String,
            args: Array<Any?> = emptyArray()
        ): Long {
            db.query(sql, args).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return 0L
                return cursor.getLong(0)
            }
        }

        private fun ensureColumnExists(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            if (hasColumn(db, table, column)) return
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex == -1) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) {
                        return true
                    }
                }
            }
            return false
        }

        private suspend fun prepopulate(database: AppDatabase) {
            val bookDao = database.bookDao()
            val wordDao = database.wordDao()

            bookDao.insert(
                Book(
                    name = PresetBookCatalog.NEW_WORDS_BOOK_NAME,
                    type = Book.TYPE_NEW_WORDS,
                    totalCount = 0,
                    isActive = false
                )
            )

            PresetBookCatalog.presets.forEachIndexed { index, preset ->
                val bookId = bookDao.insert(
                    Book(
                        name = preset.name,
                        type = Book.TYPE_PRESET,
                        totalCount = 0,
                        isActive = index == 0
                    )
                )
                upsertDraftsForBook(wordDao, bookId, preset.drafts)
                bookDao.updateTotalCount(bookId, wordDao.getWordCount(bookId))
            }
        }

        private suspend fun upsertDraftsForBook(wordDao: WordDao, bookId: Long, drafts: List<WordDraft>) {
            if (drafts.isEmpty()) return
            val contents = ArrayList<BookWordContent>(drafts.size)
            drafts.forEach { draft ->
                val rawWord = draft.word.trim()
                if (rawWord.isBlank()) return@forEach

                val phonetic = draft.phonetic.trim()
                val key = normalizeWordKey(rawWord)
                val existing = wordDao.getWordEntityByKey(key)
                val wordId = if (existing != null) {
                    if (existing.phonetic.isBlank() && phonetic.isNotBlank()) {
                        wordDao.updatePhonetic(existing.id, phonetic)
                    }
                    existing.id
                } else {
                    val insertedId = wordDao.insertWordEntity(
                        WordEntity(
                            word = rawWord,
                            wordKey = key,
                            phonetic = phonetic
                        )
                    )
                    if (insertedId > 0) {
                        insertedId
                    } else {
                        wordDao.getWordIdByKey(key) ?: return@forEach
                    }
                }

                contents.add(
                    BookWordContent(
                        wordId = wordId,
                        bookId = bookId,
                        meaning = draft.meaning,
                        example = draft.example,
                        phrases = draft.phrases,
                        synonyms = draft.synonyms,
                        relWords = draft.relWords
                    )
                )
            }
            if (contents.isNotEmpty()) {
                wordDao.upsertBookWordContents(contents)
            }
        }

        private fun normalizeWordKey(raw: String): String {
            return raw.trim().lowercase()
        }
    }
}

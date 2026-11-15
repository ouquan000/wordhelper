package com.kaoyan.wordhelper.util

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W5：备份导入的前置校验必须先于任何现库操作。
 *
 * 这里只验证 validateSqlite 本身；导入顺序由 DatabaseBackupManager.importDatabase
 * 的代码顺序保证：validateSqlite 在 checkpoint / preImportBackup / database.close 之前。
 * 测试不接触 App 的 kaoyan_words.db，只操作 cache 下的临时文件。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupValidationInstrumentedTest {

    private val validate: (File, Int) -> Unit by lazy {
        val method = DatabaseBackupManager::class.java.getDeclaredMethod(
            "validateSqlite",
            File::class.java,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        { file, maxVersion -> method.invoke(DatabaseBackupManager, file, maxVersion) }
    }

    @Test
    fun rejectsNonSqliteFileBeforeAnyImport() {
        val file = File.createTempFile("w5-bad-", ".db").apply {
            writeText("not a sqlite database at all")
        }
        try {
            assertThrows(Exception::class.java) { validate(file, 22) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsNewerSchemaVersion() {
        val file = File.createTempFile("w5-newer-", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY)")
                db.version = 99
            }
            assertThrows(Exception::class.java) { validate(file, 22) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsCurrentSchemaVersion() {
        val file = File.createTempFile("w5-good-", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY)")
                db.version = 22
            }
            validate(file, 22)
        } finally {
            file.delete()
        }
    }
}

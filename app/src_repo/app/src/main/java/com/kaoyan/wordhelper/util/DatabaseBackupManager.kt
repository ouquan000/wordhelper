package com.kaoyan.wordhelper.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.RoomDatabase
import com.kaoyan.wordhelper.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseBackupManager {
    private const val DB_NAME = "kaoyan_words.db"

    suspend fun exportDatabase(context: Context, database: AppDatabase, uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(checkpoint(database)) { "数据库正在写入，请稍后再导出" }
                val dbFile = context.getDatabasePath(DB_NAME)
                require(dbFile.exists()) { "数据库文件不存在" }
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("无法打开导出文件")
                dbFile.inputStream().use { input ->
                    output.use { out -> input.copyTo(out) }
                }
                Unit
            }
        }
    }

    /**
     * 1.4.7.2 W5：安全导入。
     *
     * 顺序：先把外部文件复制到 cache 临时文件 → 校验 SQLite 头和 integrity_check →
     * 版本不能高于当前 DB → 备份当前库 → 关库 → 原子替换。任何一步失败都不动现库；
     * 替换失败用 pre-import 备份恢复。
     */
    suspend fun importDatabase(context: Context, database: AppDatabase, uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val dbFile = context.getDatabasePath(DB_NAME)
                dbFile.parentFile?.mkdirs()
                val incoming = File(context.cacheDir, "import-${System.currentTimeMillis()}.db")
                val temp = File(dbFile.parentFile, "$DB_NAME.import.tmp")
                try {
                    context.contentResolver.openInputStream(uri)?.use { source ->
                        incoming.outputStream().use { target -> source.copyTo(target) }
                    } ?: error("无法打开导入文件")

                    val currentVersion = database.openHelper.readableDatabase.version
                    validateSqlite(incoming, currentVersion)

                    // checkpoint 失败时绝不能继续：后面会关库、删 WAL，
                    // 未 checkpoint 的已提交帧会丢。宁可让用户稍后重试。
                    require(checkpoint(database)) { "数据库正在写入，无法安全备份，请稍后再导入" }
                    val backup = preImportBackup(dbFile)
                    database.close()
                    cleanupWalFiles(dbFile)
                    incoming.copyTo(temp, overwrite = true)
                    try {
                        Files.move(
                            temp.toPath(),
                            dbFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (moveError: Exception) {
                        // 替换失败：把导入前的库原样放回，至少保证 App 还能打开。
                        if (backup.exists()) {
                            backup.copyTo(dbFile, overwrite = true)
                        }
                        throw moveError
                    }
                } finally {
                    incoming.delete()
                    // 失败路径也别把半截导入文件留在数据库目录里。
                    if (temp.exists()) temp.delete()
                }
                Unit
            }
        }
    }

    /** 校验：SQLite 头 + 完整性 + 不能把更高版本的库塞进旧 App。 */
    private fun validateSqlite(file: File, maxVersion: Int) {
        require(file.length() >= 100L) { "导入文件太小，不是完整的数据库" }
        val header = ByteArray(16)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            require(read == header.size) { "导入文件头不完整" }
        }
        require(String(header, Charsets.US_ASCII) == "SQLite format 3\u0000") {
            "导入文件不是 SQLite 数据库"
        }
        val sqlite = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            val integrity = sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            require(integrity == "ok") { "数据库完整性检查没通过：${integrity ?: "无结果"}" }
            val version = sqlite.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            require(version in 1..maxVersion) {
                "导入文件版本 $version 不在当前 App 支持范围 1..$maxVersion，请确认文件来源或先升级 App"
            }
        } finally {
            sqlite.close()
        }
    }

    /** 导入前的现库副本；文件名带时间戳，已存在就加序号，绝不覆盖旧备份。 */
    private fun preImportBackup(dbFile: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var backup = File(dbFile.parentFile, "$DB_NAME.pre-import-$stamp")
        var suffix = 2
        while (backup.exists()) {
            backup = File(dbFile.parentFile, "$DB_NAME.pre-import-$stamp-$suffix")
            suffix++
        }
        if (dbFile.exists()) dbFile.copyTo(backup, overwrite = false)
        return backup
    }

    internal fun checkpoint(database: RoomDatabase): Boolean {
        // 必须 moveToFirst() 让 Cursor 真正 step；只 query+close 不会执行 PRAGMA，
        // 这是 W5 独立测试抓到的 checkpoint no-op。
        // 返回值第一列是 busy：0 表示全部帧已 checkpoint，非 0 时不能删 WAL。
        return database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0
        }
    }

    private fun cleanupWalFiles(dbFile: File) {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }
}

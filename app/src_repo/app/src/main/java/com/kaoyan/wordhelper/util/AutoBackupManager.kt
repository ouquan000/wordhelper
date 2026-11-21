package com.kaoyan.wordhelper.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.room.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 零配置自动备份：每隔 [DEFAULT_INTERVAL_MS] 把 Room 主库 checkpoint 后
 * 复制一份到公共「下载/WordHelperBackups」，卸载 App 也不丢。
 *
 * - API 29+：MediaStore.Downloads 写入公共下载目录，无需任何运行时权限。
 * - API 26–28：静默回退到 App 专属外置下载目录（卸载会清掉，仅作降级）。
 * - 只保留最近 [KEEP_COUNT] 份，且只清理本 App 的 kaoyan_words_*.db。
 *
 * 所有入口都不抛异常、不阻塞主线程；调用方在 UI 层负责触发时机。
 */
object AutoBackupManager {

    const val DEFAULT_INTERVAL_MS = 12L * 60 * 60 * 1000
    const val KEEP_COUNT = 7
    const val BACKUP_DIR_NAME = "WordHelperBackups"
    const val FILE_PREFIX = "kaoyan_words_"

    private const val DB_NAME = "kaoyan_words.db"
    private const val FILE_SUFFIX = ".db"
    private const val PREFS_NAME = "auto_backup"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_FILE_NAME = "last_file_name"
    private const val MIME_TYPE = "application/x-sqlite3"
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIR_NAME"

    data class AutoBackupResult(
        val ok: Boolean,
        val message: String,
        val savedName: String? = null,
        val savedAt: Long = 0L
    )

    data class AutoBackupState(
        val lastSuccessAt: Long,
        val lastFileName: String?
    )

    /** 读取上次成功备份的时间与文件名；没有记录时返回 0 / null。 */
    fun readState(context: Context): AutoBackupState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AutoBackupState(
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L),
            lastFileName = prefs.getString(KEY_LAST_FILE_NAME, null)
        )
    }

    /**
     * 是否该备份了：
     * - 从未成功过（<= 0）→ true；
     * - 时钟被回拨（now < lastSuccessAt）→ true，避免永远不再备份；
     * - 否则距上次成功满 [intervalMs] 才 true。
     */
    fun shouldRun(
        lastSuccessAt: Long,
        now: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): Boolean {
        if (lastSuccessAt <= 0L) return true
        if (now < lastSuccessAt) return true
        return now - lastSuccessAt >= intervalMs
    }

    /**
     * 到点才备份；任何异常都转成 ok=false，绝不抛出。
     */
    suspend fun maybeRun(context: Context, database: RoomDatabase): AutoBackupResult =
        withContext(Dispatchers.IO) {
            try {
                val state = readState(context)
                val now = System.currentTimeMillis()
                if (!shouldRun(state.lastSuccessAt, now)) {
                    return@withContext AutoBackupResult(true, "未到备份时间")
                }

                // checkpoint 失败说明 WAL 里还有未落盘的已提交帧，此时拷出来的库可能残缺。
                if (!DatabaseBackupManager.checkpoint(database)) {
                    return@withContext AutoBackupResult(false, "数据库正在写入，请稍后再试")
                }

                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.isFile) {
                    return@withContext AutoBackupResult(false, "数据库文件不存在")
                }

                val fileName = backupFileName(now)
                val savedName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStore(context, dbFile, fileName)
                } else {
                    saveToAppDownloadDir(context, dbFile, fileName)
                }

                // 清理失败不影响本次备份结果。
                runCatching { cleanupOldBackups(context) }

                writeState(context, now, savedName)
                AutoBackupResult(
                    ok = true,
                    message = "已备份到 ${backupDirLabel()}/$savedName",
                    savedName = savedName,
                    savedAt = now
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: t.javaClass.simpleName
                AutoBackupResult(false, "备份失败：${detail.take(120)}")
            }
        }

    /** kaoyan_words_yyyyMMdd_HHmm.db（本地时区）。 */
    internal fun backupFileName(now: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(now))
        return "$FILE_PREFIX$stamp$FILE_SUFFIX"
    }

    /**
     * 纯策略：只认本 App 的 kaoyan_words_*.db，按文件名（时间戳字典序）降序，
     * 保留前 [keep] 份，返回应删除的尾部。乱序输入也正确。
     */
    internal fun expiredBackups(names: List<String>, keep: Int = KEEP_COUNT): List<String> {
        val backups = names.filter(::isBackupFileName).sortedDescending()
        if (keep <= 0) return backups
        return if (backups.size > keep) backups.drop(keep) else emptyList()
    }

    private fun isBackupFileName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    // ---------------------------------------------------------------- 写入

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, dbFile: File, fileName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")

        try {
            val output = resolver.openOutputStream(uri) ?: error("无法写入下载文件")
            output.use { out ->
                dbFile.inputStream().use { input -> input.copyTo(out) }
            }
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)
            return fileName
        } catch (t: Throwable) {
            // 不把 IS_PENDING 的半截文件留在公共目录里。
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun saveToAppDownloadDir(context: Context, dbFile: File, fileName: String): String {
        val dir = legacyBackupDir(context) ?: error("外部存储不可用")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            error("无法创建备份目录")
        }
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.tmp")
        try {
            dbFile.inputStream().use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            }
            // 同目录 rename 原子落位；个别文件系统不支持时退回 Files.move。
            if (!temp.renameTo(target)) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            return fileName
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    // ---------------------------------------------------------------- 清理

    private fun cleanupOldBackups(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cleanupMediaStore(context)
        } else {
            cleanupLegacyDir(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanupMediaStore(context: Context) {
        val entries = try {
            queryMediaStoreBackups(context, filterOwner = true)
        } catch (t: Throwable) {
            // 少数 ROM 没有 OWNER_PACKAGE_NAME 列，退回不带归属过滤的查询。
            queryMediaStoreBackups(context, filterOwner = false)
        }
        val expired = expiredBackups(entries.map { it.second }).toSet()
        if (expired.isEmpty()) return
        entries.forEach { (uri, name) ->
            if (name in expired) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryMediaStoreBackups(
        context: Context,
        filterOwner: Boolean
    ): List<Pair<Uri, String>> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (filterOwner) add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        }.toTypedArray()

        var selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) " +
            "AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = mutableListOf(RELATIVE_DIR, "$RELATIVE_DIR/", "$FILE_PREFIX%")
        if (filterOwner) {
            selection += " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            args += context.packageName
        }

        val cursor = context.contentResolver.query(
            collection,
            projection,
            selection,
            args.toTypedArray(),
            null
        ) ?: return emptyList()

        return cursor.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val ownerColumn = if (filterOwner) {
                c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            } else {
                -1
            }
            val result = ArrayList<Pair<Uri, String>>()
            while (c.moveToNext()) {
                val name = c.getString(nameColumn) ?: continue
                if (filterOwner && ownerColumn >= 0 &&
                    c.getString(ownerColumn) != context.packageName
                ) {
                    continue
                }
                val id = c.getLong(idColumn)
                result += ContentUris.withAppendedId(collection, id) to name
            }
            result
        }
    }

    private fun cleanupLegacyDir(context: Context) {
        val dir = legacyBackupDir(context) ?: return
        if (!dir.isDirectory) return
        val backups = dir.listFiles { file -> file.isFile && isBackupFileName(file.name) }
            ?.sortedByDescending { it.name }
            ?: return
        val expired = expiredBackups(backups.map { it.name }).toSet()
        if (expired.isEmpty()) return
        backups.forEach { file ->
            if (file.name in expired) runCatching { file.delete() }
        }
    }

    // ---------------------------------------------------------------- 状态/路径

    private fun writeState(context: Context, at: Long, fileName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SUCCESS_AT, at)
            .putString(KEY_LAST_FILE_NAME, fileName)
            .commit()
    }

    private fun backupDirLabel(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "下载/$BACKUP_DIR_NAME"
    } else {
        "应用下载目录/$BACKUP_DIR_NAME"
    }

    private fun legacyBackupDir(context: Context): File? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(base, BACKUP_DIR_NAME)
    }
}

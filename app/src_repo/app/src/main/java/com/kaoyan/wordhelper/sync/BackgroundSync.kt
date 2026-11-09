package com.kaoyan.wordhelper.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.kaoyan.wordhelper.KaoyanWordApp

/**
 * 后台定时同步的调度器（1.4.7.1）。
 *
 * 为什么用 JobScheduler 而不是 WorkManager：这个 App 没有 androidx.work 依赖，
 * 为一个 15 分钟一次的后台同步去加一整套 WorkManager（含 startup、Room 集成、前台服务声明）
 * 不值得。平台自带的 JobScheduler 从 API 21 就有，minSdk 26 完全够用：
 * 系统会在有网时把任务攒到一批里跑，还会做 Doze / 省电的合适处理，比自己起线程温柔得多。
 *
 * 规则：
 * - **只有填过电脑地址才调度**。没配地址时后台跑也只会立刻失败，白耗电，所以 ensure 顺手撤掉任务。
 * - 周期 15 分钟（JobScheduler 的周期下限就是 15 分钟，写更小也会被系统抬到这个值）。
 * - 必须联网才跑，且真正干活的逻辑完全复用 [SyncRepository.syncOnce]，不另写一套同步。
 * - 和前台自动同步（MainActivity.onResume、断网恢复）共用一把 mutex，
 *   再加一层「最近 5 分钟同步过就别重复跑」的节流，避免两条链路各自打一遍同一份数据。
 */
object BackgroundSync {

    /** 固定 job id：同一个 App 只挂一个后台同步任务，重复 ensure 不会叠加。 */
    const val JOB_ID = 0x5A17

    private const val TAG = "BackgroundSync"

    private const val PREFS = "background_sync"
    private const val KEY_LAST_ATTEMPT = "last_attempt_at"

    /** JobScheduler 的周期下限；再短会被系统抬到 15 分钟，写死免得有人误以为能调更密。 */
    private const val PERIOD_MS = 15 * 60_000L

    /** 弹性窗口：让系统在这一段时间里挑个「不忙」的时刻跑，别钉死在整 15 分钟。 */
    private const val FLEX_MS = 5 * 60_000L

    /** 节流：最近这么久之内同步过（不管是前台还是后台），这一轮就跳过。 */
    private const val MIN_GAP_MS = 5 * 60_000L

    /**
     * 保证「已配置地址 → 有且只有一个定时任务」，否则撤掉。
     *
     * App 启动时调一次；JobService 每次跑之前也会自查（用户在设置里清了地址后不用等下次启动）。
     * 返回值只用于日志/测试，调度失败不让 App 崩。
     */
    suspend fun ensure(context: Context): Boolean {
        val app = context.applicationContext as? KaoyanWordApp ?: return false
        val configured = runCatching { app.syncRepository.hasAddress() }.getOrDefault(false)
        if (!configured) {
            cancel(context)
            return false
        }
        val scheduler = jobScheduler(context) ?: return false
        // 已经排上了就别重排：重排会把已有的周期节奏打乱。
        val already = runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull()
        if (already != null) return true
        return runCatching {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackgroundSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS, FLEX_MS)
                // 重启后仍然有效：用户不打开 App 也能把断网时攒的学习记录送上去。
                // 需要 manifest 里的 RECEIVE_BOOT_COMPLETED 权限，缺了 schedule 会抛 SecurityException。
                .setPersisted(true)
                .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }.onFailure { Log.w(TAG, "schedule failed", it) }.getOrDefault(false)
    }

    /** 没配地址 / 用户关了地址时撤掉任务；撤不存在的不报错。 */
    fun cancel(context: Context) {
        runCatching { jobScheduler(context)?.cancel(JOB_ID) }
            .onFailure { Log.w(TAG, "cancel failed", it) }
    }

    /**
     * 这一轮要不要跳过。
     *
     * 两个依据：
     * - 上一次后台尝试还在 [MIN_GAP_MS] 内（系统偶尔会把周期任务挤在一起跑）；
     * - [com.kaoyan.wordhelper.data.repository.SyncRepository.lastSyncAtMs] 说明前台刚同步过
     *   （用户刚切回 App 顺手同步了一轮），这一轮再拉一遍纯属重复请求。
     */
    fun shouldSkip(app: KaoyanWordApp, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        if (lastAttempt > 0L && now - lastAttempt < MIN_GAP_MS) return true
        val lastSync = app.syncRepository.lastSyncAtMs
        return lastSync > 0L && now - lastSync < MIN_GAP_MS
    }

    /** 记下这一轮真的跑过了（成功或失败都记），下一轮据此节流。 */
    fun noteAttempt(app: KaoyanWordApp, now: Long = System.currentTimeMillis()) {
        runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_ATTEMPT, now)
                .apply()
        }
    }

    private fun jobScheduler(context: Context): JobScheduler? =
        context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
}

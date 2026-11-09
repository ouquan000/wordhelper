package com.kaoyan.wordhelper.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.kaoyan.wordhelper.KaoyanWordApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台定时同步的 JobService（1.4.7.1）。
 *
 * 只做一件事：调 [com.kaoyan.wordhelper.data.repository.SyncRepository.syncOnce]。
 * 同步逻辑本身、地址候选、口令、游标全在仓库里，这里绝不重复实现。
 *
 * 线程模型：onStartJob 必须马上返回（否则主线程 ANR），所以活丢给 IO 协程，
 * 返回 true 表示「还有活没干完」，干完调 jobFinished。
 *
 * 和前台同步的关系：syncOnce 内部有 mutex，同一时刻只会有一轮在跑；
 * 这里再加一层节流（[BackgroundSync.shouldSkip]），前台刚同步过就直接跳过这一轮。
 */
class BackgroundSyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    /** jobFinished 只能对同一个 JobParameters 调一次，重复调用会被系统记一条警告。 */
    private val finished = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null) return false
        finished.set(false)
        val app = applicationContext as? KaoyanWordApp
        if (app == null) {
            // 理论上不会发生（manifest 指的就是这个 Application），兜底别让任务卡着。
            jobFinished(params, false)
            return false
        }
        // 单独拿一个非空引用给协程用：JobParameters 是平台类型，避免依赖跨 lambda 的 smart cast。
        val jobParams = params
        running?.cancel()
        running = scope.launch {
            var retry = false
            try {
                // 用户在设置里清了地址：撤掉定时任务，别再空转。
                if (!app.syncRepository.hasAddress()) {
                    BackgroundSync.cancel(app)
                    return@launch
                }
                if (BackgroundSync.shouldSkip(app)) return@launch
                BackgroundSync.noteAttempt(app)
                val result = runCatching { app.syncRepository.syncOnce() }.getOrNull()
                // 失败（电脑没开 / 没网 / 口令错）交给系统的退避节奏重试，不在这里死磕。
                retry = result == null || result.isFailure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "background sync failed", t)
                retry = true
            } finally {
                if (finished.compareAndSet(false, true)) jobFinished(jobParams, retry)
            }
        }
        return true
    }

    /**
     * 系统要收回任务（比如突然断网、Doze、别的任务抢占）。
     * 返回 true = 请按退避策略再排一次；当前这轮协程直接取消，别在后台偷偷跑完。
     */
    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        running = null
        // 这里故意不调 jobFinished：任务是被系统打断的，由返回 true 让它重排。
        finished.set(true)
        return true
    }

    override fun onDestroy() {
        running?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BackgroundSyncJob"
    }
}

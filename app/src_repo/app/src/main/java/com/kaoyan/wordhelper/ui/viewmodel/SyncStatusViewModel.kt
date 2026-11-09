package com.kaoyan.wordhelper.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.repository.RewriteSyncResult
import com.kaoyan.wordhelper.data.repository.SyncPhase
import com.kaoyan.wordhelper.data.repository.SyncStatusSnapshot
import com.kaoyan.wordhelper.data.repository.SyncStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「同步状态」页的胶水层（1.4.7）。
 *
 * 这里**不新建同步逻辑**：状态全部从 [SyncStatusStore] 读，「立即重试」调的是仓库里已经存在的
 * 两个公开方法 —— 学习记录 [com.kaoyan.wordhelper.data.repository.SyncRepository.syncOnce]
 * 与记忆思路 [com.kaoyan.wordhelper.data.repository.RewriteRepository.sync]。
 *
 * 重试完把结果按 [SyncPhase] 归纳回 [SyncStatusStore]，状态页与「我的」入口同时跟着变。
 * 401 用 `RewriteRepository.consumeAuthFailure()` 判断（仓库只暴露这一个鉴权信号），
 * 所以这个页面上的重试会把那个「读一次就清」的标记消费掉 —— 在这一页上本来也该由它来提示。
 */
class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val syncRepository = (application as KaoyanWordApp).syncRepository
    private val rewriteRepository = (application as KaoyanWordApp).rewriteRepository

    /** 状态页显示的全部事实。 */
    val status: StateFlow<SyncStatusSnapshot> = SyncStatusStore.state

    /** 重写队列里还没被电脑端处理完的条数；和 [SyncStatusSnapshot.pendingCount]（学习记录）分开算。 */
    val rewritePending: StateFlow<Int> = rewriteRepository.observeOpenCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /** 一次性吐司（"同步完成" / 失败那句话），页面弹完调 [consumeToast]。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        refreshPending()
    }

    /** 只读本地队列，不联网；进页面和重试完各刷一次。 */
    fun refreshPending() {
        viewModelScope.launch {
            val count = runCatching { syncRepository.pendingCount() }.getOrDefault(0)
            SyncStatusStore.reportPending(count)
        }
    }

    /**
     * 「立即重试」：学习记录一轮 + 重写队列一轮。
     *
     * 两条链路互不依赖，任一条抛异常都不该把另一条也跳过；结果最后合并成一个阶段。
     */
    fun retryNow() {
        if (_retrying.value) return
        _retrying.value = true
        SyncStatusStore.report(SyncPhase.CONNECTING)
        viewModelScope.launch {
            try {
                val learning = runCatching { syncRepository.syncOnce() }
                    .getOrElse { Result.failure(it) }
                val rewrite = runCatching { rewriteRepository.sync() }
                    .getOrElse { RewriteSyncResult(error = it.message ?: "重写同步失败") }
                applyResult(learning, rewrite)
                refreshPending()
            } finally {
                _retrying.value = false
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    /**
     * 把两条链路的结果归纳成一个阶段。
     *
     * 判定顺序很重要：先鉴权（401）、再版本（404 / 版本号对不上）、再限流（429），最后才是普通失败。
     * 反过来会把「口令错」说成「电脑没连上」，用户照着做一辈子都连不上（1.4.6 踩过的坑）。
     */
    private fun applyResult(learning: Result<String>, rewrite: RewriteSyncResult) {
        val learningError = learning.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
        val rewriteError = rewrite.error?.takeIf { it.isNotBlank() }
        val errors = listOfNotNull(learningError, rewriteError).distinct()

        if (errors.isEmpty()) {
            val message = learning.getOrNull() ?: "同步完成"
            SyncStatusStore.report(SyncPhase.OK, message = message)
            _toast.value = message
            return
        }

        val httpCode = findHttpCode(errors)
        val unauthorized = rewriteRepository.consumeAuthFailure()
        val phase = when {
            unauthorized || httpCode == HTTP_UNAUTHORIZED -> SyncPhase.UNAUTHORIZED
            httpCode == HTTP_NOT_FOUND || errors.any { it.contains("版本") } -> SyncPhase.SERVER_TOO_OLD
            httpCode == HTTP_TOO_MANY_REQUESTS -> SyncPhase.RATE_LIMITED
            else -> SyncPhase.FAILED
        }
        // 学习记录已经同步成功、只有「重写队列」没发出去：连接本身是好的，不该把整页判成失败。
        // 重写队列本来就有自己的待发计数（状态页的「待重写 N 条」）和重试，不需要再借整体状态吓人。
        if (learning.isSuccess && phase == SyncPhase.FAILED && rewriteError != null) {
            val done = learning.getOrNull() ?: "同步完成"
            val detail = "重写队列没同步成功：$rewriteError"
            SyncStatusStore.report(SyncPhase.OK, message = "$done；$detail")
            _toast.value = detail
            return
        }
        val message = when (phase) {
            SyncPhase.UNAUTHORIZED -> "口令不对，需要重新粘贴连接串"
            SyncPhase.SERVER_TOO_OLD -> "电脑端版本过旧，请重启电脑端"
            SyncPhase.RATE_LIMITED -> "请求太频繁，正在等待"
            else -> errors.joinToString("；")
        }
        // 429 的冷却时间由 SyncRepository 按 Retry-After / 限流窗口算好并写进 store（它才是发请求的那一侧），
        // 这里若传 null 会把倒计时清掉；其他阶段则明确清空，免得状态页一直挂着「马上重试」。
        val activeRetryAt = SyncStatusStore.state.value.retryAtEpochMs
            ?.takeIf { it > System.currentTimeMillis() }
        SyncStatusStore.report(
            phase = phase,
            httpCode = httpCode,
            message = message,
            retryAtEpochMs = if (phase == SyncPhase.RATE_LIMITED) activeRetryAt else null
        )
        _toast.value = message
    }

    /** 仓库的报错里唯一稳定的机器可读位就是「HTTP 4xx」这几个字，没有更结构化的返回值可拿。 */
    private fun findHttpCode(messages: List<String>): Int? =
        messages.firstNotNullOfOrNull { HTTP_CODE.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429

        val HTTP_CODE = Regex("HTTP\\s*(\\d{3})", RegexOption.IGNORE_CASE)
    }
}

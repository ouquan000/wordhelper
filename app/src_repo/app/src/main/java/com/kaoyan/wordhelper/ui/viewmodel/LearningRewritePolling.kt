package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.SyncStatusStore
import com.kaoyan.wordhelper.data.repository.rewriteClientId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REWRITE_FLIP_SYNC_MIN_INTERVAL_MS = 8_000L

private const val STATE_FAILED = "failed"

internal class LearningRewritePolling(
    private val viewModelScope: CoroutineScope,
    private val session: LearningSessionState,
    private val study: LearningStudyFlows,
    private val rewrite: LearningRewriteFlows,
    private val queue: LearningQueueCoordinator,
    private val rewriteRepository: RewriteRepository
) {
    /** 翻面同步的节流时间戳。 */
    private var lastBackFlipSyncAt = 0L

    /** 进度轮询的 job；null = 没在轮询。 */
    private var rewritePollJob: Job? = null

    /** 上一次轮询的是哪个词，用来在换词时把进度清零。 */
    private var polledWordId: Long? = null
    private var polledRequestId: String? = null
    private var missedProgressPolls = 0

    /** 这条标记到了终态还要不要拉一次结果；标记/换词时重新置上。 */
    var pendingTerminalSync = true

    fun observeCurrentWord() {
        // 换词立刻把进度归零：上一条标记的 100% 不属于这张卡
        viewModelScope.launch {
            session._currentWord.map { it?.id }.distinctUntilChanged().collect { wordId ->
                rewrite._rewriteProgress.value = null
                polledWordId = wordId
                pendingTerminalSync = true
            }
        }
    }

    /** 回前台/进学习页：静默补发队列 + 增量拉结果，命中当前词就原地刷新卡背。 */
    fun syncRewrite() {
        viewModelScope.launch { queue.syncRewriteNow() }
    }

    /** 翻到卡背顺手同步一次；8 秒内最多一次，来回翻面不打爆电脑。 */
    fun syncRewriteOnBackFlip() {
        val now = System.currentTimeMillis()
        if (now - lastBackFlipSyncAt < REWRITE_FLIP_SYNC_MIN_INTERVAL_MS) return
        lastBackFlipSyncAt = now
        syncRewrite()
    }

    /**
     * 卡背可见且标记未完成时调用：每 4 秒问一次电脑端进度。
     * 翻回正面、离开学习页、App 进后台由调用方 stop，避免后台空转耗电。
     */
    fun startRewriteProgressPolling() {
        if (rewritePollJob?.isActive == true) return
        val wordId = session._currentWord.value?.id
        if (wordId != null && wordId != polledWordId) {
            // 换词了：上一个词的 100% 不带过来
            polledWordId = wordId
            rewrite._rewriteProgress.value = null
            pendingTerminalSync = true
        }
        rewritePollJob = viewModelScope.launch {
            while (isActive) {
                pollRewriteProgressOnce()
                delay(REWRITE_PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopRewriteProgressPolling() {
        rewritePollJob?.cancel()
        rewritePollJob = null
    }

    private suspend fun pollRewriteProgressOnce() {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        val mark = rewrite.currentRewriteMark.value ?: return
        if (mark.wordId != word.id || (mark.bookId != bookId && mark.bookId != 0L)) return
        val requestId = rewriteClientId(mark)
        if (requestId != polledRequestId) {
            polledRequestId = requestId
            missedProgressPolls = 0
            rewrite._rewriteProgress.value = null
        }
        val snapshot = rewriteRepository.statusOf(mark)
        if (session._currentWord.value?.id != word.id || (study.activeBook.value?.id ?: 0L) != bookId ||
            rewrite.currentRewriteMark.value?.let(::rewriteClientId) != requestId
        ) return
        if (snapshot == null) {
            missedProgressPolls++
            val previous = rewrite._rewriteProgress.value ?: RewriteProgressUi(fraction = null)
            rewrite._rewriteProgress.value = previous.copy(
                offline = rewritePollingOffline(
                    missedProgressPolls, SyncStatusStore.state.value.lastOriginSuccessAt, System.currentTimeMillis()
                )
            )
            return
        }
        missedProgressPolls = 0
        if (snapshot.state == RewriteRepository.STATE_MISSING) {
            rewrite._rewriteProgress.value = RewriteProgressUi(fraction = null)
            return
        }
        val state = snapshot.state.lowercase()
        val stage = snapshot.stage?.lowercase()
        val failed = state == STATE_FAILED || stage == STATE_FAILED
        val previous = rewrite._rewriteProgress.value
        val fraction = when {
            // failed 停在最后一次已知进度上，变红，不回退成 0
            failed -> previous?.fraction
            else -> fractionOfStage(stage) ?: fractionOfState(state) ?: previous?.fraction
        }
        rewrite._rewriteProgress.value = RewriteProgressUi(
            fraction = fraction,
            failed = failed,
            offline = false
        )
        val markStatus = rewrite.currentRewriteMark.value?.status
        val markStillOpen = markStatus != RewriteStatus.DONE && markStatus != RewriteStatus.FAILED
        if ((state == "done" || failed) && (pendingTerminalSync || markStillOpen)) {
            // 终态立刻拉一次结果，把卡背文字 / 标记状态换成最终值。
            // pendingTerminalSync 只在「换了词」时重置，同一个词连续重写第二遍时它是 false，
            // 所以还得看当前标记是否仍未完成——否则第二遍会停在 100% 却不换文。
            queue.syncRewriteNow()
            val after = rewriteRepository.markOf(word.id, bookId)?.status
            pendingTerminalSync = after != RewriteStatus.DONE && after != RewriteStatus.FAILED

        }
    }

    private fun fractionOfStage(stage: String?): Float? = when (stage) {
        "queued" -> 0.08f
        "generate" -> 0.45f
        "polish" -> 0.75f
        "judge" -> 0.92f
        "done" -> 1f
        else -> null
    }

    /** 老服务没有 stage：按 state 粗推断 —— pending 排队、running 已在生成。 */
    private fun fractionOfState(state: String): Float? = when (state) {
        "pending" -> 0.08f
        "running" -> 0.45f
        "done" -> 1f
        else -> null
    }
}

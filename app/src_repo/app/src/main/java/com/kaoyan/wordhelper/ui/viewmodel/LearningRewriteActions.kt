package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.repository.CardbackAuth
import com.kaoyan.wordhelper.data.repository.RewriteMarkPolicy
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 1.4.7v7-bugfix：「跳过」的欠卡没落进库时的用户可见提示（不静默吞掉）。 */
private const val SKIP_PERSIST_FAILED = "跳过没生效：重写记录没存上，请稍后重试"

/** 点击到落库之间这条记录被撤销/换版：不换词，让用户刷新后重试。 */
private const val SKIP_MARK_CHANGED = "重写记录已变化，先刷新一下再试"
private const val SKIP_ALREADY_WAITING = "这张卡已经在等结果了"

/** 结果已经合格：不做「跳过」，这张卡按既有插回规则留在队列里继续背。 */
private const val SKIP_RESULT_READY = "思路已更新，继续背当前词"

internal class LearningRewriteActions(
    private val viewModelScope: CoroutineScope,
    private val session: LearningSessionState,
    private val study: LearningStudyFlows,
    private val rewrite: LearningRewriteFlows,
    private val queue: LearningQueueCoordinator,
    private val polling: LearningRewritePolling,
    private val gate: LearningSubmissionGate,
    private val settingsRepository: SettingsRepository,
    private val rewriteRepository: RewriteRepository
) {
    fun selectRewriteModel(modelId: String) {
        if (modelId.isBlank()) return
        rewrite._rewriteModelId.value = modelId
        viewModelScope.launch { settingsRepository.updateRewriteModel(modelId) }
    }

    /** 点「重写思路」：落本地队列后立刻试发一次，发不出去就等下次同步补发。 */
    fun markRewrite(instruction: String) {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        val modelId = rewrite._rewriteModelId.value.ifBlank { RewriteModels.DEFAULT_ID }
        // 新一次标记：到终态时还要再拉一次结果
        polling.pendingTerminalSync = true
        rewrite._rewriteProgress.value = null
        viewModelScope.launch {
            // 1.4.8v1：不再因为「没填电脑地址」提前 return。enqueueAndSync 内部是
            // 「先 queueDao.upsert 落本地，再 sync() 试发」；离线时 sync 失败但 PENDING
            // 行已经留下，跳过入口才有依据。否则没地址连 mark 都没有，点重写完全没反应。
            // 先确认还是同一张卡；跳过入口现在只由数据库里的 mark 推导，不再锁存。
            if (session._currentWord.value?.id != word.id || (study.activeBook.value?.id ?: 0L) != bookId) return@launch
            // 1.5.2：新一轮重写开始，解除本 session 的「已跳过」过滤，允许同一词反复
            // 「重写 → 跳过 → 回队 → 再重写」无限轮次。
            session.sessionSkippedWordIds.remove(word.id)
            // 1.5.2：先记「本轮开始」，旧 DONE 行在这几毫秒里不能把跳过置灰。
            rewrite._activeRewriteRequestIds.update { it + (word.id to "") }
            // 排队与进度由重写按钮直接反馈。
            val sent = rewriteRepository.enqueueAndSync(
                word.id,
                word.word,
                modelId,
                instruction,
                bookId = bookId
            )
            val roundRequestId = runCatching {
                rewriteRepository.requestIdOf(word.id, bookId)
            }.getOrNull().orEmpty()
            rewrite._activeRewriteRequestIds.update { it + (word.id to roundRequestId) }
            if (!sent) {
                // 没地址/电脑没开都只是「已记下」；只有确实带了口令、且这次请求
                // 真被 401 拦下时，才提示重粘配对行，别把离线误报成口令错。
                val authFailed = rewriteRepository.consumeAuthFailure()
                val hasToken = runCatching {
                    CardbackAuth.headerValue(settingsRepository.settingsFlow.first().cardBackToken) != null
                }.getOrDefault(false)
                if (authFailed && hasToken) gate._message.value = RewriteMarkPolicy.result(
                    sent = false,
                    authFailed = authFailed,
                    hasToken = hasToken,
                    authHint = CardbackAuth.HINT
                )
            }
        }
    }

    fun cancelRewrite() {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        viewModelScope.launch {
            if (!rewriteRepository.cancel(word.id, bookId) && rewrite.currentRewriteMark.value?.status == RewriteStatus.QUEUED) {
                gate._message.value = "未能取消排队"
            }
        }
    }

    fun undoRewrite() {
        val word = session._currentWord.value ?: return
        viewModelScope.launch {
            val bookId = study.activeBook.value?.id ?: 0L
            val restored = rewriteRepository.undo(word.id, bookId)
            if (restored != null) {
                val restoredMethod = rewriteRepository.methodOf(word.id, bookId)
                session.publishWord(session._currentWord.value?.copy(approach = restored, method = restoredMethod))
                gate._message.value = "已撤销回上一版"
            } else {
                gate._message.value = "没有可撤销的上一版了"
            }
        }
    }

    /**
     * 1.4.7.2 W2：清零所有待插回标记的「停止重写」**只留给后端/运维和测试**。
     *
     * 学习页已经移除了这个入口：停掉生成会让欠卡永远背不掉，用户也就永远看不到真实的
     * 「今日完成」。保留它是为了运维排查和契约测试，UI 不再调用。
     * 电脑确认停止后才结清本地队列；连不上就明确告诉调用方没停成。
     */
    fun stopAllRewrites() {
        val book = study.activeBook.value ?: return
        viewModelScope.launch {
            val stopped = rewriteRepository.stopAllRewrites()
            if (!stopped) {
                gate._message.value = "电脑没连上，停止没生效"
                return@launch
            }
            queue.clearWaitingRewrites()
            session.sessionSkippedWordIds.clear()
            gate._message.value = "已停止重写，今天先背这些"
            queue.loadQueueForBook(
                book,
                study.newWordsLimit.value,
                study.shuffleNewWordsEnabled.value,
                study.plannedNewWordsEnabled.value
            )
        }
    }

    /**
     * 1.4.6 H：跳过当前词，先背下一个。
     *
     * 只在「已点重写、结果还没回来」时可用；词不删、今天仍要背。1.4.7.2 W2 起，
     * 生成好后按稳定槽位插回当前卡之后第 2/3 张普通卡之后（见 onWaitingRewriteReady），
     * 且**只有用户真的作答**才清 needs_reinsert —— 跳过不等于背完。
     *
     * 1.4.7v7-bugfix：
     * - 判定与界面同源（[currentRewriteSkipAvailability]），不再出现「按钮亮着点了没反应」；
     * - 与三键/拼写共用 [launchSubmit] 的互斥闸，跳过写库期间不会被一次评分打断；
     * - 只有 `markNeedsReinsert` 与库内回读都确认后才换词，落库失败会明确提示而不是静默返回。
     */
    fun skipCurrentWordForRewrite() {
        val word = session._currentWord.value ?: return
        val book = study.activeBook.value ?: return
        when (skipRewriteAvailability(
            word.id,
            book.id,
            rewrite.currentRewriteMark.value,
            rewrite._activeRewriteRequestIds.value[word.id]
        )) {
            SkipRewriteAvailability.AVAILABLE -> Unit
            SkipRewriteAvailability.RESULT_READY -> {
                // 结果已经合格：这张卡按既有插回规则处理，跳过没有意义也不允许，
                // 直接留在屏上继续背（不换词、不动进度）。
                gate._message.value = SKIP_RESULT_READY
                return
            }
            // 没有可跳过的欠卡（撤销/换词/换书后的旧入口）：按钮状态已收起或置灰，
            // 这里再拦一次，避免把还没背的词误移出队列。
            SkipRewriteAvailability.NO_MARK,
            SkipRewriteAvailability.OTHER_WORD,
            SkipRewriteAvailability.OTHER_BOOK -> return
        }
        // 与三键/拼写作答共用 launchSubmit 的互斥闸：跳过写库期间不会同时开始一次评分，
        // 评分在途时也不会把当前词从队列里拿走；重复点「跳过」同样被这道闸挡住。
        gate.launchSubmit {
            // 先把「欠一次作答」落到当前这本书；落不下去就不换卡，并且要让人看见原因。
            val saved = runCatching { rewriteRepository.markNeedsReinsert(word.id, book.id) }
            if (saved.isFailure) {
                gate._message.value = SKIP_PERSIST_FAILED
                return@launchSubmit
            }
            if (!saved.getOrDefault(false)) {
                // 点下到落库之间行被撤销/删除：不重复入队，也不静默换卡。
                gate._message.value = SKIP_MARK_CHANGED
                return@launchSubmit
            }
            // 以库里的实时行复核标记真的落在这张卡上，确认后才允许移出当前队列。
            val confirmed = runCatching { rewriteRepository.markOf(word.id, book.id) }.getOrNull()
            if (confirmed == null) {
                // 行在这期间被撤销/删除：同样不静默换卡。
                gate._message.value = SKIP_MARK_CHANGED
                return@launchSubmit
            }
            if (!confirmed.needsReinsert) {
                // 刚才置的标记被别的路径清掉（例如已经作答/运维结清）：这次跳过作废，
                // 不换词、不重复入队；合格稿仍按既有插回规则由观察器处理。
                gate._message.value = if (confirmed.status == RewriteStatus.DONE) {
                    SKIP_RESULT_READY
                } else {
                    SKIP_MARK_CHANGED
                }
                return@launchSubmit
            }
            if (study.activeBook.value?.id != book.id || session._currentWord.value?.id != word.id) {
                return@launchSubmit
            }
            // needs_reinsert 已是唯一「还欠一次作答」的依据：pending/queued/failed 都登记等待，
            // 合格后由 observeWaitingRewrite → onWaitingRewriteReady 按稳定槽位插回，
            // 不会重复插回（reinsertingWordIds 去重）。
            //
            // 点下到落库之间刚好变成 DONE 的竞争：这里**允许**这次已经获得资格的跳过成立
            // （needs_reinsert=1 + status=done），observeWaitingRewrite 下一帧就会把它登记成
            // 待插回，按「当前卡之后第 2/3 张普通卡之后」回到队列，不会丢卡也不会重插。
            // 不在这里 clearNeedsReinsert：那会让用户刚点过的跳过直接蒸发，且与「作答才结清」口径冲突。
            // 1.5.2v2：先把上一轮遗留的「待插回」状态清掉。
            // 旧实现里，DONE 的旧稿会留在 pendingReinsertWords，用户重新点重写再点跳过时，
            // 它仍按旧下标 0 插回，表现就是「跳过按了但卡不动」。
            session.forgetPendingReinsert(word.id)
            session.restoredWaitingRewriteWords.remove(word.id)
            val newlyWaiting = session.waitingRewriteWords.putIfAbsent(word.id, word) == null
            // 不论是不是已经在等待集合里，都要把这张卡从本次会话队列里过滤掉。
            session.sessionSkippedWordIds.add(word.id)
            if (newlyWaiting) {
                queue.observeWaitingRewrite(word, book.id)
            }
            session.refreshWaitingCount()
            queue.loadQueueForBook(
                book,
                study.newWordsLimit.value,
                study.shuffleNewWordsEnabled.value,
                study.plannedNewWordsEnabled.value
            )
            // 最后一道保险：loadQueueForBook 期间若有旧观察器把词插回队首，这里强制前移。
            // 只在当前卡仍是它时动手，避免误推用户已经切到的下一张。
            if (session._currentWord.value?.id == word.id) {
                val remaining = session.activeQueue.filterNot { it.id == word.id }
                if (remaining.size != session.activeQueue.size) {
                    session.updateQueue(remaining)
                } else {
                    session.publishWord(remaining.firstOrNull())
                }
            }
            gate._message.value = if (newlyWaiting) {
                "已跳过，结果好了会自动插回来"
            } else {
                SKIP_ALREADY_WAITING
            }
        }
    }
}

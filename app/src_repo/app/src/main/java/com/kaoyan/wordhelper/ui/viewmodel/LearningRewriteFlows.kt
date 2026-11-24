@file:JvmName("LearningViewModelKt")
@file:JvmMultifileClass

package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.data.model.ApproachReview
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.data.model.RewriteModels
import com.kaoyan.wordhelper.data.model.feedbackRatingForRevision
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 卡背重写进度（1.4.3.1）。
 *
 * [fraction] 为 null = 服务端没说清阶段，用不确定进度条；
 * [offline] = 这一轮没连上电脑，界面上提示「等电脑上线」。
 */
data class RewriteProgressUi(
    val fraction: Float?,
    val failed: Boolean = false,
    val offline: Boolean = false
)

internal fun rewritePollingOffline(missed: Int, lastConnectionAt: Long?, now: Long): Boolean =
    missed >= 2 && (lastConnectionAt == null || now - lastConnectionAt > 45_000L)

/**
 * 1.4.7v7-bugfix：当前这张卡能不能「跳过、先背下一个」。
 *
 * 判定只在这里写一次：暴露给界面的 [LearningViewModel.currentRewriteSkipAvailability]
 * 与命令 [LearningViewModel.skipCurrentWordForRewrite] 共用同一份，
 * 避免「按钮亮着、点下去却被 ViewModel 静默 return」这种按了没反应的状态。
 */
internal enum class SkipRewriteAvailability {
    /** 有本词本书记录、且结果还没合格：可以跳过。 */
    AVAILABLE,

    /** 没有任何重写记录（被撤销/取消/同步删掉了）：入口应消失。 */
    NO_MARK,

    /** 记录的词或书跟当前卡对不上（换词/换书后的旧按钮）：直接丢弃。 */
    OTHER_WORD,
    OTHER_BOOK,

    /** 结果已经合格：按既有插回规则处理，跳过没有意义，入口置灰。 */
    RESULT_READY
}

internal fun skipRewriteAvailability(
    wordId: Long?,
    bookId: Long?,
    mark: RewriteQueueItem?,
    activeRequestId: String? = null
): SkipRewriteAvailability {
    if (wordId == null || bookId == null) return SkipRewriteAvailability.OTHER_WORD
    if (mark == null) return SkipRewriteAvailability.NO_MARK
    if (mark.wordId != wordId) return SkipRewriteAvailability.OTHER_WORD
    // book_id = 0 是 16_17 迁移留下的全局行，同 observeMark 的口径，算作当前书。
    if (mark.bookId != 0L && mark.bookId != bookId) return SkipRewriteAvailability.OTHER_BOOK
    // 1.5.3：历史已结清的 DONE 行不能把「跳过」入口永久亮着。
    // null = 当前词没有本轮活跃重写；空串 = 本轮刚点下、requestId 还没落库。
    if (mark.status == RewriteStatus.DONE) {
        if (activeRequestId == null) {
            return SkipRewriteAvailability.NO_MARK
        }
        if (activeRequestId.isBlank()) {
            return SkipRewriteAvailability.AVAILABLE
        }
        return if (mark.requestId == activeRequestId) {
            SkipRewriteAvailability.RESULT_READY
        } else {
            SkipRewriteAvailability.AVAILABLE
        }
    }
    // 旧一轮的 DONE 在新一轮 requestId 已生成时不能挡住入口。
    if (activeRequestId != null && activeRequestId.isNotBlank() && mark.requestId != activeRequestId) {
        return SkipRewriteAvailability.AVAILABLE
    }
    return SkipRewriteAvailability.AVAILABLE
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class LearningRewriteFlows(
    private val viewModelScope: CoroutineScope,
    private val rewriteRepository: RewriteRepository,
    private val database: AppDatabase,
    private val session: LearningSessionState,
    private val study: LearningStudyFlows
) {
    /** 下拉里的模型；电脑端下发，离线/没连上就用内置 5 个。 */
    val _rewriteModels = MutableStateFlow(RewriteModels.DEFAULT)
    val rewriteModels: StateFlow<List<RewriteModel>> = _rewriteModels.asStateFlow()

    val _rewriteModelId = MutableStateFlow(RewriteModels.DEFAULT_ID)
    val rewriteModelId: StateFlow<String> = _rewriteModelId.asStateFlow()

    /** 当前这张卡的重写标记；null = 还没标记过。 */
    val currentRewriteMark: StateFlow<RewriteQueueItem?> = combine(session._currentWord, study.activeBook) { word, book ->
        word?.let { it.id to (book?.id ?: 0L) }
    }.flatMapLatest { key ->
        if (key == null) flowOf(null) else rewriteRepository.observeMark(key.first, key.second)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 1.5.2：本轮重写的 requestId（wordId -> requestId；空串=刚点下还没落库）。 */
    val _activeRewriteRequestIds = MutableStateFlow<Map<Long, String>>(emptyMap())

    /**
     * 1.5.2v2：右侧「跳过」只认数据库里的当前行，不再依赖会话锁存。
     *
     * 旧实现要求 `_skipRewriteLatchedWordId == currentWord.id`；App 重启、进程被回收、
     * 从别的页面回来时这个锁存是空的，但队列行明明还在 queued，于是按钮状态和真实
     * 状态脱节，点了只会弹「已经在等结果」。现在只要当前词有 mark 就能推导：
     * 本轮未完成 -> AVAILABLE；本轮 DONE -> RESULT_READY（保留胶囊但置灰）。
     */
    internal val currentRewriteSkipAvailability: StateFlow<SkipRewriteAvailability> = combine(
        session._currentWord,
        study.activeBook,
        currentRewriteMark,
        _activeRewriteRequestIds
    ) { word, book, mark, activeIds ->
        if (word == null) {
            SkipRewriteAvailability.NO_MARK
        } else {
            skipRewriteAvailability(word.id, book?.id, mark, activeIds[word.id])
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SkipRewriteAvailability.NO_MARK
    )

    /**
     * 当前词的记忆思路，直接来自数据库。
     * 卡背读它而不是读词对象快照：电脑端结果不管被哪一次同步落库，界面都会立刻换文。
     */
    val liveApproach: StateFlow<String?> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(null)
            else rewriteRepository.observeApproach(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 卡背方法标签，同样直接盯库；重写结果落库后胶囊立刻换，不等下一次翻面。 */
    val liveMethod: StateFlow<String?> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(null)
            else rewriteRepository.observeMethod(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 1.5.3v2：当前词有没有「上一版」可恢复（编辑弹窗里的恢复按钮据此亮灭）。 */
    val memoryEditCanRestore: StateFlow<Boolean> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(false)
            else rewriteRepository.observePrevApproach(word.id, bookId).map { !it.isNullOrBlank() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 1.4.7.1：当前这版思路的「未复核」标记（服务端 unverified + reason）。
     * 和 [liveApproach] 用同一套按书回退规则，角标永远跟着正文走；
     * EMPTY = 已复核 / 老数据 / 老服务端，卡背不显示角标。
     */
    val liveApproachReview: StateFlow<ApproachReview> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(ApproachReview.EMPTY)
            else rewriteRepository.observeApproachReview(word.id, bookId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApproachReview.EMPTY)

    /**
     * 1.4.6.1：当前词在当前书里、**当前这一版思路**上的 👍/👎（null = 这一版还没表过态）。
     * 不依赖重写标记：普通卡也有自己的 rev（种子思路 = 0），换词/换书/换版都会自动重订阅。
     * 只拿来把按钮显示成已选状态；写入走 submitRewriteFeedback。
     */
    val currentRewriteFeedback: StateFlow<String?> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) {
                flowOf(null)
            } else {
                combine(
                    rewriteRepository.observeFeedback(word.id, bookId),
                    rewriteRepository.observeApproachRev(word.id, bookId)
                ) { rows, approachRev -> feedbackRatingForRevision(rows, approachRev) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 1.5.2 点赞++：当前这一版思路累计收到的 👍 次数。
     * 换词/换书/换版自动重订阅；连击未落库时由界面再叠加 [pendingLikeBurst]。
     */
    val currentRewriteLikeCount: StateFlow<Int> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) {
                flowOf(0)
            } else {
                combine(
                    rewriteRepository.observeFeedback(word.id, bookId),
                    rewriteRepository.observeApproachRev(word.id, bookId)
                ) { rows, rev ->
                    rows.filter { it.approachRev == rev && it.rating == RewriteFeedbackRating.UP }
                        .sumOf { it.count }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 1.5.3：当前这张卡的「免洗金牌」状态。
     * 锁定后闲时洗词、批量重写和词卡热更都不会覆盖这份助记。
     */
    val currentMemoryLocked: StateFlow<Boolean> = combine(study.activeBook, session._currentWord) { book, word ->
        (book?.id ?: 0L) to word
    }
        .flatMapLatest { (bookId, word) ->
            if (word == null) flowOf(false)
            else database.memoryLockDao().observe(word.id, bookId).map { it?.locked == true }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 卡背上的进度条数据；null = 还没问过电脑端。 */
    val _rewriteProgress = MutableStateFlow<RewriteProgressUi?>(null)
    val rewriteProgress: StateFlow<RewriteProgressUi?> = _rewriteProgress.asStateFlow()

    /** 同步把卡背文字换掉时 +1，界面据此闪 2 秒「已更新」。 */
    val _rewriteUpdatedTick = MutableStateFlow(0)
    val rewriteUpdatedTick: StateFlow<Int> = _rewriteUpdatedTick.asStateFlow()
}

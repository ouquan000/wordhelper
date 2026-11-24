package com.kaoyan.wordhelper.ui.viewmodel

import androidx.room.withTransaction
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.entity.BookMemory
import com.kaoyan.wordhelper.data.entity.MemoryLock
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.repository.FeedbackBurstPolicy
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val LIKE_BURST_DEBOUNCE_MS = FeedbackBurstPolicy.DEBOUNCE_MS

internal class LearningMemoryFeedback<T : Any>(
    private val viewModelScope: CoroutineScope,
    private val session: LearningSessionState,
    private val study: LearningStudyFlows,
    private val rewrite: LearningRewriteFlows,
    private val gate: LearningSubmissionGate,
    private val database: AppDatabase,
    private val rewriteRepository: RewriteRepository,
    private val likeUi: (Long, Int) -> T
) {
    private val _pendingLikeUi = MutableStateFlow(likeUi(0L, 0))
    val pendingLikeBurst: StateFlow<T> = _pendingLikeUi.asStateFlow()

    private data class PendingLikeBurst(
        val wordId: Long,
        val bookId: Long,
        val approachRev: Int,
        val word: String,
        val approach: String,
        val method: String,
        val model: String,
        val instruction: String,
        var delta: Int = 0
    )

    private var pendingLikeBurstData: PendingLikeBurst? = null
    private var pendingLikeJob: Job? = null

    /**
     * 1.4.6：卡背上的 👍/👎。
     * 保存反馈。重写由卡背操作单独提交。
     * 写入永远成功（离线也存本地），联网补发交给 RewriteRepository.sync。
     */
    fun submitRewriteFeedback(
        rating: String,
        instruction: String = "",
        count: Int = 1,
        reasons: List<String> = emptyList(),
        comment: String = ""
    ) {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        viewModelScope.launch {
            runCatching {
                // 以点击这一刻库里的版本为准，别让 StateFlow 的旧值把评价挂到上一版。
                val approachRev = rewriteRepository.approachRevOf(word.id, bookId)
                rewriteRepository.submitFeedback(
                    wordId = word.id,
                    rating = rating,
                    word = word.word,
                    approach = rewrite.liveApproach.value ?: word.approach,
                    method = rewrite.liveMethod.value.orEmpty().ifBlank { word.method },
                    model = rewrite._rewriteModelId.value,
                    instruction = instruction.trim().take(200),
                    bookId = bookId,
                    approachRev = approachRev,
                    count = count,
                    reasons = reasons,
                    comment = comment
                )
            }
        }
    }

    /**
     * 1.5.2 点赞++：连点先在本地累加，停手 1.8 秒或切走卡片时合并成一条反馈。
     * 这样既不会每点一下发一次请求，也不会把连击丢掉。
     */
    fun tapRewriteFeedback(
        rating: String,
        instruction: String = "",
        reasons: List<String> = emptyList(),
        comment: String = ""
    ) {
        if (rating == RewriteFeedbackRating.DOWN) {
            flushPendingLikes()
            submitRewriteFeedback(rating, instruction, reasons = reasons, comment = comment)
            return
        }
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        viewModelScope.launch {
            val rev = rewriteRepository.approachRevOf(word.id, bookId)
            val current = pendingLikeBurstData
            if (current == null ||
                current.wordId != word.id ||
                current.bookId != bookId ||
                current.approachRev != rev
            ) {
                flushPendingLikesNow()
                pendingLikeBurstData = PendingLikeBurst(
                    wordId = word.id,
                    bookId = bookId,
                    approachRev = rev,
                    word = word.word,
                    approach = rewrite.liveApproach.value ?: word.approach,
                    method = rewrite.liveMethod.value.orEmpty().ifBlank { word.method },
                    model = rewrite._rewriteModelId.value,
                    instruction = instruction.trim().take(200)
                )
            }
            val burst = pendingLikeBurstData ?: return@launch
            burst.delta = FeedbackBurstPolicy.nextCount(burst.delta)
            _pendingLikeUi.value = likeUi(burst.wordId, burst.delta)
            pendingLikeJob?.cancel()
            pendingLikeJob = viewModelScope.launch {
                delay(LIKE_BURST_DEBOUNCE_MS)
                flushPendingLikesNow()
            }
        }
    }

    /** 切走当前卡/退出页面时把还没落库的连击补上。 */
    fun flushPendingLikes() {
        viewModelScope.launch { flushPendingLikesNow() }
    }

    private suspend fun flushPendingLikesNow() {
        val burst = pendingLikeBurstData ?: return
        pendingLikeBurstData = null
        _pendingLikeUi.value = likeUi(0L, 0)
        if (burst.delta <= 0) return
        runCatching {
            rewriteRepository.submitFeedback(
                wordId = burst.wordId,
                rating = RewriteFeedbackRating.UP,
                word = burst.word,
                approach = burst.approach,
                method = burst.method,
                model = burst.model,
                instruction = burst.instruction,
                bookId = burst.bookId,
                approachRev = burst.approachRev,
                count = burst.delta
            )
        }
    }

    /**
     * 1.5.3：用户手改当前卡背上的记忆思路。
     * 按书写入 tb_book_memory，并保留上一版供撤销；绝不碰 tb_progress。
     */
    fun saveMemoryEdit(text: String) {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            gate._message.value = "记忆思路不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                database.withTransaction {
                    val now = System.currentTimeMillis()
                    val existing = database.bookMemoryDao().getForBook(word.id, bookId)
                    val global = database.wordMemoryDao().getByWordId(word.id)
                    if (existing != null) {
                        database.bookMemoryDao().upsert(
                            existing.copy(
                                approach = cleaned,
                                prevApproach = existing.approach,
                                prevMethod = existing.method,
                                method = existing.method,
                                updatedAt = now,
                                source = WordMemory.SOURCE_USER,
                                rev = existing.rev + 1,
                                unverified = false,
                                unverifiedReason = null
                            )
                        )
                    } else {
                        database.bookMemoryDao().upsert(
                            BookMemory(
                                wordId = word.id,
                                bookId = bookId,
                                approach = cleaned,
                                collocations = global?.collocations.orEmpty(),
                                method = global?.method.orEmpty(),
                                updatedAt = now,
                                source = WordMemory.SOURCE_USER,
                                rev = (global?.rev ?: 0) + 1,
                                prevApproach = global?.approach,
                                prevMethod = global?.method,
                                unverified = false,
                                unverifiedReason = null
                            )
                        )
                    }
                }
            }.onFailure {
                gate._message.value = "保存失败：${it.message ?: "数据库写入异常"}"
            }
        }
    }

    /** 1.5.3v2：把当前手改思路回退到上一版（撤销胶囊的替代，放在编辑弹窗内）。 */
    fun restoreMemoryEdit() {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        if (bookId <= 0L) return
        viewModelScope.launch {
            runCatching {
                database.withTransaction {
                    val existing = database.bookMemoryDao().getForBook(word.id, bookId) ?: return@withTransaction
                    val prev = existing.prevApproach?.trim().orEmpty()
                    if (prev.isBlank()) return@withTransaction
                    database.bookMemoryDao().upsert(
                        existing.copy(
                            approach = prev,
                            prevApproach = null,
                            prevMethod = null,
                            method = existing.prevMethod ?: existing.method,
                            updatedAt = System.currentTimeMillis(),
                            source = WordMemory.SOURCE_USER,
                            rev = existing.rev + 1,
                            unverified = false,
                            unverifiedReason = null
                        )
                    )
                }
            }.onFailure {
                gate._message.value = "恢复失败：${it.message ?: "数据库写入异常"}"
            }
        }
    }

    /** 1.5.3：免洗金牌开关。本地立即生效，电脑端后台尽力同步。 */
    fun toggleMemoryLock() {
        val word = session._currentWord.value ?: return
        val bookId = study.activeBook.value?.id ?: 0L
        val locked = !rewrite.currentMemoryLocked.value
        viewModelScope.launch {
            runCatching {
                database.memoryLockDao().upsert(
                    MemoryLock(
                        wordId = word.id,
                        bookId = bookId,
                        locked = locked,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onSuccess { lockId ->
                launch {
                    val synced = runCatching {
                        rewriteRepository.setMemoryLock(word.id, bookId, locked)
                    }.getOrDefault(false)
                    if (synced) {
                        // 1.5.3v2 C5：锁已推到电脑端，标记 synced=1，不再重复补发。
                        runCatching { database.memoryLockDao().markSynced(lockId) }
                    }
                }
            }.onFailure {
                gate._message.value = "锁定失败：${it.message ?: "数据库写入异常"}"
            }
        }
    }
}

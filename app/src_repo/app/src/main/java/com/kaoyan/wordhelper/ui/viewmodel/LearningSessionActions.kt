package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Book
import com.kaoyan.wordhelper.data.repository.AddToNewWordsSource
import com.kaoyan.wordhelper.data.repository.CheckInResult
import com.kaoyan.wordhelper.data.repository.WordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class QueueLoadParams(
    val book: Book?,
    val newWordsLimit: Int,
    val shuffleNewWords: Boolean,
    val plannedModeEnabled: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class LearningSessionActions(
    private val viewModelScope: CoroutineScope,
    private val repository: WordRepository,
    private val session: LearningSessionState,
    private val study: LearningStudyFlows,
    private val queue: LearningQueueCoordinator,
    private val gate: LearningSubmissionGate
) {
    fun observeQueue() {
        viewModelScope.launch {
            combine(
                study.activeBook,
                study.newWordsLimit,
                study.shuffleNewWordsEnabled,
                study.todayNewWordsPlan,
                study.plannedNewWordsEnabled
            ) { book, limit, shuffle, _, plannedEnabled ->
                QueueLoadParams(
                    book = book,
                    newWordsLimit = limit,
                    shuffleNewWords = shuffle,
                    plannedModeEnabled = plannedEnabled
                )
            }.collect { params ->
                if (session.pendingFailedWordId != null) return@collect
                queue.loadQueueForBook(
                    book = params.book,
                    newWordsLimit = params.newWordsLimit,
                    shuffleNewWords = params.shuffleNewWords,
                    plannedModeEnabled = params.plannedModeEnabled
                )
            }
        }
        viewModelScope.launch {
            repository.getNewWordIdsFlow().collect {
                if (session.pendingFailedWordId != null) return@collect
                val book = study.activeBook.value
                if (book?.type == Book.TYPE_NEW_WORDS) {
                    queue.loadQueueForBook(book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value, study.plannedNewWordsEnabled.value)
                }
            }
        }
        viewModelScope.launch {
            study.activeBook
                .flatMapLatest { book ->
                    if (book == null) {
                        flowOf(0)
                    } else {
                        repository.getEarlyReviewCountFlow(book.id)
                    }
                }
                .collect {
                    if (session.pendingFailedWordId != null) return@collect
                    val book = study.activeBook.value
                    if (book != null) {
                        queue.loadQueueForBook(book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value, study.plannedNewWordsEnabled.value)
                    }
                }
        }
    }

    fun switchBook(bookId: Long) {
        session.immediateRetryQueue.clear()
        session.sessionSkippedWordIds.clear()
        queue.clearWaitingRewrites()
        session.queueBookId = null
        session.pendingFailedWordId = null
        session.answeredInSession = 0
        viewModelScope.launch {
            repository.switchBook(bookId)
        }
    }

    fun toggleNewWord() {
        val word = session._currentWord.value ?: return
        viewModelScope.launch {
            val exists = repository.isInNewWords(word.id)
            if (exists) {
                repository.removeFromNewWords(word.id)
            } else {
                repository.addToNewWords(word.id, AddToNewWordsSource.BUTTON)
            }
        }
    }

    fun refreshQueue() {
        val book = study.activeBook.value ?: return
        viewModelScope.launch {
            session.pendingFailedWordId = null
            // 手动刷新不能把正在等重写的词放回来，否则同一张卡会在队列里出现两次。
            val stillWaiting = session.waitingRewriteWords.keys.toSet() + session.restoredWaitingRewriteWords.keys
            session.sessionSkippedWordIds.clear()
            session.sessionSkippedWordIds.addAll(stillWaiting)
            session.answeredInSession = 0
            queue.loadQueueForBook(book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value, study.plannedNewWordsEnabled.value)
        }
    }

    fun disperseReviewPressure() {
        val book = study.activeBook.value ?: return
        if (book.type == Book.TYPE_NEW_WORDS) return
        if (!study.reviewPressureReliefEnabled.value) return
        if (gate.isSubmitting.value) return
        val dailyCap = study.reviewPressureDailyCap.value.coerceAtLeast(1)
        gate.launchSubmit {
            val movedCount = withContext(Dispatchers.IO) {
                repository.disperseReviewPressure(book.id, dailyCap)
            }
            gate._message.value = if (movedCount > 0) {
                "已分散 $movedCount 个待复习单词到后续天"
            } else {
                "当前待复习数量未超过上限"
            }
            session.pendingFailedWordId = null
            queue.loadQueueForBook(book, study.newWordsLimit.value, study.shuffleNewWordsEnabled.value, study.plannedNewWordsEnabled.value)
        }
    }

    fun clearMessage() {
        gate._message.value = null
    }

    fun completeTodayCheckIn() {
        gate.launchSubmit {
            // 打卡入口只有完成页这一张卡，但仓库层 checkInToday() 只认学习活动数，
            // 不认重写欠卡。这里补最小保护：今天还有没背完的重写卡就不许打卡。
            // （直接调仓库的测试/未来入口仍可绕过，见 W2 报告的收口建议。）
            if (study.todayPendingRewriteCount.value > 0) {
                gate._message.value = "还有重写卡没背完，背完再打卡"
                return@launchSubmit
            }
            when (withContext(Dispatchers.IO) { repository.checkInToday() }) {
                CheckInResult.SUCCESS -> gate._message.value = "打卡成功，明天继续保持"
                CheckInResult.ALREADY_CHECKED_IN -> gate._message.value = "今天已经打过卡了"
                CheckInResult.NOT_ELIGIBLE -> gate._message.value = "请先完成今日背词再打卡"
            }
        }
    }
}

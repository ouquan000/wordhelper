@file:JvmName("LearningViewModelKt")
@file:JvmMultifileClass

package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.DailyStatsAggregate
import com.kaoyan.wordhelper.util.DateUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class LearningCheckInUiState(
    val showCompletionPanel: Boolean = false,
    val checkedInToday: Boolean = false,
    val streakDays: Int = 0,
    val weekCheckInDays: Int = 0,
    val todayStudyCount: Int = 0,
    val todayCheckInTimeText: String = ""
)

/**
 * 1.4.7.2 W2 完成判定。
 *
 * [pendingRewriteCount] = **今天发起、且用户还没实际作答**的重写卡数（needs_reinsert=1 且
 * 创建学习日 == 今天）。发送成功 / 生成完成 / 合格 / 已插回 / 退出学习页都不清这个标记，
 * 只有用户真的点了三键之一（或判定太简单、拼写作答）才清 —— 所以它空着才算今天背完。
 * 昨天没背完的重写卡不阻塞今天，仍留在库里等待正常派发。
 */
internal fun shouldShowCompletionCheckInPanel(
    currentWord: Word?,
    totalCount: Int,
    pendingRewriteCount: Int = 0
): Boolean {
    return currentWord == null && totalCount > 0 && pendingRewriteCount <= 0
}

/** 等待卡条数：本地等待集合与「今天仍未作答的重写卡」取最大值，既不漏也不重复计数。 */
internal fun combinedWaitingRewriteCount(localWaitingCount: Int, pendingTodayCount: Int): Int =
    maxOf(localWaitingCount.coerceAtLeast(0), pendingTodayCount.coerceAtLeast(0))

/**
 * 今天发起、且还没被用户实际作答的重写卡数。
 *
 * 「发起学习日」= [RewriteQueueItem.createdAt] 落在哪个学习日（4 点换日，见 DateUtils），
 * 不用 status —— pending/queued 只代表电脑端还在生成，failed 只代表这次没生成成功，
 * 三者都跟用户有没有背过无关；只有作答路径会清 needs_reinsert。
 */
internal fun countPendingRewritesForLearningDate(
    rows: List<RewriteQueueItem>,
    today: LocalDate = DateUtils.currentLearningDate()
): Int = rows.count { row ->
    row.needsReinsert &&
        DateUtils.currentLearningDate(row.createdAt) == today
}

internal fun buildLearningCheckInUiState(
    weekAggregates: List<DailyStatsAggregate>,
    heatmapAggregates: List<DailyStatsAggregate>,
    currentWord: Word?,
    totalCount: Int,
    pendingRewriteCount: Int = 0,
    today: LocalDate = DateUtils.currentLearningDate()
): LearningCheckInUiState {
    val todayAggregate = weekAggregates.firstOrNull { parseLearningAggregateDate(it.date) == today }
    val todayCheckedIn = (todayAggregate?.checkInCount ?: 0) > 0
    val weekCheckInDays = weekAggregates.count { it.checkInCount > 0 }
    val checkInDates = heatmapAggregates.filter { it.checkInCount > 0 }
        .mapNotNull { parseLearningAggregateDate(it.date) }
        .toSet()
    val streakDays = computeLearningCheckInStreak(today, checkInDates)
    val todayStudyCount = todayAggregate?.let {
        it.newWordsCount + it.reviewWordsCount + it.spellPracticeCount
    } ?: 0
    val todayCheckInTimeText = if (todayCheckedIn && (todayAggregate?.lastCheckInTime ?: 0L) > 0L) {
        DateUtils.formatDateTime(todayAggregate?.lastCheckInTime ?: 0L)
    } else {
        ""
    }
    return LearningCheckInUiState(
        showCompletionPanel = shouldShowCompletionCheckInPanel(
            currentWord = currentWord,
            totalCount = totalCount,
            pendingRewriteCount = pendingRewriteCount
        ),
        checkedInToday = todayCheckedIn,
        streakDays = streakDays,
        weekCheckInDays = weekCheckInDays,
        todayStudyCount = todayStudyCount,
        todayCheckInTimeText = todayCheckInTimeText
    )
}

private fun parseLearningAggregateDate(raw: String): LocalDate? {
    return runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}

private fun computeLearningCheckInStreak(today: LocalDate, checkInDates: Set<LocalDate>): Int {
    var streak = 0
    var cursor = today
    while (checkInDates.contains(cursor)) {
        streak += 1
        cursor = cursor.minusDays(1)
    }
    return streak
}

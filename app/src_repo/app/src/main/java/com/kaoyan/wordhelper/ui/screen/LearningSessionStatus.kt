package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.ui.viewmodel.LearningCheckInUiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel

@Composable
internal fun ColumnScope.LearningEmptyContent(
    waitingRewriteCount: Int,
    checkInState: LearningCheckInUiState,
    viewModel: LearningViewModel
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center
    ) {
        if (waitingRewriteCount > 0) {
            LearningWaitingRewriteCard(
                count = waitingRewriteCount,
                onRefresh = viewModel::syncRewrite,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("learning_waiting_rewrite_card")
            )
        } else if (checkInState.showCompletionPanel) {
            LearningCompletionCheckInCard(
                state = checkInState,
                onCheckIn = viewModel::completeTodayCheckIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("learning_completion_checkin_card")
            )
        } else {
            Text(text = "暂无单词", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * H：当天词背完、但今天发起过的重写卡还没被实际背掉时，不显示「完成」，显示这张等待卡。
 *
 * 1.4.7.2 W2：学习页不再提供「停止重写」入口 —— 停掉生成会让欠卡永远背不掉，
 * 用户也就永远看不到真实完成。停止能力只留在后端/运维侧。
 */
@Composable
private fun LearningWaitingRewriteCard(
    count: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Text("还有 $count 张重写卡没背完", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "正在生成或等待重试；合格一张就插回一张，背完今天才算完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRefresh) {
                Text("现在刷新")
            }
        }
    }
}

@Composable
private fun LearningCompletionCheckInCard(
    state: LearningCheckInUiState,
    onCheckIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "今日背词完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = if (state.checkedInToday) {
                    if (state.todayCheckInTimeText.isNotBlank()) {
                        "今日已打卡 · ${state.todayCheckInTimeText}"
                    } else {
                        "今日已打卡"
                    }
                } else {
                    "今天的学习任务已经完成，点击完成今日打卡。"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("learning_completion_checkin_status")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CompletionMetric(label = "今日学习", value = state.todayStudyCount.toString(), unit = "词")
                CompletionMetric(label = "连续打卡", value = state.streakDays.toString(), unit = "天")
                CompletionMetric(label = "本周打卡", value = state.weekCheckInDays.toString(), unit = "天")
            }
            FilledTonalButton(
                onClick = onCheckIn,
                enabled = !state.checkedInToday,
                modifier = Modifier.testTag("learning_completion_checkin_button")
            ) {
                Text(text = if (state.checkedInToday) "今日已打卡" else "完成今日打卡")
            }
        }
    }
}

@Composable
private fun CompletionMetric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReviewPressureReliefBanner(
    dueReviewCount: Int,
    dailyCap: Int,
    onRelieve: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
        ),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "今日待复习 $dueReviewCount，超过上限 $dailyCap",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "点击后会把超出部分均匀顺延到后续天。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            OutlinedButton(onClick = onRelieve, enabled = enabled) {
                Text(text = "分散压力")
            }
        }
    }
}

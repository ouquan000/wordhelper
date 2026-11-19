package com.kaoyan.wordhelper.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * FSRS-5 记忆状态卡片（1.4.8）。
 *
 * 只展示由 [com.kaoyan.wordhelper.ui.viewmodel.buildFsrsInsights] 算好的聚合值，
 * 不在 Composable 里做任何统计，保证与单测口径一致。
 */
@Composable
fun FsrsStatsCard(
    averageRetrievability: Float,
    averageStability: Float,
    dueCount: Int,
    lapseCount: Int,
    nextIntervalDays: Int,
    desiredRetention: Float,
    hasData: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stats_fsrs_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "FSRS-5 记忆状态",
                style = MaterialTheme.typography.titleLarge
            )

            if (!hasData) {
                Text(
                    text = "暂无复习记录，先学几张卡",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val animatedRetrievability by animateFloatAsState(
                targetValue = averageRetrievability.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 600),
                label = "fsrsRetrievability"
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "平均提取概率 R",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "${(animatedRetrievability * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LinearProgressIndicator(
                    progress = { animatedRetrievability },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FsrsStatItem(
                    label = "平均稳定性 S",
                    value = "${String.format(Locale.US, "%.1f", averageStability)} 天"
                )
                FsrsStatItem(label = "今日到期", value = "$dueCount")
                FsrsStatItem(label = "累计遗忘", value = "$lapseCount")
            }

            Text(
                text = "按 ${(desiredRetention * 100).roundToInt()}% 留存率，平均下次间隔约 $nextIntervalDays 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FsrsStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

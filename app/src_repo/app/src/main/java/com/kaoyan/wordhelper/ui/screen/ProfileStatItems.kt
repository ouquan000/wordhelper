package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.MethodCount

@Composable
internal fun ProfileStatItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(text = unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ProfileEmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 「按记忆方法」分布：主招 × 词数，按多少降序，最多 6 个。没有已学词就整块不出现。 */
@Composable
internal fun ProfileMethodBreakdown(counts: List<MethodCount>) {
    // 分子分母必须同口径：只统计有标签的词，没标签的单独报一句，否则「加起来对不上 N」像丢数据。
    val labeled = counts.filter { it.method.isNotBlank() && it.count > 0 }
    val total = labeled.sumOf { it.count }
    if (total <= 0) return
    val unlabeled = counts.filter { it.method.isBlank() }.sumOf { it.count }
    val parts = labeled
        .sortedByDescending { it.count }
        .take(6)
        .map { "${methodLabelOfProfile(it.method)} ${it.count}" }
    if (parts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "按记忆方法（已标注 $total 词）",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (unlabeled > 0) {
            Text(
                text = "另有 $unlabeled 词还没有标签（重写思路后会补上）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 方法键 → 中文标签；这里只用于统计展示，卡背上的胶囊另有实现。 */
private fun methodLabelOfProfile(method: String): String = when (method) {
    "sound" -> "谐音"
    "meaning" -> "联想"
    "shape" -> "字形"
    "root" -> "词根"
    "contrast" -> "对比"
    "polysemy" -> "多义"
    else -> method
}

package com.kaoyan.wordhelper.ui.screen

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal data class RewriteBarPresentation(
    val label: String,
    val fill: Float? = null,
    val error: String? = null
)

internal fun rewriteBarPresentation(
    mark: RewriteQueueItem?,
    progress: RewriteProgressUi?
): RewriteBarPresentation = when (mark?.status) {
    RewriteStatus.PENDING -> RewriteBarPresentation("待上传", fill = 0.04f)
    RewriteStatus.QUEUED -> {
        val fill = (progress?.fraction ?: 0.08f).coerceIn(0f, 1f)
        val label = when {
            progress?.offline == true -> "等待电脑上线"
            fill <= 0.08f -> "排队中"
            else -> "生成中 ${(fill * 100f).roundToInt()}%"
        }
        RewriteBarPresentation(label, fill = fill)
    }
    RewriteStatus.FAILED -> RewriteBarPresentation("重写思路", error = mark.error?.trim()?.takeIf { it.isNotEmpty() })
    else -> RewriteBarPresentation("重写思路")
}

internal fun formatRewriteTime(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

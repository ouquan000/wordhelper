package com.kaoyan.wordhelper.ui.screen

import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.RewriteStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.5.3：RewriteBar 的唯一状态展示契约。
 *
 * 之前 PENDING/QUEUED 同时画进度条文案和元数据行文案，真机上出现
 * 「已送电脑 / 已记下，等电脑上线 / 电脑已收到 · 排队中」三行互搏。
 * 这里把「哪一行显示什么、进度条是否出现、撤销是否出现」收敛成一个纯函数，
 * UI 只负责渲染，JVM 单测可以直接钉住互斥关系。
 */
internal data class RewriteBarPresentation(
    val statusLine: String,
    val showProgress: Boolean,
    val showUndo: Boolean
)

internal fun rewriteBarPresentation(
    mark: RewriteQueueItem?,
    hasProgress: Boolean,
    justUpdated: Boolean
): RewriteBarPresentation {
    if (mark == null) {
        return RewriteBarPresentation("", false, false)
    }
    if (justUpdated) {
        return RewriteBarPresentation(
            statusLine = "已更新",
            showProgress = false,
            showUndo = mark.status == RewriteStatus.DONE
        )
    }
    return when (mark.status) {
        RewriteStatus.PENDING -> RewriteBarPresentation(
            statusLine = "已标记 · 待上传",
            // 待上传阶段没有真实生成进度；即使拿到旧 progress 也不显示第二条文案。
            showProgress = false,
            showUndo = false
        )
        RewriteStatus.QUEUED -> RewriteBarPresentation(
            // 有真实进度时，进度条自己会显示「排队中」/百分比，状态行只报「电脑已收到」，
            // 不再出现「电脑已收到 · 排队中」与进度条「排队中」两行同义文案。
            statusLine = if (hasProgress) "电脑已收到" else "电脑已收到 · 排队中",
            // 1.5.3v2：QUEUED 且拿到 progress 时，ProgressRow 是活代码——它把 0.08/0.45/0.75/0.92
            // 的阶段进度真实画出来，而不是永远压在「排队中」文案后面。
            showProgress = hasProgress,
            showUndo = false
        )
        RewriteStatus.DONE -> RewriteBarPresentation(
            statusLine = "上次重写 ${formatRewriteTime(mark.updatedAt)}",
            showProgress = false,
            showUndo = true
        )
        RewriteStatus.FAILED -> RewriteBarPresentation(
            statusLine = "上次没成功：${mark.error ?: "未知原因"}",
            showProgress = false,
            showUndo = false
        )
        else -> RewriteBarPresentation("", false, false)
    }
}

internal fun formatRewriteTime(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}


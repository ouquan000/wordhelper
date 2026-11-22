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
            statusLine = "电脑已收到 · 排队中",
            // QUEUED 阶段只保留这一行状态；进度百分比等运行态由后续状态切换承载，
            // 避免再出现「排队中」与「电脑已收到 · 排队中」两行同义文案。
            showProgress = false,
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


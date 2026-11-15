package com.kaoyan.wordhelper.data.repository

internal data class RewriteResultCursor(val seq: Long, val streamId: String?)

internal data class RewriteCursorBinding(
    val cursor: RewriteResultCursor,
    val rewind: Boolean,
    val replaceSkipped: Boolean
)

/** An empty page can still be from a new stream whose first result reused the old seq. */
internal fun RewriteResultCursor.bind(
    reportedStream: String?,
    highWater: Long?,
    legacyNext: Long?
): RewriteCursorBinding {
    val stream = reportedStream?.takeIf { it.isNotBlank() }
    val streamChanged = stream != null && stream != streamId
    val rolledBack = seq > 0 && (highWater ?: legacyNext)?.let { it in 0 until seq } == true
    val rewind = seq > 0 && (streamChanged || rolledBack)
    return RewriteCursorBinding(
        cursor = RewriteResultCursor(if (rewind) 0L else seq, stream ?: streamId),
        rewind = rewind,
        // A first bind to an upgraded server might still have valid deferred results.
        replaceSkipped = (streamChanged && streamId != null) || rolledBack
    )
}

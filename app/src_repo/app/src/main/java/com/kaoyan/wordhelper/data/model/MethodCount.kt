package com.kaoyan.wordhelper.data.model

/**
 * 「按记忆方法」的聚合行：method 是 WordMemory.method 的英文键（sound / meaning / shape /
 * root / contrast / polysemy），空串表示这个词还没有落到方法标签（老数据或用户自建）。
 *
 * 只用于统计展示，条数是「词」不是「次」。
 */
data class MethodCount(
    val method: String = "",
    val count: Int = 0
)

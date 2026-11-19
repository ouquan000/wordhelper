package com.kaoyan.wordhelper.data.model

enum class CardMotionMode(val id: String, val label: String, val detail: String) {
    FULL("full", "完整模式", "材质流光、姿态光影与烟雾极光"),
    LIGHT("light", "轻量模式", "保留姿态光影，减弱流光，背景缓慢渐变"),
    STATIC("static", "静态模式", "背景和材质静止，轻触仍可翻卡");

    companion object {
        fun fromId(id: String?): CardMotionMode = entries.firstOrNull { it.id == id } ?: FULL
    }
}

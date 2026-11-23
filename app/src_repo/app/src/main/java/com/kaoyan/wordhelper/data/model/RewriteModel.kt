package com.kaoyan.wordhelper.data.model

/**
 * 重写模型（1.4.3）。
 *
 * 列表由电脑端 `/models` 下发，手机只存 id/显示名，不显示推理倍率。
 * [DEFAULT] 是电脑没连上时的兜底，与 tools/rewrite-service/providers.py 的常量保持一致。
 */
data class RewriteModel(
    val id: String,
    val name: String,
    val provider: String = "",
    val reasoning: String = ""
)

object RewriteModels {
    const val DEFAULT_ID = "ds-v41-flash"

    val DEFAULT: List<RewriteModel> = listOf(
        RewriteModel(id = "ds-v41-flash", name = "DeepSeek V4.1 Flash", provider = "wb", reasoning = "max"),
        RewriteModel(id = "ds-v4-pro", name = "DeepSeek V4 Pro", provider = "wb", reasoning = "high"),
        RewriteModel(id = "glm53", name = "GLM 5.3", provider = "wb", reasoning = "high"),
        RewriteModel(id = "minimax-m31-flash", name = "MiniMax M3.1 Flash", provider = "wb", reasoning = "high")
    )

    fun nameOf(models: List<RewriteModel>, id: String): String {
        return models.firstOrNull { it.id == id }?.name
            ?: DEFAULT.firstOrNull { it.id == id }?.name
            ?: id
    }
}

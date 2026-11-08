package com.kaoyan.wordhelper.data.model

import com.google.gson.Gson

/**
 * 卡背结构清单（1.4.2）。
 *
 * 契约见 docs/cardback-schema-1.4.2.md。这里只做「解析 + 归一化 + 校验」，
 * 不含任何 Android 依赖，方便单测。
 *
 * 设计要点：
 * - 未知块类型不抛错，解析成 type = null，渲染时跳过（1.4.3 的 REWRITE_BUTTON 就是这么进来的）。
 * - style 的 color / size 都是「可缺省的覆盖项」，缺省 = 该块类型的原生样式，
 *   这样默认清单渲染出来的样子与 1.4.1 完全一致。
 * - 任何一份清单只要「一个块都渲染不出来」就整份拒收，保证卡背永远不空白。
 */
enum class CardBackBlockType(val key: String) {
    TITLE("TITLE"),
    PHONETIC("PHONETIC"),
    DIVIDER("DIVIDER"),
    MEANING("MEANING"),
    MEMORY("MEMORY"),
    COLLOCATIONS("COLLOCATIONS"),
    EXAMPLE("EXAMPLE"),
    PHRASES("PHRASES"),
    SYNONYMS("SYNONYMS"),
    REL_WORDS("REL_WORDS"),
    EF_STATS("EF_STATS");

    /** 该块的默认标题；null 表示这个类型天生没有标题。 */
    val defaultTitle: String?
        get() = when (this) {
            MEANING -> "释义"
            MEMORY -> "记忆思路"
            COLLOCATIONS -> "常用搭配"
            EXAMPLE -> "例句"
            PHRASES -> "短语"
            SYNONYMS -> "近义词"
            REL_WORDS -> "同根词"
            EF_STATS -> ""
            else -> null
        }

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String?): CardBackBlockType? =
            key?.trim()?.uppercase()?.let { byKey[it] }

        val allowedKeys: List<String> = entries.map { it.key }
    }
}

/** 颜色只下发语义名，绝不下发 hex——否则暗色模式会糊。 */
enum class CardBackColor(val key: String) {
    DEFAULT("default"),
    PRIMARY("primary"),
    SECONDARY("secondary"),
    ACCENT("accent"),
    MUTED("muted"),
    WARNING("warning");

    companion object {
        fun fromKey(key: String?): CardBackColor {
            val raw = key?.trim()
            return entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: DEFAULT
        }

        val allowedKeys: List<String> = entries.map { it.key }
    }
}

/** 字号档；null = 用该块的原生字号。 */
enum class CardBackSize(val key: String) {
    S("S"),
    M("M"),
    L("L");

    companion object {
        fun fromKey(key: String?): CardBackSize? {
            val raw = key?.trim()
            return entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }
        }

        val allowedKeys: List<String> = entries.map { it.key }
    }
}

data class CardBackBlockStyle(
    val color: CardBackColor = CardBackColor.DEFAULT,
    val size: CardBackSize? = null
)

data class CardBackBlock(
    val id: String,
    /** null = 手机还不认识这个类型，渲染时跳过。 */
    val type: CardBackBlockType?,
    val rawType: String,
    val visible: Boolean = true,
    /** null = 用类型默认标题；"" = 不显示标题。 */
    val title: String? = null,
    val style: CardBackBlockStyle = CardBackBlockStyle()
) {
    /** 实际要显示的标题；null = 不显示。 */
    val resolvedTitle: String?
        get() = when (title) {
            null -> type?.defaultTitle
            else -> title
        }
}

data class CardBackMotion(
    val titleDurationMs: Int = 820,
    val staggerGapMs: Int = 90,
    val entryDurationMs: Int = 220,
    val overshootPercent: Int = 72,
    val overshootPx: Float = -1f,
    val overshootScale: Float = 1.0015f
)

data class CardBackConfig(
    val schemaVersion: Int = 1,
    val book: String? = null,
    val revision: Int = 0,
    val updatedAt: String? = null,
    val spacingDp: Int = 12,
    val motion: CardBackMotion = CardBackMotion(),
    val blocks: List<CardBackBlock> = emptyList()
) {
    /** 实际会被画出来的块：可见 + 类型认识。 */
    val renderableBlocks: List<CardBackBlock> = blocks.filter { it.visible && it.type != null }

    /** 被跳过的未知类型（用于给用户一句提示）。 */
    val unknownTypes: List<String>
        get() = blocks.filter { it.type == null }.map { it.rawType }.distinct()
}

sealed interface CardBackDecodeResult {
    data class Ok(val config: CardBackConfig) : CardBackDecodeResult
    data class Rejected(val reason: String) : CardBackDecodeResult
}

object CardBackCodec {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val FILENAME = "cardback.json"
    const val LAST_GOOD_FILENAME = "cardback.lastgood.json"

    private val gson = Gson()

    // Gson 走反射赋值，不会执行 Kotlin 默认值，所以这里全部可空，再统一归一化。
    private data class RawStyle(val color: String? = null, val size: String? = null)

    private data class RawBlock(
        val id: String? = null,
        val type: String? = null,
        val visible: Boolean? = null,
        val title: String? = null,
        val style: RawStyle? = null
    )

    private data class RawMotion(
        val titleDurationMs: Double? = null,
        val staggerGapMs: Double? = null,
        val entryDurationMs: Double? = null,
        val overshootPercent: Double? = null,
        val overshootPx: Double? = null,
        val overshootScale: Double? = null
    )

    private data class RawConfig(
        val schemaVersion: Int? = null,
        val book: String? = null,
        val revision: Int? = null,
        val updatedAt: String? = null,
        val spacing: Int? = null,
        val motion: RawMotion? = null,
        val blocks: List<RawBlock>? = null
    )

    fun decode(json: String?): CardBackDecodeResult {
        if (json.isNullOrBlank()) return CardBackDecodeResult.Rejected("卡背结构内容为空")

        val raw = try {
            gson.fromJson(json, RawConfig::class.java)
        } catch (e: Exception) {
            return CardBackDecodeResult.Rejected("不是合法的 JSON：${e.message ?: "解析失败"}")
        } ?: return CardBackDecodeResult.Rejected("不是合法的 JSON 对象")

        val version = raw.schemaVersion
            ?: return CardBackDecodeResult.Rejected("缺少 schemaVersion")
        if (version < 1) return CardBackDecodeResult.Rejected("schemaVersion 必须 >= 1")
        if (version > SUPPORTED_SCHEMA_VERSION) {
            return CardBackDecodeResult.Rejected(
                "这份结构是 $version 版做的，当前 App 只认 ${SUPPORTED_SCHEMA_VERSION} 版"
            )
        }

        val rawBlocks = raw.blocks
        if (rawBlocks.isNullOrEmpty()) return CardBackDecodeResult.Rejected("清单里一个块都没有")

        val usedIds = HashSet<String>(rawBlocks.size)
        val blocks = rawBlocks.mapIndexed { index, item ->
            val rawType = item.type?.trim().orEmpty()
            val type = CardBackBlockType.fromKey(rawType)
            var id = item.id?.trim().orEmpty()
            if (id.isEmpty()) id = "block-$index"
            // id 去重：重复的补后缀，保证 LazyColumn 的 key 唯一
            var unique = id
            var n = 2
            while (!usedIds.add(unique)) {
                unique = "$id-$n"
                n++
            }
            CardBackBlock(
                id = unique,
                type = type,
                rawType = rawType.ifEmpty { "UNKNOWN" },
                visible = item.visible ?: true,
                title = item.title,
                style = CardBackBlockStyle(
                    color = CardBackColor.fromKey(item.style?.color),
                    size = CardBackSize.fromKey(item.style?.size)
                )
            )
        }

        if (blocks.none { it.visible && it.type != null }) {
            return CardBackDecodeResult.Rejected("所有块都被隐藏或不认识，卡背会一片空白")
        }

        val motion = raw.motion
        return CardBackDecodeResult.Ok(
            CardBackConfig(
                schemaVersion = version,
                book = raw.book,
                revision = raw.revision ?: 0,
                updatedAt = raw.updatedAt,
                spacingDp = (raw.spacing ?: 12).coerceIn(4, 32),
                motion = CardBackMotion(
                    titleDurationMs = positiveInt(motion?.titleDurationMs, 820),
                    staggerGapMs = positiveInt(motion?.staggerGapMs, 90),
                    entryDurationMs = positiveInt(motion?.entryDurationMs, 220),
                    overshootPercent = positiveInt(motion?.overshootPercent, 72).coerceIn(1, 99),
                    overshootPx = (motion?.overshootPx ?: -1.0).toFloat(),
                    overshootScale = (motion?.overshootScale ?: 1.0015).toFloat().takeIf { it > 0f } ?: 1f
                ),
                blocks = blocks
            )
        )
    }

    private fun positiveInt(value: Double?, fallback: Int): Int {
        if (value == null || value.isNaN() || value <= 0.0) return fallback
        return value.toInt().coerceIn(1, 5000)
    }
}

package com.kaoyan.wordhelper.data.model

/**
 * 内置默认卡背清单 = 1.4.1 卡背的原样。
 *
 * 这份东西有两个用途：
 * 1. 首次启动 / 本地没有任何缓存时的兜底；
 * 2. 「恢复默认」按钮把清单重置回这里。
 *
 * 注意：改动这里就等于改动「默认长什么样」，改之前先确认 1.4.1 基线截图。
 */
object CardBackDefaults {

    const val DEFAULT_JSON: String = """
{
  "schemaVersion": 1,
  "book": "六级记忆卡",
  "revision": 0,
  "updatedAt": "",
  "spacing": 12,
  "motion": {
    "titleDurationMs": 820,
    "staggerGapMs": 90,
    "entryDurationMs": 220,
    "overshootPercent": 72,
    "overshootPx": -1,
    "overshootScale": 1.0015
  },
  "blocks": [
    { "id": "word",         "type": "TITLE",        "visible": true },
    { "id": "phonetic",     "type": "PHONETIC",     "visible": true },
    { "id": "divider-1",    "type": "DIVIDER",      "visible": true },
    { "id": "meaning",      "type": "MEANING",      "visible": true },
    { "id": "memory",       "type": "MEMORY",       "visible": true },
    { "id": "collocations", "type": "COLLOCATIONS",  "visible": true },
    { "id": "example",      "type": "EXAMPLE",      "visible": true },
    { "id": "phrases",      "type": "PHRASES",      "visible": true },
    { "id": "synonyms",     "type": "SYNONYMS",     "visible": true },
    { "id": "rel-words",    "type": "REL_WORDS",    "visible": true }
  ]
}
"""

    val config: CardBackConfig by lazy {
        when (val result = CardBackCodec.decode(DEFAULT_JSON)) {
            is CardBackDecodeResult.Ok -> result.config
            is CardBackDecodeResult.Rejected ->
                // 内置清单解析不出来说明代码本身坏了，给一份最小可用结构，绝不白屏。
                CardBackConfig(
                    blocks = listOf(
                        CardBackBlock("word", CardBackBlockType.TITLE, "TITLE"),
                        CardBackBlock("meaning", CardBackBlockType.MEANING, "MEANING")
                    )
                )
        }
    }
}

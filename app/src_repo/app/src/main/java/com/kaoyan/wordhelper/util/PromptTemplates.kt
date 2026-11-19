package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.model.AIContentType

object PromptTemplates {

    private const val MEMORY_AID_PERSONA =
        "你是坐在用户旁边的聪明室友：只默认对方认识初高中核心熟词（cat / he / run / cut / cure / mini / sub 这一档），四级词一律当陌生词，严禁拿生僻希腊 / 拉丁词根（polein / olesc / skept / radi / sui 这类）充数。"

    private const val MEMORY_AID_VOICE_RULES =
        "说人话，禁报幕：第一句直接亮抓手，不许“先看 / 因此选择 / 把抽象具体化为 / 整体来看”这种开场；禁翻译腔：不许出现 体现 / 表明 / 呈现 / 构成 / 意味着 / 进行 / 使得 / 对于…而言 / 通过…来 / 在…的情况下 / 核心意象 / 整体绑定 / 形成对照。"

    fun systemInstruction(type: AIContentType): String {
        return when (type) {
            AIContentType.EXAMPLE -> {
                "你是考研英语助教。必须严格按用户给定模板输出，不得添加额外说明、寒暄或总结。"
            }

            AIContentType.MEMORY_AID -> {
                MEMORY_AID_PERSONA + MEMORY_AID_VOICE_RULES +
                    "只输出 3–5 行短句，最后一行必须是“——> 中文释义”；不要“词根词缀 / 联想记忆 / 复习提示”三段标题，不要解释、寒暄或总结。"
            }

            AIContentType.SENTENCE -> {
                "你是英语长难句解析助手。必须严格输出“句子主干/语法成分标注/中文翻译”三段，不得增加其他标题。"
            }

            AIContentType.WORD_TRANSLATION -> {
                "你是考研英语词义助手。必须严格输出“中文翻译/词性”两段，不得添加额外说明。"
            }
        }
    }

    fun build(type: AIContentType, query: String): String {
        return when (type) {
            AIContentType.EXAMPLE -> buildExamplePrompt(query)
            AIContentType.MEMORY_AID -> buildMemoryAidPrompt(query)
            AIContentType.SENTENCE -> buildSentencePrompt(query)
            AIContentType.WORD_TRANSLATION -> buildWordTranslationPrompt(query)
        }
    }

    private fun buildExamplePrompt(word: String): String {
        return """
            请围绕单词 "$word" 生成 2 个考研阅读风格英文例句，严格按以下格式输出：
            【例句1】
            <英文例句>
            【翻译1】
            <中文翻译>
            【例句2】
            <英文例句>
            【翻译2】
            <中文翻译>

            规则：
            1. 英文例句控制在 12-24 个词。
            2. 中文翻译准确、自然。
            3. 只输出上述模板，不要添加解释。
        """.trimIndent()
    }

    private fun buildMemoryAidPrompt(word: String): String {
        return """
            请为单词 "$word" 写一张 App 卡背：你就是坐在用户旁边的聪明室友，只讲能让他当场记住的几句话。

            $MEMORY_AID_PERSONA
            $MEMORY_AID_VOICE_RULES

            【选招优先级：从上往下挑，能上一档就不用下一档】
            T0 一跳谐音：读音 → 一个中文谐音 → 画面；必须对得上音标，不硬掰。
            T1 初高中熟词拼装 / 透明词根：拿初高中熟词拼，如 diminish 用 mini 的 min，random 用 ran + dom。
            T2 真宿敌对比：只对考纲内真实易混词用，如 adapt / adopt；没有就不许硬拉形近词凑数。
            T3 扎眼字形：最多拆 3 块，禁止单字母算命；拼写与读音对不上时必须提醒。
            T4 熟词生义：从熟悉的物理动作推到抽象引申。
            T5 保底画面：画面必须和读音或拼写焊死，遮住英文还能反推出这个词。

            【输出格式】
            1. 3–5 行短句，一行一步；别写小作文，也别为凑行数灌水。
            2. 第一句直接亮抓手，禁报幕：不许“先看 / 因此选择 / 把抽象具体化为 / 整体来看”这种开场。
            3. 最后一行必须是 `——> 中文释义`，释义要具体、对得上这个词。
            4. 不要“词根词缀 / 联想记忆 / 复习提示”三段标题，不要按字数硬压或为凑字数砍内容。
            5. 只输出这 3–5 行，不要解释、前言或总结。
        """.trimIndent()
    }

    private fun buildSentencePrompt(sentence: String): String {
        return """
            请解析下面英语长难句：
            "$sentence"
            请严格按以下格式输出，不要添加额外说明：
            ## 句子主干
            （一句话概括主谓宾核心结构）
            ## 语法成分标注
            （按短语/从句分点解释其语法功能）
            ## 中文翻译
            （给出自然、通顺的中文翻译）
            不得输出其他标题、前言或总结。
        """.trimIndent()
    }

    private fun buildWordTranslationPrompt(word: String): String {
        return """
            请为单词 "$word" 给出常见中文释义，严格按以下格式输出：
            【中文翻译】
            <给出 1-3 个常见义项，使用“；”分隔>
            【词性】
            <常见词性缩写，如 n./v./adj./adv.；不确定时写“未标注”>

            规则：
            1. 只输出上述模板，不要添加解释。
            2. 中文翻译尽量简洁，适合背词场景。
        """.trimIndent()
    }
}

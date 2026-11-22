package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryPosTagger] 的规则测试。
 *
 * 数据取自真实种子库 content/seed/cet6_cards.json（correlate / exist / extract / increase），
 * 断言只看「贴不贴标、贴了什么、原文有没有被改坏」。
 */
class MemoryPosTaggerTest {

    /** 真实数据：v. 三个义项 + n. 一个义项，正文是纯中文描述型。 */
    private val correlateMeaning = "v. 关联；使相互关联；相关；使有相互关系\nn. 相关物"

    /** 真实数据：只有一个词性。 */
    private val existMeaning = "v. 存在；生存；出现；发生"

    /** 跨词性共用义项「摘录」：必须 v./n. 都算合法。 */
    private val extractMeaning = "v. 摘录；提取；n. 摘录；选段"

    private val increaseMeaning = "v. 增加；提高\nn. 增长；增量"

    private val correlateApproach = "① 想两个同学的成绩一起涨、一起跌，老师指着曲线说这两件事 correlate，彼此跟着动。\n" +
        "② 再想天气越热冰淇淋卖得越多，你把两个数据摆在一张表上找对应关系，就是 correlate。\n" +
        "③ 动词是“使相互关联、相关”，名词 correlate 就是那个能对上号的“相关物”。"

    @Test
    fun correlate_meaning里识别出v和n两个合法词性() {
        assertEquals(setOf("v.", "n."), MemoryPosTagger.legalPositions(correlateMeaning))
    }

    @Test
    fun correlate_中文描述型正文只在写了词性词的那一行拆出v和n() {
        val tagged = MemoryPosTagger.tag(correlateApproach, correlateMeaning)
        // ①② 全是场景描述、没有任何词性信号 → 留白；③ 写了「动词」「名词」→ 拆两段。
        assertEquals(4, tagged.size)
        assertNull(tagged[0].pos)
        assertNull(tagged[1].pos)
        assertEquals("v.", tagged[2].pos)
        assertEquals("③ 动词是“使相互关联、相关”，", tagged[2].text)
        assertEquals("n.", tagged[3].pos)
        assertEquals("名词 correlate 就是那个能对上号的“相关物”。", tagged[3].text)
    }

    @Test
    fun correlate_箭头后的义项串按meaning顺序分组成v和n() {
        val body = "老师指着两条曲线说 → 关联；使相互关联；相关；相关物"
        val tagged = MemoryPosTagger.tag(body, correlateMeaning)
        assertEquals(2, tagged.size)
        assertEquals("v.", tagged[0].pos)
        assertEquals("老师指着两条曲线说 → 关联；使相互关联；相关；", tagged[0].text)
        assertEquals("n.", tagged[1].pos)
        assertEquals("相关物", tagged[1].text)
    }

    @Test
    fun exist_箭头收口义项同族时整行贴v且不拆行() {
        val body = "画面中她和木柱一起留在原处，木柱清楚地立着，突出“确实存在”——> 存在；生存；出现"
        val tagged = MemoryPosTagger.tag(body, existMeaning)
        assertEquals(1, tagged.size)
        assertEquals("v.", tagged[0].pos)
        // ——> 归一成 →，其余一字不动。
        assertEquals(
            "画面中她和木柱一起留在原处，木柱清楚地立着，突出“确实存在”→ 存在；生存；出现",
            tagged[0].text
        )
    }

    @Test
    fun 显式token_点号后直接跟中文或引号也认得并且给出染色区间() {
        val tagged = MemoryPosTagger.tag("这个词 n.“相关物”指能对上号的东西", correlateMeaning)
        assertEquals(1, tagged.size)
        assertEquals("n.", tagged[0].pos)
        assertEquals(listOf(PosSpan(4, 6)), tagged[0].inlineSpans)
    }

    @Test
    fun extract_跨词性共用义项不因为去重丢掉n() {
        assertEquals(setOf("v.", "n."), MemoryPosTagger.legalPositions(extractMeaning))
        val body = "① 从书里抽出重点，v. 摘录\n② 抽出来的那一段本身，n. 摘录"
        val tagged = MemoryPosTagger.tag(body, extractMeaning)
        assertEquals(2, tagged.size)
        assertEquals("v.", tagged[0].pos)
        assertEquals("n.", tagged[1].pos)
        // 同一个义项「摘录」在两个词性下都留着。
        assertTrue(tagged.all { it.text.endsWith("摘录") })
    }

    @Test
    fun increase_两个词性各占一行时分别贴标() {
        val body = "① 价格一路往上走，v. 增加、提高。\n② 涨出来的那一截本身，n. 增长、增量。"
        val tagged = MemoryPosTagger.tag(body, increaseMeaning)
        assertEquals(2, tagged.size)
        assertEquals("v.", tagged[0].pos)
        assertEquals("n.", tagged[1].pos)
    }

    @Test
    fun 混合句_按逗号切开且原文顺序与文字都不变() {
        val body = "名词是“相关物”，动词是“使关联”。"
        val tagged = MemoryPosTagger.tag(body, correlateMeaning)
        assertEquals(2, tagged.size)
        assertEquals("n.", tagged[0].pos)
        assertEquals("名词是“相关物”，", tagged[0].text)
        assertEquals("v.", tagged[1].pos)
        assertEquals("动词是“使关联”。", tagged[1].text)
        // 拆开再拼回去必须还是原句（一字不差）。
        assertEquals(body, tagged.joinToString("") { it.text })
    }

    @Test
    fun 及物与不及物动词分别贴vt和vi() {
        val meaning = "vt. 使相互关联\nvi. 相互关联"
        val tagged = MemoryPosTagger.tag("及物动词用法是“把 A 和 B 连起来”，不及物动词用法是“两者自己关联”。", meaning)
        assertEquals(2, tagged.size)
        assertEquals("vt.", tagged[0].pos)
        assertEquals("vi.", tagged[1].pos)
    }

    @Test
    fun 一行只有一种词性时多写几遍也不拆行() {
        val body = "① 先是动词，后面还是动词。"
        val tagged = MemoryPosTagger.tag(body, existMeaning)
        assertEquals(1, tagged.size)
        assertEquals("v.", tagged[0].pos)
        assertEquals(body, tagged[0].text)
    }

    @Test
    fun 没有信号或判不准时一律留白() {
        // 纯场景描述
        assertNull(MemoryPosTagger.tag("① 想两个同学的成绩一起涨、一起跌。", correlateMeaning).single().pos)
        // 有箭头但收口义项在 meaning 里查不到
        assertNull(MemoryPosTagger.tag("突出的就是 → 某种没说清的收口", correlateMeaning).single().pos)
        // 段落编号本身不是信号
        assertNull(MemoryPosTagger.tag("④ 画面里只有编号，没有词性。", correlateMeaning).single().pos)
    }

    @Test
    fun meaning里没有的词性不能贴() {
        // correlate 只有 v./n.，正文写了「副词」「形容词」也不认。
        val tagged = MemoryPosTagger.tag("副词用法很少见，形容词倒是常见。", correlateMeaning)
        assertEquals(1, tagged.size)
        assertNull(tagged[0].pos)
    }

    @Test
    fun meaning为空时一个标都不贴() {
        assertTrue(MemoryPosTagger.tag("v. 增加；n. 增长", "").all { it.pos == null })
        assertTrue(MemoryPosTagger.legalPositions("").isEmpty())
    }

    @Test
    fun 正文一个字都不丢() {
        val bodies = listOf(
            correlateApproach,
            "老师指着两条曲线说 → 关联；使相互关联；相关；相关物",
            "画面中她和木柱一起留在原处，突出“确实存在”——> 存在；生存；出现",
            "名词是“相关物”，动词是“使关联”。",
            "① 从书里抢出重点，v. 摘录\n② 抽出来的那一段本身，n. 摘录"
        )
        for (body in bodies) {
            val tagged = MemoryPosTagger.tag(body, correlateMeaning)
            val expected = body.split("\n")
                .map { line -> line.replace(Regex("""(?:—+|－+|-+)>"""), "→").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("")
            assertEquals(expected, tagged.joinToString("") { it.text })
        }
    }
}

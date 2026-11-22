package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryPosTagger] 的规则测试（1.5.3 语义：一行进、一行出，词性只做行内小标）。
 *
 * 数据取自真实种子库 content/seed/cet6_cards.json（correlate / exist / extract / increase /
 * appropriate），断言只看四件事：一行有没有被拆、贴了什么词性、小标落在哪个区间、
 * 原文有没有被改坏。
 */
class MemoryPosTaggerTest {

    /** 真实数据：v. 三个义项 + n. 一个义项，正文是纯中文描述型。 */
    private val correlateMeaning = "v. 关联；使相互关联；相关；使有相互关系\nn. 相关物"

    /** 真实数据：只有一个词性。 */
    private val existMeaning = "v. 存在；生存；出现；发生"

    /** 跨词性共用义项「摘录」：必须 v./n. 都算合法。 */
    private val extractMeaning = "v. 摘录；提取；n. 摘录；选段"

    private val increaseMeaning = "v. 增加；提高\nn. 增长；增量"

    /** 真实数据：appropriate 只有 adj. 一个词性，三个义项。 */
    private val appropriateMeaning = "adj. 恰当的；合适的；适当的"

    private val correlateApproach = "① 想两个同学的成绩一起涨、一起跌，老师指着曲线说这两件事 correlate，彼此跟着动。\n" +
        "② 再想天气越热冰淇淋卖得越多，你把两个数据摆在一张表上找对应关系，就是 correlate。\n" +
        "③ 动词是“使相互关联、相关”，名词 correlate 就是那个能对上号的“相关物”。"

    /** 真实数据：appropriate 的记忆思路，四行纯场景描述，一个词性词都没写。 */
    private val appropriateApproach = "① 想象你去参加婚礼，试衣服时挑了一件花衬衫。\n" +
        "② 你换到第三套，一件素色长裙，站在镜子前觉得“就是它”。\n" +
        "③ 旁边人说 That's appropriate，就是这套正合适。\n" +
        "④ 所以 appropriate 不是“最好”，而是“对场合刚刚好”。"

    // ---------------------------------------------------------------- 基础识别

    @Test
    fun correlate_meaning里识别出v和n两个合法词性() {
        assertEquals(setOf("v.", "n."), MemoryPosTagger.legalPositions(correlateMeaning))
    }

    @Test
    fun correlate_中文描述型正文一行进一行出且第三行同时嵌v和n两枚小标() {
        val tagged = MemoryPosTagger.tag(correlateApproach, correlateMeaning)

        // 1.5.3 的硬要求：正文三行就是三条，绝不因为一行里有两个词性就拆行。
        assertEquals(3, tagged.size)
        assertEquals(appropriateLineOf(correlateApproach, 3), tagged[2].text)

        // ①② 没有任何词性信号：留白、一个小标都没有。
        assertNull(tagged[0].pos)
        assertTrue(tagged[0].badges.isEmpty())
        assertNull(tagged[1].pos)
        assertTrue(tagged[1].badges.isEmpty())

        // ③ 行内两种词性并存 → 整行 pos 留 null（不猜），但两枚小标都要落在行内。
        assertNull(tagged[2].pos)
        assertEquals(listOf("v.", "n."), tagged[2].badges.map { it.pos })

        // 小标顶替的是「动词」「名词」这两个中文词性词本身。
        assertEquals("动词", tagged[2].text.substring(tagged[2].badges[0].start, tagged[2].badges[0].end))
        assertEquals("名词", tagged[2].text.substring(tagged[2].badges[1].start, tagged[2].badges[1].end))
        assertEquals(tagged[2].text.indexOf("动词"), tagged[2].badges[0].start)
        assertEquals(tagged[2].text.indexOf("名词"), tagged[2].badges[1].start)
    }

    @Test
    fun correlate_箭头后的义项串按词性分组且不劈成两行() {
        val body = "老师指着两条曲线说 → 关联；使相互关联；相关；相关物"
        val tagged = MemoryPosTagger.tag(body, correlateMeaning)

        // 关键回归点：1.5.2v2 会把这一行拦腰劈成「…相关；」+「相关物」两行。
        assertEquals(1, tagged.size)
        assertEquals(body, tagged[0].text)
        assertNull(tagged[0].pos)

        assertEquals(listOf("v.", "n."), tagged[0].badges.map { it.pos })
        // 义项串里的小标是「插在分组前面」，不删正文：start == end。
        tagged[0].badges.forEach { mark -> assertEquals(mark.start, mark.end) }
        assertEquals(body.indexOf("关联"), tagged[0].badges[0].start)
        assertEquals(body.indexOf("相关物"), tagged[0].badges[1].start)
        assertTrue(tagged[0].text.substring(tagged[0].badges[0].start).startsWith("关联"))
        assertTrue(tagged[0].text.substring(tagged[0].badges[1].start).startsWith("相关物"))
    }

    @Test
    fun exist_箭头收口义项同族时整行贴v且只插一枚小标() {
        val body = "画面中她和木柱一起留在原处，木柱清楚地立着，突出“确实存在”——> 存在；生存；出现"
        val tagged = MemoryPosTagger.tag(body, existMeaning)

        assertEquals(1, tagged.size)
        assertEquals("v.", tagged[0].pos)
        assertEquals(1, tagged[0].badges.size)
        // ——> 归一成 →，其余一字不动。
        assertEquals(
            "画面中她和木柱一起留在原处，木柱清楚地立着，突出“确实存在”→ 存在；生存；出现",
            tagged[0].text
        )
        // 引号内部的“确实存在”不参与分组，小标只插在箭头之后的那一串义项前面。
        val arrowAt = tagged[0].text.indexOf('→')
        assertEquals(tagged[0].text.indexOf("存在", arrowAt), tagged[0].badges[0].start)
        assertTrue(tagged[0].badges[0].start > arrowAt)
    }

    @Test
    fun 显式token_就地换成小标且区间正好盖住token本身() {
        val body = "这个词 n.“相关物”指能对上号的东西"
        val tagged = MemoryPosTagger.tag(body, correlateMeaning)

        assertEquals(1, tagged.size)
        assertEquals(body, tagged[0].text)
        assertEquals("n.", tagged[0].pos)
        assertEquals(1, tagged[0].badges.size)
        assertEquals(PosMark(4, 6, "n."), tagged[0].badges[0])
        assertEquals("n.", body.substring(4, 6))
    }

    // ---------------------------------------------------------------- 多词性

    @Test
    fun extract_多词性各自贴标且跨词性共用义项不因为去重丢掉n() {
        assertEquals(setOf("v.", "n."), MemoryPosTagger.legalPositions(extractMeaning))

        val body = "① 从书里抽出重点，v. 摘录\n② 抽出来的那一段本身，n. 摘录"
        val tagged = MemoryPosTagger.tag(body, extractMeaning)

        assertEquals(2, tagged.size)
        assertEquals(listOf("v.", "n."), tagged.map { it.pos })
        assertEquals(1, tagged[0].badges.size)
        assertEquals(1, tagged[1].badges.size)
        // 小标替换掉的正是正文里写着的显式 token。
        assertEquals("v.", tagged[0].text.substring(tagged[0].badges[0].start, tagged[0].badges[0].end))
        assertEquals("n.", tagged[1].text.substring(tagged[1].badges[0].start, tagged[1].badges[0].end))
        // 同一个义项「摘录」在两个词性下都留着。
        assertTrue(tagged.all { it.text.endsWith("摘录") })
    }

    @Test
    fun increase_两个词性各占一行时分别贴标且原文不动() {
        val body = "① 价格一路往上走，v. 增加、提高。\n② 涨出来的那一截本身，n. 增长、增量。"
        val tagged = MemoryPosTagger.tag(body, increaseMeaning)

        assertEquals(2, tagged.size)
        assertEquals(listOf("v.", "n."), tagged.map { it.pos })
        assertEquals(listOf("v."), tagged[0].badges.map { it.pos })
        assertEquals(listOf("n."), tagged[1].badges.map { it.pos })
        // 原文一个字都不删，小标只是把小标自己嵌进去。
        assertTrue(tagged[0].text.contains("v. 增加、提高。"))
        assertTrue(tagged[1].text.contains("n. 增长、增量。"))
    }

    @Test
    fun 混合句_一行里两个词性都给小标但整行不拆() {
        val body = "名词是“相关物”，动词是“使关联”。"
        val tagged = MemoryPosTagger.tag(body, correlateMeaning)

        assertEquals(1, tagged.size)
        assertEquals(body, tagged[0].text)
        assertNull(tagged[0].pos)
        assertEquals(listOf("n.", "v."), tagged[0].badges.map { it.pos })
        assertEquals(body.indexOf("名词"), tagged[0].badges[0].start)
        assertEquals(body.indexOf("动词"), tagged[0].badges[1].start)
    }

    @Test
    fun 及物与不及物动词分别贴vt和vi() {
        val meaning = "vt. 使相互关联\nvi. 相互关联"
        val body = "及物动词用法是“把 A 和 B 连起来”，不及物动词用法是“两者自己关联”。"
        val tagged = MemoryPosTagger.tag(body, meaning)

        assertEquals(1, tagged.size)
        assertEquals(listOf("vt.", "vi."), tagged[0].badges.map { it.pos })
        // 「不及物动词」不能被「动词」抢走。
        assertEquals("及物动词", body.substring(tagged[0].badges[0].start, tagged[0].badges[0].end))
        assertEquals("不及物动词", body.substring(tagged[0].badges[1].start, tagged[0].badges[1].end))
    }

    // ---------------------------------------------------------------- appropriate / 留白

    @Test
    fun appropriate_四行段落没有任何信号时一行一条全部留白() {
        val tagged = MemoryPosTagger.tag(appropriateApproach, appropriateMeaning)

        assertEquals(4, tagged.size)
        assertTrue(tagged.all { it.pos == null })
        assertTrue(tagged.all { it.badges.isEmpty() })
        // 编号与整段文字都在，一行都没被拆。
        assertEquals(appropriateLineOf(appropriateApproach, 1), tagged[0].text)
        assertEquals(appropriateLineOf(appropriateApproach, 4), tagged[3].text)
    }

    @Test
    fun appropriate_箭头后的三个义项同属adj时只插一枚小标() {
        val body = "④ 所以 appropriate 不是“最好”，而是“对场合刚刚好”→ 恰当的；合适的；适当的"
        val tagged = MemoryPosTagger.tag(body, appropriateMeaning)

        assertEquals(1, tagged.size)
        assertEquals(body, tagged[0].text)
        assertEquals("adj.", tagged[0].pos)
        assertEquals(listOf("adj."), tagged[0].badges.map { it.pos })
        assertEquals(body.indexOf("恰当的"), tagged[0].badges[0].start)
        // 三块义项同词性 → 合并成一组，只插一枚，不重复插三枚。
        assertEquals(1, tagged[0].badges.size)
    }

    @Test
    fun 一行只有一种词性时多写几遍也只按出现位置各贴一枚() {
        val body = "① 先是动词，后面还是动词。"
        val tagged = MemoryPosTagger.tag(body, existMeaning)

        assertEquals(1, tagged.size)
        assertEquals("v.", tagged[0].pos)
        assertEquals(body, tagged[0].text)
        assertEquals(listOf("v.", "v."), tagged[0].badges.map { it.pos })
    }

    @Test
    fun 没有信号或判不准时一律留白() {
        // 纯场景描述
        val plain = MemoryPosTagger.tag("① 想两个同学的成绩一起涨、一起跌。", correlateMeaning).single()
        assertNull(plain.pos)
        assertTrue(plain.badges.isEmpty())
        // 有箭头但收口义项在 meaning 里查不到
        val unknown = MemoryPosTagger.tag("突出的就是 → 某种没说清的收口", correlateMeaning).single()
        assertNull(unknown.pos)
        assertTrue(unknown.badges.isEmpty())
        // 段落编号本身不是信号
        val numbered = MemoryPosTagger.tag("④ 画面里只有编号，没有词性。", correlateMeaning).single()
        assertNull(numbered.pos)
        assertTrue(numbered.badges.isEmpty())
    }

    @Test
    fun meaning里没有的词性不能贴() {
        // correlate 只有 v./n.，正文写了「副词」「形容词」也不认。
        val tagged = MemoryPosTagger.tag("副词用法很少见，形容词倒是常见。", correlateMeaning)
        assertEquals(1, tagged.size)
        assertNull(tagged[0].pos)
        assertTrue(tagged[0].badges.isEmpty())
    }

    @Test
    fun meaning为空时一个标都不贴() {
        val tagged = MemoryPosTagger.tag("v. 增加；n. 增长", "")
        assertTrue(tagged.all { it.pos == null })
        assertTrue(tagged.all { it.badges.isEmpty() })
        assertTrue(MemoryPosTagger.legalPositions("").isEmpty())
    }

    // ---------------------------------------------------------------- 保底：原文不许改坏

    @Test
    fun 正文一个字都不丢() {
        val bodies = listOf(
            correlateApproach,
            "老师指着两条曲线说 → 关联；使相互关联；相关；相关物",
            "画面中她和木柱一起留在原处，突出“确实存在”——> 存在；生存；出现",
            "名词是“相关物”，动词是“使关联”。",
            "① 从书里抢出重点，v. 摘录\n② 抽出来的那一段本身，n. 摘录",
            appropriateApproach
        )
        for (body in bodies) {
            val tagged = MemoryPosTagger.tag(body, correlateMeaning)
            val expected = body.split("\n")
                .map { line -> line.replace(Regex("""(?:—+|－+|-+)>"""), "→").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("")
            assertEquals(expected, tagged.joinToString("") { it.text })
            // 1.5.3 起「行数 == 非空行数」，任何一行都不许被拆成两条。
            assertEquals(expected, tagged.joinToString("") { it.text })
            assertEquals(body.split("\n").count { it.isNotBlank() }, tagged.size)
        }
    }

    /** 取正文的第 [index] 行（1-based，已 trim），避免在断言里手抄长句。 */
    private fun appropriateLineOf(body: String, index: Int): String =
        body.split("\n")[index - 1].trim()
}

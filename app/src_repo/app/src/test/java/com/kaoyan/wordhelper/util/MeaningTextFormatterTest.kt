package com.kaoyan.wordhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningTextFormatterTest {

    /** 清洗后的真实数据（create） */
    private val createCleaned = "v. 创造；创建；引起；任命；建造"

    /** 清洗前的真实数据（create），同一个词性前缀写了三遍、引起出现两次 */
    private val createLegacy = "v. 创造；v. 创建；v. 引起；任命；建造；引起"

    @Test
    fun create_rendersAsOneVerbLineWithSinglePrefix() {
        val lines = MeaningTextFormatter.displayLines(createCleaned)
        assertEquals(1, lines.size)
        assertEquals("v.", lines[0].pos)
        assertEquals("创造；创建；引起；任命；建造", lines[0].text)
        assertEquals(1, lines[0].text.split("；").count { it == "引起" })
        assertEquals("v. 创造；创建；引起；任命；建造", MeaningTextFormatter.displayText(createCleaned))
    }

    @Test
    fun create_legacyPrefixesAndDuplicateCollapseToTheSameLine() {
        assertEquals(
            listOf(MeaningLine("v.", "创造；创建；引起；任命；建造")),
            MeaningTextFormatter.displayLines(createLegacy)
        )
        assertEquals(
            MeaningTextFormatter.displayLines(createCleaned),
            MeaningTextFormatter.displayLines(createLegacy)
        )
    }

    @Test
    fun bishop_society_keepExactlyOnePrefixPerPos() {
        val expectedText = listOf(
            "主教；象；热果子酒",       // bishop
            "社会；社团；协会；交往；社交界" // society
        )
        val legacy = listOf(
            "n. 主教；n. 象；热果子酒；象",
            "n. 社会；n. 社团；n. 协会；交往；社交界；社团"
        )
        legacy.forEachIndexed { index, meaning ->
            val lines = MeaningTextFormatter.displayLines(meaning)
            assertEquals(1, lines.size)
            assertEquals("n.", lines[0].pos)
            assertEquals(expectedText[index], lines[0].text)
            assertEquals("n. " + expectedText[index], MeaningTextFormatter.displayText(meaning))
        }
    }

    @Test
    fun conduct_keepsOnePrefixPerBlockAndBothPos() {
        val meaning = "v. 实施；开展；v. 指挥；v. 传导；为人；管理\nn. 行为；举动；指导"
        assertEquals(
            listOf(
                MeaningLine("v.", "实施；开展；指挥；传导；为人；管理"),
                MeaningLine("n.", "行为；举动；指导")
            ),
            MeaningTextFormatter.displayLines(meaning)
        )
        assertEquals(
            "v. 实施；开展；指挥；传导；为人；管理\nn. 行为；举动；指导",
            MeaningTextFormatter.displayText(meaning)
        )
    }

    @Test
    fun spare_keepsOnePrefixPerPosAcrossThreeBlocks() {
        val meaning = "v. 抽出；腾出；空闲的；备用的；v. 饶恕；放过\n" +
            "n. 剩余；备用品；备件；备用零件；备用轮胎\n" +
            "adj. 多余的；备用的；空闲的；节约的；瘦的"
        assertEquals(
            listOf(
                MeaningLine("v.", "抽出；腾出；空闲的；备用的；饶恕；放过"),
                MeaningLine("n.", "剩余；备用品；备件；备用零件；备用轮胎"),
                MeaningLine("adj.", "多余的；备用的；空闲的；节约的；瘦的")
            ),
            MeaningTextFormatter.displayLines(meaning)
        )
    }

    @Test
    fun extract_keepsTheSameGlossUnderBothPos() {
        val meaning = "v. 提取；拔出；提炼；采掘；摘录；取出\nn. 榨出物；精汁；摘录；选段"
        val lines = MeaningTextFormatter.displayLines(meaning)
        assertEquals(2, lines.size)
        assertEquals("v.", lines[0].pos)
        assertEquals("n.", lines[1].pos)
        assertTrue(lines[0].text.contains("摘录"))
        assertTrue(lines[1].text.contains("摘录"))
    }

    @Test
    fun posLessText_isRenderedAsIsWithNullPos() {
        val meaning = "空调设备；空调系统的调节"
        assertEquals(
            listOf(MeaningLine(null, "空调设备；空调系统的调节")),
            MeaningTextFormatter.displayLines(meaning)
        )
        assertNull(MeaningTextFormatter.displayLines(meaning)[0].pos)
        assertEquals("空调设备；空调系统的调节", MeaningTextFormatter.displayText(meaning))
    }

    @Test
    fun emptyInput_producesNothing() {
        assertEquals(emptyList<MeaningLine>(), MeaningTextFormatter.parse(""))
        assertEquals(emptyList<MeaningLine>(), MeaningTextFormatter.displayLines(""))
        assertEquals("", MeaningTextFormatter.displayText(""))
        // 只有分隔符/空白的块也全部丢弃
        assertEquals(emptyList<MeaningLine>(), MeaningTextFormatter.displayLines(" ；； \n\n；"))
    }

    @Test
    fun halfWidthSemicolonAndBlankSegments_areHandled() {
        assertEquals(
            listOf(MeaningLine("v.", "创造；创建")),
            MeaningTextFormatter.displayLines("v. 创造;；;创建；")
        )
    }

    @Test
    fun mixedPosLines_keepTheirSourceOrder() {
        val meaning = "n. 甲；v. 乙；adj. 丙"
        assertEquals(
            listOf(MeaningLine("n.", "甲"), MeaningLine("v.", "乙"), MeaningLine("adj.", "丙")),
            MeaningTextFormatter.displayLines(meaning)
        )
        // 同一行里同词性的段合并，但词性首次出现的顺序不变
        assertEquals(
            listOf(MeaningLine("v.", "一；三"), MeaningLine("n.", "二")),
            MeaningTextFormatter.displayLines("v. 一；n. 二；v. 三")
        )
    }

    @Test
    fun parse_keepsEverySegmentAndInheritsPosInsideABlock() {
        assertEquals(
            listOf(
                MeaningLine("v.", "创造"),
                MeaningLine("v.", "创建"),
                MeaningLine("v.", "引起"),
                MeaningLine(null, "空调设备")
            ),
            MeaningTextFormatter.parse("v. 创造；创建；引起\n空调设备")
        )
    }

    @Test
    fun blocksAreNeverMergedAcrossLines() {
        // 同一词性跨块不去重、不合并：行数保持原样
        val meaning = "v. 发射\nv. 发射；投掷"
        assertEquals(
            listOf(MeaningLine("v.", "发射"), MeaningLine("v.", "发射；投掷")),
            MeaningTextFormatter.displayLines(meaning)
        )
        assertEquals("v. 发射\nv. 发射；投掷", MeaningTextFormatter.displayText(meaning))
    }
}

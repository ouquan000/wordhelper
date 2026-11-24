package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.util.MemoryPosTagger
import com.kaoyan.wordhelper.util.TaggedLine

/**
 * 卡背的记忆思路 / 常用搭配区块：一行一条，空行丢弃。
 * 文本自带 ①②③ 时原样显示，不重复编号。
 */
@Composable
internal fun WordMemorySection(
    title: String?,
    content: String,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    /** 1.4.7.1：这段内容是「没复核」的产出（只有 MEMORY 区块会传 true）。 */
    unverified: Boolean = false,
    /** 未复核的原因；点角标展开，空 = 只有角标没有说明。 */
    unverifiedReason: String? = null,
    /** MEMORY 左侧 2dp 渐变引导线；搭配区不需要。 */
    showGuide: Boolean = false,
    /** true = 英文亮白、中文柔和灰白（搭配）；false = 整行正文色（思路）。 */
    bilingual: Boolean = false,
    /** 词性小标的合法性来源（该词的 meaning）；空 = 一个标都不贴。 */
    meaning: String = ""
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(content) {
        content.split("\n")
            .map { normalizeMemoryArrows(it.trim()) }
            .filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    // 思路区逐行贴词性小标：只认正文写明的信号 + meaning 里确实有的词性，判不准就留白。
    val taggedLines = remember(content, meaning, bilingual) {
        if (bilingual) emptyList() else MemoryPosTagger.tag(content, meaning)
    }
    // 换版（正文或原因变了）自动收起：别把上一版的原因留在新正文旁边。
    var reasonExpanded by remember(content, unverifiedReason, unverified) { mutableStateOf(false) }
    val reason = unverifiedReason?.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!title.isNullOrEmpty() || unverified) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        style = titleStyle,
                        color = titleColor
                    )
                }
                if (unverified) {
                    UnverifiedChip(
                        hasReason = reason != null,
                        expanded = reasonExpanded,
                        onClick = { reasonExpanded = !reasonExpanded }
                    )
                }
            }
        }
        if (!title.isNullOrEmpty()) {
            // 标题下的 0.5dp 极细分割线：杂志式栏目标记，不靠深色卡片盒分层。
            HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.55f))
        }
        // 原因跟在标题行下面、正文上面：点开就看到，不打断读思路。
        if (unverified && reasonExpanded && reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = palette.subInk
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            if (showGuide) {
                // 2dp 渐变细竖线：把整段思路收成一条可读的「引文」。
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    palette.accent.copy(alpha = 0.85f),
                                    palette.accent.copy(alpha = 0.06f)
                                )
                            )
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (bilingual) 7.dp else 9.dp)
            ) {
                if (bilingual) {
                    lines.forEach { line ->
                        BilingualLine(
                            text = line,
                            style = contentStyle,
                            strongColor = palette.ink,
                            softColor = palette.subInk
                        )
                    }
                } else {
                    taggedLines.forEach { tagged ->
                        MemoryPosLine(
                            line = tagged,
                            style = contentStyle,
                            color = palette.ink,
                            dark = palette.dark
                        )
                    }
                }
            }
        }
    }
}

/** 行内小标占位符的高度（宽度按词性文字长度估算，见 [posInlineContent]）。 */
private val PosInlineBadgeHeight = 16.sp

/**
 * 记忆思路正文的一行（1.5.3：词性小标**行内嵌入**）。
 *
 * 三条硬规矩，都是为了修掉 1.5.2v2 的两个毛病：
 * 1. 一行就是一行：整行正文只交给一个 `Text` 渲染。**不在行首插 Row**，否则 `adj.`
 *    小标会骑到「③」编号的脑门上、左边还多出一段假缩进；也**不把一行拆成几段**去拼 Column，
 *    否则 `→` 后面的义项串会被拦腰劈成两行。
 * 2. [TaggedLine.badges] 里的每个词性做成一枚 `InlineTextContent` 占位符，用
 *    `appendInlineContent` 原地嵌回文本流；小标跟着字走，换行交给系统排版决定。
 * 3. 判不出词性的行（badges 为空）就是纯 `Text`，不加任何额外排版——宁缺毋滥。
 *
 * 翻卡/滑动/选中的手势挂在外层，这里只是文本节点，行间距由外层 Column 的
 * `Arrangement.spacedBy` 决定，未做任何改动。
 */
@Composable
private fun MemoryPosLine(
    line: TaggedLine,
    style: TextStyle,
    color: Color,
    dark: Boolean
) {
    val marks = remember(line) { posMarks(line) }
    if (marks.isEmpty()) {
        Text(
            text = line.text,
            style = style,
            color = color,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    val inlineContent = remember(marks, dark) { posInlineContent(marks, dark) }
    val annotated = remember(line, marks) { buildPosText(line.text, marks) }
    Text(
        text = annotated,
        inlineContent = inlineContent,
        style = style,
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 一个小标在正文上的落点，`id` 同时用于 AnnotatedString 与 inlineContent 映射。 */
private data class PosMarkPlace(
    val id: String,
    val pos: String,
    val start: Int,
    val end: Int
)

/** 把 [TaggedLine.badges] 过滤成可渲染的落点：越界或与前一个重叠的直接丢掉，绝不吞正文。 */
private fun posMarks(line: TaggedLine): List<PosMarkPlace> {
    val marks = ArrayList<PosMarkPlace>(line.badges.size)
    var cursor = 0
    for ((index, badge) in line.badges.withIndex()) {
        if (badge.start < cursor || badge.start > line.text.length) continue
        val end = badge.end.coerceIn(badge.start, line.text.length)
        marks.add(PosMarkPlace("pos-$index-${badge.pos}", badge.pos, badge.start, end))
        cursor = end
    }
    return marks
}

/** 把正文按落点拼成 AnnotatedString：被小标顶替的片段换成占位符，其余原样保留。 */
private fun buildPosText(text: String, marks: List<PosMarkPlace>): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (mark in marks) {
            if (mark.start > cursor) append(text.substring(cursor, mark.start))
            // start == end 的小标就是插在这一点前面，正文一个字都不删。
            appendInlineContent(mark.id, mark.pos)
            cursor = mark.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

/**
 * 小标占位符表。`Placeholder` 必须给死宽高（InlineTextContent 不会 hug 内容），
 * 所以宽度按词性文字长度估：10sp SemiBold 下每字符约 6sp，再加 9sp 内边距。
 */
private fun posInlineContent(
    marks: List<PosMarkPlace>,
    dark: Boolean
): Map<String, InlineTextContent> {
    val map = LinkedHashMap<String, InlineTextContent>(marks.size)
    for (mark in marks) {
        map[mark.id] = InlineTextContent(
            Placeholder(
                width = (mark.pos.length * 6 + 9).sp,
                height = PosInlineBadgeHeight,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
            )
        ) { _ ->
            PosInlineBadge(pos = mark.pos, dark = dark, modifier = Modifier.fillMaxSize())
        }
    }
    return map
}

/** 行内小标本体：与 [PosBadge] 同款配色、圆角、描边，尺寸由占位符给定。 */
@Composable
private fun PosInlineBadge(pos: String, dark: Boolean, modifier: Modifier = Modifier) {
    val color = posBadgeColor(pos.lowercase(), dark)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (dark) 0.18f else 0.11f))
            .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = pos,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp
            ),
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 「未复核」角标（1.4.7.1）。
 *
 * 刻意用中性色（onSurface 低透明度底 + onSurfaceVariant 字）、小字号、药丸形：
 * 它只是说「这版没走完复核」，不是错误，不能用 error 色去吓人。
 * 有原因时点一下展开 / 收起；没有原因就保持静态，别装作能点。
 */
@Composable
private fun UnverifiedChip(hasReason: Boolean, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        modifier = Modifier.clickable(
            enabled = hasReason,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "未复核",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasReason) {
                Text(
                    text = if (expanded) "收起" else "原因",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** 思路里的箭头统一成 →，避免 ——> / --> / → 三种写法混排。 */
private fun normalizeMemoryArrows(text: String): String =
    text.replace(Regex("""(?:—+|－+|-+)>"""), "→")

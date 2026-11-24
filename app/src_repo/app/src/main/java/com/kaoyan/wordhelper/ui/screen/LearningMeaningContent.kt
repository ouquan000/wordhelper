package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaoyan.wordhelper.data.model.CardBackSize
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette
import com.kaoyan.wordhelper.util.MeaningTextFormatter

/**
 * 释义按词性分行：v. … / n. … 各占一行，同一词性下的多个义项仍用「；」连在一行。
 * 只在「；」紧跟词性标记时才断行，避免把普通义项分隔符也切开。
 */
private val MEANING_POS_BREAK = Regex(
    """[；;]\s*(?=(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.\s)""" +
        "|" +
        """\s+(?=(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.\s)"""
)

private fun formatMeaningByPos(meaning: String): String {
    if (meaning.isBlank()) return meaning
    // 1.5.2：统一交给纯 Kotlin 的 MeaningTextFormatter，合并同词性、去重同义项。
    return MeaningTextFormatter.displayText(meaning)
}

@Composable
internal fun WordExtraSection(
    title: String?,
    content: String,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!title.isNullOrEmpty()) {
            Text(text = title, style = titleStyle, color = titleColor)
            HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.55f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                BilingualLine(
                    text = line,
                    style = contentStyle,
                    strongColor = palette.ink,
                    softColor = palette.subInk
                )
            }
        }
    }
}

/**
 * 例句：标题是 11sp 微标 + 0.5dp 极细分割线，正文不再套 Surface 盒。
 * 英文行亮白、中文行柔和灰白；若整段无法区分中英（没有拉丁字母），整体保持亮白。
 */
@Composable
internal fun WordExampleSection(
    title: String?,
    content: String,
    titleColor: androidx.compose.ui.graphics.Color,
    titleStyle: TextStyle,
    contentStyle: TextStyle,
    overrideColor: androidx.compose.ui.graphics.Color?
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    val hasEnglish = remember(lines) {
        lines.any { line -> line.any { it in 'a'..'z' || it in 'A'..'Z' } }
    }
    val strongColor = overrideColor ?: palette.ink
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!title.isNullOrEmpty()) {
            Text(text = title, style = titleStyle, color = titleColor)
            HorizontalDivider(color = palette.panelBorder.copy(alpha = 0.55f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            lines.forEach { line ->
                if (hasEnglish) {
                    BilingualLine(
                        text = line,
                        style = contentStyle,
                        strongColor = strongColor,
                        softColor = palette.subInk
                    )
                } else {
                    Text(text = line, style = contentStyle, color = strongColor)
                }
            }
        }
    }
}

/**
 * 英文在前、中文紧随的同一行：英文用亮色，中文用柔和灰白。
 * 找不到中文起点（整行英文）时整行亮色；整行中文时才整体柔和。
 */
@Composable
internal fun BilingualLine(
    text: String,
    style: TextStyle,
    strongColor: Color,
    softColor: Color
) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val cjkIndex = trimmed.indexOfFirst { it.isCjk() }
    when {
        cjkIndex < 0 -> Text(text = trimmed, style = style, color = strongColor)
        cjkIndex == 0 -> Text(text = trimmed, style = style, color = softColor)
        else -> {
            val annotated = buildAnnotatedString {
                withStyle(SpanStyle(color = strongColor)) {
                    append(trimmed.substring(0, cjkIndex).trimEnd())
                }
                append("  ")
                withStyle(SpanStyle(color = softColor)) {
                    append(trimmed.substring(cjkIndex))
                }
            }
            Text(text = annotated, style = style)
        }
    }
}

/**
 * 释义：把词性解析成微型彩色圆角徽章，后面紧跟 19sp Medium 的高对比中文释义。
 * 多词性按行展示；没有词性时直接显示整段释义。保留 [formatMeaningByPos] 的分行能力。
 */
@Composable
internal fun MeaningBlock(
    meaning: String,
    bodyStyle: CardBackSize?,
    overrideColor: Color?
) {
    val palette = LocalHoloCardPalette.current
    val lines = remember(meaning) {
        MeaningTextFormatter.displayLines(meaning)
    }
    val meaningStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 19.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Medium
    ).scaledBy(bodyStyle)
    val meaningColor = overrideColor ?: palette.ink
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        lines.forEach { line ->
            val pos = line.pos?.lowercase()
            if (pos != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    PosBadge(pos = pos, dark = palette.dark, modifier = Modifier.padding(top = 3.dp))
                    Text(
                        text = line.text,
                        style = meaningStyle,
                        color = meaningColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(text = line.text, style = meaningStyle, color = meaningColor)
            }
        }
    }
}

/** 单个词性徽章：10sp、圆角 4dp、同色描边 + 低透明底，彩色但不抢正文。 */
@Composable
internal fun PosBadge(
    pos: String,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    val color = posBadgeColor(pos, dark)
    Text(
        text = pos,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp
        ),
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (dark) 0.18f else 0.11f))
            .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

internal fun posBadgeColor(pos: String, dark: Boolean): Color = if (dark) {
    when (pos) {
        "n." -> Color(0xFF8FC2FF)
        "v.", "vt.", "vi." -> Color(0xFFFFC27A)
        "adj." -> Color(0xFFFF9EC4)
        "adv." -> Color(0xFF7FE7CE)
        "prep.", "conj." -> Color(0xFFC9B0FF)
        else -> Color(0xFFB7C6D8)
    }
} else {
    when (pos) {
        "n." -> Color(0xFF2F6FBF)
        "v.", "vt.", "vi." -> Color(0xFF9A5B00)
        "adj." -> Color(0xFFB03A6B)
        "adv." -> Color(0xFF00796B)
        "prep.", "conj." -> Color(0xFF6A4CBF)
        else -> Color(0xFF526273)
    }
}

private fun Char.isCjk(): Boolean =
    this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

private val MEANING_POS_LINE = Regex(
    """^((?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\.)\s*(.*)$""",
    RegexOption.IGNORE_CASE
)

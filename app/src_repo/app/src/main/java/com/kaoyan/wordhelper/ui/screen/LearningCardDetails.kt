package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
internal fun AdaptiveWordText(
    text: String,
    preferredStyle: TextStyle,
    fallbackStyles: List<TextStyle>,
    maxLines: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val availableWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val styleCandidates = remember(preferredStyle, fallbackStyles) {
            buildList {
                add(preferredStyle)
                fallbackStyles.forEach { style ->
                    if (style != preferredStyle) {
                        add(style)
                    }
                }
            }
        }
        val finalStyle = remember(text, styleCandidates, maxLines, availableWidthPx) {
            styleCandidates.firstOrNull { style ->
                val layoutResult = textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = maxLines,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = availableWidthPx)
                )
                !layoutResult.hasVisualOverflow
            } ?: styleCandidates.lastOrNull() ?: preferredStyle
        }

        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = finalStyle,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun MemoryActionIcon(
    icon: ImageVector,
    description: String,
    ink: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier.size(36.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (highlighted) listOf(Color(0xFFFDE6AA), Color(0xFFDFAE58).copy(alpha = 0.55f))
                    else listOf(Color.Transparent, Color.Transparent)
                )
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = ink.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.size(19.dp)
        )
    }
}
@Composable
internal fun ReviewTimeTag(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {    Surface(
        modifier = modifier
            .testTag("learning_review_tag")
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * 六脉神剑里的哪一招。生成器输出的 method 是英文枚举，卡背上给人看的是中文短标签。
 * 只做「主打的一招」——语料里每个词只带主招，不要为了凑两个标签去编。
 */
internal fun methodLabelOf(method: String): String? = when (method.trim().lowercase()) {
    "sound" -> "谐音"
    "meaning" -> "联想"
    "shape" -> "字形"
    "root" -> "词根"
    "contrast" -> "对比"
    "polysemy" -> "多义"
    else -> null
}

/** 卡背音标行右侧的小标签：告诉人这条思路靠什么记，不抢正文字号。 */
@Composable
internal fun MemoryMethodChip(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

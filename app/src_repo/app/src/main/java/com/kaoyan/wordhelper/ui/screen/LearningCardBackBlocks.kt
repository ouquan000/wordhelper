package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.CardBackBlock
import com.kaoyan.wordhelper.data.model.CardBackBlockType
import com.kaoyan.wordhelper.data.model.CardBackColor
import com.kaoyan.wordhelper.data.model.CardBackConfig
import com.kaoyan.wordhelper.data.model.CardBackSize
import com.kaoyan.wordhelper.ui.component.LocalHoloCardPalette

/**
 * 卡背内容区（1.4.2）：块顺序 / 显隐 / 标题文案 / 颜色 / 字号全部来自 [CardBackConfig]。
 *
 * 红线：默认清单（CardBackDefaults.DEFAULT_JSON）渲染出来必须与 1.4.1 逐像素一致。
 * 所以每个块的字体、内边距、分隔线、圆角都照抄原实现；配置里的 color / size 只是
 * 「可选覆盖」，不填时就用该类型的原生样式。
 */
@Composable
internal fun CardBackBlocks(
    config: CardBackConfig,
    word: Word,
    displayWord: String,
    backWordStyle: TextStyle,
    scrollState: androidx.compose.foundation.ScrollState,
    interactive: Boolean,
    /** 1.4.7.1：这版思路没复核时，MEMORY 区块标题旁挂一个中性角标。 */
    unverified: Boolean = false,
    unverifiedReason: String? = null,
    backActions: (@Composable (Boolean) -> Unit)? = null
) {
    val palette = LocalHoloCardPalette.current
    val blocks = config.renderableBlocks
    // 1.5.3v2：上下两端真实透明渐隐——Offscreen 离屏合成后用 DstIn 做 alpha 通道淡出，
    // 适配所有全息/珠光材质，无发白接缝（不能再用 palette.frontTop/frontBottom 纯色叠加）。
    val fadePx = with(LocalDensity.current) { 22.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // 顶部淡出：0 → 22dp 内 alpha 从 0 到 1
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black,
                            startY = 0f,
                            endY = fadePx
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    // 底部淡出：末 22dp 内 alpha 从 1 到 0
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            1f to Color.Transparent,
                            startY = size.height - 20.dp.toPx(),
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                .verticalScroll(scrollState, enabled = interactive)
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 20.dp)
        ) {
            blocks.forEachIndexed { index, block ->
                if (index > 0) {
                    val previous = blocks[index - 1]
                    val gap = when {
                        previous.type == CardBackBlockType.TITLE &&
                            block.type == CardBackBlockType.PHONETIC -> 2
                        block.type == CardBackBlockType.MEANING -> 8
                        block.type == CardBackBlockType.MEMORY -> 18
                        else -> config.spacingDp
                    }
                    Spacer(modifier = Modifier.height(gap.dp))
                }
                CardBackBlockView(
                    block = block,
                    word = word,
                    displayWord = displayWord,
                    backWordStyle = backWordStyle,
                    unverified = unverified,
                    unverifiedReason = unverifiedReason
                )
            }
            // 1.4.3.2：重写区放在正文最后，跟着滚动；用分隔线把「内容」和「操作」分开。
            if (backActions != null) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = palette.ink.copy(alpha = 0.12f))
                Spacer(modifier = Modifier.height(11.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 吞掉空白区的点击，免得点状态文字时把卡片翻回正面（背面不可见时不吞）
                        .clickable(
                            enabled = interactive,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { }
                ) {
                    backActions(interactive)
                }
            }
        }
    }
}

/**
 * 渲染单个块。它会向父 Column 吐**多个**兄弟节点（例如 MEANING 的标题 + 正文），
 * 这是有意的：1.4.1 里它们本来就是 spacedBy(12dp) 的兄弟，套一层嵌套 Column 会改间距。
 *
 * `type == null` 表示手机还不认识这个块，直接跳过；汇总提示由外层负责。
 */
@Composable
private fun CardBackBlockView(
    block: CardBackBlock,
    word: Word,
    displayWord: String,
    backWordStyle: TextStyle,
    unverified: Boolean = false,
    unverifiedReason: String? = null
) {
    val palette = LocalHoloCardPalette.current
    val overrideColor = block.style.color.resolveCardBackColor()
    val bodyStyle = block.style.size
    // 卡背区块的小标：11sp、字距略开；颜色统一取调色板的 subInk（亮皮肤深灰、暗皮肤亮灰）。
    val sectionLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.9.sp
    )
    val sectionLabelColor = overrideColor ?: palette.subInk
    when (block.type) {
        CardBackBlockType.TITLE -> {
            // 杂志级词头：与正面同一支 serif，32sp Medium；长词自动降档，最多 2 行。
            val titleStyle = backWordStyle.copy(
                color = overrideColor ?: palette.ink
            )
            AdaptiveWordText(
                text = displayWord,
                preferredStyle = titleStyle,
                fallbackStyles = listOf(
                    titleStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
                    titleStyle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                    titleStyle.copy(fontSize = 20.sp, lineHeight = 26.sp)
                ),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }

        CardBackBlockType.PHONETIC -> if (word.phonetic.isNotBlank()) {
            // 音标紧贴词头下方，不再单独占一个大块；记忆方法胶囊已上移到卡背顶栏。
            Text(
                text = word.phonetic,
                style = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle).copy(
                    lineHeight = 18.sp,
                    letterSpacing = 0.3.sp
                ),
                color = overrideColor ?: palette.subInk,
                modifier = Modifier.fillMaxWidth()
            )
        }

        CardBackBlockType.DIVIDER -> HorizontalDivider(
            color = palette.panelBorder.copy(alpha = 0.7f)
        )

        CardBackBlockType.MEANING -> if (word.meaning.isNotBlank()) {
            // 大号蓝色「释义」标题已删除；词性徽章 + 高对比释义直接排版。
            MeaningBlock(
                meaning = word.meaning,
                bodyStyle = bodyStyle,
                overrideColor = overrideColor
            )
        }

        CardBackBlockType.MEMORY -> WordMemorySection(
            title = block.resolvedTitle,
            content = word.approach,
            // 词性小标的合法性来源：只贴 meaning 里真实存在的词性。
            meaning = word.meaning,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle).copy(
                lineHeight = 24.sp
            ),
            // 角标只挂在「思路」这一块：unverified 说的是这一版重写结果，不是搭配。
            unverified = unverified,
            unverifiedReason = unverifiedReason,
            showGuide = true
        )

        CardBackBlockType.COLLOCATIONS -> WordMemorySection(
            title = block.resolvedTitle,
            content = word.collocations,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodyMedium.scaledBy(bodyStyle).copy(
                lineHeight = 23.sp
            ),
            bilingual = true
        )

        CardBackBlockType.EXAMPLE -> if (word.example.isNotBlank()) {
            WordExampleSection(
                title = block.resolvedTitle,
                content = word.example,
                titleColor = sectionLabelColor,
                titleStyle = sectionLabelStyle,
                contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle).copy(
                    lineHeight = 21.sp
                ),
                overrideColor = overrideColor
            )
        }

        CardBackBlockType.PHRASES -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.phrases,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        CardBackBlockType.SYNONYMS -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.synonyms,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        CardBackBlockType.REL_WORDS -> WordExtraSection(
            title = block.resolvedTitle,
            content = word.relWords,
            titleColor = sectionLabelColor,
            titleStyle = sectionLabelStyle,
            contentStyle = MaterialTheme.typography.bodySmall.scaledBy(bodyStyle)
        )

        // 1.4.3.1：EF 系数因子不再展示（easeFactor 的计算与存储不变）
        CardBackBlockType.EF_STATS -> Unit

        null -> Unit
    }
}

/**
 * 语义色 → 具体颜色。
 *
 * `null` 和 `DEFAULT` 都表示「不覆盖」，走该块的原生标题色 —— 这样默认清单才能和
 * 1.4.1 一模一样。要强行压成正文色用 `MUTED`（onSurfaceVariant）。
 */
@Composable
private fun CardBackColor.resolveCardBackColor(): androidx.compose.ui.graphics.Color? =
    when (this) {
        CardBackColor.DEFAULT -> null
        CardBackColor.PRIMARY -> MaterialTheme.colorScheme.primary
        CardBackColor.SECONDARY -> MaterialTheme.colorScheme.secondary
        CardBackColor.ACCENT -> MaterialTheme.colorScheme.tertiary
        CardBackColor.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        CardBackColor.WARNING -> MaterialTheme.colorScheme.error
    }

/** 字号三档 → 缩放后的样式；null / M 原样返回，保证默认清单零变化。 */
internal fun TextStyle.scaledBy(size: CardBackSize?): TextStyle {
    if (size == null || size == CardBackSize.M) return this
    val factor = if (size == CardBackSize.S) 0.9f else 1.15f
    val scaledFontSize = if (fontSize.isSpecified) (fontSize.value * factor).sp else fontSize
    val scaledLineHeight = if (lineHeight.isSpecified) (lineHeight.value * factor).sp else lineHeight
    return copy(fontSize = scaledFontSize, lineHeight = scaledLineHeight)
}

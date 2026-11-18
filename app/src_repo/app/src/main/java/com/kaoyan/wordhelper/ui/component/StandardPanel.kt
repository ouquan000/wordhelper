package com.kaoyan.wordhelper.ui.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme

/**
 * 标准透明面板：日夜双模式页面统一使用的容器。
 *
 * 底色完全透明，让最底层的 AmbientBackdrop 透上来；只靠 20dp 圆角和 1dp 极细描边
 * 勾勒轮廓，避免 Material Card 默认实心 surface 把氛围背景盖死。
 * 需要点击时传入 onClick，涟漪会被裁剪在圆角内。
 */
@Composable
fun StandardPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val darkTheme = LocalAppDarkTheme.current
    // 浅色用深墨蓝细描边，深色用白色细描边；都只做轮廓，不压暗内容。
    val borderColor = if (darkTheme) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color(0xFF0F172A).copy(alpha = 0.12f)
    }
    val interactionSource = remember { MutableInteractionSource() }

    // 只提供「默认」文字色：面板内没有显式设置颜色的 Text 跟随当前主题，
    // 深色模式下不再落到黑色；调用方显式设置的颜色（Text(color = ...)）仍然优先。
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = modifier
                .clip(shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .background(Color.Transparent)
                .border(width = 1.dp, color = borderColor, shape = shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

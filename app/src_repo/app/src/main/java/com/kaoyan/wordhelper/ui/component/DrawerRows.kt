package com.kaoyan.wordhelper.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme

/** 按下缩到 0.97f、松手弹簧回弹 1f 的通用按压手感。 */
@Composable
internal fun Modifier.pressScaleModifier(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "drawerPressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
internal fun ModeOptionRow(
    text: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val darkTheme = LocalAppDarkTheme.current
    val shape = RoundedCornerShape(14.dp)
    // 选中项 = 蓝紫流光胶囊；未选中 = 极淡玻璃。浅色改用深墨文字/描边，深色保持白玻璃。
    val fillBrush = if (selected) {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF7C9BFF).copy(alpha = if (darkTheme) 0.38f else 0.22f),
                Color(0xFF9B7CFF).copy(alpha = if (darkTheme) 0.22f else 0.14f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                if (darkTheme) {
                    Color.White.copy(alpha = 0.05f)
                } else {
                    Color(0xFF0F172A).copy(alpha = 0.04f)
                },
                if (darkTheme) {
                    Color.White.copy(alpha = 0.02f)
                } else {
                    Color(0xFF0F172A).copy(alpha = 0.02f)
                }
            )
        )
    }
    val borderColor = when {
        selected && darkTheme -> Color.White.copy(alpha = 0.24f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        darkTheme -> Color.White.copy(alpha = 0.10f)
        else -> Color(0xFF0F172A).copy(alpha = 0.10f)
    }
    Surface(
        modifier = Modifier
            .width(200.dp)
            .height(48.dp)
            .pressScaleModifier(interactionSource)
            .background(fillBrush, shape)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .testTag(testTag),
        shape = shape,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected && darkTheme -> Color(0xFFF8FAFC)
                    selected -> Color(0xFF0F172A)
                    darkTheme -> Color(0xFFCBD5E1)
                    else -> Color(0xFF475569)
                },
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "当前模式",
                    tint = if (darkTheme) Color(0xFFBFD0FF) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
internal fun DrawerNavRow(
    label: String,
    icon: ImageVector,
    testTag: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 选中态只画一层胶囊：高光渐变 + 1dp 描边。圆角取 50%，56dp 行高正好是胶囊形，
    // 不再套 Surface 的第二层形状——那层裁剪会在渐变外面再叠出一个矩形底框。
    val shape = RoundedCornerShape(percent = 50)
    val darkTheme = LocalAppDarkTheme.current
    val primary = MaterialTheme.colorScheme.primary
    // 玻璃行：未选中是极淡底 + 细描边，选中是蓝紫渐变 + 发光。
    // 浅色用淡蓝紫渐变 + 主色边框/深墨文字，深色保持原来的深玻璃高亮。
    val fillStart by animateColorAsState(
        targetValue = if (selected) {
            if (darkTheme) primary.copy(alpha = 0.34f) else Color(0xFF7C9BFF).copy(alpha = 0.22f)
        } else {
            if (darkTheme) Color.White.copy(alpha = 0.04f) else Color(0xFF0F172A).copy(alpha = 0.04f)
        },
        animationSpec = tween(200),
        label = "drawerNavFillStart"
    )
    val fillEnd by animateColorAsState(
        targetValue = if (selected) {
            Color(0xFF9B7CFF).copy(alpha = if (darkTheme) 0.10f else 0.14f)
        } else {
            if (darkTheme) Color.White.copy(alpha = 0.04f) else Color(0xFF0F172A).copy(alpha = 0.04f)
        },
        animationSpec = tween(200),
        label = "drawerNavFillEnd"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            primary.copy(alpha = 0.45f)
        } else {
            if (darkTheme) Color.White.copy(alpha = 0.10f) else Color(0xFF0F172A).copy(alpha = 0.10f)
        },
        animationSpec = tween(200),
        label = "drawerNavBorder"
    )
    val glow by animateDpAsState(
        targetValue = if (selected && darkTheme) 10.dp else 0.dp,
        animationSpec = tween(200),
        label = "drawerNavGlow"
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            selected && darkTheme -> Color.White
            selected -> Color(0xFF0F172A)
            darkTheme -> Color(0xFFE2E8F0)
            else -> Color(0xFF475569)
        },
        animationSpec = tween(200),
        label = "drawerNavLabel"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            selected && darkTheme -> Color(0xFFBFD0FF)
            selected -> primary
            darkTheme -> Color(0xFF94A3B8)
            else -> Color(0xFF64748B)
        },
        animationSpec = tween(200),
        label = "drawerNavIcon"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScaleModifier(interactionSource)
            .shadow(glow, shape, ambientColor = primary, spotColor = primary)
            // 先裁成胶囊再铺高光/描边：点击涟漪也被关在胶囊里，不会冒出方角。
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(fillStart, fillEnd)))
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .testTag(testTag)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor
        )
    }
}

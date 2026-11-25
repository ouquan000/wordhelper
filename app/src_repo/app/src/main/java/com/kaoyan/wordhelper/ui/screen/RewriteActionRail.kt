package com.kaoyan.wordhelper.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.util.SwipeRailStateMachine

internal enum class RailControl(val tag: String, val tint: Color) {
    Model("rewrite_model_pill", Color(0xFF96A7FA)),
    Rewrite("rewrite_action_pill", Color(0xFFBE9AF3)),
    Up("rewrite_feedback_up", Color(0xFF55C5B2)),
    Down("rewrite_feedback_down", Color(0xFFF184A4))
}

internal data class RailOption(val label: String, val submit: () -> Unit)
internal data class OpenRail(
    val control: RailControl,
    val options: List<RailOption>,
    val selection: SwipeRailStateMachine.Selection
)

internal fun shortNameOf(name: String): String {
    val lower = name.lowercase()
    return when {
        "deepseek" in lower && "flash" in lower -> "DS 4.1F"
        "deepseek" in lower && "pro" in lower -> "DS 4 Pro"
        "glm" in lower -> "GLM 5.3"
        "minimax" in lower -> "MiniMax"
        else -> name.substringBefore(" ").ifBlank { name }
    }
}

@Composable
internal fun ActionRailOverlay(visible: Boolean, rail: OpenRail?, dark: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(130)) + scaleIn(spring(dampingRatio = 0.78f, stiffness = 520f), 0.94f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), 0.94f)
    ) {
        if (rail != null) ActionRail(rail, dark)
    }
}

@Composable
private fun ActionRail(rail: OpenRail, dark: Boolean) {
    val foreground = if (dark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val shell = if (dark) Color(0xEB151822) else Color(0xF2FFFFFF)
    val neutral = if (dark) Color(0xEB222631) else Color(0xF2E9EDF2)
    val shape = RoundedCornerShape(24.dp)
    val thumbShape = RoundedCornerShape(19.dp)
    val canceled = rail.selection.canceled
    val thumb by animateFloatAsState(
        rail.selection.index.toFloat(), spring(dampingRatio = 0.78f, stiffness = 520f), label = "actionRailThumb"
    )
    val scale by animateFloatAsState(
        if (canceled) 0.96f else 1f, spring(dampingRatio = 0.78f, stiffness = 520f), label = "actionRailScale"
    )
    val tint by animateFloatAsState(if (canceled) 0f else 1f, tween(140), label = "actionRailTint")
    val wash = rail.control.tint
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(56.dp).testTag("rewrite_action_rail")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(12.dp, shape)
            .clip(shape)
            .background(lerp(neutral, shell, tint))
            .background(
                Brush.horizontalGradient(listOf(wash.copy(alpha = tint * 0.20f), Color.Transparent, wash.copy(alpha = tint * 0.12f)))
            )
            .border(
                1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f))), shape
            )
            .drawBehind {
                val step = size.width / rail.options.size
                repeat(rail.options.size - 1) { i ->
                    val x = step * (i + 1)
                    drawLine(
                        (if (dark) Color.White else Color.Black).copy(alpha = 0.08f * tint),
                        Offset(x, 14.dp.toPx()), Offset(x, size.height - 14.dp.toPx()), strokeWidth = 1.dp.toPx()
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val segment = maxWidth / rail.options.size
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = segment * thumb + 4.dp)
                .width((segment - 8.dp).coerceAtLeast(1.dp)).height(46.dp)
                .graphicsLayer { alpha = tint }
                .shadow(5.dp, thumbShape, clip = false, ambientColor = wash, spotColor = wash)
                .clip(thumbShape)
                .background(
                    Brush.verticalGradient(
                        if (dark) listOf(
                            lerp(Color(0xFF30344B), wash, 0.27f),
                            lerp(Color(0xFF262A40), wash, 0.10f)
                        ) else listOf(Color.White, lerp(Color(0xFFE7EDF9), wash, 0.25f))
                    )
                )
                .border(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.15f))), thumbShape)
        )
        Row(Modifier.fillMaxSize().graphicsLayer { alpha = tint }) {
            rail.options.forEachIndexed { index, option ->
                val active = rail.selection.index == index
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        option.label,
                        color = foreground.copy(alpha = if (active) 1f else 0.62f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .graphicsLayer { scaleX = if (active) 1.04f else 1f; scaleY = scaleX }
                            .testTag("rewrite_rail_option_$index")
                    )
                }
            }
        }
        if (canceled) {
            Text(
                "松手取消",
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("rewrite_rail_canceled")
            )
        }
    }
}

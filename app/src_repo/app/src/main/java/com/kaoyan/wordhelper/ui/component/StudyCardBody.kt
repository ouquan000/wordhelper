package com.kaoyan.wordhelper.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal val StudyCardShape = RoundedCornerShape(19.dp)
private val EdgeThickness = 2.6.dp
private const val RadiansPerDegree = (PI / 180.0).toFloat()

internal fun turnExposure(angle: Float): Float = abs(sin(angle * RadiansPerDegree))

@Composable
internal fun StudyCardBody(
    modifier: Modifier,
    angle: () -> Float,
    back: Boolean,
    light: HoloLightState,
    turnEnabled: Boolean,
    onTurn: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalHoloCardPalette.current
    val edgeColor = if (palette.dark) palette.accent else Color(0xFF9DAFC9)
    val edgeFinish = remember(edgeColor) {
        Brush.horizontalGradient(listOf(edgeColor, Color(0xFF63738C), Color(0xFFDCE6F0)))
    }
    Box(
        modifier.cardContactShadow(angle).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = turnEnabled,
            onClickLabel = if (back) "翻回正面" else "查看卡背",
            onClick = onTurn
        )
    ) {
        // The laminate shares the face's projection, with an offset along the card normal.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val a = angle()
                    val exposure = turnExposure(a)
                    cardPose(a, back, exposure, light)
                    translationX *= -1f
                    alpha = exposure
                }
                .background(edgeColor, StudyCardShape)
        )
        // Join the two narrow projections when the card is almost exactly edge-on.
        Box(
            Modifier.align(Alignment.Center)
                .fillMaxHeight(0.87f)
                .width(EdgeThickness)
                .graphicsLayer {
                    val exposure = turnExposure(angle())
                    alpha = ((exposure - 0.998f) / 0.0018f).coerceIn(0f, 1f)
                }
                .background(edgeFinish, RoundedCornerShape(1.dp))
        )
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val a = angle()
                    val exposure = turnExposure(a)
                    cardPose(a, back, exposure, light)
                    shape = StudyCardShape
                    clip = true
                },
            content = content
        )
    }
}

private fun GraphicsLayerScope.cardPose(
    angle: Float,
    back: Boolean,
    exposure: Float,
    light: HoloLightState
) {
    val attenuation = 1f - 0.88f * exposure
    val faceAngle = angle - if (back) 180f else 0f
    rotationY = (faceAngle + light.tiltX * 1.7f * attenuation).coerceIn(-90f, 90f)
    rotationX = light.tiltY * 1.25f * attenuation
    cameraDistance = (size.width / density).coerceAtLeast(260f) * 0.103f
    scaleX = 1f - 0.13f * exposure
    scaleY = scaleX
    translationX = exposure * EdgeThickness.toPx() * (if (back) -0.5f else 0.5f)
}

private fun Modifier.cardContactShadow(angle: () -> Float): Modifier = drawWithCache {
    val center = Offset(size.width * 0.5f, size.height - 2.dp.toPx())
    val radius = (size.width * 0.51f).coerceAtLeast(1f)
    val gradient = Brush.radialGradient(
        0f to Color(0x33101D35),
        0.66f to Color(0x19101D35),
        1f to Color.Transparent,
        center = center,
        radius = radius
    )
    onDrawBehind {
        val projectedWidth = abs(cos(angle() * RadiansPerDegree))
        // A bounded, feathered contact shadow stays below the card throughout the turn.
        scale(0.26f + 0.74f * projectedWidth, 0.065f, pivot = center) {
            drawCircle(gradient, radius, center)
        }
    }
}

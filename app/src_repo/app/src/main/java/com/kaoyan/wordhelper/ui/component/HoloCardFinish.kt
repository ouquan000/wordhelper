package com.kaoyan.wordhelper.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal enum class HoloFinish { CLASSIC, PRISM, RAINBOW, PEARL, SPARKLE, CHROME, ETCHED }

private data class FoilGrain(val x: Float, val y: Float, val radius: Float, val phase: Float)
private val diamondGrain = Random(73).let { random ->
    List(185) {
        FoilGrain(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
    }
}

internal fun Modifier.drawHoloFinish(
    style: HoloFinish,
    dark: Boolean,
    frontTop: Color,
    frontBottom: Color,
    accent: Color,
    intensity: Float,
    cornerSize: CornerSize,
    motionAmount: Float,
    timeSeconds: () -> Float,
    lightX: () -> Float,
    lightY: () -> Float,
    turn: () -> Float,
    back: Boolean
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    if (w < 1f || h < 1f) return@drawWithCache onDrawBehind {}
    val bleed = maxOf(w, h)
    val overscan = Offset(-bleed, -bleed)
    val fill = Size(w + 2f * bleed, h + 2f * bleed)
    val base = Brush.linearGradient(
        listOf(frontTop, lerp(frontTop, frontBottom, 0.55f), frontBottom),
        end = Offset(w * 0.8f, h)
    )
    fun mist(color: Color, x: Float, y: Float, radius: Float) = Brush.radialGradient(
        0f to color, 0.54f to color.copy(alpha = color.alpha * 0.34f),
        1f to color.copy(alpha = 0f),
        center = Offset(w * x, h * y), radius = bleed * radius
    )
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.43f else 0.92f),
        0.46f to accent.copy(alpha = if (dark) 0.12f else 0.15f),
        1f to accent.copy(alpha = 0.50f),
        end = Offset(w * 0.85f, h)
    )

    val fields = when (style) {
        HoloFinish.CLASSIC -> listOf(
            mist(Color(0xFF82AFEA).copy(alpha = if (dark) 0.26f else 0.37f), 0.09f, 0.13f, 0.62f),
            mist(Color.White.copy(alpha = if (dark) 0.080f else 0.83f), 0.78f, 0.72f, 0.59f)
        )
        HoloFinish.PRISM -> listOf(
            mist(Color(0xFF385BF3).copy(alpha = 0.68f), 0.18f, 0.10f, 0.63f),
            mist(Color(0xFF7E58F9).copy(alpha = 0.39f), 0.91f, 0.75f, 0.54f),
            Brush.linearGradient(
                0f to Color.Transparent, 0.38f to Color.Transparent,
                0.52f to Color(0xFF6FCEFF).copy(alpha = 0.20f),
                0.65f to Color(0xFF8497FF).copy(alpha = 0.10f), 1f to Color.Transparent,
                start = Offset(w * 0.51f, -h * 0.13f), end = Offset(w * 0.86f, h * 0.95f)
            )
        )
        HoloFinish.RAINBOW -> listOf(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.21f to Color.Transparent,
                0.31f to Color(0xFFDB76C8).copy(alpha = 0.46f),
                0.39f to Color(0xFFEB9F68).copy(alpha = 0.49f),
                0.48f to Color(0xFFADC865).copy(alpha = 0.40f),
                0.55f to Color(0xFF47C9C3).copy(alpha = 0.41f),
                0.63f to Color(0xFF647CE7).copy(alpha = 0.43f),
                0.75f to Color.Transparent, 1f to Color.Transparent,
                start = Offset(-w * 0.40f, h * 0.44f), end = Offset(w * 1.40f, h * 0.69f)
            )
        )
        HoloFinish.PEARL -> listOf(
            mist(Color(0xFFF1CBEC).copy(alpha = if (back) 0.58f else 0.70f), 0.91f, 0.24f, 0.66f),
            mist(Color(0xFFBCE7DE).copy(alpha = 0.65f), 0.12f, 0.70f, 0.65f),
            mist(Color(0xFFF7DCBC).copy(alpha = 0.60f), 0.86f, 0.94f, 0.57f)
        )
        HoloFinish.SPARKLE -> listOf(
            mist(Color(0xFF4F5BBE).copy(alpha = 0.43f), 0.78f, 0.18f, 0.65f),
            mist(Color(0xFF308CB1).copy(alpha = 0.30f), 0.17f, 0.92f, 0.50f)
        )
        HoloFinish.CHROME -> listOf(
            mist(Color.White.copy(alpha = 0.88f), 0.36f, 0.12f, 0.53f),
            mist(Color(0xFFA6B4D3).copy(alpha = 0.57f), 0.93f, 0.85f, 0.60f)
        )
        HoloFinish.ETCHED -> listOf(
            mist(Color(0xFFA7AFBE).copy(alpha = 0.23f), 0.77f, 0.77f, 0.49f),
            mist(Color(0xFF7693AA).copy(alpha = 0.17f), 0.14f, 0.21f, 0.38f)
        )
    }

    val silver = if (style == HoloFinish.CHROME) liquidSilver else null
    val silverShade = if (silver != null) ColorFilter.tint(Color(0xFF5D738E)) else null
    val reflectionSpan = IntSize((w * 1.36f).roundToInt(), (h * 1.28f).roundToInt())
    val engraving = if (style == HoloFinish.ETCHED) {
        Path().apply {
            val origin = Offset(w * 0.81f, h * 0.73f)
            val step = maxOf(2.dp.toPx(), w * 0.012f)
            for (ring in 0..44) {
                val r = w * 0.02f + step * ring
                addOval(Rect(center = origin, radius = r))
            }
        }
    } else null
    val engraveInk = Brush.linearGradient(
        listOf(Color.Transparent, Color(0xFFB2BBD4).copy(alpha = 0.28f), Color.Transparent),
        start = Offset(w * 0.40f, h * 0.52f), end = Offset(w, h * 0.99f)
    )
    val rimInset = 0.7.dp.toPx()
    val corner = (cornerSize.toPx(size, this) - rimInset).coerceIn(0f, minOf(w, h) * 0.5f)
    val rimSize = Size((w - 2 * rimInset).coerceAtLeast(0f), (h - 2 * rimInset).coerceAtLeast(0f))

    onDrawBehind {
        val t = if (motionAmount == 0f) 0f else timeSeconds()
        val sensorX = lightX()
        val sensorY = lightY()
        val tiltSweep = turn()
        val drift = sin(t * 0.29f + 0.38f) * motionAmount
        val swell = sin(t * 0.19f + 1.7f) * motionAmount
        val dx = w * (sensorX * 0.14f + tiltSweep * 0.21f)
        val dy = h * sensorY * 0.10f

        drawRect(base)
        if (engraving != null) {
            drawPath(engraving, engraveInk, alpha = 0.70f, style = Stroke(0.52.dp.toPx()))
        }
        fields.forEachIndexed { index, brush ->
            val direction = if (index % 2 == 0) 1f else -1f
            val x = dx + drift * w * (if (style == HoloFinish.RAINBOW) 0.19f else 0.09f) * direction
            val y = dy + swell * h * (if (style == HoloFinish.CHROME) 0.12f else 0.07f) * direction
            val alpha = intensity * (0.90f + 0.10f * swell * direction)
            translate(x, y) {
                drawRect(brush, topLeft = overscan, size = fill, alpha = alpha)
            }
        }
        if (silver != null) {
            val position = IntOffset(
                (-0.18f * w + dx * 0.56f + drift * w * 0.06f).roundToInt(),
                (-0.14f * h + dy * 0.52f + swell * h * 0.07f).roundToInt()
            )
            drawImage(silver.shade, dstOffset = position, dstSize = reflectionSpan,
                alpha = intensity * 0.70f, colorFilter = silverShade)
            drawImage(silver.gloss, dstOffset = position, dstSize = reflectionSpan,
                alpha = intensity)
        }
        if (style == HoloFinish.SPARKLE) {
            val glintX = 0.69f + drift * 0.12f + sensorX * 0.19f
            val glintY = 0.73f + swell * 0.08f + sensorY * 0.15f
            for (grain in diamondGrain) {
                val distance = abs(grain.x - glintX) + abs(grain.y - glintY)
                val focus = (1f - distance * 2.4f).coerceIn(0f, 1f)
                val shimmer = 0.70f + 0.30f * sin(t * 0.58f + grain.phase * 6.28f)
                val alpha = (0.091f + focus * grain.phase * shimmer * 0.64f) * intensity
                val radius = minOf(w * 0.008f, (0.26f + grain.radius * 0.71f).dp.toPx())
                drawCircle(
                    color = if (grain.phase < 0.42f) Color(0xFFC2CDFF) else Color(0xFFF5FBFF),
                    radius = radius,
                    center = Offset(grain.x * w, grain.y * h),
                    alpha = alpha,
                )
            }
        }
        drawRoundRect(
            brush = rim, topLeft = Offset(rimInset, rimInset), size = rimSize,
            cornerRadius = CornerRadius(corner), style = Stroke(rimInset)
        )
    }
}

private data class SilverOptics(val gloss: ImageBitmap, val shade: ImageBitmap)

// Neutral masks keep the reflected highlights independent of the card's day/night pigment.
private val liquidSilver: SilverOptics by lazy {
    val width = 144
    val height = 224
    val gloss = IntArray(width * height)
    val shade = IntArray(width * height)
    for (j in 0 until height) {
        val y = j.toFloat() / (height - 1)
        for (i in 0 until width) {
            val x = i.toFloat() / (width - 1)
            val curve = 0.67f + 0.109f * sin(x * 6.65f + 0.84f) +
                0.026f * sin(x * 13.9f - 0.45f)
            val distance = y - curve
            val widthAtX = 0.046f + 0.017f * sin(x * 5.8f + 0.63f)
            val primary = bell((distance - 0.027f) / widthAtX)
            val highlightTail = bell((distance - 0.066f) / 0.10f) * 0.37f
            val secondary = bell((y - 0.23f - 0.040f * sin(x * 5.2f)) / 0.078f) * 0.36f
            val darkShoulder = bell((distance + 0.050f) / (widthAtX * 1.36f)) * 0.70f
            val lowerShoulder = bell((distance - 0.191f) / 0.084f) * 0.37f
            val index = j * width + i
            gloss[index] = silverMask(primary * 0.88f + highlightTail + secondary)
            shade[index] = silverMask(darkShoulder + lowerShoulder)
        }
    }
    fun bitmap(pixels: IntArray) =
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    SilverOptics(bitmap(gloss), bitmap(shade))
}

private fun bell(value: Float): Float = exp(-value * value)

private fun silverMask(alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00FFFFFF

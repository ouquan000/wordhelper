package com.kaoyan.wordhelper.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kaoyan.wordhelper.data.model.CardMotionMode
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class AmbientBackdropMode(val id: String, val label: String) {
    AUTO("auto", "跟随卡片"),
    AURORA("aurora", "极光暗雾"),
    ABYSS("abyss", "深海波光"),
    EMBER("ember", "星云余烬"),
    VELVET("velvet", "纯黑丝绒");

    companion object {
        fun fromId(id: String?): AmbientBackdropMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

private data class AtmospherePalette(val base: Color, val smoke: Color, val aurora: Color)

private fun atmospherePalette(
    mode: AmbientBackdropMode,
    style: HoloCardStyle,
    night: Boolean
): AtmospherePalette {
    val colors = when (mode) {
        AmbientBackdropMode.AUTO -> when (style) {
            HoloCardStyle.CLASSIC -> 0xFF607DE7 to 0xFF6ACABD
            HoloCardStyle.PRISM -> 0xFF5049EF to 0xFF8999FF
            HoloCardStyle.RAINBOW -> 0xFFB755A8 to 0xFF62D7C6
            HoloCardStyle.PEARL -> 0xFFAC86DE to 0xFFEBAFAE
            HoloCardStyle.SPARKLE -> 0xFF5C63E3 to 0xFF60BCD6
            HoloCardStyle.CHROME -> 0xFF8190C6 to 0xFF9BC9C4
            HoloCardStyle.ETCHED -> 0xFF627696 to 0xFF659996
        }
        AmbientBackdropMode.AURORA -> 0xFF7755E8 to 0xFF57CBB9
        AmbientBackdropMode.ABYSS -> 0xFF4C5CD6 to 0xFF5AABD5
        AmbientBackdropMode.EMBER -> 0xFFB55588 to 0xFFE3A779
        AmbientBackdropMode.VELVET -> 0xFF475063 to 0xFF718394
    }
    val base = if (night) Color(0xFF080D1A) else Color(0xFFFCF9F7)
    val (smoke, aurora) = Color(colors.first) to Color(colors.second)
    return if (night) {
        AtmospherePalette(base, smoke, aurora)
    } else {
        AtmospherePalette(base, lerp(smoke, Color.White, 0.45f), lerp(aurora, Color.White, 0.30f))
    }
}

@Composable
fun AmbientBackdrop(
    mode: AmbientBackdropMode,
    cardStyle: HoloCardStyle,
    motionMode: CardMotionMode,
    timeSeconds: () -> Float,
    modifier: Modifier = Modifier
) {
    val night = LocalAppDarkTheme.current
    val target = atmospherePalette(mode, cardStyle, night)
    val spec = tween<Color>(2400, easing = FastOutSlowInEasing)
    val base = animateColorAsState(target.base, spec, label = "atmosphere base")
    val smoke = animateColorAsState(target.smoke, spec, label = "atmosphere smoke")
    val aurora = animateColorAsState(target.aurora, spec, label = "atmosphere aurora")
    val depth = animateFloatAsState(
        if (night) 1f else 0f, tween(2400), label = "atmosphere depth"
    )
    val textures = produceState<AtmosphereTextures?>(null, motionMode) {
        if (motionMode == CardMotionMode.FULL) {
            value = withContext(Dispatchers.Default) { atmosphereTextures }
        }
    }
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val span = maxOf(w, h).coerceAtLeast(1f)
        val maxShift = w * 0.15f
        val gradientSize = Size(w + maxShift * 2f, h)
        val fallback = Brush.linearGradient(
            0f to smoke.value.copy(alpha = 0.07f),
            0.38f to smoke.value.copy(alpha = 0.28f),
            0.74f to aurora.value.copy(alpha = 0.19f),
            1f to aurora.value.copy(alpha = 0.04f),
            start = Offset(-maxShift, h * 0.16f), end = Offset(w, h * 0.96f)
        )
        val mask = textures.value
        val smokeTint = ColorFilter.tint(smoke.value)
        val auroraTint = ColorFilter.tint(aurora.value)
        val imageSize = IntSize((w * 1.32f).roundToInt(), (h * 1.18f).roundToInt())
        val vignette = Brush.radialGradient(
            0f to Color.Transparent,
            0.56f to Color.Transparent,
            1f to Color(0xFF03060E).copy(alpha = 0.36f),
            center = Offset(w * 0.50f, h * 0.45f), radius = span * 0.65f
        )
        onDrawBehind {
            val t = if (motionMode == CardMotionMode.STATIC) 0f else timeSeconds()
            val darkness = depth.value
            drawRect(base.value)
            if (motionMode == CardMotionMode.FULL && mask != null &&
                imageSize.width > 0 && imageSize.height > 0
            ) {
                drawImage(
                    mask.smoke,
                    dstOffset = IntOffset(
                        (w * (-0.16f + 0.095f * sin(t * 0.15f))).roundToInt(),
                        (h * (-0.10f + 0.048f * sin(t * 0.11f + 1.2f))).roundToInt()
                    ),
                    dstSize = imageSize,
                    alpha = 0.49f + 0.39f * darkness,
                    colorFilter = smokeTint
                )
                drawImage(
                    mask.smoke,
                    dstOffset = IntOffset(
                        (w * (-0.15f - 0.081f * sin(t * 0.129f + 1.6f))).roundToInt(),
                        (h * (-0.06f - 0.055f * sin(t * 0.151f))).roundToInt()
                    ),
                    dstSize = imageSize,
                    alpha = (0.12f + 0.18f * darkness) * (0.83f + 0.17f * sin(t * 0.21f + 1.8f)),
                    colorFilter = auroraTint
                )
                drawImage(
                    mask.aurora,
                    dstOffset = IntOffset(
                        (w * (-0.16f + 0.12f * sin(t * 0.107f + 1.2f))).roundToInt(),
                        (h * (-0.095f + 0.055f * sin(t * 0.093f))).roundToInt()
                    ),
                    dstSize = imageSize,
                    alpha = (0.30f + 0.30f * darkness) * (0.89f + 0.11f * sin(t * 0.27f)),
                    colorFilter = auroraTint
                )
            } else {
                val x = maxShift * sin(t * 0.13f)
                translate(x, 0f) {
                    drawRect(fallback, Offset(-maxShift, 0f), gradientSize)
                }
            }
            if (darkness > 0f) drawRect(vignette, alpha = darkness)
        }
    })
}

private data class AtmosphereTextures(val smoke: ImageBitmap, val aurora: ImageBitmap)

private val atmosphereTextures: AtmosphereTextures by lazy {
    val width = 280
    val height = 480
    val smoke = IntArray(width * height)
    val aurora = IntArray(width * height)
    val noise = CloudNoise()
    for (j in 0 until height) {
        val y = j.toFloat() / (height - 1)
        for (i in 0 until width) {
            val x = i.toFloat() / (width - 1)
            val wx = x * 3.7f + 1.76f * noise.fractal(x * 2.6f + 1f, y * 4.1f + 3f)
            val wy = y * 6.4f + 1.67f * noise.fractal(x * 3.2f + 6f, y * 4.7f + 1f)
            val veil = noise.fractal(wx, wy)
            val ridge = 1f - abs(noise.fractal(wx * 1.43f + 4f, wy * 1.43f + 3f) * 2f - 1f)
            val density = ((veil - 0.27f) * 1.58f).coerceIn(0f, 1f)
            val surround = exp(-3.5f * (y - 0.45f) * (y - 0.45f))
            val ash = density * density * (0.34f + 0.66f * ridge) * surround
            val ribbonY = 0.44f + 0.15f * sin(x * 6.7f - 0.41f) + 0.18f * (veil - 0.5f)
            val distance = (y - ribbonY) / (0.20f - 0.04f * sin(x * 5.5f))
            val ribbon = exp(-distance * distance * 2.1f) * (0.19f + density * ridge * 0.74f)
            val index = j * width + i
            smoke[index] = whiteMask(ash * 1.46f)
            aurora[index] = whiteMask(ribbon * (0.63f + 0.37f * sin(x * 2.9f)))
        }
    }
    fun bitmap(pixels: IntArray) =
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    AtmosphereTextures(bitmap(smoke), bitmap(aurora))
}

private fun whiteMask(alpha: Float): Int =
    ((255f * alpha.coerceIn(0f, 1f)).roundToInt() shl 24) or 0x00FFFFFF

private class CloudNoise {
    private val samples = Random(37).let { random -> FloatArray(32 * 32) { random.nextFloat() } }

    fun fractal(x: Float, y: Float): Float =
        0.56f * sample(x, y) +
            0.28f * sample(x * 2.07f + 3f, y * 2.07f) +
            0.12f * sample(x * 4.19f, y * 4.19f + 3f) +
            0.04f * sample(x * 8.31f, y * 8.31f)

    private fun sample(x: Float, y: Float): Float {
        val i = floor(x).toInt()
        val j = floor(y).toInt()
        val u = smooth(x - i)
        val v = smooth(y - j)
        val a = mix(value(i, j), value(i + 1, j), u)
        val b = mix(value(i, j + 1), value(i + 1, j + 1), u)
        return mix(a, b, v)
    }

    private fun value(x: Int, y: Int): Float = samples[(y and 31) * 32 + (x and 31)]
    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)
    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

package com.kaoyan.wordhelper.ui.component

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sqrt

internal class CardTiltListener(
    private val screenRotation: Int,
    private val onDelta: (Float, Float) -> Unit
) : SensorEventListener {
    private val matrix = FloatArray(9)
    private var baseline: Offset? = null

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val gravity = if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, v)
            Offset(matrix[6], matrix[7])
        } else {
            val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (!length.isFinite() || length < 1e-3f) return
            Offset(v[0] / length, v[1] / length)
        }
        if (!gravity.x.isFinite() || !gravity.y.isFinite()) return
        val oriented = when (screenRotation) {
            Surface.ROTATION_90 -> Offset(-gravity.y, gravity.x)
            Surface.ROTATION_180 -> -gravity
            Surface.ROTATION_270 -> Offset(gravity.y, -gravity.x)
            else -> gravity
        }
        val neutral = baseline ?: run {
            baseline = oriented
            return
        }
        val delta = (oriented - neutral) / 0.29f
        onDelta(delta.x, delta.y)
    }
}

internal fun settleTilt(current: Float, raw: Float): Float {
    val target = if (abs(raw) < 0.035f) 0f else raw.coerceIn(-1f, 1f)
    val next = current + 0.14f * (target - current)
    return if (abs(target - next) < 0.002f) target else next
}

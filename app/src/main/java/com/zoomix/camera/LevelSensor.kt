package com.zoomix.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Inclinação do celular em graus (positivo = topo inclinado para a direita, sentido horário),
 * relativa à posição mais próxima de 0°/90°/180°/270°, ou seja, entre -45 e 45.
 */
@Composable
fun rememberTilt(enabled: Boolean): State<Float> {
    val ctx = LocalContext.current
    val tilt = remember { mutableStateOf(0f) }
    DisposableEffect(enabled) {
        if (!enabled) {
            tilt.value = 0f
            return@DisposableEffect onDispose { }
        }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) return@DisposableEffect onDispose { }

        var smooth = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val gx = e.values[0]
                val gy = e.values[1]
                // Celular quase deitado (apontando para cima/baixo): o ângulo fica instável.
                if (hypot(gx, gy) < 3f) return
                val psi = Math.toDegrees(atan2(-gx.toDouble(), gy.toDouble())).toFloat()
                val a = psi - 90f * (psi / 90f).roundToInt()
                smooth += 0.15f * (a - smooth)
                tilt.value = smooth
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

package io.github.brunovinicioslg.medeai.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.brunovinicioslg.medeai.geometry.Vec3
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Smoothed gravity in device axes (the resting reading points up), from the fused gravity sensor
 * or, without one, from the low-pass filtered accelerometer. Emits a single null when the device
 * has neither. Stops listening when the collector goes away.
 */
fun gravityFlow(context: Context): Flow<Vec3?> = callbackFlow {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (manager == null || sensor == null) {
        trySend(null)
        awaitClose()
        return@callbackFlow
    }
    var smoothed: Vec3? = null
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val raw = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
            if (!raw.isFinite()) return
            val previous = smoothed
            val next = if (previous == null) raw else previous + (raw - previous) * SMOOTHING
            smoothed = next
            trySend(next)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    awaitClose { manager.unregisterListener(listener) }
}.conflate()

/** Weight of each new reading: steadies the aim and the bubble without noticeable lag. */
private const val SMOOTHING = 0.15

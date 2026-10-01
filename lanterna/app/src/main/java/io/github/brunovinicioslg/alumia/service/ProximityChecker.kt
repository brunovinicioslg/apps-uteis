package io.github.brunovinicioslg.alumia.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Proximity reading taken when a shake fires, to ignore shakes while the phone is in a pocket or bag.
 *
 * Some sensors first repeat their last known value and only then measure, so a "far" first reading
 * gets a moment to turn into "near"; a "near" one is enough at once.
 */
class ProximityChecker(context: Context) {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)

    // The wake-up variant keeps reporting while the processor sleeps, as it does with the screen off.
    private val sensor: Sensor? = sensorManager?.let {
        it.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: it.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    /** False when the device has no proximity sensor or it does not answer in time. */
    suspend fun isCovered(): Boolean {
        val manager = sensorManager ?: return false
        val proximity = sensor ?: return false
        // Android's own rule: some sensors report a maximum range far above their "far" value.
        val threshold = minOf(proximity.maximumRange, TYPICAL_THRESHOLD_CM)
        val readings = Channel<Boolean>(Channel.CONFLATED)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val distance = event.values.firstOrNull() ?: return
                readings.trySend(distance >= 0f && distance < threshold)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, proximity, SensorManager.SENSOR_DELAY_FASTEST)) return false
        try {
            val first = withTimeoutOrNull(FIRST_READING_TIMEOUT_MS) { readings.receive() } ?: return false
            if (first) return true
            return withTimeoutOrNull(CONFIRM_FAR_MS) {
                do {
                    val near = readings.receive()
                } while (!near)
                true
            } ?: false
        } finally {
            manager.unregisterListener(listener)
        }
    }

    private companion object {
        const val FIRST_READING_TIMEOUT_MS = 500L
        const val CONFIRM_FAR_MS = 150L
        const val TYPICAL_THRESHOLD_CM = 5f
    }
}

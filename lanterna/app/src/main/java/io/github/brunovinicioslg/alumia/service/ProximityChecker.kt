package io.github.brunovinicioslg.alumia.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot proximity reading, used to ignore shakes while the phone is in a pocket or bag. */
class ProximityChecker(context: Context) {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    /** False when the device has no proximity sensor or it does not answer in time. */
    suspend fun isCovered(): Boolean {
        val manager = sensorManager ?: return false
        val proximity = sensor ?: return false
        val distance = withTimeoutOrNull(TIMEOUT_MS) { readOnce(manager, proximity) } ?: return false
        return distance < proximity.maximumRange
    }

    private suspend fun readOnce(manager: SensorManager, proximity: Sensor): Float? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    manager.unregisterListener(this)
                    if (continuation.isActive) continuation.resume(event.values.firstOrNull())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            continuation.invokeOnCancellation { manager.unregisterListener(listener) }
            // On-change sensors deliver the current value right after registration.
            if (!manager.registerListener(listener, proximity, SensorManager.SENSOR_DELAY_FASTEST)) {
                continuation.resume(null)
            }
        }

    private companion object {
        const val TIMEOUT_MS = 300L
    }
}

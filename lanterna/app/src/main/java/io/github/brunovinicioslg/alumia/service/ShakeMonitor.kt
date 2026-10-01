package io.github.brunovinicioslg.alumia.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.brunovinicioslg.alumia.core.detection.ShakeConfig
import io.github.brunovinicioslg.alumia.core.detection.ShakeDetector

/**
 * Feeds the accelerometer into [ShakeDetector] and calls [onShake] on the main thread.
 *
 * With the screen off it prefers the wake-up accelerometer, whose events wake the CPU by themselves,
 * with a small batching delay to save power. Devices without one get a regular accelerometer plus a
 * wake lock, because a regular sensor stops delivering once the CPU sleeps. The phone is then often in
 * a pocket, so the gesture needs one stroke more ([ShakeConfig.forScreenOff]).
 */
class ShakeMonitor(private val context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val wakeUpAccelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
    private val detector = ShakeDetector()
    private val wakeLock = WakeLockHolder(context, "alumia:shake")

    private var enabled = false
    private var config = ShakeConfig()
    private var workWithScreenOff = false
    private var screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    private var registered: Sensor? = null
    private var screenReceiverRegistered = false

    val isSupported: Boolean get() = accelerometer != null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                else -> return
            }
            reconfigure()
        }
    }

    fun update(enabled: Boolean, config: ShakeConfig, workWithScreenOff: Boolean) {
        this.config = config
        this.enabled = enabled
        this.workWithScreenOff = workWithScreenOff
        setScreenReceiverRegistered(enabled)
        reconfigure()
    }

    fun stop() {
        enabled = false
        setScreenReceiverRegistered(false)
        reconfigure()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        if (detector.onSample(event.timestamp, event.values[0], event.values[1], event.values[2])) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun reconfigure() {
        val manager = sensorManager ?: return
        val listen = enabled && (screenOn || workWithScreenOff)
        val screenOffMode = listen && !screenOn
        val wanted = if (screenOffMode) config.forScreenOff() else config
        if (detector.config != wanted) detector.config = wanted
        val target = when {
            !listen -> null
            screenOffMode -> wakeUpAccelerometer ?: accelerometer
            else -> accelerometer
        }
        if (target != registered) {
            manager.unregisterListener(this)
            registered = null
            detector.reset()
            if (target != null) {
                val batched = screenOffMode && target.isWakeUpSensor && target.fifoMaxEventCount > 0
                val latencyUs = if (batched) SCREEN_OFF_BATCH_LATENCY_US else 0
                if (manager.registerListener(this, target, SAMPLING_PERIOD_US, latencyUs)) registered = target
            }
        }
        val sensor = registered
        if (screenOffMode && sensor != null && !sensor.isWakeUpSensor) wakeLock.acquire() else wakeLock.release()
    }

    private fun setScreenReceiverRegistered(register: Boolean) {
        if (register == screenReceiverRegistered) return
        if (register) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        } else {
            context.unregisterReceiver(screenReceiver)
        }
        screenReceiverRegistered = register
    }

    private companion object {
        /** ~50 Hz, enough for shakes of up to ~6 Hz. */
        const val SAMPLING_PERIOD_US = 20_000
        const val SCREEN_OFF_BATCH_LATENCY_US = 100_000
    }
}

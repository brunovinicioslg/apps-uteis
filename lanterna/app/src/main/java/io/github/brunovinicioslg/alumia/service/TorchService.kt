package io.github.brunovinicioslg.alumia.service

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.brunovinicioslg.alumia.appContainer
import io.github.brunovinicioslg.alumia.core.policy.Policies
import io.github.brunovinicioslg.alumia.core.settings.Settings
import io.github.brunovinicioslg.alumia.torch.TorchResult
import io.github.brunovinicioslg.alumia.torch.TorchState
import io.github.brunovinicioslg.alumia.widget.TorchWidgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while shake detection is enabled or while this app keeps the light
 * on (the camera service turns the torch off when the process that turned it on dies).
 *
 * Everything runs on the main thread, so no state here needs synchronization.
 */
class TorchService : Service() {

    private val scope = MainScope()
    private val container by lazy { appContainer }
    private lateinit var shakeMonitor: ShakeMonitor
    private lateinit var proximity: ProximityChecker
    private lateinit var feedback: Feedback
    private lateinit var torchOnWakeLock: WakeLockHolder

    private var settings: Settings? = null
    private var pendingCommands = 0
    private var lastStartId = 0

    /** Set once the service asked to stop; late updates must not re-post the notification. */
    private var stopping = false
    private var gestureInProgress = false
    private var autoOffJob: Job? = null
    private var lastBatteryPercent: Int? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val percent = level * 100 / scale
            val previous = lastBatteryPercent
            lastBatteryPercent = percent
            val threshold = settings?.lowBatteryPercent ?: return
            if (torchOnByApp() && Policies.crossedLowBattery(previous, percent, threshold)) {
                runCommand { container.torch.setEnabled(false) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TorchNotifications.ensureChannel(this)
        shakeMonitor = ShakeMonitor(this, ::onShake)
        proximity = ProximityChecker(this)
        feedback = Feedback(this)
        torchOnWakeLock = WakeLockHolder(this, "alumia:torch-on")

        scope.launch { container.settingsRepository.settings.collect(::applySettings) }
        scope.launch { container.torch.state.collect(::onTorchState) }
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        stopping = false
        // Every start through startForegroundService() must be answered with startForeground().
        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE_TORCH -> runCommand { toggleTorch(fromGesture = false) }
            ACTION_TOGGLE_DETECTION -> runCommand { updateSettings { it.copy(detectionEnabled = !it.detectionEnabled) } }
            ACTION_DISABLE_DETECTION -> runCommand { updateSettings { it.copy(detectionEnabled = false) } }
            else -> evaluateLifecycle() // ACTION_START, or a restart by the system (null intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        NotificationManagerCompat.from(this).cancel(TorchNotifications.NOTIFICATION_ID)
        shakeMonitor.stop()
        autoOffJob?.cancel()
        torchOnWakeLock.release()
        if (batteryReceiverRegistered) unregisterReceiver(batteryReceiver)
        scope.cancel()
        super.onDestroy()
    }

    /** Runs [block] while preventing the service from stopping until it finishes. */
    private fun runCommand(block: suspend () -> Unit) {
        pendingCommands++
        scope.launch {
            try {
                block()
            } finally {
                pendingCommands--
                evaluateLifecycle()
            }
        }
    }

    private suspend fun updateSettings(transform: (Settings) -> Settings) {
        // Applied right away so the lifecycle check that follows sees the new value.
        applySettings(container.settingsRepository.update(transform))
    }

    private fun applySettings(new: Settings) {
        val old = settings
        settings = new
        shakeMonitor.update(new.detectionEnabled, new.shakeConfig(), new.workWithScreenOff)
        if (old != null && old.torchLevelPercent != new.torchLevelPercent) container.torch.applyLevel(new.torchLevelPercent)
        if (old?.autoOffMinutes != new.autoOffMinutes) rescheduleAutoOff()
        refreshNotification()
        evaluateLifecycle()
    }

    private fun onTorchState(state: TorchState) {
        rescheduleAutoOff()
        refreshNotification()
        TorchWidgets.update(this, state is TorchState.On)
        evaluateLifecycle()
    }

    private fun onShake() {
        if (gestureInProgress) return
        gestureInProgress = true
        runCommand {
            try {
                val current = settings ?: return@runCommand
                val inCall = isInCall()
                val covered = current.ignoreInPocket && !inCall && proximity.isCovered()
                if (Policies.gestureBlock(inCall, covered, current.ignoreInPocket) == null) {
                    toggleTorch(fromGesture = true)
                }
            } finally {
                gestureInProgress = false
            }
        }
    }

    private suspend fun toggleTorch(fromGesture: Boolean) {
        val current = settings ?: container.settingsRepository.settings.first()
        val result = container.torch.toggle(current.torchLevelPercent)
        if (fromGesture && current.vibrate) {
            when (result) {
                TorchResult.TURNED_ON -> feedback.turnedOn()
                TorchResult.TURNED_OFF -> feedback.turnedOff()
                TorchResult.NO_FLASH, TorchResult.BUSY -> feedback.failed()
            }
        }
    }

    private fun rescheduleAutoOff() {
        autoOffJob?.cancel()
        autoOffJob = null
        val minutes = settings?.autoOffMinutes ?: 0
        if (!torchOnByApp()) {
            torchOnWakeLock.release()
            return
        }
        // The light draws far more power than an awake CPU, and the lock keeps timers accurate.
        torchOnWakeLock.acquire()
        if (minutes <= 0) return
        autoOffJob = scope.launch {
            delay(minutes * 60_000L)
            runCommand { container.torch.setEnabled(false) }
        }
    }

    private fun evaluateLifecycle() {
        val current = settings
        val keep = Policies.shouldKeepServiceRunning(
            settingsLoaded = current != null,
            detectionEnabled = current?.detectionEnabled == true,
            torchOnByApp = torchOnByApp(),
            pendingCommands = pendingCommands,
        )
        if (!keep && !stopping) {
            stopping = true
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // Stops only if no newer start request arrived in the meantime.
            stopSelf(lastStartId)
        }
    }

    private fun enterForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, TorchNotifications.NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot enter the foreground", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot enter the foreground", e)
            false
        }
    }

    private fun refreshNotification() {
        if (stopping) return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(TorchNotifications.NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification() =
        TorchNotifications.build(this, settings?.detectionEnabled == true, container.torch.state.value)

    private fun torchOnByApp(): Boolean = (container.torch.state.value as? TorchState.On)?.byApp == true

    private fun isInCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    companion object {
        private const val TAG = "TorchService"
        const val ACTION_START = "io.github.brunovinicioslg.alumia.service.START"
        const val ACTION_TOGGLE_TORCH = "io.github.brunovinicioslg.alumia.service.TOGGLE_TORCH"
        const val ACTION_TOGGLE_DETECTION = "io.github.brunovinicioslg.alumia.service.TOGGLE_DETECTION"
        const val ACTION_DISABLE_DETECTION = "io.github.brunovinicioslg.alumia.service.DISABLE_DETECTION"
    }
}

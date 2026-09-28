package io.github.brunovinicioslg.ladeira.app.drive

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.app.appContainer
import io.github.brunovinicioslg.ladeira.app.settings.AppSettings
import io.github.brunovinicioslg.ladeira.drive.DriveEngine
import io.github.brunovinicioslg.ladeira.drive.DriveState
import io.github.brunovinicioslg.ladeira.drive.MotionEstimator
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.matching.GpsFix
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Follows the GPS while driving: matches each fix to a road, predicts the road ahead and speaks
 * the alerts. Runs as a location foreground service so it keeps working with the screen off or
 * another app in front. Started only from the visible app, so it needs no background location.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The engine is not thread-safe: every fix and every change to it runs on this one thread. */
    private val engineThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ladeira-drive") }
    private lateinit var engine: DriveEngine
    private val motion = MotionEstimator()
    private lateinit var phrases: AlertPhrases
    private var speech: AlertSpeech? = null
    private var started = false

    @Volatile private var active = false

    @Volatile private var settings = AppSettings()

    @Volatile private var lastFixAt = 0L
    private var lastBearing: Double? = null // engine thread

    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val session get() = appContainer.driveSession

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) = session.update { it.copy(gpsEnabled = true) }
        override fun onProviderDisabled(provider: String) = session.update { it.copy(gpsEnabled = false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = DriveEngine(appContainer.regionStore.roadSource, VehicleProfile.CAR)
        phrases = AlertPhrases(this)
        NavigationNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            if (startInForeground()) begin() else stopNavigation()
        }
        // Not sticky: a restart from the background could not use the location anyway.
        return START_NOT_STICKY
    }

    private fun startInForeground(): Boolean {
        if (!hasLocationPermission(this)) return false
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NavigationNotifications.NOTIFICATION_ID, NavigationNotifications.build(this, null), type)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked before start", e)
            false
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app went to the background first.
            Log.w(TAG, "Not allowed to start in the foreground", e)
            false
        }
    }

    private fun begin() {
        active = true
        val provider = bestProvider()
        session.update {
            it.copy(
                running = true,
                drive = DriveState(DriveState.Status.WAITING_FOR_GPS),
                bearing = null,
                gpsEnabled = provider != null && LocationManagerCompat.isLocationEnabled(locationManager),
            )
        }
        speech = AlertSpeech(this) { available -> session.update { it.copy(voiceAvailable = available) } }

        scope.launch {
            appContainer.settingsRepository.settings.collect { s ->
                settings = s
                onEngine { engine.profile = s.vehicle }
            }
        }
        // A region imported or removed while driving: load the roads again.
        scope.launch { appContainer.regionStore.regions.drop(1).collect { onEngine { engine.invalidate() } } }
        scope.launch {
            session.status.map { notificationText(it) }.distinctUntilChanged().collect { updateNotification(it) }
        }
        scope.launch { watchSignal() }

        if (provider == null) return
        val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, engineThread, listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked", e)
            stopNavigation()
        }
    }

    private fun bestProvider(): String? {
        val providers = locationManager.allProviders
        val preferred = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return preferred.firstOrNull { it in providers }
    }

    /** Runs on the engine thread. */
    private fun onFix(location: Location) {
        if (!active) return
        lastFixAt = SystemClock.elapsedRealtime()
        val fix = motion.complete(
            GpsFix(
                position = LatLon(location.latitude, location.longitude),
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else DEFAULT_ACCURACY_M,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
                timeMillis = location.elapsedRealtimeNanos / 1_000_000,
            ),
        )
        val update = try {
            engine.update(fix)
        } catch (e: RuntimeException) {
            // Damaged data must not end the trip: skip this fix, the next one may be fine.
            Log.e(TAG, "Drive engine failed on a fix", e)
            return
        }
        val bearing = travelBearing(update.state, fix)
        if (!active) return
        session.update { it.copy(drive = update.state, bearing = bearing) }
        val current = settings
        if (update.alerts.isNotEmpty() && current.voiceEnabled && engine.profile.voiceAlerts) {
            val sentences = update.alerts.map(phrases::speech)
            sentences.forEach { Log.i(TAG, "Alert: $it") }
            mainHandler.post { sentences.forEach { speech?.speak(it) } }
        }
    }

    /** Engine thread: the direction the road ahead goes, else the GPS heading, else the last one. */
    private fun travelBearing(state: DriveState, fix: GpsFix): Double? {
        val position = state.position
        val ahead = position?.let { p -> state.ahead.firstOrNull { Geo.distance(p, it.position) >= BEARING_LOOKAHEAD_M } }
        val bearing = when {
            position != null && ahead != null -> Geo.bearing(position, ahead.position)
            fix.bearingDegrees != null && (fix.speedMps ?: 0.0) >= MIN_SPEED_FOR_HEADING_MPS -> fix.bearingDegrees
            else -> lastBearing
        }
        lastBearing = bearing
        return bearing
    }

    /** Tunnels and garages: after a few seconds without fixes the screen says so. */
    private suspend fun watchSignal() {
        while (scope.isActive) {
            delay(SIGNAL_CHECK_MS)
            val silent = SystemClock.elapsedRealtime() - lastFixAt > SIGNAL_LOST_MS
            if (silent) {
                session.update { status ->
                    val drive = status.drive
                    if (drive == null || drive.status == DriveState.Status.WAITING_FOR_GPS) status
                    else status.copy(drive = DriveState(DriveState.Status.WAITING_FOR_GPS, position = drive.position))
                }
            }
        }
    }

    private fun notificationText(status: NavigationStatus): String? {
        val drive = status.drive ?: return null
        return when (drive.status) {
            DriveState.Status.WAITING_FOR_GPS -> getString(R.string.status_waiting_gps)
            DriveState.Status.NO_MAP_DATA -> getString(R.string.status_no_map_data)
            DriveState.Status.OFF_ROAD -> getString(R.string.status_off_road)
            DriveState.Status.ON_ROAD -> {
                val road = drive.road?.let { r -> r.ref?.let(phrases::roadNumber) ?: r.name } ?: getString(R.string.unnamed_road)
                val next = drive.current?.let { (_, kind) -> getString(R.string.now_item, phrases.kindName(kind)) }
                    ?: drive.next?.let { (_, kind) -> phrases.kindName(kind) }
                listOfNotNull(road, next).joinToString(" · ")
            }
        }
    }

    private fun updateNotification(text: String?) {
        if (!active) return
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(NavigationNotifications.NOTIFICATION_ID, NavigationNotifications.build(this, text))
    }

    private fun onEngine(block: () -> Unit) {
        if (!engineThread.isShutdown) engineThread.execute(block)
    }

    private fun stopNavigation() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        try {
            LocationManagerCompat.removeUpdates(locationManager, listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked; updates already stopped", e)
        }
        scope.cancel()
        speech?.shutdown()
        speech = null
        engineThread.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        session.update { NavigationStatus(voiceAvailable = it.voiceAvailable, gpsEnabled = it.gpsEnabled) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.brunovinicioslg.ladeira.action.STOP_NAVIGATION"
        private const val TAG = "NavigationService"
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val DEFAULT_ACCURACY_M = 20.0
        private const val SIGNAL_CHECK_MS = 2_000L
        private const val SIGNAL_LOST_MS = 8_000L
        private const val BEARING_LOOKAHEAD_M = 40.0
        private const val MIN_SPEED_FOR_HEADING_MPS = 2.0

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}

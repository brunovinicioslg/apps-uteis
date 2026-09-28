package io.github.brunovinicioslg.ladeira.app.drive

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.ladeira.app.TestData
import io.github.brunovinicioslg.ladeira.app.appContainer
import io.github.brunovinicioslg.ladeira.drive.DriveState
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/** The whole trip: GPS fixes in, road matched, alerts spoken, as the service runs on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class NavigationServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val container get() = app.appContainer
    private val locationManager = app.getSystemService(LocationManager::class.java)
    private var controller: ServiceController<NavigationService>? = null
    private var clock = 0L

    @Before
    fun setUp() {
        container.regionStore.install("serra.ldrp", TestData.serraPackage().inputStream())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(locationManager).setLocationEnabled(true)
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("pt-BR"))
    }

    @After
    fun tearDown() {
        controller?.destroy()
        ShadowTextToSpeech.reset()
    }

    private fun startService(): NavigationService {
        val c = Robolectric.buildService(NavigationService::class.java).create().startCommand(0, 1)
        controller = c
        idle()
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        return c.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun spoken(): List<String> = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).spokenTextList

    /** Sends one fix and waits for the engine thread to publish it. */
    private fun fix(position: LatLon, speedMps: Float = 20f, bearing: Float = 0f) {
        clock += 1_000
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = position.lat
            longitude = position.lon
            accuracy = 5f
            speed = speedMps
            this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = clock * 1_000_000
        }
        shadowOf(locationManager).simulateLocation(location)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            val shown = container.driveSession.status.value.drive?.position
            if (shown != null && Geo.distance(shown, position) < 30) return
            Thread.sleep(1)
        }
        throw AssertionError("The fix at $position was not published")
    }

    @Test
    fun drivingDownTheSerraAnnouncesTheDescentAndTheCameraOnce() {
        startService()
        assertThat(container.driveSession.status.value.running).isTrue()
        assertThat(container.driveSession.status.value.voiceAvailable).isTrue()

        var d = 0.0
        while (d <= 8_000.0) {
            fix(TestData.along(d))
            d += 20.0
        }
        idle()

        val status = container.driveSession.status.value
        assertThat(status.drive?.status).isEqualTo(DriveState.Status.ON_ROAD)
        assertThat(status.drive?.road?.ref).isEqualTo("BR-040")
        assertThat(status.bearing).isWithin(2.0).of(0.0)
        val said = spoken()
        assertThat(said.filter { it.startsWith("Descida longa em") }).hasSize(1)
        assertThat(said.single { it.startsWith("Descida longa") }).endsWith("Use o freio motor.")
        assertThat(said.filter { it.startsWith("Radar em") }).containsExactly("Radar em 300 metros. Limite de 80.")
    }

    @Test
    fun voiceOffStaysQuiet() {
        kotlinx.coroutines.runBlocking { container.settingsRepository.update { it.copy(voiceEnabled = false) } }
        startService()
        var d = 2_000.0
        while (d <= 3_500.0) {
            fix(TestData.along(d))
            d += 20.0
        }
        idle()
        assertThat(spoken()).isEmpty()
        kotlinx.coroutines.runBlocking { container.settingsRepository.update { it.copy(voiceEnabled = true) } }
    }

    @Test
    fun outsideTheRegionsTheScreenSaysSo() {
        startService()
        fix(LatLon(-15.8, -47.9), speedMps = 0f)
        assertThat(container.driveSession.status.value.drive?.status).isEqualTo(DriveState.Status.NO_MAP_DATA)
    }

    @Test
    fun stoppingClearsTheSessionAndReleasesTheVoice() {
        startService()
        fix(TestData.along(100.0))
        controller!!.destroy()
        controller = null
        idle()
        val status = container.driveSession.status.value
        assertThat(status.running).isFalse()
        assertThat(status.drive).isNull()
        assertThat(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).isShutdown).isTrue()
        assertThat(shadowOf(locationManager).getLocationRequests(LocationManager.GPS_PROVIDER)).isEmpty()
    }

    @Test
    fun withoutPermissionTheServiceStopsItself() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val c = Robolectric.buildService(NavigationService::class.java).create().startCommand(0, 1)
        controller = c
        idle()
        assertThat(shadowOf(c.get()).isStoppedBySelf).isTrue()
        assertThat(container.driveSession.status.value.running).isFalse()
    }
}

package io.github.brunovinicioslg.ladeira.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.brunovinicioslg.ladeira.app.drive.NavigationStatus
import io.github.brunovinicioslg.ladeira.app.ui.theme.LadeiraTheme
import io.github.brunovinicioslg.ladeira.drive.DriveState
import io.github.brunovinicioslg.ladeira.drive.RoadInfo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.profile.Slope
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class DrivePanelTest {

    @get:Rule val compose = createComposeRule()

    private var started = 0
    private var mapsOpened = 0
    private var marked = 0
    private var pointsOpened = 0
    private var vehicle: VehicleProfile? = null

    private fun show(status: NavigationStatus, hasRegions: Boolean = true, problem: PermissionProblem? = null, pendingMarks: Int = 0) {
        compose.setContent {
            LadeiraTheme {
                DriveScreen(
                    status = status,
                    vehicle = VehicleProfile.CAR,
                    hasRegions = hasRegions,
                    following = true,
                    permissionProblem = problem,
                    onStart = { started++ },
                    onStop = {},
                    onVehicleChange = { vehicle = it },
                    onOpenMaps = { mapsOpened++ },
                    onRecenter = {},
                    onOpenGpsSettings = {},
                    onOpenAppSettings = {},
                    map = { _, _ -> },
                    onMark = { marked++ },
                    pendingMarks = pendingMarks,
                    onOpenPoints = { pointsOpened++ },
                )
            }
        }
    }

    @Test
    fun withoutRegionsItPointsToTheMapsScreen() {
        show(NavigationStatus(), hasRegions = false)
        compose.onNodeWithText("Nenhum mapa instalado").assertIsDisplayed()
        compose.onNodeWithText("Mapas e ajustes").performClick()
        assertEquals(1, mapsOpened)
    }

    @Test
    fun readyToStartWithAVehicleChoice() {
        show(NavigationStatus())
        compose.onNodeWithText("Caminhão").performClick()
        assertEquals(VehicleProfile.TRUCK, vehicle)
        compose.onNodeWithText("Iniciar").performClick()
        assertEquals(1, started)
    }

    @Test
    fun deniedPermissionIsExplained() {
        show(NavigationStatus(), problem = PermissionProblem.APPROXIMATE_ONLY)
        compose.onNodeWithText("Ative a localização precisa para o Ladeira nas configurações.").assertIsDisplayed()
    }

    @Test
    fun drivingShowsTheRoadTheSlopeAheadAndTheCamera() {
        val slope = Slope(start = 1_200.0, end = 4_400.0, elevationChange = -192.0, maxGradePercent = 8.0)
        val drive = DriveState(
            status = DriveState.Status.ON_ROAD,
            position = LatLon(-20.0, -43.95),
            speedKmh = 71.6,
            road = RoadInfo(name = "Rodovia BR-040", ref = "BR-040;BR-356", maxSpeedKmh = 80),
            gradePercent = -1.0,
            elevation = 1_301.0,
            next = slope to SlopeKind.LONG_DESCENT,
            nextPoi = Poi(PoiType.SPEED_CAMERA, LatLon(-19.99, -43.95), speedLimitKmh = 80) to 2_730.0,
        )
        show(NavigationStatus(running = true, drive = drive, voiceAvailable = true))
        compose.onNodeWithText("72").assertIsDisplayed()
        compose.onNodeWithText("BR-040 / BR-356").assertIsDisplayed()
        compose.onNodeWithContentDescription("Limite de velocidade: 80 km/h").assertIsDisplayed()
        compose.onNodeWithText("Plano").assertIsDisplayed()
        compose.onNodeWithText("Descida longa em 1,2 km · 3,2 km a 6%").assertIsDisplayed()
        compose.onNodeWithText("Radar em 2,7 km · limite 80 km/h").assertIsDisplayed()
    }

    @Test
    fun markingWhileDrivingIsOneTap() {
        show(NavigationStatus(running = true, drive = DriveState(DriveState.Status.WAITING_FOR_GPS)))
        compose.onNodeWithText("Marcar aqui").performClick()
        assertEquals(1, marked)
    }

    @Test
    fun marksWithoutATypeAreRecalledWhenStopped() {
        show(NavigationStatus(), pendingMarks = 2)
        compose.onNodeWithText("2 pontos marcados sem tipo: escolher agora").performClick()
        assertEquals(1, pointsOpened)
    }

    @Test
    fun statusMessagesWhileDriving() {
        show(NavigationStatus(running = true, drive = DriveState(DriveState.Status.NO_MAP_DATA)))
        compose.onNodeWithText("Nenhum mapa instalado cobre este lugar.").assertIsDisplayed()
    }

    @Test
    fun theVehicleSitsLowInTheVisibleMap() {
        val insets = MapInsets.around(screenHeight = 2_400, topBarBottom = 300, panelHeight = 900)
        assertEquals(900, insets.bottom)
        // Padded area 900..1500: its center, 1200, is three quarters down the visible 300..1500.
        assertEquals(1_200, (insets.top + (2_400 - insets.bottom)) / 2)
        assertEquals(MapInsets(0, 0), MapInsets.around(0, 0, 0))
        assertEquals(MapInsets(0, 0), MapInsets.around(1_000, 300, 900))
    }

    @Test
    fun gpsOffOffersTheSettings() {
        show(NavigationStatus(running = true, drive = DriveState(DriveState.Status.WAITING_FOR_GPS), gpsEnabled = false))
        compose.onNodeWithText("O GPS está desligado.").assertIsDisplayed()
        compose.onNodeWithText("Ligar GPS").assertIsDisplayed()
    }
}

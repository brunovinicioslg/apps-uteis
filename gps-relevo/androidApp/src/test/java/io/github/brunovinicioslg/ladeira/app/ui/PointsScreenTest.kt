package io.github.brunovinicioslg.ladeira.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.brunovinicioslg.ladeira.app.ui.theme.LadeiraTheme
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.UserPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class PointsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val saved = mutableListOf<UserPoint>()
    private val deleted = mutableListOf<String>()

    private val mark = UserPoint("m", Poi(PoiType.OTHER, LatLon(-19.9, -43.9), directionDegrees = 12.0), createdAtMillis = 1_727_000_000_000, needsType = true)

    private fun show(points: List<UserPoint>) {
        compose.setContent {
            LadeiraTheme {
                PointsScreen(
                    points = points,
                    message = null,
                    onSave = { saved += it },
                    onDelete = { deleted += it },
                    onImport = {},
                    onExport = {},
                    onMessageShown = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun aMarkIsClassifiedAsACameraWithItsLimit() {
        show(listOf(mark))
        compose.onNodeWithText("Escolha o tipo").performClick()
        // Nothing chosen yet: saving would store "Alerta" by accident.
        compose.onNodeWithText("Salvar").assertIsNotEnabled()
        compose.onNodeWithText("Radar").performClick()
        compose.onNodeWithText("Limite (km/h)").performScrollTo().performTextInput("60")
        compose.onNodeWithText("Salvar").performClick()

        val point = saved.single()
        assertEquals(PoiType.SPEED_CAMERA, point.poi.type)
        assertEquals(60, point.poi.speedLimitKmh)
        assertEquals(12.0, point.poi.directionDegrees)
        assertFalse(point.needsType)
    }

    @Test
    fun aPointCanBeDeleted() {
        show(listOf(mark.copy(needsType = false, poi = mark.poi.copy(type = PoiType.POTHOLE))))
        compose.onNodeWithText("Buraco").performClick()
        compose.onNodeWithText("Apagar").performScrollTo().performClick()
        compose.onNodeWithText("Apagar este ponto?").assertExists()
        // The editor stays behind the confirmation: its button is the last one drawn.
        compose.onAllNodesWithText("Apagar").onLast().performClick()
        assertEquals(listOf("m"), deleted)
    }

    @Test
    fun withoutPointsItExplainsHowToMark() {
        show(emptyList())
        compose.onNodeWithText("Nenhum ponto marcado.", substring = true).assertExists()
        compose.onNodeWithText("Exportar").assertIsNotEnabled()
    }
}

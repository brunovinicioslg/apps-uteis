package io.github.brunovinicioslg.medeai.ui

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.medeai.geometry.Vec2
import io.github.brunovinicioslg.medeai.level.Level
import io.github.brunovinicioslg.medeai.settings.AppSettings
import io.github.brunovinicioslg.medeai.tilt.TiltSession
import io.github.brunovinicioslg.medeai.ui.level.LevelContent
import io.github.brunovinicioslg.medeai.ui.level.LevelState
import io.github.brunovinicioslg.medeai.ui.photo.PHOTO_CANVAS_TAG
import io.github.brunovinicioslg.medeai.ui.photo.PhotoMeasure
import io.github.brunovinicioslg.medeai.ui.photo.PhotoUiState
import io.github.brunovinicioslg.medeai.ui.photo.ReferenceChoice
import io.github.brunovinicioslg.medeai.ui.theme.MedeAiTheme
import io.github.brunovinicioslg.medeai.ui.tilt.TiltPanel
import io.github.brunovinicioslg.medeai.units.UnitSystem
import kotlin.math.PI
import kotlin.math.atan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w411dp-h2000dp")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { MedeAiTheme { content() } }

    @Test
    fun `home lists the tools and switches units`() {
        var opened: Tool? = null
        var units: UnitSystem? = null
        show { HomeScreen(AppSettings(UnitSystem.METRIC), onOpen = { opened = it }, onUnitSystemChange = { units = it }) }
        compose.onNodeWithText("Medir por foto").performClick()
        assertThat(opened).isEqualTo(Tool.PHOTO)
        compose.onNodeWithText("Nível").performClick()
        assertThat(opened).isEqualTo(Tool.LEVEL)
        compose.onNodeWithText("Medir com a câmera (AR)").performClick()
        assertThat(opened).isEqualTo(Tool.AR)
        compose.onNodeWithText("Imperial (pés, polegadas)").performClick()
        assertThat(units).isEqualTo(UnitSystem.IMPERIAL)
    }

    @Test
    fun `level shows when a surface is level`() {
        show { LevelContent(LevelState.Measuring(Level.read(0.0, 0.0, 9.81)!!)) }
        compose.onNodeWithText("Nivelado").assertExists()
    }

    @Test
    fun `level shows the tilt otherwise`() {
        val tilt = 2.0 * PI / 180
        show { LevelContent(LevelState.Measuring(Level.read(9.81 * kotlin.math.sin(tilt), 0.0, 9.81 * kotlin.math.cos(tilt))!!)) }
        compose.onNodeWithText("2,0°").assertExists()
        compose.onNodeWithText("Nivelado").assertDoesNotExist()
    }

    @Test
    fun `level explains a missing sensor`() {
        show { LevelContent(LevelState.NoSensor) }
        compose.onNodeWithText("Este aparelho não tem sensor de inclinação.").assertExists()
    }

    @Test
    fun `tilt panel previews and marks the base`() {
        var marked: Double? = null
        val aim = -atan(1.4 / 4.0) * 180 / PI // a floor point 4 m away
        show {
            TiltPanel(TiltSession(1.4), elevation = aim, unitSystem = UnitSystem.METRIC, onMark = { marked = it }, onRestart = {}, onCameraHeightChange = {})
        }
        compose.onNodeWithText("4,00 m").assertExists()
        compose.onNodeWithText("Marcar base").assertIsEnabled().performClick()
        assertThat(marked).isEqualTo(aim)
        compose.onNodeWithText("Altura do celular: 1,40 m (alterar)").assertExists()
    }

    @Test
    fun `tilt panel refuses an aim above the horizon`() {
        show {
            TiltPanel(TiltSession(1.4), elevation = 5.0, unitSystem = UnitSystem.METRIC, onMark = {}, onRestart = {}, onCameraHeightChange = {})
        }
        compose.onNodeWithText("Mire mais para baixo").assertExists()
        compose.onNodeWithText("Marcar base").assertIsNotEnabled()
    }

    @Test
    fun `tilt panel shows both results when done`() {
        show {
            TiltPanel(TiltSession(1.4, 4.0, 3.0), elevation = 0.0, unitSystem = UnitSystem.METRIC, onMark = {}, onRestart = {}, onCameraHeightChange = {})
        }
        compose.onNodeWithText("Distância: 4,00 m").assertExists()
        compose.onNodeWithText("Altura: 3,00 m").assertExists()
        compose.onNodeWithText("Recomeçar").assertExists()
    }

    @Test
    fun `photo taps land on image pixels`() {
        val taps = mutableListOf<Vec2>()
        var reference: ReferenceChoice? = null
        show {
            PhotoMeasure(
                image = android.graphics.Bitmap.createBitmap(1000, 800, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap(),
                state = PhotoUiState(),
                unitSystem = UnitSystem.METRIC,
                onTap = { taps += it },
                onMove = { _, _ -> },
                onUndo = {},
                onClear = {},
                onReference = { choice, _, _ -> reference = choice },
                onNewPhoto = {},
            )
        }
        compose.onNodeWithText("Toque nos 4 cantos do cartão (0 de 4). Arraste para ajustar.").assertExists()
        compose.onNodeWithTag(PHOTO_CANVAS_TAG).performTouchInput { click(center) }
        val tap = taps.single()
        assertThat(tap.x).isWithin(2.0).of(500.0)
        assertThat(tap.y).isWithin(2.0).of(400.0)
        compose.onNodeWithText("Folha A4").performClick()
        assertThat(reference).isEqualTo(ReferenceChoice.A4)
    }
}

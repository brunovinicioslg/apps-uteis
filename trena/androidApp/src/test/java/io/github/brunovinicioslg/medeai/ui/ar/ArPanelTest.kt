package io.github.brunovinicioslg.medeai.ui.ar

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.medeai.geometry.Vec3
import io.github.brunovinicioslg.medeai.measure.MeasureMode
import io.github.brunovinicioslg.medeai.measure.MeasureResult
import io.github.brunovinicioslg.medeai.ui.theme.MedeAiTheme
import io.github.brunovinicioslg.medeai.units.UnitSystem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w411dp-h900dp")
class ArPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val commands = mutableListOf<ArCommand>()

    private fun show(overlay: ArOverlay) = compose.setContent {
        MedeAiTheme { MeasurePanel(overlay, UnitSystem.METRIC, onCommand = { commands += it }) }
    }

    private val a = Vec3(0.0, 0.0, 0.0)

    @Test
    fun `live distance to the crosshair and adding a point`() {
        val result = MeasureResult.of(MeasureMode.DISTANCE, listOf(a), closed = false, crosshair = Vec3(1.234, 0.0, 0.0))
        show(ArOverlay(ArStatus.READY, MeasureMode.DISTANCE, points = listOf(ScreenPoint(10f, 10f)), result = result, canUndo = true))
        compose.onNodeWithText("1,23 m").assertExists()
        compose.onNodeWithContentDescription("Marcar ponto").performClick()
        compose.onNodeWithText("Desfazer").performClick()
        compose.onNodeWithText("Trechos").performClick()
        assertThat(commands).containsExactly(ArCommand.Add, ArCommand.Undo, ArCommand.Mode(MeasureMode.PATH)).inOrder()
    }

    @Test
    fun `nothing is added while the crosshair is off every surface`() {
        show(ArOverlay(ArStatus.FIND_SURFACE))
        compose.onNodeWithText("Aponte para o chão ou para uma parede e mova o celular devagar.").assertExists()
        compose.onNodeWithText("Mire no início e toque em Marcar ponto").assertExists()
        compose.onNodeWithContentDescription("Marcar ponto").performClick()
        assertThat(commands).isEmpty()
    }

    @Test
    fun `an outline closes on its first point and shows the area`() {
        val square = listOf(a, Vec3(2.0, 0.0, 0.0), Vec3(2.0, 0.0, 1.5))
        val result = MeasureResult.of(MeasureMode.AREA, square, closed = false, crosshair = a)
        show(ArOverlay(ArStatus.READY, MeasureMode.AREA, points = List(3) { ScreenPoint(0f, 0f) }, result = result, canUndo = true, snapToFirst = true))
        compose.onNodeWithText("Toque para fechar o contorno.").assertExists()
        compose.onNodeWithText("Área 1,50 m² · perímetro 6,00 m").assertExists()
        compose.onNodeWithContentDescription("Fechar contorno").performClick()
        assertThat(commands).containsExactly(ArCommand.Add)
    }

    @Test
    fun `a failed ARCore says to use the other modes`() {
        show(ArOverlay(ArStatus.FAILED))
        compose.onNodeWithText("A realidade aumentada parou de funcionar neste celular. Use as medidas por foto ou por inclinação.").assertExists()
    }
}

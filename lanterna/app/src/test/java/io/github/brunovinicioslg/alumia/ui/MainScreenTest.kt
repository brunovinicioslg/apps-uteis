package io.github.brunovinicioslg.alumia.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.alumia.core.detection.Sensitivity
import io.github.brunovinicioslg.alumia.core.settings.Settings
import io.github.brunovinicioslg.alumia.torch.TorchState
import io.github.brunovinicioslg.alumia.ui.theme.AlumiaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A tall screen keeps every list item composed, so absence checks are meaningful.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w411dp-h2400dp")
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var settings = Settings()
    private var torchClicks = 0

    private fun show(
        settings: Settings = Settings(),
        torch: TorchState = TorchState.Off,
        notificationsGranted: Boolean = true,
        batteryUnrestricted: Boolean = true,
    ) {
        this.settings = settings
        compose.setContent {
            AlumiaTheme {
                MainScreen(
                    state = MainScreenState(
                        settings = settings,
                        torch = torch,
                        torchMaxLevel = 1,
                        notificationsGranted = notificationsGranted,
                        batteryUnrestricted = batteryUnrestricted,
                        sideKeyEnabled = false,
                        manufacturer = Manufacturer.OTHER,
                    ),
                    actions = MainScreenActions(
                        onToggleTorch = { torchClicks++ },
                        onSettingsChange = { transform -> this.settings = transform(this.settings) },
                        onRequestNotifications = {},
                        onOpenBatterySettings = {},
                        onSideKeyChange = {},
                        onOpenSourceCode = {},
                    ),
                )
            }
        }
    }

    @Test
    fun `torch button toggles the light`() {
        show()
        compose.onNodeWithContentDescription("Ligar lanterna").performClick()
        assertThat(torchClicks).isEqualTo(1)
        compose.onNodeWithText("Lanterna desligada").assertExists()
    }

    @Test
    fun `lit torch offers to turn off`() {
        show(torch = TorchState.On(byApp = true))
        compose.onNodeWithContentDescription("Desligar lanterna").assertExists()
        compose.onNodeWithText("Lanterna ligada").assertExists()
    }

    @Test
    fun `devices without flash cannot press the button`() {
        show(torch = TorchState.NoFlash)
        compose.onNodeWithContentDescription("Ligar lanterna").assertIsNotEnabled()
        compose.onNodeWithText("Este aparelho não tem flash").assertExists()
    }

    @Test
    fun `gesture options only show while detection is on`() {
        show(settings = Settings(detectionEnabled = false))
        compose.onNodeWithText("Sensibilidade").assertDoesNotExist()
        compose.onNodeWithText("Chacoalhar para ligar").performClick()
        assertThat(settings.detectionEnabled).isTrue()
    }

    @Test
    fun `choosing a sensitivity updates settings`() {
        show()
        compose.onNodeWithText("Sensibilidade").assertExists()
        compose.onNodeWithText("Alta").performClick()
        assertThat(settings.sensitivity).isEqualTo(Sensitivity.HIGH)
    }

    @Test
    fun `choosing an auto off time updates settings`() {
        show()
        compose.onNodeWithText("10 min").performClick()
        assertThat(settings.autoOffMinutes).isEqualTo(10)
    }

    @Test
    fun `battery hint needs detection on and a restricted app`() {
        show(settings = Settings(detectionEnabled = true), batteryUnrestricted = false)
        compose.onNodeWithText("Evite que o sistema desligue o chacoalhar").assertExists()
    }

    @Test
    fun `no battery hint when detection is off`() {
        show(settings = Settings(detectionEnabled = false), batteryUnrestricted = false)
        compose.onNodeWithText("Evite que o sistema desligue o chacoalhar").assertDoesNotExist()
    }

    @Test
    fun `notification hint appears when permission is missing`() {
        show(notificationsGranted = false)
        compose.onNodeWithText("Permitir notificações").assertExists()
    }
}

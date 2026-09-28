package io.github.brunovinicioslg.alumia.service

import android.app.Notification
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.alumia.torch.TorchState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class TorchNotificationsTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }

    @Test
    fun `detecting with the light off offers turn on and disable`() {
        val n = TorchNotifications.build(context, detectionEnabled = true, torch = TorchState.Off)
        assertThat(n.title()).isEqualTo("Chacoalhe o celular para ligar a lanterna")
        assertThat(n.actionTitles()).containsExactly("Ligar", "Desativar chacoalhar").inOrder()
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
    }

    @Test
    fun `light kept on without detection only offers turn off`() {
        val n = TorchNotifications.build(context, detectionEnabled = false, torch = TorchState.On(byApp = true))
        assertThat(n.title()).isEqualTo("Lanterna ligada")
        assertThat(n.actionTitles()).containsExactly("Desligar")
    }

    @Test
    fun `devices without flash get no light action`() {
        val n = TorchNotifications.build(context, detectionEnabled = true, torch = TorchState.NoFlash)
        assertThat(n.actionTitles()).containsExactly("Desativar chacoalhar")
    }
}

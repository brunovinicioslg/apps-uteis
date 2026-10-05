package io.github.brunovinicioslg.sigilo.app.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SensitiveTest {

    @Test
    @Config(sdk = [34])
    fun `on Android 14 the window is kept from apps that are not accessibility tools`() {
        val view = View(ApplicationProvider.getApplicationContext())
        view.markAccessibilitySensitive()
        assertThat(view.isAccessibilityDataSensitive).isTrue()
    }

    @Test
    @Config(sdk = [34])
    fun `a dialog's content marks its own window`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { SensitiveWindow() }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(activity.window.decorView.isAccessibilityDataSensitive).isTrue()
    }

    @Test
    @Config(sdk = [33])
    fun `older Android versions are left alone`() {
        // The setting only exists from Android 14 on: calling it here would crash.
        View(ApplicationProvider.getApplicationContext()).markAccessibilitySensitive()
    }
}

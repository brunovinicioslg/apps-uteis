package io.github.brunovinicioslg.alumia.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.alumia.service.TorchNotifications
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceStatusTest {

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `shown while notifications are allowed`() {
        TorchNotifications.ensureChannel(context)
        assertThat(DeviceStatus.notificationShown(context)).isTrue()
    }

    @Test
    fun `hidden when the user turns its channel off`() {
        manager.createNotificationChannel(
            NotificationChannel(TorchNotifications.CHANNEL_ID, "Chacoalhar", NotificationManager.IMPORTANCE_NONE),
        )
        assertThat(DeviceStatus.notificationShown(context)).isFalse()
    }

    @Test
    fun `hidden when the app's notifications are off`() {
        TorchNotifications.ensureChannel(context)
        shadowOf(manager).setNotificationsEnabled(false)
        assertThat(DeviceStatus.notificationShown(context)).isFalse()
    }

    @Test
    fun `hidden without the permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(DeviceStatus.notificationShown(context)).isFalse()
    }
}

package io.github.brunovinicioslg.alumia.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.brunovinicioslg.alumia.R
import io.github.brunovinicioslg.alumia.torch.TorchState
import io.github.brunovinicioslg.alumia.ui.MainActivity

object TorchNotifications {
    const val CHANNEL_ID = "detection"
    const val NOTIFICATION_ID = 1

    private const val REQUEST_OPEN_APP = 10
    private const val REQUEST_TOGGLE_TORCH = 11
    private const val REQUEST_DISABLE_DETECTION = 12

    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_name))
            .setDescription(context.getString(R.string.notification_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun build(context: Context, detectionEnabled: Boolean, torch: TorchState): Notification {
        val torchOn = torch is TorchState.On
        val text = when {
            torchOn -> R.string.notification_torch_on
            detectionEnabled -> R.string.notification_detecting
            else -> R.string.notification_starting
        }
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .apply {
                if (torch != TorchState.NoFlash) {
                    addAction(
                        0,
                        context.getString(if (torchOn) R.string.action_turn_off else R.string.action_turn_on),
                        TorchServiceLauncher.pendingIntent(context, TorchService.ACTION_TOGGLE_TORCH, REQUEST_TOGGLE_TORCH),
                    )
                }
                if (detectionEnabled) {
                    addAction(
                        0,
                        context.getString(R.string.action_disable_detection),
                        TorchServiceLauncher.pendingIntent(context, TorchService.ACTION_DISABLE_DETECTION, REQUEST_DISABLE_DETECTION),
                    )
                }
            }
            .build()
    }
}

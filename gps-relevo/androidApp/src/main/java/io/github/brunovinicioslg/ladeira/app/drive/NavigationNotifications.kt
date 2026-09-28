package io.github.brunovinicioslg.ladeira.app.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.app.ui.MainActivity

object NavigationNotifications {
    const val CHANNEL_ID = "navigation"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.nav_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.nav_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, text: String?): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, NavigationService::class.java).setAction(NavigationService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.nav_notification_title))
            .setContentText(text ?: context.getString(R.string.status_waiting_gps))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

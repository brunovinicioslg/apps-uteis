package io.github.brunovinicioslg.sossego.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.rules.NotifyMode
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.data.HistoryRepository
import io.github.brunovinicioslg.sossego.device.DeviceStatus
import io.github.brunovinicioslg.sossego.text.callerText
import io.github.brunovinicioslg.sossego.text.reasonText
import io.github.brunovinicioslg.sossego.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

/**
 * The optional notifications about blocked calls: none by default, one per call, or a single one
 * with the day's count. Always silent.
 */
class BlockNotifier(
    private val context: Context,
    private val history: HistoryRepository,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_name))
            .setDescription(context.getString(R.string.notification_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Called off the main thread, after [call] was recorded. */
    fun onBlocked(call: BlockedCall, mode: NotifyMode) {
        if (mode == NotifyMode.NONE || !DeviceStatus.notificationsGranted(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val caller = callerText(context, call.key, call.reason)
        val notification = when (mode) {
            NotifyMode.EACH -> base()
                .setContentTitle(context.getString(R.string.notification_blocked_title))
                .setContentText(context.getString(R.string.notification_blocked_text, caller, reasonText(context, call.reason)))
                .setWhen(call.time)
                .setShowWhen(true)
                .build()
            else -> {
                val today = history.countSince(startOfDay(call.time))
                base()
                    .setContentTitle(context.resources.getQuantityString(R.plurals.notification_daily_title, today, today))
                    .setContentText(context.getString(R.string.notification_daily_last, caller))
                    .setOnlyAlertOnce(true)
                    .build()
            }
        }
        val id = if (mode == NotifyMode.EACH) EACH_ID_BASE + (call.time / 1000 % 100_000).toInt() else DAILY_ID
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // The permission was taken back between the check and now: nothing to show.
        }
    }

    private fun base(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_HISTORY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
    }

    private fun startOfDay(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli()

    companion object {
        const val CHANNEL_ID = "blocked_calls"
        private const val DAILY_ID = 1
        private const val EACH_ID_BASE = 1000
    }
}

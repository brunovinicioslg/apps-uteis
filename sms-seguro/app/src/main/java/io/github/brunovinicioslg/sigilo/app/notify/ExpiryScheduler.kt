package io.github.brunovinicioslg.sigilo.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.brunovinicioslg.sigilo.app.appContainer
import kotlinx.coroutines.launch

/**
 * Wakes the app when the next disappearing message is due. The alarm is inexact (no special
 * permission), so deletion can run a few minutes late; the screens hide expired messages on time
 * regardless, and a locked app deletes them as soon as it is opened.
 */
class ExpiryScheduler(private val context: Context) {
    fun schedule(at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, ExpiryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) alarms.cancel(intent) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }
}

class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val pending = goAsync()
        container.scope.launch {
            try {
                container.lock.withEngine { it.deleteExpired() }
            } finally {
                pending.finish()
            }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_READ) return
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        if (conversationId < 0) return
        val container = context.appContainer
        container.notifier.cancel(conversationId)
        val pending = goAsync()
        container.scope.launch {
            try {
                container.lock.withEngine { it.markRead(conversationId) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "io.github.brunovinicioslg.sigilo.action.MARK_READ"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}

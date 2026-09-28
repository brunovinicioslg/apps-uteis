package io.github.brunovinicioslg.alumia.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

object TorchServiceLauncher {
    private const val TAG = "TorchServiceLauncher"

    /**
     * Starts [TorchService] with [action]. Only succeeds from contexts Android allows to start a
     * foreground service (visible UI, widget/notification/tile click, boot broadcast).
     */
    fun send(context: Context, action: String): Boolean = try {
        ContextCompat.startForegroundService(context, intent(context, action))
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException on Android 12+.
        Log.w(TAG, "Not allowed to start the service now", e)
        false
    }

    /** For notification actions and widget buttons. */
    fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun intent(context: Context, action: String) = Intent(context, TorchService::class.java).setAction(action)
}

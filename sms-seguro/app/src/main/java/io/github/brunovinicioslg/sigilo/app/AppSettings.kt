package io.github.brunovinicioslg.sigilo.app

import android.content.Context
import androidx.core.content.edit

/**
 * Preferences that must be readable while the app is locked, so they live outside the encrypted
 * database. None of them is secret.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How long after leaving the app it locks (with a password set). */
    var autoLockMillis: Long
        get() = prefs.getLong(KEY_AUTO_LOCK, DEFAULT_AUTO_LOCK_MS)
        set(value) = prefs.edit { putLong(KEY_AUTO_LOCK, value) }

    /**
     * Show the text of ordinary SMS in notifications (encrypted ones never show it). Off by default:
     * screenshots are blocked in the app, but not in the notification shade.
     */
    var showOrdinaryContent: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CONTENT, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_CONTENT, value) }

    companion object {
        private const val KEY_AUTO_LOCK = "auto_lock_ms"
        private const val KEY_SHOW_CONTENT = "show_ordinary_content"
        const val DEFAULT_AUTO_LOCK_MS = 60_000L
        val AUTO_LOCK_CHOICES = listOf(0L, 60_000L, 5 * 60_000L, 30 * 60_000L)
    }
}

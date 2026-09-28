package io.github.brunovinicioslg.alumia.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the CPU awake until [release]. Uses a timed, periodically renewed lock so a bug can never
 * hold it forever: if renewal stops, the system releases it after [TIMEOUT_MS].
 */
class WakeLockHolder(context: Context, tag: String) {

    private val wakeLock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        ?.apply { setReferenceCounted(false) }
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    private val renew = object : Runnable {
        override fun run() {
            // Re-acquiring a non-reference-counted lock resets its timeout.
            wakeLock?.acquire(TIMEOUT_MS)
            handler.postDelayed(this, RENEW_MS)
        }
    }

    fun acquire() {
        if (active) return
        active = true
        renew.run()
    }

    fun release() {
        if (!active) return
        active = false
        handler.removeCallbacks(renew)
        if (wakeLock?.isHeld == true) wakeLock.release()
    }

    private companion object {
        const val TIMEOUT_MS = 10 * 60 * 1000L
        const val RENEW_MS = 9 * 60 * 1000L
    }
}

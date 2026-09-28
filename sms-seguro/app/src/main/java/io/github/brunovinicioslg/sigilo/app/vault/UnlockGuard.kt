package io.github.brunovinicioslg.sigilo.app.vault

import java.io.File
import java.io.IOException

/**
 * Slows down password guessing: after [FREE_ATTEMPTS] wrong passwords, each new try waits longer
 * (30 s, 1 min, 2 min... up to 15 min). The count survives restarts; it is not secret.
 */
class UnlockGuard(private val file: File, now: () -> Long = System::currentTimeMillis) {

    private var failures = 0
    private var lastFailureAt = 0L

    init {
        try {
            val parts = file.readText().trim().split(' ')
            failures = parts[0].toInt().coerceAtLeast(0)
            lastFailureAt = parts[1].toLong()
        } catch (_: IOException) {
            // No file yet: no failures.
        } catch (_: RuntimeException) {
            // Damaged: start from a pause rather than from zero, so corrupting it gains nothing.
            failures = FREE_ATTEMPTS
            lastFailureAt = now()
        }
    }

    val failedAttempts: Int get() = failures

    /** Milliseconds until the next try is allowed; 0 when it is allowed now. */
    fun waitMillis(now: Long): Long {
        if (failures < FREE_ATTEMPTS) return 0
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
        val pause = (FIRST_PAUSE_MS shl doublings).coerceAtMost(MAX_PAUSE_MS)
        // A clock set backwards must not unlock the pause either.
        val elapsed = (now - lastFailureAt).coerceAtLeast(0)
        return (pause - elapsed).coerceAtLeast(0)
    }

    fun recordFailure(now: Long) {
        failures++
        lastFailureAt = now
        save()
    }

    fun recordSuccess() {
        failures = 0
        lastFailureAt = 0
        save()
    }

    private fun save() {
        file.writeText("$failures $lastFailureAt")
    }

    companion object {
        const val FREE_ATTEMPTS = 5
        const val FIRST_PAUSE_MS = 30_000L
        const val MAX_PAUSE_MS = 15 * 60_000L
        private const val MAX_DOUBLINGS = 10
    }
}

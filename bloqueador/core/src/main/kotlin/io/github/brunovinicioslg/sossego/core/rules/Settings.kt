package io.github.brunovinicioslg.sossego.core.rules

import java.time.DayOfWeek
import java.time.LocalDateTime

/** What gets blocked. [OFF] is never stored as the chosen mode: it is [Settings.enabled] off. */
enum class Mode {
    OFF,

    /** Only numbers on the block list (and the extra filters that are on). */
    BLOCKLIST,

    /** Everyone except the allow list and the contacts. */
    ALLOWLIST,

    /** Every call, contacts included. */
    BLOCK_ALL,
}

/** What happens to a blocked call. */
enum class BlockAction {
    /** Declined, as if the user had pressed the red button (it may go to voicemail). */
    REJECT,

    /** Let through without ringing: it ends as a missed call. */
    SILENCE,
}

enum class NotifyMode {
    NONE,

    /** One notification per blocked call. */
    EACH,

    /** A single notification with the day's count. */
    DAILY_COUNT,
}

/**
 * A daily time window with another mode, such as "only the allow list from 22:00 to 07:00".
 * A window that crosses midnight belongs to the day it starts on.
 *
 * @param startMinute minutes after midnight; equal to [endMinute] means the whole day
 */
data class Schedule(
    val enabled: Boolean = false,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val mode: Mode = Mode.ALLOWLIST,
) {
    fun isActive(now: LocalDateTime): Boolean {
        if (!enabled || days.isEmpty()) return false
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek
        return when {
            startMinute == endMinute -> today in days
            startMinute < endMinute -> minute in startMinute until endMinute && today in days
            // Crosses midnight: the evening part is today's, the morning part yesterday's.
            minute >= startMinute -> today in days
            minute < endMinute -> today.minus(1) in days
            else -> false
        }
    }

    fun sanitized(): Schedule = copy(
        startMinute = startMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinute = endMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        mode = if (mode == Mode.OFF) Mode.ALLOWLIST else mode,
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * User preferences. Every value is always valid: [sanitized] fixes anything read from storage.
 * Turning blocking off keeps [mode], so turning it back on (the Quick Settings tile) restores it.
 */
data class Settings(
    val enabled: Boolean = true,
    val mode: Mode = Mode.BLOCKLIST,
    val action: BlockAction = BlockAction.REJECT,
    /** Blocked calls still show in the phone app's call history (as blocked). */
    val keepInCallLog: Boolean = true,
    val notifications: NotifyMode = NotifyMode.NONE,
    val blockHidden: Boolean = false,
    val blockTelemarketing: Boolean = true,
    val blockInternational: Boolean = false,
    /** With the allow list only: a number that calls again within [REPEAT_WINDOW_MS] gets through. */
    val repeatCallers: Boolean = true,
    val schedule: Schedule = Schedule(),
) {
    /** The mode in force at [now]. */
    fun effectiveMode(now: LocalDateTime): Mode = when {
        !enabled -> Mode.OFF
        schedule.isActive(now) -> schedule.mode
        else -> mode
    }

    fun sanitized(): Settings = copy(
        mode = if (mode == Mode.OFF) Mode.BLOCKLIST else mode,
        schedule = schedule.sanitized(),
    )

    companion object {
        const val REPEAT_WINDOW_MS = 3 * 60_000L
    }
}

package io.github.brunovinicioslg.alumia.core.policy

import kotlin.math.roundToInt

/** Why a detected shake was not allowed to toggle the light. */
enum class GestureBlock {
    IN_CALL,
    IN_POCKET,
}

object Policies {

    /**
     * The background service runs while detection is on or while the app itself keeps the light on
     * (the camera service turns the torch off when the process that turned it on dies). It never
     * stops while a command is still being handled or before settings are loaded.
     */
    fun shouldKeepServiceRunning(
        settingsLoaded: Boolean,
        detectionEnabled: Boolean,
        torchOnByApp: Boolean,
        pendingCommands: Int,
    ): Boolean = !settingsLoaded || pendingCommands > 0 || detectionEnabled || torchOnByApp

    /** True only on the transition into the low-battery zone, so the user can turn the light back on. */
    fun crossedLowBattery(previousPercent: Int?, currentPercent: Int, thresholdPercent: Int): Boolean {
        if (thresholdPercent <= 0 || previousPercent == null) return false
        return previousPercent > thresholdPercent && currentPercent <= thresholdPercent
    }

    fun gestureBlock(inCall: Boolean, proximityCovered: Boolean, ignoreInPocket: Boolean): GestureBlock? = when {
        inCall -> GestureBlock.IN_CALL
        ignoreInPocket && proximityCovered -> GestureBlock.IN_POCKET
        else -> null
    }

    /** Maps a 1..100 brightness percentage to a hardware torch level in 1..[maxLevel]. */
    fun torchLevel(percent: Int, maxLevel: Int): Int {
        if (maxLevel <= 1) return 1
        val clamped = percent.coerceIn(1, 100)
        return (clamped / 100f * maxLevel).roundToInt().coerceIn(1, maxLevel)
    }
}

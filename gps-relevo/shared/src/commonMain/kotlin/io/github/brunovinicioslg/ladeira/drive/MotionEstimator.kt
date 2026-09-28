package io.github.brunovinicioslg.ladeira.drive

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.matching.GpsFix
import kotlin.math.max

/**
 * Fills in speed and heading from consecutive positions when the location source leaves them out
 * (some receivers, emulators, network locations). Reported values are always kept as they are.
 */
class MotionEstimator {
    private var previous: GpsFix? = null

    fun complete(fix: GpsFix): GpsFix {
        val before = previous
        previous = fix
        if (before == null || (fix.speedMps != null && fix.bearingDegrees != null)) return fix
        val seconds = (fix.timeMillis - before.timeMillis) / 1000.0
        if (seconds <= 0 || seconds > MAX_GAP_S) return fix
        val meters = Geo.distance(before.position, fix.position)
        // Movement smaller than the GPS error is noise, not driving.
        val moved = meters >= max(MIN_MOVE_M, fix.accuracyM / 2)
        return fix.copy(
            speedMps = fix.speedMps ?: if (moved) meters / seconds else 0.0,
            bearingDegrees = fix.bearingDegrees ?: if (moved) Geo.bearing(before.position, fix.position) else null,
        )
    }

    private companion object {
        const val MAX_GAP_S = 10.0
        const val MIN_MOVE_M = 3.0
    }
}

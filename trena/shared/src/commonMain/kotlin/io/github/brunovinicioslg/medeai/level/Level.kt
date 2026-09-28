package io.github.brunovinicioslg.medeai.level

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

/** Spirit level from a gravity reading in device axes (Android reports the resting reading pointing up). */
object Level {

    enum class Orientation {
        /** Lying on a surface: checks the surface itself (two axes). */
        FLAT,

        /** Standing on an edge: checks that edge against horizontal or vertical. */
        UPRIGHT,
    }

    data class Reading(
        val orientation: Orientation,
        /** Surface tilt towards the device's x axis (right side up is positive), degrees. */
        val tiltX: Double,
        /** Surface tilt towards the device's y axis (top side up is positive), degrees. */
        val tiltY: Double,
        /** Total surface tilt, degrees. */
        val surfaceTilt: Double,
        /** Upright only: angle of the phone's edges from the nearest horizontal/vertical, in [-45, 45]. */
        val edgeError: Double,
    ) {
        /** Deviation that matters for the current orientation. */
        val error: Double get() = if (orientation == Orientation.FLAT) surfaceTilt else abs(edgeError)
    }

    /** Beyond 45° from lying flat the phone is treated as standing on an edge. */
    private const val FLAT_LIMIT_DEGREES = 45.0

    fun read(gx: Double, gy: Double, gz: Double): Reading? {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (!g.isFinite() || g < 1e-6) return null
        val surfaceTilt = degrees(acos((abs(gz) / g).coerceIn(-1.0, 1.0)))
        val orientation = if (surfaceTilt <= FLAT_LIMIT_DEGREES) Orientation.FLAT else Orientation.UPRIGHT
        val inPlane = degrees(atan2(gx, gy))
        // The reading is the "up" direction in device axes: raising the right side tilts the x axis
        // upwards, so up gains a positive x component.
        return Reading(
            orientation = orientation,
            tiltX = degrees(asin((gx / g).coerceIn(-1.0, 1.0))),
            tiltY = degrees(asin((gy / g).coerceIn(-1.0, 1.0))),
            surfaceTilt = surfaceTilt,
            edgeError = inPlane - 90 * round(inPlane / 90),
        )
    }

    /**
     * Rotates the x/y of a device-axes reading into screen axes for the display rotation, given in
     * quarter turns as Android's Surface.ROTATION_* constants (0..3). Z is unaffected.
     */
    fun toScreenAxes(x: Double, y: Double, quarterTurns: Int): Pair<Double, Double> = when (quarterTurns.mod(4)) {
        1 -> -y to x
        2 -> -x to -y
        3 -> y to -x
        else -> x to y
    }

    private fun degrees(radians: Double) = radians * 180 / PI
}

package io.github.brunovinicioslg.medeai.tilt

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Measuring without AR: with the phone held at a known height, the angle at which the camera sees
 * a point on the floor gives the distance to it (trigonometry), and the angle to the top of an
 * object then gives its height. Accuracy depends on holding the phone at the stated height.
 */
object TiltRanging {

    /** Below this depression angle the distance explodes with tiny angle errors. */
    const val MIN_DEPRESSION_DEGREES = 3.0
    const val MAX_DEPRESSION_DEGREES = 89.0

    /**
     * Elevation of the back camera's optical axis above the horizon in degrees (-90 = straight
     * down), from a gravity reading in device axes. Android reports the reading of a phone at
     * rest pointing up, and the back camera looks along the device's -Z axis.
     */
    fun cameraElevationDegrees(gx: Double, gy: Double, gz: Double): Double? {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (!g.isFinite() || g < 1e-6) return null
        return asin((-gz / g).coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /**
     * Horizontal distance to a floor point seen [depressionDegrees] below the horizon from a camera
     * [cameraHeight] meters above the floor. Null outside the reliable angle range.
     */
    fun distanceToFloorPoint(cameraHeight: Double, depressionDegrees: Double): Double? {
        if (!(cameraHeight > 0.0) || depressionDegrees !in MIN_DEPRESSION_DEGREES..MAX_DEPRESSION_DEGREES) return null
        return cameraHeight / tan(depressionDegrees * PI / 180)
    }

    /**
     * Height above the floor of a point at horizontal [distance] seen at [elevationDegrees] (positive
     * above the horizon). Null for angles too close to vertical.
     */
    fun pointHeight(cameraHeight: Double, distance: Double, elevationDegrees: Double): Double? {
        if (!(distance > 0.0) || elevationDegrees !in -MAX_DEPRESSION_DEGREES..MAX_DEPRESSION_DEGREES) return null
        return cameraHeight + distance * tan(elevationDegrees * PI / 180)
    }
}

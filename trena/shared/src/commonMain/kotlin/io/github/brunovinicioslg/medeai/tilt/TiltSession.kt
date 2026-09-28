package io.github.brunovinicioslg.medeai.tilt

/**
 * The tilt measurement flow: aim at the base of an object (where it touches the floor) to get its
 * distance, then at its top to get its height. Immutable: every action returns a new session.
 */
data class TiltSession(
    /** Height of the phone above the floor, in meters. */
    val cameraHeight: Double,
    /** Horizontal distance to the object's base, once marked. */
    val baseDistance: Double? = null,
    /** Height of the object's top above the floor, once marked. */
    val objectHeight: Double? = null,
) {
    enum class Step { AIM_AT_BASE, AIM_AT_TOP, DONE }

    val step: Step
        get() = when {
            baseDistance == null -> Step.AIM_AT_BASE
            objectHeight == null -> Step.AIM_AT_TOP
            else -> Step.DONE
        }

    /** What the current step would measure at the given camera elevation; null if unreliable. */
    fun preview(elevationDegrees: Double): Double? = when (step) {
        Step.AIM_AT_BASE -> TiltRanging.distanceToFloorPoint(cameraHeight, -elevationDegrees)
        Step.AIM_AT_TOP -> baseDistance?.let { TiltRanging.pointHeight(cameraHeight, it, elevationDegrees) }
        Step.DONE -> null
    }

    /** Records the current step; returns the same session when the aim is unreliable. */
    fun mark(elevationDegrees: Double): TiltSession {
        val value = preview(elevationDegrees) ?: return this
        return when (step) {
            Step.AIM_AT_BASE -> copy(baseDistance = value)
            Step.AIM_AT_TOP -> copy(objectHeight = value.coerceAtLeast(0.0))
            Step.DONE -> this
        }
    }

    fun restart() = TiltSession(cameraHeight)

    fun withCameraHeight(meters: Double) = TiltSession(meters.coerceIn(MIN_CAMERA_HEIGHT, MAX_CAMERA_HEIGHT))

    companion object {
        const val MIN_CAMERA_HEIGHT = 0.3
        const val MAX_CAMERA_HEIGHT = 2.5
    }
}

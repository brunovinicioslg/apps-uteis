package io.github.brunovinicioslg.alumia.core.detection

/**
 * Minimum linear acceleration (gravity removed, in m/s²) a movement must reach to count as a stroke.
 */
enum class Sensitivity(val thresholdMs2: Float) {
    LOW(15f),
    MEDIUM(11f),
    HIGH(8f),
}

/**
 * Tuning of [ShakeDetector].
 *
 * A shake is a sequence of [requiredStrokes] strokes, each pointing roughly opposite to the previous
 * one (back and forth), with consecutive strokes between [minStrokeGapNanos] and [maxStrokeGapNanos]
 * apart. The upper gap keeps slow swings, walking and running (steps ~330 ms apart) from firing.
 */
data class ShakeConfig(
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val requiredStrokes: Int = DEFAULT_STROKES,
    val minStrokeGapNanos: Long = 60 * NANOS_PER_MILLI,
    val maxStrokeGapNanos: Long = 300 * NANOS_PER_MILLI,
    val cooldownNanos: Long = 1_500 * NANOS_PER_MILLI,
    val gravityTimeConstantNanos: Long = 400 * NANOS_PER_MILLI,
) {
    init {
        require(requiredStrokes in MIN_STROKES..MAX_STROKES) {
            "requiredStrokes must be in $MIN_STROKES..$MAX_STROKES, was $requiredStrokes"
        }
        require(minStrokeGapNanos in 1 until maxStrokeGapNanos) { "invalid stroke gap range" }
        require(cooldownNanos >= 0) { "cooldownNanos must not be negative" }
        require(gravityTimeConstantNanos > 0) { "gravityTimeConstantNanos must be positive" }
    }

    companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MIN_STROKES = 2
        const val MAX_STROKES = 6
        const val DEFAULT_STROKES = 3
    }
}

package io.github.brunovinicioslg.ladeira.profile

import kotlin.math.abs

/** Minimum average grade and length for a slope to be worth a warning. */
data class SlopeRule(val minAverageGradePercent: Double, val minLengthM: Double)

enum class SlopeKind { CLIMB, DESCENT, LONG_DESCENT }

/** Warning thresholds tuned to what matters for each kind of vehicle. */
enum class VehicleProfile(
    val climb: SlopeRule?,
    val descent: SlopeRule?,
    /** Descents this long deserve an engine-braking warning. */
    val longDescentM: Double,
    val voiceAlerts: Boolean,
) {
    CAR(SlopeRule(6.0, 400.0), SlopeRule(6.0, 400.0), longDescentM = 2_000.0, voiceAlerts = true),

    /** Heavy vehicles: long descents overheat brakes, climbs cost speed. */
    TRUCK(SlopeRule(4.0, 500.0), SlopeRule(4.0, 800.0), longDescentM = 1_500.0, voiceAlerts = true),
    MOTORCYCLE(SlopeRule(6.0, 400.0), SlopeRule(6.0, 300.0), longDescentM = 2_000.0, voiceAlerts = true),
    BICYCLE(SlopeRule(3.0, 200.0), SlopeRule(5.0, 300.0), longDescentM = 1_000.0, voiceAlerts = true),

    /** Profile only, no spoken warnings. */
    WALKING(null, null, longDescentM = Double.MAX_VALUE, voiceAlerts = false),
    ;

    /** Null when the slope is not worth warning about. */
    fun classify(slope: Slope): SlopeKind? {
        val rule = if (slope.isClimb) climb else descent
        rule ?: return null
        if (abs(slope.averageGradePercent) < rule.minAverageGradePercent || slope.length < rule.minLengthM) return null
        return when {
            slope.isClimb -> SlopeKind.CLIMB
            slope.length >= longDescentM -> SlopeKind.LONG_DESCENT
            else -> SlopeKind.DESCENT
        }
    }
}

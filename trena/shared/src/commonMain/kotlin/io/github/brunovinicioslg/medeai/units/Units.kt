package io.github.brunovinicioslg.medeai.units

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

enum class UnitSystem { METRIC, IMPERIAL }

enum class LengthUnit(val metersPerUnit: Double, val symbol: String, val defaultDecimals: Int) {
    MILLIMETER(0.001, "mm", 0),
    CENTIMETER(0.01, "cm", 1),
    METER(1.0, "m", 2),
    INCH(0.0254, "in", 1),
    FOOT(0.3048, "ft", 2),

    /** Feet and inches, e.g. "5 ft 3,5 in". */
    FOOT_INCH(0.3048, "ft", 1),
    ;

    companion object {
        /** A readable unit for [meters]: centimeters below 1 m, meters above; feet and inches for imperial. */
        fun auto(system: UnitSystem, meters: Double): LengthUnit = when (system) {
            UnitSystem.METRIC -> if (abs(meters) < 1.0) CENTIMETER else METER
            UnitSystem.IMPERIAL -> FOOT_INCH
        }
    }
}

/**
 * Formats measurements without platform locale APIs (shared with iOS). Rounds half away from zero,
 * never shows "-0", and carries rounding into the next unit (11.96 in becomes 1 ft 0,0 in).
 */
class MeasureFormatter(private val decimalSeparator: Char = ',') {

    fun length(meters: Double, unit: LengthUnit, decimals: Int = unit.defaultDecimals): String {
        if (!meters.isFinite()) return "—"
        if (unit == LengthUnit.FOOT_INCH) return feetAndInches(meters, decimals)
        return "${number(meters / unit.metersPerUnit, decimals)} ${unit.symbol}"
    }

    fun length(meters: Double, system: UnitSystem): String = length(meters, LengthUnit.auto(system, meters))

    /** Square centimeters/meters for metric, square inches/feet for imperial. */
    fun area(squareMeters: Double, system: UnitSystem): String {
        if (!squareMeters.isFinite()) return "—"
        return when (system) {
            UnitSystem.METRIC ->
                if (abs(squareMeters) < 1.0) "${number(squareMeters / CM2, 0)} cm²" else "${number(squareMeters, 2)} m²"
            UnitSystem.IMPERIAL ->
                if (abs(squareMeters) < FT2) "${number(squareMeters / IN2, 1)} in²" else "${number(squareMeters / FT2, 2)} ft²"
        }
    }

    fun degrees(value: Double, decimals: Int = 1): String = if (value.isFinite()) "${number(value, decimals)}°" else "—"

    private fun feetAndInches(meters: Double, inchDecimals: Int): String {
        val factor = 10.0.pow(inchDecimals)
        val totalSteps = roundHalfUp(abs(meters) / INCH * factor)
        val stepsPerFoot = (12 * factor).toLong()
        val feet = totalSteps / stepsPerFoot
        val inches = (totalSteps % stepsPerFoot) / factor
        val sign = if (meters < 0 && totalSteps != 0L) "-" else ""
        val inchText = "${number(inches, inchDecimals)} in"
        return if (feet == 0L) "$sign$inchText" else "$sign$feet ft $inchText"
    }

    /** Fixed number of decimals, no digit grouping. */
    fun number(value: Double, decimals: Int): String {
        require(decimals in 0..6) { "decimals must be in 0..6" }
        val factor = 10.0.pow(decimals).toLong()
        val scaled = roundHalfUp(abs(value) * factor)
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        val whole = scaled / factor
        if (decimals == 0) return "$sign$whole"
        val fraction = (scaled % factor).toString().padStart(decimals, '0')
        return "$sign$whole$decimalSeparator$fraction"
    }

    // The tiny offset absorbs binary representation error (1.005 is stored as 1.00499999...).
    private fun roundHalfUp(nonNegative: Double): Long = floor(nonNegative + 0.5 + 1e-9).toLong()

    private companion object {
        const val INCH = 0.0254
        const val CM2 = 1e-4
        const val IN2 = 0.00064516
        const val FT2 = 0.09290304
    }
}

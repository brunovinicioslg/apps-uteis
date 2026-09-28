package io.github.brunovinicioslg.ladeira.profile

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Elevation at a distance along a path. */
data class ProfilePoint(val distance: Double, val elevation: Double)

/** A continuous climb or descent along a path. Distances are measured from the path start. */
data class Slope(
    val start: Double,
    val end: Double,
    val elevationChange: Double,
    /** Steepest grade over any [SlopeDetector.MAX_GRADE_WINDOW_M] window, in percent (absolute value). */
    val maxGradePercent: Double,
) {
    val length: Double get() = end - start
    val averageGradePercent: Double get() = if (length > 0) elevationChange / length * 100 else 0.0
    val isClimb: Boolean get() = elevationChange > 0
}

/**
 * Finds climbs and descents in an elevation profile. Terrain models are noisy at road scale (they
 * measure the hill a highway cuts through, not the cut), so the profile is resampled, smoothed over
 * [SMOOTHING_WINDOW_M], and short interruptions are bridged: a long descent with a short flat bit
 * in the middle is still one long descent.
 */
object SlopeDetector {
    const val STEP_M = 25.0
    const val SMOOTHING_WINDOW_M = 250.0
    const val MAX_GRADE_WINDOW_M = 200.0

    fun detect(
        profile: List<ProfilePoint>,
        minGradePercent: Double = 3.0,
        minLengthM: Double = 200.0,
        maxGapM: Double = 150.0,
    ): List<Slope> {
        val samples = smooth(resample(profile))
        return segments(samples, minGradePercent, maxGapM).map { toSlope(samples, it) }
            .filter { it.length >= minLengthM && abs(it.averageGradePercent) >= minGradePercent }
    }

    /**
     * Slopes a [vehicle] should be warned about. Detection runs with a looser threshold than the
     * vehicle's rule, because noise breaks a steady 6 % climb into pieces that dip under 6 %; each
     * stretch found is then judged by its average, and when the whole stretch is too gentle, its
     * longest part that still meets the rule is reported instead.
     */
    fun detectFor(profile: List<ProfilePoint>, vehicle: VehicleProfile, maxGapM: Double = 150.0): List<Pair<Slope, SlopeKind>> {
        val rules = listOfNotNull(vehicle.climb, vehicle.descent)
        if (rules.isEmpty()) return emptyList()
        val samples = smooth(resample(profile))
        val detection = (rules.minOf { it.minAverageGradePercent } - DETECTION_MARGIN_PERCENT).coerceAtLeast(MIN_DETECTION_PERCENT)
        return segments(samples, detection, maxGapM).mapNotNull { range ->
            val whole = toSlope(samples, range)
            val rule = (if (whole.isClimb) vehicle.climb else vehicle.descent) ?: return@mapNotNull null
            val part = if (qualifies(whole, rule)) range else longestQualifyingPart(samples, range, rule, whole.isClimb)
            part?.let { trimGentleEnds(samples, it, rule, whole.isClimb) }
                ?.let { toSlope(samples, it) }
                ?.let { s -> vehicle.classify(s)?.let { s to it } }
        }
    }

    /**
     * Drops gentle lead-ins and run-outs so a warning describes the steep core ("8 % for 800 m",
     * not "6 % for 1.4 km" averaged with a gentle approach), while keeping the rule satisfied.
     */
    private fun trimGentleEnds(samples: List<ProfilePoint>, range: IntRange, rule: SlopeRule, climb: Boolean): IntRange {
        val sign = if (climb) 1 else -1
        val window = (EDGE_WINDOW_M / STEP_M).roundToInt()
        val minSteps = (rule.minLengthM / STEP_M).roundToInt()
        fun grade(i: Int, j: Int) = (samples[j].elevation - samples[i].elevation) / (samples[j].distance - samples[i].distance) * 100 * sign
        var i = range.first
        var j = range.last + 1
        while (j - i > minSteps && i + window <= j && grade(i, i + window) < rule.minAverageGradePercent && grade(i + 1, j) >= rule.minAverageGradePercent) i++
        while (j - i > minSteps && j - window >= i && grade(j - window, j) < rule.minAverageGradePercent && grade(i, j - 1) >= rule.minAverageGradePercent) j--
        return i until j
    }

    private const val EDGE_WINDOW_M = 100.0

    private const val DETECTION_MARGIN_PERCENT = 2.5
    private const val MIN_DETECTION_PERCENT = 2.0

    private fun qualifies(s: Slope, rule: SlopeRule) =
        s.length >= rule.minLengthM && abs(s.averageGradePercent) >= rule.minAverageGradePercent

    /** Longest sub-stretch whose average grade meets [rule]; O(n²) on at most a few hundred samples. */
    private fun longestQualifyingPart(samples: List<ProfilePoint>, range: IntRange, rule: SlopeRule, climb: Boolean): IntRange? {
        val sign = if (climb) 1 else -1
        val from = range.first
        val to = range.last + 1
        val minSteps = (rule.minLengthM / STEP_M).roundToInt()
        var best: Pair<Int, Int>? = null
        for (i in from until to) {
            for (j in to downTo i + minSteps) {
                if (best != null && j - i <= best.second - best.first) break
                val grade = (samples[j].elevation - samples[i].elevation) / (samples[j].distance - samples[i].distance) * 100
                if (grade * sign >= rule.minAverageGradePercent) {
                    best = i to j
                    break
                }
            }
        }
        return best?.let { (i, j) -> i until j }
    }

    /** Interval index ranges (interval i spans samples i..i+1) of consistent climbing or descending. */
    private fun segments(samples: List<ProfilePoint>, minGradePercent: Double, maxGapM: Double): List<IntRange> {
        if (samples.size < 3) return emptyList()
        val threshold = minGradePercent / 100
        val direction = IntArray(samples.size - 1) { i ->
            val g = (samples[i + 1].elevation - samples[i].elevation) / STEP_M
            when {
                g >= threshold -> 1
                g <= -threshold -> -1
                else -> 0
            }
        }
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < direction.size) {
            val sign = direction[i]
            if (sign == 0) {
                i++
                continue
            }
            var end = i
            var j = i + 1
            while (j < direction.size) {
                if (direction[j] == sign) {
                    end = j
                    j++
                    continue
                }
                // Look past a short interruption that does not reverse the trend by more than 2 m.
                var k = j
                while (k < direction.size && direction[k] != sign && (k - j + 1) * STEP_M <= maxGapM) k++
                val bridged = k < direction.size && direction[k] == sign &&
                    (samples[k].elevation - samples[j].elevation) * sign > -2.0
                if (!bridged) break
                end = k
                j = k + 1
            }
            runs += i..end
            i = end + 1
        }
        return runs
    }

    private fun toSlope(samples: List<ProfilePoint>, range: IntRange): Slope {
        val start = samples[range.first]
        val finish = samples[range.last + 1]
        return Slope(
            start = start.distance,
            end = finish.distance,
            elevationChange = finish.elevation - start.elevation,
            maxGradePercent = maxWindowGrade(samples, range.first, range.last + 1),
        )
    }

    /** Linear resampling every [STEP_M] meters; drops points out of distance order. */
    fun resample(profile: List<ProfilePoint>): List<ProfilePoint> {
        val clean = profile.filter { it.distance.isFinite() && it.elevation.isFinite() }
            .fold(mutableListOf<ProfilePoint>()) { acc, p -> acc.also { if (it.isEmpty() || p.distance > it.last().distance) it += p } }
        if (clean.size < 2) return clean
        val out = mutableListOf<ProfilePoint>()
        var j = 0
        var d = clean.first().distance
        while (d <= clean.last().distance + 1e-9) {
            while (j < clean.size - 2 && clean[j + 1].distance < d) j++
            val a = clean[j]
            val b = clean[j + 1]
            val t = ((d - a.distance) / (b.distance - a.distance)).coerceIn(0.0, 1.0)
            out += ProfilePoint(d, a.elevation + (b.elevation - a.elevation) * t)
            d += STEP_M
        }
        return out
    }

    /**
     * Centered moving average over [SMOOTHING_WINDOW_M]. Near the ends the window shrinks
     * symmetrically: a lopsided window would bias the grade of the first and last meters, and the
     * first meters matter most (they say whether the vehicle is already on a slope).
     */
    fun smooth(samples: List<ProfilePoint>): List<ProfilePoint> {
        val half = (SMOOTHING_WINDOW_M / STEP_M / 2).roundToInt()
        if (samples.size < 3 || half == 0) return samples
        return samples.indices.map { i ->
            val reach = min(half, min(i, samples.size - 1 - i))
            var sum = 0.0
            for (k in i - reach..i + reach) sum += samples[k].elevation
            ProfilePoint(samples[i].distance, sum / (2 * reach + 1))
        }
    }

    private fun maxWindowGrade(samples: List<ProfilePoint>, from: Int, to: Int): Double {
        val window = (MAX_GRADE_WINDOW_M / STEP_M).roundToInt().coerceAtLeast(1)
        var best = 0.0
        for (i in from until to) {
            val j = min(i + window, to)
            if (j <= i) continue
            val g = abs(samples[j].elevation - samples[i].elevation) / (samples[j].distance - samples[i].distance) * 100
            if (g > best) best = g
        }
        return best
    }
}

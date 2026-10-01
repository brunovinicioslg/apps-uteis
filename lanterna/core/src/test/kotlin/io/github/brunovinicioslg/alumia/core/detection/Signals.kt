package io.github.brunovinicioslg.alumia.core.detection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal const val G = 9.80665

internal data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(dot(this))
    fun normalized() = this * (1.0 / norm())

    /** Any unit vector perpendicular to this one. */
    fun perpendicular(rnd: Random): Vec3 {
        while (true) {
            val candidate = randomUnit(rnd)
            val p = candidate - this * candidate.dot(this)
            if (p.norm() > 0.1) return p.normalized()
        }
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)

        fun randomUnit(rnd: Random): Vec3 {
            val z = rnd.nextDouble(-1.0, 1.0)
            val theta = rnd.nextDouble(0.0, 2 * PI)
            val r = sqrt(1 - z * z)
            return Vec3(r * cos(theta), r * sin(theta), z)
        }
    }
}

internal data class Sample(val timeNanos: Long, val x: Float, val y: Float, val z: Float)

/**
 * Builds synthetic accelerometer traces: gravity along [up] (what a resting phone measures),
 * plus the linear acceleration of each segment, plus uniform noise.
 */
internal class SignalBuilder(
    private val rateHz: Double,
    private val rnd: Random,
    val up: Vec3 = Vec3.randomUnit(rnd),
    private val noise: Double = 0.2,
) {
    private val samples = mutableListOf<Sample>()
    private var timeSeconds = 1.0

    val nowNanos: Long get() = (timeSeconds * 1e9).toLong()

    fun rest(seconds: Double) = segment(seconds) { Vec3.ZERO }

    /** [linear] receives the time in seconds since the start of this segment. */
    fun segment(seconds: Double, linear: (Double) -> Vec3): SignalBuilder {
        val count = (seconds * rateHz).roundToInt()
        repeat(count) { i ->
            val a = up * G + linear(i / rateHz) + Vec3(jitter(), jitter(), jitter())
            samples += Sample(nowNanos, a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
            timeSeconds += 1 / rateHz
        }
        return this
    }

    /** A back-and-forth shake along [axis]: exactly [halfCycles] strokes. */
    fun shake(axis: Vec3, frequencyHz: Double, amplitude: Double, halfCycles: Int) =
        segment(halfCycles / (2 * frequencyHz)) { t -> axis * (amplitude * sin(2 * PI * frequencyHz * t)) }

    fun build(): List<Sample> = samples.toList()

    private fun jitter() = if (noise == 0.0) 0.0 else rnd.nextDouble(-noise, noise)
}

internal fun ShakeDetector.triggerTimes(samples: List<Sample>): List<Long> =
    samples.filter { onSample(it.timeNanos, it.x, it.y, it.z) }.map { it.timeNanos }

/** Runs [block] with [cases] independent, reproducible random generators. */
internal fun forEachCase(cases: Int, baseSeed: Long = 20260927L, block: (caseSeed: Long, rnd: Random) -> Unit) {
    repeat(cases) { i ->
        val seed = baseSeed + i
        block(seed, Random(seed))
    }
}

/**
 * Running with the phone in a pocket: heel impact, rebound and the flight phase (free fall), all
 * along the body's vertical, at running cadence.
 */
internal fun runningInPocket(rnd: Random): List<Sample> {
    val builder = SignalBuilder(rnd.nextDouble(50.0, 200.0), rnd)
    val period = 1 / rnd.nextDouble(2.5, 3.2)
    val impact = rnd.nextDouble(18.0, 35.0)
    val pulse = rnd.nextDouble(0.07, 0.12)
    val rebound = impact * rnd.nextDouble(0.3, 0.6)
    val reboundLength = 0.06
    val flight = rnd.nextDouble(0.08, 0.14)
    val profile = { phase: Double ->
        when {
            phase < pulse -> impact * sin(PI * phase / pulse)
            phase < pulse + reboundLength -> -rebound * sin(PI * (phase - pulse) / reboundLength)
            phase > period - flight -> -G * 0.9
            else -> 0.0
        }
    }
    // Without its mean the trace averages to gravity, as a real one does.
    val steps = 1000
    val mean = (0 until steps).sumOf { profile(it * period / steps) } / steps
    return builder.segment(60.0) { t -> builder.up * (profile(t % period) - mean) }.build()
}

/** A hand shake whose strokes differ, as a real hand's do: each has its own length, strength and direction. */
internal fun unevenHandShake(rnd: Random, threshold: Float, halfCycles: Int): List<Sample> {
    val builder = SignalBuilder(rnd.nextDouble(40.0, 200.0), rnd)
    val frequency = rnd.nextDouble(2.0, 5.0)
    val amplitude = rnd.nextDouble(threshold * 1.6, 40.0)
    val axis = Vec3.randomUnit(rnd)
    builder.rest(1.0)
    repeat(halfCycles) { i ->
        val length = 1 / (2 * frequency) * (1 + rnd.nextDouble(-0.2, 0.2))
        val strength = amplitude * (1 + rnd.nextDouble(-0.3, 0.3))
        val direction = (axis + axis.perpendicular(rnd) * rnd.nextDouble(-0.35, 0.35)).normalized()
        val sign = if (i % 2 == 0) 1.0 else -1.0
        builder.segment(length) { t -> direction * (sign * strength * sin(PI * t / length)) }
    }
    return builder.rest(1.0).build()
}

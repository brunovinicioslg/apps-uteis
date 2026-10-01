package io.github.brunovinicioslg.alumia.core.detection

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ShakeDetectorTest {

    @Test
    fun `resting phone never triggers`() {
        forEachCase(200) { seed, rnd ->
            val signal = SignalBuilder(rateHz = rnd.nextDouble(40.0, 200.0), rnd = rnd, noise = 0.5).rest(10.0).build()
            val detector = ShakeDetector(ShakeConfig(sensitivity = Sensitivity.HIGH, requiredStrokes = 2))
            assertEquals(emptyList(), detector.triggerTimes(signal), "seed=$seed")
        }
    }

    @ParameterizedTest
    @EnumSource(Sensitivity::class)
    fun `any real shake triggers exactly once`(sensitivity: Sensitivity) {
        forEachCase(400) { seed, rnd ->
            val frequency = rnd.nextDouble(2.0, 5.0)
            val amplitude = rnd.nextDouble(sensitivity.thresholdMs2 * 1.6, 40.0)
            val rate = rnd.nextDouble(40.0, 200.0)
            val builder = SignalBuilder(rate, rnd)
            val axis = Vec3.randomUnit(rnd)
            val signal = builder.rest(1.0)
                .segment(1.0) { t -> axis * (amplitude * sin(2 * PI * frequency * t)) }
                .rest(1.0)
                .build()
            val detector = ShakeDetector(ShakeConfig(sensitivity = sensitivity))
            assertEquals(
                1,
                detector.triggerTimes(signal).size,
                "seed=$seed f=$frequency a=$amplitude rate=$rate",
            )
        }
    }

    @Test
    fun `fires on the last required stroke, not before`() {
        forEachCase(300) { seed, rnd ->
            val required = rnd.nextInt(ShakeConfig.MIN_STROKES, ShakeConfig.MAX_STROKES + 1)
            val frequency = rnd.nextDouble(2.0, 5.0)
            val amplitude = rnd.nextDouble(20.0, 40.0)
            val axis = Vec3.randomUnit(rnd)
            val config = ShakeConfig(requiredStrokes = required)

            val short = SignalBuilder(100.0, rnd).rest(1.0).shake(axis, frequency, amplitude, required - 1).rest(2.0).build()
            assertEquals(emptyList(), ShakeDetector(config).triggerTimes(short), "short seed=$seed")

            val builder = SignalBuilder(100.0, rnd).rest(1.0).shake(axis, frequency, amplitude, required)
            val gestureEnd = builder.nowNanos
            val exact = builder.rest(2.0).build()
            val triggers = ShakeDetector(config).triggerTimes(exact)
            assertEquals(1, triggers.size, "exact seed=$seed")
            val latencyMs = (triggers.single() - gestureEnd) / 1e6
            assertTrue(latencyMs <= 150, "latency ${latencyMs}ms after the gesture, seed=$seed")
        }
    }

    @ParameterizedTest
    @EnumSource(value = Sensitivity::class, names = ["LOW", "MEDIUM"])
    fun `walking never triggers`(sensitivity: Sensitivity) {
        forEachCase(100) { seed, rnd ->
            val builder = SignalBuilder(rnd.nextDouble(40.0, 200.0), rnd)
            val side = builder.up.perpendicular(rnd)
            val stepHz = rnd.nextDouble(1.6, 2.4)
            val bounce = rnd.nextDouble(2.0, 5.0)
            val sway = rnd.nextDouble(1.0, 2.0)
            val signal = builder.segment(60.0) { t ->
                builder.up * (bounce * sin(2 * PI * stepHz * t)) + side * (sway * sin(PI * stepHz * t))
            }.build()
            val detector = ShakeDetector(ShakeConfig(sensitivity = sensitivity))
            assertEquals(emptyList(), detector.triggerTimes(signal), "seed=$seed")
        }
    }

    @ParameterizedTest
    @EnumSource(value = Sensitivity::class, names = ["LOW", "MEDIUM"])
    fun `running with the phone in hand never triggers`(sensitivity: Sensitivity) {
        forEachCase(100) { seed, rnd ->
            val builder = SignalBuilder(rnd.nextDouble(40.0, 200.0), rnd)
            val forward = builder.up.perpendicular(rnd)
            val cadenceHz = rnd.nextDouble(2.5, 3.2)
            val period = 1 / cadenceHz
            val impact = rnd.nextDouble(15.0, 28.0)
            val pulse = 0.12
            val mean = impact * (2 * pulse / PI) / period
            val armSwing = rnd.nextDouble(5.0, 10.0)
            val signal = builder.segment(60.0) { t ->
                val phase = t % period
                val vertical = (if (phase < pulse) impact * sin(PI * phase / pulse) else 0.0) - mean
                builder.up * vertical + forward * (armSwing * sin(PI * cadenceHz * t))
            }.build()
            val detector = ShakeDetector(ShakeConfig(sensitivity = sensitivity))
            assertEquals(emptyList(), detector.triggerTimes(signal), "seed=$seed cadence=$cadenceHz")
        }
    }

    @ParameterizedTest
    @EnumSource(value = Sensitivity::class, names = ["LOW", "MEDIUM"])
    fun `running with the phone in a pocket never triggers`(sensitivity: Sensitivity) {
        // Impact, rebound and flight make quick back-and-forth peaks; their uneven strength gives them away.
        forEachCase(100) { seed, rnd ->
            val detector = ShakeDetector(ShakeConfig(sensitivity = sensitivity))
            assertEquals(emptyList(), detector.triggerTimes(runningInPocket(rnd)), "seed=$seed")
        }
    }

    @ParameterizedTest
    @EnumSource(Sensitivity::class)
    fun `uneven hand shakes still trigger exactly once`(sensitivity: Sensitivity) {
        for (strokes in listOf(ShakeConfig.DEFAULT_STROKES, ShakeConfig.DEFAULT_STROKES + 1)) {
            forEachCase(300) { seed, rnd ->
                val signal = unevenHandShake(rnd, sensitivity.thresholdMs2, halfCycles = strokes + 2)
                val detector = ShakeDetector(ShakeConfig(sensitivity = sensitivity, requiredStrokes = strokes))
                assertEquals(1, detector.triggerTimes(signal).size, "seed=$seed strokes=$strokes")
            }
        }
    }

    @Test
    fun `with the screen off one more stroke is needed`() {
        assertEquals(4, ShakeConfig(requiredStrokes = 3).forScreenOff().requiredStrokes)
        assertEquals(ShakeConfig.MAX_STROKES, ShakeConfig(requiredStrokes = ShakeConfig.MAX_STROKES).forScreenOff().requiredStrokes)
        assertEquals(Sensitivity.LOW, ShakeConfig(sensitivity = Sensitivity.LOW).forScreenOff().sensitivity)
    }

    @Test
    fun `slow swings never trigger`() {
        forEachCase(200) { seed, rnd ->
            val frequency = rnd.nextDouble(0.3, 1.4)
            val amplitude = rnd.nextDouble(10.0, 25.0)
            val signal = SignalBuilder(100.0, rnd).rest(1.0)
                .shake(Vec3.randomUnit(rnd), frequency, amplitude, halfCycles = 8)
                .rest(1.0)
                .build()
            val detector = ShakeDetector(ShakeConfig(sensitivity = Sensitivity.HIGH, requiredStrokes = 2))
            assertEquals(emptyList(), detector.triggerTimes(signal), "seed=$seed f=$frequency")
        }
    }

    @ParameterizedTest
    @EnumSource(value = Sensitivity::class, names = ["LOW", "MEDIUM"])
    fun `dropping the phone on a table never triggers`(sensitivity: Sensitivity) {
        forEachCase(100) { seed, rnd ->
            val builder = SignalBuilder(rnd.nextDouble(100.0, 200.0), rnd)
            val fallSeconds = rnd.nextDouble(0.15, 0.4)
            val peak = rnd.nextDouble(30.0, 80.0)
            val ringHz = rnd.nextDouble(40.0, 60.0)
            val signal = builder.rest(1.0)
                .segment(fallSeconds) { builder.up * -G } // free fall: the sensor reads ~0
                .segment(0.2) { t -> builder.up * (peak * exp(-t / 0.03) * kotlin.math.cos(2 * PI * ringHz * t)) }
                .rest(1.0)
                .build()
            val detector = ShakeDetector(ShakeConfig(sensitivity = sensitivity))
            assertEquals(emptyList(), detector.triggerTimes(signal), "seed=$seed")
        }
    }

    @Test
    fun `cooldown prevents a second toggle right after the first`() {
        forEachCase(100) { seed, rnd ->
            val axis = Vec3.randomUnit(rnd)
            val close = SignalBuilder(100.0, rnd).rest(1.0)
                .shake(axis, 3.0, 25.0, 3).rest(0.4).shake(axis, 3.0, 25.0, 3)
                .rest(1.0).build()
            assertEquals(1, ShakeDetector().triggerTimes(close).size, "close seed=$seed")

            val apart = SignalBuilder(100.0, rnd).rest(1.0)
                .shake(axis, 3.0, 25.0, 3).rest(2.0).shake(axis, 3.0, 25.0, 3)
                .rest(1.0).build()
            assertEquals(2, ShakeDetector().triggerTimes(apart).size, "apart seed=$seed")
        }
    }

    @Test
    fun `strokes in the same direction do not chain`() {
        forEachCase(100) { seed, rnd ->
            val builder = SignalBuilder(100.0, rnd)
            val axis = Vec3.randomUnit(rnd)
            builder.rest(1.0)
            repeat(6) {
                // A one-sided bump every 150 ms (same direction each time).
                builder.segment(0.15) { t -> if (t < 0.06) axis * (25 * sin(PI * t / 0.06)) else Vec3.ZERO }
            }
            val detector = ShakeDetector(ShakeConfig(requiredStrokes = 2))
            assertEquals(emptyList(), detector.triggerTimes(builder.rest(1.0).build()), "seed=$seed")
        }
    }

    @Test
    fun `a sensor pause breaks the chain`() {
        val rnd = kotlin.random.Random(7)
        val axis = Vec3.randomUnit(rnd)
        val before = SignalBuilder(100.0, rnd).rest(1.0).shake(axis, 3.0, 25.0, 2).build()
        val after = SignalBuilder(100.0, rnd).rest(1.0).shake(axis, 3.0, 25.0, 1).rest(1.0).build()
            .map { it.copy(timeNanos = it.timeNanos + before.last().timeNanos) } // 1 s gap
        assertEquals(emptyList(), ShakeDetector().triggerTimes(before + after))
    }

    @Test
    fun `invalid and out of order samples are ignored`() {
        val rnd = kotlin.random.Random(11)
        val signal = SignalBuilder(100.0, rnd).rest(1.0).shake(Vec3.randomUnit(rnd), 3.0, 25.0, 3).rest(1.0).build()
        val noisy = signal.flatMap { s ->
            listOf(
                s,
                s.copy(x = Float.NaN),
                s.copy(y = Float.POSITIVE_INFINITY),
                s.copy(timeNanos = s.timeNanos - 5_000_000), // late duplicate from the past
            )
        }
        assertEquals(1, ShakeDetector().triggerTimes(noisy).size)
    }

    @Test
    fun `changing the config resets a partial gesture`() {
        val rnd = kotlin.random.Random(3)
        val axis = Vec3.randomUnit(rnd)
        val samples = SignalBuilder(100.0, rnd).rest(1.0).shake(axis, 3.0, 25.0, 2).build()
        val detector = ShakeDetector(ShakeConfig(requiredStrokes = 3))
        detector.triggerTimes(samples)
        detector.config = ShakeConfig(requiredStrokes = 2)
        val next = SignalBuilder(100.0, rnd).shake(axis, 3.0, 25.0, 1).rest(1.0).build()
            .map { it.copy(timeNanos = it.timeNanos + samples.last().timeNanos - 1_000_000_000L + 10_000_000L) }
        assertEquals(emptyList(), detector.triggerTimes(next))
    }
}

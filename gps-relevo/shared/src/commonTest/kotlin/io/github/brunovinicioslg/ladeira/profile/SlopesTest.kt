package io.github.brunovinicioslg.ladeira.profile

import io.github.brunovinicioslg.ladeira.forEachCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlopesTest {

    /** Profile every 25 m for [length] meters. */
    private fun profile(length: Double, elevation: (Double) -> Double) =
        (0..(length / 25).toInt()).map { i -> ProfilePoint(i * 25.0, elevation(i * 25.0)) }

    /** Piecewise-linear terrain from (distance, grade %) breakpoints. */
    private fun terrain(vararg segments: Pair<Double, Double>): (Double) -> Double = { d ->
        var h = 800.0
        var start = 0.0
        for ((length, grade) in segments) {
            val part = (d - start).coerceIn(0.0, length)
            h += part * grade / 100
            start += length
        }
        h
    }

    @Test
    fun flatRoadHasNoSlopes() {
        assertTrue(SlopeDetector.detect(profile(3_000.0) { 800.0 }).isEmpty())
    }

    @Test
    fun uniformClimbIsFoundWithItsGrade() {
        val slopes = SlopeDetector.detect(profile(3_000.0, terrain(1_000.0 to 0.0, 1_000.0 to 6.0, 1_000.0 to 0.0)))
        val s = slopes.single()
        assertTrue(s.isClimb)
        assertEquals(1_000.0, s.length, 150.0) // smoothing blurs the edges a little
        assertEquals(6.0, s.averageGradePercent, 0.6)
        assertEquals(6.0, s.maxGradePercent, 0.3)
        assertEquals(1_000.0, s.start, 100.0)
    }

    @Test
    fun climbThenDescent() {
        val slopes = SlopeDetector.detect(profile(3_000.0, terrain(500.0 to 0.0, 800.0 to 7.0, 900.0 to -5.0, 800.0 to 0.0)))
        assertEquals(2, slopes.size)
        assertTrue(slopes[0].isClimb)
        assertTrue(!slopes[1].isClimb)
        assertEquals(-5.0, slopes[1].averageGradePercent, 0.7)
    }

    @Test
    fun terrainNoiseDoesNotBreakASlope() {
        forEachCase(200) { seed, rnd ->
            val base = terrain(500.0 to 0.0, 2_000.0 to -6.0, 500.0 to 0.0)
            val noisy = profile(3_000.0) { base(it) + rnd.nextDouble(-1.5, 1.5) }
            val slopes = SlopeDetector.detect(noisy)
            assertEquals(1, slopes.size, "seed=$seed: $slopes")
            assertEquals(-6.0, slopes.single().averageGradePercent, 1.0, "seed=$seed")
        }
    }

    @Test
    fun shortFlatBitsDoNotSplitALongDescent() {
        val slopes = SlopeDetector.detect(profile(4_000.0, terrain(300.0 to 0.0, 1_500.0 to -6.0, 100.0 to 0.0, 1_500.0 to -6.0, 600.0 to 0.0)))
        assertEquals(1, slopes.size, "$slopes")
        assertEquals(3_100.0, slopes.single().length, 200.0)
    }

    @Test
    fun longFlatSectionsDoSplit() {
        val slopes = SlopeDetector.detect(profile(4_500.0, terrain(300.0 to 0.0, 1_500.0 to -6.0, 600.0 to 0.0, 1_500.0 to -6.0, 600.0 to 0.0)))
        assertEquals(2, slopes.size, "$slopes")
    }

    @Test
    fun shortBumpsAreIgnored() {
        // 10 m up over 100 m: a bump, not a climb worth a warning for motor vehicles.
        val bump = profile(2_000.0, terrain(900.0 to 0.0, 100.0 to 10.0, 1_000.0 to 0.0))
        for (vehicle in listOf(VehicleProfile.CAR, VehicleProfile.TRUCK, VehicleProfile.MOTORCYCLE)) {
            assertTrue(SlopeDetector.detectFor(bump, vehicle).isEmpty(), "$vehicle")
        }
    }

    @Test
    fun resamplingToleratesMessyInput() {
        val messy = listOf(
            ProfilePoint(0.0, 800.0),
            ProfilePoint(0.0, 900.0), // duplicate distance
            ProfilePoint(Double.NaN, 5.0),
            ProfilePoint(100.0, 810.0),
            ProfilePoint(50.0, 700.0), // out of order
            ProfilePoint(200.0, 820.0),
        )
        val r = SlopeDetector.resample(messy)
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0, 125.0, 150.0, 175.0, 200.0), r.map { it.distance })
        assertEquals(805.0, r[2].elevation, 1e-9)
    }

    @Test
    fun noisySteadyClimbIsWarnedForCars() {
        // Reproduces the BR-040 case: a 6.2 % climb whose 25 m steps dip under 6 % because of noise.
        forEachCase(200) { seed, rnd ->
            val base = terrain(500.0 to 0.0, 1_100.0 to 6.2, 900.0 to 0.0)
            val noisy = profile(2_500.0) { base(it) + rnd.nextDouble(-2.0, 2.0) }
            val found = SlopeDetector.detectFor(noisy, VehicleProfile.CAR)
            assertEquals(1, found.size, "seed=$seed: $found")
            val (slope, kind) = found.single()
            assertEquals(SlopeKind.CLIMB, kind)
            assertTrue(slope.averageGradePercent >= 6.0 && slope.length >= 400.0, "seed=$seed: $slope")
        }
    }

    @Test
    fun steepPartOfAGentleClimbIsReported() {
        val base = terrain(300.0 to 0.0, 1_500.0 to 3.5, 800.0 to 8.0, 500.0 to 3.5, 400.0 to 0.0)
        val (slope, kind) = SlopeDetector.detectFor(profile(3_500.0, base), VehicleProfile.CAR).single()
        assertEquals(SlopeKind.CLIMB, kind)
        assertTrue(slope.averageGradePercent >= 6.0, "$slope")
        assertEquals(1_800.0, slope.start, 250.0, "starts around the steep part")
        // The 3.5 % approaches are under the truck rule (4 %) too, so trucks get the same steep core.
        val truck = SlopeDetector.detectFor(profile(3_500.0, base), VehicleProfile.TRUCK).single().first
        assertTrue(truck.start <= 1_900.0 && truck.end >= 2_500.0, "$truck")
    }

    @Test
    fun walkingDetectsNothing() {
        assertTrue(SlopeDetector.detectFor(profile(2_000.0, terrain(2_000.0 to 12.0)), VehicleProfile.WALKING).isEmpty())
    }

    @Test
    fun vehicleProfilesClassifySlopes() {
        val climb = Slope(0.0, 600.0, 36.0, 7.0) // 6 %
        val gentleLongDescent = Slope(0.0, 2_500.0, -112.5, 5.0) // -4.5 %
        val steepLongDescent = Slope(0.0, 2_500.0, -175.0, 8.0) // -7 %
        assertEquals(SlopeKind.CLIMB, VehicleProfile.CAR.classify(climb))
        assertNull(VehicleProfile.CAR.classify(gentleLongDescent), "too gentle for a car")
        assertEquals(SlopeKind.LONG_DESCENT, VehicleProfile.CAR.classify(steepLongDescent))
        assertEquals(SlopeKind.LONG_DESCENT, VehicleProfile.TRUCK.classify(gentleLongDescent), "trucks care about gentle long descents")
        assertNull(VehicleProfile.WALKING.classify(steepLongDescent))
    }
}

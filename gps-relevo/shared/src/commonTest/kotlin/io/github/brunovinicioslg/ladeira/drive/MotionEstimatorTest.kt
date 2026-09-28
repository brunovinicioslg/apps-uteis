package io.github.brunovinicioslg.ladeira.drive

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.matching.GpsFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MotionEstimatorTest {

    @Test
    fun speedAndHeadingComeFromConsecutivePositions() {
        val m = MotionEstimator()
        m.complete(GpsFix(BH, accuracyM = 5.0, timeMillis = 0))
        val next = m.complete(GpsFix(Geo.destination(BH, 45.0, 20.0), accuracyM = 5.0, timeMillis = 1_000))
        assertEquals(20.0, assertNotNull(next.speedMps), 0.1)
        assertEquals(45.0, assertNotNull(next.bearingDegrees), 0.5)
    }

    @Test
    fun reportedValuesAreKept() {
        val m = MotionEstimator()
        m.complete(GpsFix(BH, accuracyM = 5.0, timeMillis = 0))
        val next = m.complete(GpsFix(Geo.destination(BH, 45.0, 20.0), 5.0, speedMps = 18.0, bearingDegrees = 50.0, timeMillis = 1_000))
        assertEquals(18.0, next.speedMps)
        assertEquals(50.0, next.bearingDegrees)
    }

    @Test
    fun jitterWhileStoppedIsNotMovement() {
        val m = MotionEstimator()
        m.complete(GpsFix(BH, accuracyM = 12.0, timeMillis = 0))
        val next = m.complete(GpsFix(Geo.destination(BH, 120.0, 4.0), accuracyM = 12.0, timeMillis = 1_000))
        assertEquals(0.0, next.speedMps)
        assertNull(next.bearingDegrees)
    }

    @Test
    fun oldOrOutOfOrderFixesAreNotUsed() {
        val m = MotionEstimator()
        m.complete(GpsFix(BH, accuracyM = 5.0, timeMillis = 0))
        assertNull(m.complete(GpsFix(Geo.destination(BH, 0.0, 500.0), 5.0, timeMillis = 60_000)).speedMps, "after a long gap")
        assertNull(m.complete(GpsFix(Geo.destination(BH, 0.0, 520.0), 5.0, timeMillis = 59_000)).speedMps, "time went backwards")
    }
}

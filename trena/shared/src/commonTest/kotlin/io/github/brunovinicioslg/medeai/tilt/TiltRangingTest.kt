package io.github.brunovinicioslg.medeai.tilt

import io.github.brunovinicioslg.medeai.forEachCase
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TiltRangingTest {

    private val g = 9.81

    @Test
    fun cameraElevationFromGravity() {
        assertEquals(-90.0, assertNotNull(TiltRanging.cameraElevationDegrees(0.0, 0.0, g)), 1e-9, "flat, screen up: camera looks down")
        assertEquals(0.0, assertNotNull(TiltRanging.cameraElevationDegrees(0.0, g, 0.0)), 1e-9, "upright: camera looks at the horizon")
        assertEquals(90.0, assertNotNull(TiltRanging.cameraElevationDegrees(0.0, 0.0, -g)), 1e-9, "screen down: camera looks up")
        val tilt = 30 * PI / 180
        assertEquals(-30.0, assertNotNull(TiltRanging.cameraElevationDegrees(0.0, g * cos(tilt), g * sin(tilt))), 1e-9)
        assertNull(TiltRanging.cameraElevationDegrees(0.0, 0.0, 0.0))
    }

    @Test
    fun distanceAndHeightInvertTheGeometry() {
        forEachCase(1000) { seed, rnd ->
            val cameraHeight = rnd.nextDouble(0.5, 2.0)
            val distance = rnd.nextDouble(0.5, 20.0)
            val depression = atan(cameraHeight / distance) * 180 / PI
            val measured = TiltRanging.distanceToFloorPoint(cameraHeight, depression)
            if (depression < TiltRanging.MIN_DEPRESSION_DEGREES) {
                assertNull(measured, "too shallow to trust, seed=$seed")
                return@forEachCase
            }
            assertEquals(distance, assertNotNull(measured, "seed=$seed"), 1e-9 * distance)
            val objectHeight = rnd.nextDouble(0.0, 15.0)
            val elevation = atan((objectHeight - cameraHeight) / distance) * 180 / PI
            assertEquals(objectHeight, assertNotNull(TiltRanging.pointHeight(cameraHeight, distance, elevation)), 1e-9 * (1 + objectHeight))
        }
    }

    @Test
    fun unreliableInputsAreRejected() {
        assertNull(TiltRanging.distanceToFloorPoint(1.5, 1.0), "nearly horizontal")
        assertNull(TiltRanging.distanceToFloorPoint(1.5, 90.0), "straight down")
        assertNull(TiltRanging.distanceToFloorPoint(0.0, 30.0), "no height")
        assertNull(TiltRanging.distanceToFloorPoint(Double.NaN, 30.0))
        assertNull(TiltRanging.pointHeight(1.5, 0.0, 10.0), "no distance")
        assertNull(TiltRanging.pointHeight(1.5, 3.0, 89.5), "vertical")
    }
}

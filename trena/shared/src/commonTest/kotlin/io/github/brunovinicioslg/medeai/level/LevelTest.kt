package io.github.brunovinicioslg.medeai.level

import io.github.brunovinicioslg.medeai.forEachCase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelTest {

    private val g = 9.81
    private fun rad(deg: Double) = deg * PI / 180

    @Test
    fun flatPhoneOnALevelSurface() {
        val r = assertNotNull(Level.read(0.0, 0.0, g))
        assertEquals(Level.Orientation.FLAT, r.orientation)
        assertEquals(0.0, r.surfaceTilt, 1e-9)
        assertEquals(0.0, r.error, 1e-9)
    }

    @Test
    fun flatTiltSignsFollowTheRaisedSide() {
        // Android documents the resting reading as +9.81 on z for a phone lying screen up, i.e. the
        // reading points up. Raising the right side by 5° tilts the device x axis upwards, so the
        // "up" vector expressed in device axes gains +sin(5°) on x.
        val right = assertNotNull(Level.read(g * sin(rad(5.0)), 0.0, g * cos(rad(5.0))))
        assertEquals(5.0, right.tiltX, 1e-9)
        assertEquals(5.0, right.surfaceTilt, 1e-9)
        val left = assertNotNull(Level.read(-g * sin(rad(5.0)), 0.0, g * cos(rad(5.0))))
        assertEquals(-5.0, left.tiltX, 1e-9)
        val top = assertNotNull(Level.read(0.0, g * sin(rad(2.0)), g * cos(rad(2.0))))
        assertEquals(2.0, top.tiltY, 1e-9)
    }

    @Test
    fun uprightEdgesAgainstHorizontalAndVertical() {
        for (base in listOf(0.0, 90.0, 180.0, -90.0)) {
            forEachCase(50) { seed, rnd ->
                val error = rnd.nextDouble(-40.0, 40.0)
                val angle = rad(base + error)
                val r = assertNotNull(Level.read(g * sin(angle), g * cos(angle), 0.0))
                assertEquals(Level.Orientation.UPRIGHT, r.orientation, "seed=$seed")
                assertEquals(error, r.edgeError, 1e-9, "base=$base seed=$seed")
                assertEquals(abs(error), r.error, 1e-9)
            }
        }
    }

    @Test
    fun orientationSwitchesAtFortyFiveDegrees() {
        val flat = assertNotNull(Level.read(0.0, g * sin(rad(44.0)), g * cos(rad(44.0))))
        assertEquals(Level.Orientation.FLAT, flat.orientation)
        val upright = assertNotNull(Level.read(0.0, g * sin(rad(46.0)), g * cos(rad(46.0))))
        assertEquals(Level.Orientation.UPRIGHT, upright.orientation)
        // Screen facing down is still lying flat.
        assertTrue(assertNotNull(Level.read(0.0, 0.0, -g)).orientation == Level.Orientation.FLAT)
    }

    @Test
    fun screenAxesFollowTheDisplayRotation() {
        // Same mapping as Android's AccelerometerPlay sample.
        assertEquals(1.0 to 2.0, Level.toScreenAxes(1.0, 2.0, 0))
        assertEquals(-2.0 to 1.0, Level.toScreenAxes(1.0, 2.0, 1))
        assertEquals(-1.0 to -2.0, Level.toScreenAxes(1.0, 2.0, 2))
        assertEquals(2.0 to -1.0, Level.toScreenAxes(1.0, 2.0, 3))
        assertEquals(Level.toScreenAxes(1.0, 2.0, 3), Level.toScreenAxes(1.0, 2.0, -1))
    }

    @Test
    fun invalidReadings() {
        assertNull(Level.read(0.0, 0.0, 0.0))
        assertNull(Level.read(Double.NaN, 1.0, 1.0))
    }
}

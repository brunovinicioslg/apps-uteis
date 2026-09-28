package io.github.brunovinicioslg.medeai.tilt

import kotlin.math.PI
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class TiltSessionTest {

    private fun elevationTo(dy: Double, dx: Double) = atan(dy / dx) * 180 / PI

    @Test
    fun fullFlowMeasuresDistanceThenHeight() {
        val h = 1.4
        var session = TiltSession(cameraHeight = h)
        assertEquals(TiltSession.Step.AIM_AT_BASE, session.step)

        val baseAim = elevationTo(-h, 4.0) // a floor point 4 m away
        assertEquals(4.0, assertNotNull(session.preview(baseAim)), 1e-9)
        session = session.mark(baseAim)
        assertEquals(TiltSession.Step.AIM_AT_TOP, session.step)
        assertEquals(4.0, assertNotNull(session.baseDistance), 1e-9)

        val topAim = elevationTo(3.0 - h, 4.0) // top of a 3 m tall object
        session = session.mark(topAim)
        assertEquals(TiltSession.Step.DONE, session.step)
        assertEquals(3.0, assertNotNull(session.objectHeight), 1e-9)
        assertNull(session.preview(10.0))
        assertSame(session, session.mark(10.0), "nothing left to mark")
    }

    @Test
    fun unreliableAimDoesNotAdvance() {
        val session = TiltSession(cameraHeight = 1.5)
        assertSame(session, session.mark(5.0), "camera above the horizon cannot see the floor base")
        assertSame(session, session.mark(-1.0), "too shallow")
    }

    @Test
    fun objectsShorterThanThePhoneWork() {
        val h = 1.5
        val session = TiltSession(h).mark(elevationTo(-h, 2.0)).mark(elevationTo(0.8 - h, 2.0))
        assertEquals(0.8, assertNotNull(session.objectHeight), 1e-9)
    }

    @Test
    fun restartAndHeightChangesClearTheMeasurement() {
        val done = TiltSession(1.4).mark(-30.0).mark(10.0)
        assertEquals(TiltSession(1.4), done.restart())
        assertEquals(TiltSession(1.6), done.withCameraHeight(1.6))
        assertEquals(TiltSession.MAX_CAMERA_HEIGHT, done.withCameraHeight(9.0).cameraHeight)
        assertEquals(TiltSession.MIN_CAMERA_HEIGHT, done.withCameraHeight(-1.0).cameraHeight)
    }
}

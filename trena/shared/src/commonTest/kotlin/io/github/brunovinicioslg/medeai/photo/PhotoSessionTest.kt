package io.github.brunovinicioslg.medeai.photo

import io.github.brunovinicioslg.medeai.geometry.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoSessionTest {

    // A 20 x 10 cm reference seen straight on at 1000 px per meter.
    private val corners = listOf(Vec2(100.0, 100.0), Vec2(300.0, 100.0), Vec2(300.0, 200.0), Vec2(100.0, 200.0))

    private fun calibrated() = corners.fold(PhotoSession().withReference(0.20, 0.10)) { s, p -> s.tap(p) }

    @Test
    fun cornersFirstThenMeasurements() {
        var s = PhotoSession()
        assertTrue(s.placingCorners)
        s = calibrated()
        assertFalse(s.placingCorners)
        assertNotNull(s.plane)
        s = s.tap(Vec2(100.0, 500.0))
        assertNull(s.length(0), "waiting for the second point")
        s = s.tap(Vec2(600.0, 500.0))
        assertEquals(0.5, assertNotNull(s.length(0)), 1e-9)
        s = s.tap(Vec2(0.0, 0.0)).tap(Vec2(0.0, 100.0))
        assertEquals(0.1, assertNotNull(s.length(1)), 1e-9)
        assertNull(s.length(5))
    }

    @Test
    fun draggingPointsUpdatesResults() {
        var s = calibrated().tap(Vec2(100.0, 500.0)).tap(Vec2(600.0, 500.0))
        val handle = assertNotNull(s.handleNear(Vec2(598.0, 503.0), radius = 10.0))
        assertEquals(PhotoSession.Handle.SegmentEnd(0), handle)
        s = s.move(handle, Vec2(400.0, 500.0))
        assertEquals(0.3, assertNotNull(s.length(0)), 1e-9)
        // Moving a corner recalibrates: reference now looks twice as wide -> half the scale.
        s = s.move(PhotoSession.Handle.Corner(1), Vec2(500.0, 100.0)).move(PhotoSession.Handle.Corner(2), Vec2(500.0, 200.0))
        assertEquals(0.15, assertNotNull(s.length(0)), 1e-9)
        assertNull(s.handleNear(Vec2(2000.0, 2000.0), radius = 10.0))
    }

    @Test
    fun undoRemovesTheLastPointOnly() {
        var s = calibrated().tap(Vec2(0.0, 0.0)).tap(Vec2(10.0, 0.0))
        s = s.undo()
        assertEquals(1, s.segments.size)
        assertNull(s.segments.single().end)
        s = s.undo()
        assertTrue(s.segments.isEmpty())
        s = s.undo()
        assertEquals(3, s.corners.size)
        assertTrue(s.placingCorners)
        repeat(5) { s = s.undo() }
        assertEquals(PhotoSession().withReference(0.20, 0.10), s)
    }

    @Test
    fun badCornersAreReported() {
        val collinear = listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0), Vec2(2.0, 2.0), Vec2(3.0, 3.0))
        val s = collinear.fold(PhotoSession()) { acc, p -> acc.tap(p) }
        assertTrue(s.invalidCorners)
        assertNull(s.tap(Vec2(0.0, 5.0)).tap(Vec2(5.0, 5.0)).length(0))
    }

    @Test
    fun referenceChangesRescaleAndIgnoreInvalidSizes() {
        val s = calibrated().tap(Vec2(100.0, 500.0)).tap(Vec2(600.0, 500.0))
        assertEquals(1.0, assertNotNull(s.withReference(0.40, 0.20).length(0)), 1e-9)
        assertEquals(s, s.withReference(0.0, 0.2))
        assertEquals(s, s.tap(Vec2(Double.NaN, 1.0)))
        assertTrue(s.clearMeasurements().segments.isEmpty())
    }
}

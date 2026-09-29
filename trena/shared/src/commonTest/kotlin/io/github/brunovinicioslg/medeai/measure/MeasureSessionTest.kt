package io.github.brunovinicioslg.medeai.measure

import io.github.brunovinicioslg.medeai.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureSessionTest {

    private fun v(x: Double, z: Double) = Vec3(x, 0.0, z)

    @Test
    fun distanceTakesTwoPointsAndAThirdStartsOver() {
        var s = MeasureState<String>(MeasureMode.DISTANCE)
        assertFalse(s.continues)
        s = s.add("a").state
        assertTrue(s.continues)
        s = s.add("b").state
        assertFalse(s.continues)
        val change = s.add("c")
        assertEquals(listOf("c"), change.state.points)
        assertEquals(listOf("a", "b"), change.dropped)
    }

    @Test
    fun anOutlineClosesWithThreePointsAndUndoReopensIt() {
        var s = MeasureState<String>(MeasureMode.AREA)
        s = s.add("a").state.add("b").state
        assertFalse(s.canClose)
        assertEquals(s, s.close().state)
        s = s.add("c").state
        assertTrue(s.canClose)
        s = s.close().state
        assertTrue(s.closed)
        assertFalse(s.continues)
        // Undo reopens without losing a point; a new point after closing starts another outline.
        assertEquals(listOf("a", "b", "c"), s.undo().state.points)
        assertFalse(s.undo().state.closed)
        assertEquals(listOf("a", "b", "c"), s.add("d").dropped)
    }

    @Test
    fun undoClearAndModeChangeReturnWhatToRelease() {
        val s = MeasureState<String>(MeasureMode.PATH).add("a").state.add("b").state
        assertEquals(listOf("b"), s.undo().dropped)
        assertEquals(listOf("a", "b"), s.clear().dropped)
        assertEquals(MeasureMode.AREA, s.withMode(MeasureMode.AREA).state.mode)
        assertEquals(listOf("a", "b"), s.withMode(MeasureMode.AREA).dropped)
        assertEquals(s, s.withMode(MeasureMode.PATH).state)
        assertEquals(MeasureState<String>(MeasureMode.PATH), MeasureState<String>(MeasureMode.PATH).undo().state)
    }

    @Test
    fun liveStretchToTheCrosshair() {
        val one = MeasureResult.of(MeasureMode.DISTANCE, listOf(v(0.0, 0.0)), closed = false, crosshair = v(3.0, 4.0))
        assertEquals(listOf(Stretch(0, null, 5.0, live = true)), one.stretches)
        assertEquals(5.0, one.total)
        // Complete distance: the crosshair would start over, so it adds nothing.
        val two = MeasureResult.of(MeasureMode.DISTANCE, listOf(v(0.0, 0.0), v(0.0, 2.0)), closed = false, crosshair = v(9.0, 9.0))
        assertEquals(2.0, two.total)
        assertEquals(1, two.stretches.size)
        // Nothing placed, or the crosshair off every surface.
        assertNull(MeasureResult.of(MeasureMode.PATH, emptyList(), closed = false, crosshair = v(1.0, 1.0)).total)
        assertNull(MeasureResult.of(MeasureMode.PATH, listOf(v(0.0, 0.0)), closed = false, crosshair = null).total)
    }

    @Test
    fun pathTotalsEveryStretch() {
        val r = MeasureResult.of(MeasureMode.PATH, listOf(v(0.0, 0.0), v(1.0, 0.0), v(1.0, 2.0)), closed = false, crosshair = v(4.0, 2.0))
        assertEquals(listOf(1.0, 2.0, 3.0), r.stretches.map { it.length })
        assertEquals(6.0, r.total)
    }

    @Test
    fun areaWhileOpenCountsTheCrosshairAndClosedCountsTheClosingSide() {
        val square = listOf(v(0.0, 0.0), v(2.0, 0.0), v(2.0, 1.5))
        val open = MeasureResult.of(MeasureMode.AREA, square, closed = false, crosshair = v(0.0, 1.5))
        assertEquals(3.0, assertNotNull(open.polygon).area, 1e-9)
        val closed = MeasureResult.of(MeasureMode.AREA, square + v(0.0, 1.5), closed = true, crosshair = v(9.0, 9.0))
        assertEquals(3.0, assertNotNull(closed.polygon).area, 1e-9)
        assertEquals(7.0, closed.polygon.perimeter, 1e-9)
        assertEquals(listOf(2.0, 1.5, 2.0, 1.5), closed.stretches.map { it.length })
        assertTrue(closed.stretches.none { it.live })
        assertNull(MeasureResult.of(MeasureMode.AREA, square.take(2), closed = false, crosshair = null).polygon)
    }

    @Test
    fun theCrosshairOnTheFirstPointPreviewsTheClosedOutline() {
        val triangle = listOf(v(0.0, 0.0), v(2.0, 0.0), v(2.0, 1.5))
        val closing = MeasureResult.of(MeasureMode.AREA, triangle, closed = false, crosshair = triangle.first())
        val polygon = assertNotNull(closing.polygon)
        assertFalse(polygon.selfIntersecting, "the first point again is not a crossing")
        assertEquals(1.5, polygon.area, 1e-9)
        assertEquals(2.5, closing.stretches.last().length, 1e-9)
    }
}

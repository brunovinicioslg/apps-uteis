package io.github.brunovinicioslg.medeai.measure

import io.github.brunovinicioslg.medeai.RigidMotion
import io.github.brunovinicioslg.medeai.forEachCase
import io.github.brunovinicioslg.medeai.geometry.Vec3
import io.github.brunovinicioslg.medeai.point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasurementsTest {

    private fun flat(vararg xy: Double) = xy.toList().chunked(2).map { (x, y) -> Vec3(x, y, 0.0) }

    @Test
    fun unitSquareAnywhereInSpace() {
        forEachCase(300) { seed, rnd ->
            val motion = RigidMotion(rnd)
            val square = flat(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0).map(motion::apply)
            val m = assertNotNull(Measurements.polygon(square), "seed=$seed")
            assertEquals(1.0, m.area, 1e-9, "seed=$seed")
            assertEquals(4.0, m.perimeter, 1e-9)
            assertEquals(0.0, m.maxDeviation, 1e-9)
            assertFalse(m.selfIntersecting)
        }
    }

    @Test
    fun regularPolygonsMatchTheFormula() {
        forEachCase(300) { seed, rnd ->
            val n = rnd.nextInt(3, 13)
            val r = rnd.nextDouble(0.05, 10.0)
            val motion = RigidMotion(rnd)
            val outline = List(n) { k -> Vec3(r * cos(2 * PI * k / n), r * sin(2 * PI * k / n), 0.0) }.map(motion::apply)
            val m = assertNotNull(Measurements.polygon(outline))
            val expected = n * r * r * sin(2 * PI / n) / 2
            assertEquals(expected, m.area, expected * 1e-9, "seed=$seed n=$n")
            assertEquals(2 * n * r * sin(PI / n), m.perimeter, 1e-9 * r * n)
        }
    }

    @Test
    fun areaIsTheSameWhateverTheViewpoint() {
        forEachCase(300) { seed, rnd ->
            // Star-shaped around the origin (hence simple): one point per angular sector, random radii.
            // Sorting random angles is not enough: with every point on one side of the origin the
            // closing edge can cross another edge.
            val n = rnd.nextInt(3, 15)
            val angles = List(n) { k -> (k + rnd.nextDouble(0.0, 0.9)) * 2 * PI / n }
            val outline = angles.map { a -> rnd.nextDouble(0.2, 3.0).let { Vec3(it * cos(a), it * sin(a), 0.0) } }
            val reference = Measurements.polygon(outline) ?: return@forEachCase // skipped when collinear
            val moved = assertNotNull(Measurements.polygon(outline.map(RigidMotion(rnd)::apply)), "seed=$seed")
            assertEquals(reference.area, moved.area, 1e-9 * (1 + reference.area), "seed=$seed")
            assertFalse(reference.selfIntersecting, "star-shaped outline, seed=$seed")
        }
    }

    @Test
    fun crossedOutlinesAreFlagged() {
        val bowTie = flat(0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0)
        assertTrue(assertNotNull(Measurements.polygon(bowTie)).selfIntersecting)
        val square = flat(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        assertFalse(assertNotNull(Measurements.polygon(square)).selfIntersecting)
        val triangle = flat(0.0, 0.0, 2.0, 0.0, 0.0, 1.0)
        assertEquals(1.0, assertNotNull(Measurements.polygon(triangle)).area, 1e-12)
    }

    @Test
    fun bentOutlinesReportTheirDeviation() {
        val bent = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.2), Vec3(0.0, 1.0, 0.0))
        val m = assertNotNull(Measurements.polygon(bent))
        assertTrue(m.maxDeviation in 0.04..0.06, "deviation ${m.maxDeviation}")
    }

    @Test
    fun degenerateOutlinesHaveNoArea() {
        assertNull(Measurements.polygon(flat(0.0, 0.0, 1.0, 1.0)))
        assertNull(Measurements.polygon(flat(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0)))
    }

    @Test
    fun rectangleFromThreePoints() {
        forEachCase(300) { seed, rnd ->
            val a = rnd.point()
            val b = rnd.point()
            val c = rnd.point()
            val r = Measurements.rectangle(a, b, c) ?: return@forEachCase
            val side = b - a
            val distanceToLine = ((c - a) cross side).length() / side.length()
            assertEquals(side.length(), r.width, 1e-9, "seed=$seed")
            assertEquals(distanceToLine, r.height, 1e-9, "seed=$seed")
            assertEquals(r.width * r.height, r.area, 1e-9)
            val (p0, p1, p2, p3) = r.corners
            assertEquals(0.0, (p1 - p0) dot (p3 - p0), 1e-9, "right angle, seed=$seed")
            assertEquals(0.0, (p2 - p1) dot (p0 - p1), 1e-9, "right angle, seed=$seed")
            assertEquals(r.width, p2.distanceTo(p3), 1e-9)
        }
        val p = Vec3(1.0, 1.0, 1.0)
        assertNull(Measurements.rectangle(p, p, Vec3(2.0, 0.0, 0.0)), "zero width")
        assertNull(Measurements.rectangle(p, Vec3(2.0, 1.0, 1.0), Vec3(5.0, 1.0, 1.0)), "zero height")
    }

    @Test
    fun heightsDistancesAndAngles() {
        val floor = Vec3(1.0, 0.0, 2.0)
        val top = Vec3(1.3, 2.5, 2.4)
        assertEquals(2.5, Measurements.verticalDistance(floor, top), 1e-12)
        assertEquals(0.5, Measurements.horizontalDistance(floor, top), 1e-12)
        assertEquals(90.0, assertNotNull(Measurements.angleDegrees(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, Vec3(0.0, 0.0, 3.0))), 1e-9)
        assertEquals(180.0, assertNotNull(Measurements.angleDegrees(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, Vec3(-2.0, 0.0, 0.0))), 1e-9)
        assertNull(Measurements.angleDegrees(Vec3.ZERO, Vec3.ZERO, Vec3(1.0, 0.0, 0.0)))
        val path = listOf(Vec3.ZERO, Vec3(3.0, 0.0, 0.0), Vec3(3.0, 4.0, 0.0))
        assertEquals(7.0, Measurements.pathLength(path), 1e-12)
        assertEquals(0.0, Measurements.pathLength(listOf(Vec3.ZERO)), 0.0)
        assertTrue(abs(Measurements.distance(Vec3.ZERO, Vec3(3.0, 4.0, 12.0)) - 13.0) < 1e-12)
    }
}

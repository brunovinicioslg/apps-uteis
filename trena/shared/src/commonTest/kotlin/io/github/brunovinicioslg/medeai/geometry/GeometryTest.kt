package io.github.brunovinicioslg.medeai.geometry

import io.github.brunovinicioslg.medeai.forEachCase
import io.github.brunovinicioslg.medeai.point
import io.github.brunovinicioslg.medeai.unitVector
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun eigenDecompositionSatisfiesDefinition() {
        forEachCase(500) { seed, rnd ->
            // Random symmetric matrix built from random vectors (covariance-like, any sign mix).
            val m = Array(3) { DoubleArray(3) }
            repeat(rnd.nextInt(1, 6)) {
                val v = rnd.point(3.0)
                val w = rnd.nextDouble(-2.0, 2.0)
                val c = doubleArrayOf(v.x, v.y, v.z)
                for (i in 0 until 3) for (j in 0 until 3) m[i][j] += w * c[i] * c[j]
            }
            val eigen = symmetricEigen3(m)
            assertTrue(eigen.values[0] <= eigen.values[1] && eigen.values[1] <= eigen.values[2], "ascending, seed=$seed")
            val scale = (0 until 3).maxOf { i -> (0 until 3).maxOf { j -> abs(m[i][j]) } }.coerceAtLeast(1e-12)
            for (k in 0 until 3) {
                val v = eigen.vectors[k]
                val av = Vec3(
                    m[0][0] * v.x + m[0][1] * v.y + m[0][2] * v.z,
                    m[1][0] * v.x + m[1][1] * v.y + m[1][2] * v.z,
                    m[2][0] * v.x + m[2][1] * v.y + m[2][2] * v.z,
                )
                assertTrue((av - v * eigen.values[k]).length() < 1e-9 * scale, "A v = λ v, seed=$seed k=$k")
                assertEquals(1.0, v.length(), 1e-12)
                for (j in k + 1 until 3) assertEquals(0.0, v dot eigen.vectors[j], 1e-9, "orthogonal, seed=$seed")
            }
        }
    }

    @Test
    fun planeFitRecoversExactPlanes() {
        forEachCase(500) { seed, rnd ->
            val truth = Plane(rnd.point(), rnd.unitVector())
            val points = List(rnd.nextInt(3, 12)) {
                truth.point + truth.axisU * rnd.nextDouble(-3.0, 3.0) + truth.axisV * rnd.nextDouble(-3.0, 3.0)
            }
            val fitted = assertNotNull(Plane.fit(points), "seed=$seed")
            assertTrue(abs(fitted.normal dot truth.normal) > 1 - 1e-9, "normal, seed=$seed")
            points.forEach { assertEquals(0.0, fitted.signedDistance(it), 1e-9, "seed=$seed") }
        }
    }

    @Test
    fun planeFitIsRobustToSmallNoise() {
        forEachCase(200) { seed, rnd ->
            val truth = Plane(rnd.point(), rnd.unitVector())
            val points = List(20) {
                truth.point + truth.axisU * rnd.nextDouble(-1.0, 1.0) + truth.axisV * rnd.nextDouble(-1.0, 1.0) +
                    truth.normal * rnd.nextDouble(-0.002, 0.002)
            }
            val fitted = assertNotNull(Plane.fit(points), "seed=$seed")
            assertTrue(abs(fitted.normal dot truth.normal) > 0.9999, "seed=$seed") // within ~0.8°
        }
    }

    @Test
    fun degeneratePointSetsHaveNoPlane() {
        val a = Vec3(1.0, 2.0, 3.0)
        val dir = Vec3(0.3, -0.2, 0.9)
        assertNull(Plane.fit(listOf(a, a + dir)), "two points")
        assertNull(Plane.fit(listOf(a, a + dir, a + dir * 2.0, a + dir * 7.5)), "collinear")
        assertNull(Plane.fit(listOf(a, a, a)), "coincident")
        assertNull(Plane.fit(listOf(a, a + dir, Vec3(Double.NaN, 0.0, 0.0))), "not finite")
    }

    @Test
    fun planeCoordinatesPreserveDistances() {
        forEachCase(200) { seed, rnd ->
            val plane = Plane(rnd.point(), rnd.unitVector())
            val p = plane.project(rnd.point())
            val q = plane.project(rnd.point())
            val d2 = plane.toPlaneCoordinates(p).distanceTo(plane.toPlaneCoordinates(q))
            assertEquals(p.distanceTo(q), d2, 1e-9, "seed=$seed")
            assertEquals(0.0, plane.axisU dot plane.normal, 1e-12)
            assertEquals(0.0, plane.axisV dot plane.normal, 1e-12)
            assertEquals(0.0, plane.axisU dot plane.axisV, 1e-12)
        }
    }
}

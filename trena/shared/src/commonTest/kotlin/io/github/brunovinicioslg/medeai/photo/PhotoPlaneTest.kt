package io.github.brunovinicioslg.medeai.photo

import io.github.brunovinicioslg.medeai.forEachCase
import io.github.brunovinicioslg.medeai.geometry.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhotoPlaneTest {

    /** Pinhole camera looking at the z=0 plane from [distance] meters, tilted by up to [maxTiltDeg]. */
    private class Camera(rnd: Random, maxTiltDeg: Double) {
        private val r: Array<DoubleArray>
        private val t = doubleArrayOf(rnd.nextDouble(-0.1, 0.1), rnd.nextDouble(-0.1, 0.1), rnd.nextDouble(0.6, 1.2))
        private val f = rnd.nextDouble(800.0, 3000.0)
        private val cx = rnd.nextDouble(500.0, 2000.0)
        private val cy = rnd.nextDouble(500.0, 2000.0)

        init {
            val a = rnd.nextDouble(-maxTiltDeg, maxTiltDeg) * PI / 180
            val b = rnd.nextDouble(-maxTiltDeg, maxTiltDeg) * PI / 180
            val c = rnd.nextDouble(0.0, 2 * PI) // any roll: the photo may be rotated
            val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), -sin(a)), doubleArrayOf(0.0, sin(a), cos(a)))
            val ry = arrayOf(doubleArrayOf(cos(b), 0.0, sin(b)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(b), 0.0, cos(b)))
            val rz = arrayOf(doubleArrayOf(cos(c), -sin(c), 0.0), doubleArrayOf(sin(c), cos(c), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            r = mul(rx, mul(ry, rz))
        }

        fun project(p: Vec2): Vec2 {
            val x = r[0][0] * p.x + r[0][1] * p.y + t[0]
            val y = r[1][0] * p.x + r[1][1] * p.y + t[1]
            val z = r[2][0] * p.x + r[2][1] * p.y + t[2]
            return Vec2(f * x / z + cx, f * y / z + cy)
        }

        private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> a[i][k] * b[k][j] } } }
    }

    private fun Random.planePoint() = Vec2(nextDouble(-0.3, 0.3), nextDouble(-0.3, 0.3))

    @Test
    fun homographyRecoversProjectiveMaps() {
        forEachCase(500) { seed, rnd ->
            val camera = Camera(rnd, maxTiltDeg = 50.0)
            val real = listOf(Vec2(0.0, 0.0), Vec2(0.2, 0.0), Vec2(0.2, 0.15), Vec2(0.0, 0.15))
            val image = real.map(camera::project)
            val h = assertNotNull(Homography.fromCorrespondences(image, real), "seed=$seed")
            repeat(10) {
                val p = rnd.planePoint()
                val back = assertNotNull(h.map(camera.project(p)))
                assertEquals(0.0, back.distanceTo(p), 1e-7, "seed=$seed")
            }
        }
    }

    @Test
    fun cardCalibratedPhotoMeasuresTheSurface() {
        forEachCase(500) { seed, rnd ->
            val camera = Camera(rnd, maxTiltDeg = 30.0)
            val card = ReferenceObject.CARD
            // The card lies anywhere on the surface, in any orientation.
            val angle = rnd.nextDouble(0.0, 2 * PI)
            val origin = rnd.planePoint()
            val u = Vec2(cos(angle), sin(angle))
            val v = Vec2(-sin(angle), cos(angle))
            val corners = listOf(
                origin,
                origin + u * card.widthMeters,
                origin + u * card.widthMeters + v * card.heightMeters,
                origin + v * card.heightMeters,
            ).map(camera::project).shuffled(rnd)

            val plane = assertNotNull(PhotoPlane.calibrate(corners, card), "seed=$seed")
            repeat(10) {
                val a = rnd.planePoint()
                val b = rnd.planePoint()
                val measured = assertNotNull(plane.distance(camera.project(a), camera.project(b)))
                assertEquals(a.distanceTo(b), measured, 1e-6, "seed=$seed")
            }
        }
    }

    @Test
    fun badCornerSetsAreRejected() {
        val collinear = listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0), Vec2(2.0, 2.0), Vec2(0.0, 5.0))
        assertNull(PhotoPlane.calibrate(collinear, ReferenceObject.A4_SHEET))
        val repeated = listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        assertNull(PhotoPlane.calibrate(repeated, ReferenceObject.CARD))
        val square = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), Vec2(0.0, 100.0))
        assertNull(PhotoPlane.calibrate(square, 0.0, 1.0), "no size")
        val sheet = assertNotNull(PhotoPlane.calibrate(square.reversed(), 0.2, 0.2))
        assertEquals(0.2, assertNotNull(sheet.distance(Vec2(0.0, 0.0), Vec2(100.0, 0.0))), 1e-9)
    }
}

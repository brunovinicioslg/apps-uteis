package io.github.brunovinicioslg.medeai.photo

import io.github.brunovinicioslg.medeai.geometry.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Everyday objects of standard size used as a scale reference in photo mode. */
enum class ReferenceObject(val widthMeters: Double, val heightMeters: Double) {
    /** ISO/IEC 7810 ID-1: credit, debit and ID cards. */
    CARD(0.08560, 0.05398),
    A4_SHEET(0.210, 0.297),
    LETTER_SHEET(0.2159, 0.2794),
}

/** Projective mapping between two planes, estimated from four point pairs. */
class Homography private constructor(private val h: DoubleArray) {

    /** Null when the point maps to infinity (it lies on the horizon line of the plane). */
    fun map(p: Vec2): Vec2? {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        if (abs(w) < 1e-12) return null
        val result = Vec2((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
        return result.takeIf { it.isFinite() }
    }

    companion object {
        /**
         * Direct linear transform with Hartley normalization for numerical stability.
         * Null if three of the points on either side are (nearly) collinear.
         */
        fun fromCorrespondences(source: List<Vec2>, target: List<Vec2>): Homography? {
            require(source.size == 4 && target.size == 4) { "Exactly four point pairs are needed" }
            if (source.any { !it.isFinite() } || target.any { !it.isFinite() }) return null
            if (hasCollinearTriple(source) || hasCollinearTriple(target)) return null
            val (srcNorm, srcT) = normalize(source)
            val (dstNorm, dstT) = normalize(target)

            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = srcNorm[i]
                val (u, v) = dstNorm[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val solution = solve8(a) ?: return null
            val hNorm = DoubleArray(9) { if (it < 8) solution[it] else 1.0 }
            // H = inverse(T_dst) * H_norm * T_src
            val composed = multiply(invertSimilarity(dstT), multiply(hNorm, srcT))
            if (composed.any { !it.isFinite() }) return null
            return Homography(composed)
        }

        /** Translate to the centroid and scale so the mean distance from it is sqrt(2). */
        private fun normalize(points: List<Vec2>): Pair<List<Vec2>, DoubleArray> {
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            val meanDist = points.sumOf { sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy)) } / points.size
            val s = sqrt(2.0) / meanDist
            val t = doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
            return points.map { Vec2(s * (it.x - cx), s * (it.y - cy)) } to t
        }

        private fun invertSimilarity(t: DoubleArray): DoubleArray {
            val s = t[0]
            return doubleArrayOf(1 / s, 0.0, -t[2] / s, 0.0, 1 / s, -t[5] / s, 0.0, 0.0, 1.0)
        }

        private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
            val r = idx / 3
            val c = idx % 3
            (0 until 3).sumOf { k -> a[r * 3 + k] * b[k * 3 + c] }
        }

        /** Gaussian elimination with partial pivoting on an 8x9 augmented matrix. */
        private fun solve8(m: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(m[it][col]) }
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
                for (row in col + 1 until n) {
                    val f = m[row][col] / m[col][col]
                    for (k in col until n + 1) m[row][k] -= f * m[col][k]
                }
            }
            val x = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var sum = m[row][n]
                for (k in row + 1 until n) sum -= m[row][k] * x[k]
                x[row] = sum / m[row][row]
            }
            return x
        }

        private fun hasCollinearTriple(points: List<Vec2>): Boolean {
            val scale = (0 until 4).maxOf { i -> (0 until 4).maxOf { j -> points[i].distanceTo(points[j]) } }
            if (scale == 0.0) return true
            for (i in 0 until 4) for (j in i + 1 until 4) for (k in j + 1 until 4) {
                val twiceArea = abs((points[j] - points[i]) cross (points[k] - points[i]))
                if (twiceArea < scale * scale * 1e-6) return true
            }
            return false
        }
    }
}

/**
 * Photo mode: after the user marks the four corners of a reference object lying on a flat
 * surface, any two points on that same surface can be measured in meters.
 */
class PhotoPlane private constructor(private val imageToPlane: Homography) {

    /** Meters between two image points (pixels); null if either maps outside the visible plane. */
    fun distance(a: Vec2, b: Vec2): Double? {
        val pa = imageToPlane.map(a) ?: return null
        val pb = imageToPlane.map(b) ?: return null
        return pa.distanceTo(pb)
    }

    companion object {
        /**
         * [corners] are the reference's corners in the image, in any order. The longer sides in the
         * image are matched to the longer real sides. Null if the corners do not form a convex
         * quadrilateral.
         */
        fun calibrate(corners: List<Vec2>, reference: ReferenceObject): PhotoPlane? =
            calibrate(corners, reference.widthMeters, reference.heightMeters)

        fun calibrate(corners: List<Vec2>, widthMeters: Double, heightMeters: Double): PhotoPlane? {
            if (corners.size != 4 || !(widthMeters > 0.0) || !(heightMeters > 0.0)) return null
            val ordered = orderAroundCentroid(corners)
            if (!isConvex(ordered)) return null
            val long = maxOf(widthMeters, heightMeters)
            val short = minOf(widthMeters, heightMeters)
            val firstPairLength = ordered[0].distanceTo(ordered[1]) + ordered[2].distanceTo(ordered[3])
            val secondPairLength = ordered[1].distanceTo(ordered[2]) + ordered[3].distanceTo(ordered[0])
            val (along, across) = if (firstPairLength >= secondPairLength) long to short else short to long
            val target = listOf(Vec2(0.0, 0.0), Vec2(along, 0.0), Vec2(along, across), Vec2(0.0, across))
            return Homography.fromCorrespondences(ordered, target)?.let(::PhotoPlane)
        }

        /** Sorts the points by angle around their centroid, giving the outline of a convex shape. */
        fun orderAroundCentroid(points: List<Vec2>): List<Vec2> {
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            return points.sortedBy { atan2(it.y - cy, it.x - cx) }
        }

        internal fun isConvex(quad: List<Vec2>): Boolean {
            val signs = (0 until 4).map { i ->
                val a = quad[i]
                val b = quad[(i + 1) % 4]
                val c = quad[(i + 2) % 4]
                (b - a) cross (c - b)
            }
            return signs.all { it > 0 } || signs.all { it < 0 }
        }
    }
}

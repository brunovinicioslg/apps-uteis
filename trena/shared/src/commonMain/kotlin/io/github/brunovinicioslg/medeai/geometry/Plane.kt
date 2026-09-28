package io.github.brunovinicioslg.medeai.geometry

import kotlin.math.abs

/** A plane through [point] with unit [normal]. */
class Plane(val point: Vec3, normal: Vec3) {

    val normal: Vec3 = normal.normalized()

    /** Orthonormal axes spanning the plane; together with [normal] they form a right-handed frame. */
    val axisU: Vec3
    val axisV: Vec3

    init {
        // Start from the world axis least aligned with the normal for a well-conditioned cross product.
        val helper = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
            .minBy { abs(it dot this.normal) }
        axisU = (helper cross this.normal).normalized()
        axisV = this.normal cross axisU
    }

    fun signedDistance(p: Vec3): Double = (p - point) dot normal

    fun project(p: Vec3): Vec3 = p - normal * signedDistance(p)

    fun toPlaneCoordinates(p: Vec3): Vec2 {
        val d = p - point
        return Vec2(d dot axisU, d dot axisV)
    }

    companion object {
        /**
         * Least-squares plane through [points] (the normal is the direction of least variance).
         * Null when there are fewer than 3 points or they are (nearly) collinear.
         */
        fun fit(points: List<Vec3>): Plane? {
            if (points.size < 3 || points.any { !it.isFinite() }) return null
            val centroid = points.fold(Vec3.ZERO) { acc, p -> acc + p } / points.size.toDouble()
            val cov = Array(3) { DoubleArray(3) }
            for (p in points) {
                val d = doubleArrayOf(p.x - centroid.x, p.y - centroid.y, p.z - centroid.z)
                for (i in 0 until 3) for (j in 0 until 3) cov[i][j] += d[i] * d[j]
            }
            val eigen = symmetricEigen3(cov)
            val largest = eigen.values[2]
            if (largest <= 0.0) return null // all points coincide
            if (eigen.values[1] <= largest * COLLINEAR_RATIO) return null
            return Plane(centroid, eigen.vectors[0])
        }

        private const val COLLINEAR_RATIO = 1e-12
    }
}

package io.github.brunovinicioslg.medeai.measure

import io.github.brunovinicioslg.medeai.geometry.Plane
import io.github.brunovinicioslg.medeai.geometry.Vec2
import io.github.brunovinicioslg.medeai.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/** Area of a closed outline, measured on its best-fit plane. */
data class PolygonMeasurement(
    /** Square meters. */
    val area: Double,
    /** Meters, including the closing edge. */
    val perimeter: Double,
    /** Largest distance of a point from the fitted plane, in meters; large values mean a bent outline. */
    val maxDeviation: Double,
    /** Edges cross each other, so [area] does not describe a real surface. */
    val selfIntersecting: Boolean,
)

/** A rectangle built from one full side and a point on the opposite side. */
data class Rectangle(val corners: List<Vec3>, val width: Double, val height: Double) {
    val area: Double get() = width * height
}

object Measurements {

    fun distance(a: Vec3, b: Vec3): Double = a.distanceTo(b)

    /** Total length of an open path through [points]. */
    fun pathLength(points: List<Vec3>): Double = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()

    /** Height difference along the gravity-aligned [up] axis. */
    fun verticalDistance(a: Vec3, b: Vec3, up: Vec3 = Vec3.UP): Double = abs((b - a) dot up.normalized())

    /** Distance ignoring height (projected on the floor). */
    fun horizontalDistance(a: Vec3, b: Vec3, up: Vec3 = Vec3.UP): Double {
        val d = b - a
        val u = up.normalized()
        return (d - u * (d dot u)).length()
    }

    /** Angle at [vertex] between the rays to [a] and [b], in degrees; null if a ray has zero length. */
    fun angleDegrees(a: Vec3, vertex: Vec3, b: Vec3): Double? {
        val u = a - vertex
        val v = b - vertex
        val lengths = u.length() * v.length()
        if (lengths == 0.0) return null
        return acos(((u dot v) / lengths).coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /** Null for fewer than 3 points or collinear points. */
    fun polygon(points: List<Vec3>): PolygonMeasurement? {
        val plane = Plane.fit(points) ?: return null
        val flat = points.map(plane::toPlaneCoordinates)
        val perimeter = (points + points.first()).zipWithNext { a, b -> a.distanceTo(b) }.sum()
        return PolygonMeasurement(
            area = abs(shoelace(flat)) / 2,
            perimeter = perimeter,
            maxDeviation = points.maxOf { abs(plane.signedDistance(it)) },
            selfIntersecting = hasSelfIntersection(flat),
        )
    }

    /**
     * Rectangle with side [a]-[b] whose opposite side passes through [c] (only the part of [c]
     * perpendicular to the first side counts). Null if any side would have zero length.
     */
    fun rectangle(a: Vec3, b: Vec3, c: Vec3): Rectangle? {
        val side = b - a
        val sideLengthSq = side dot side
        if (sideLengthSq == 0.0) return null
        val toC = c - b
        val across = toC - side * ((toC dot side) / sideLengthSq)
        val height = across.length()
        if (height == 0.0) return null
        return Rectangle(listOf(a, b, b + across, a + across), width = sqrt(sideLengthSq), height = height)
    }

    private fun shoelace(points: List<Vec2>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            sum += p.x * q.y - q.x * p.y
        }
        return sum
    }

    /** True if two non-adjacent edges of the closed outline touch or cross. */
    private fun hasSelfIntersection(points: List<Vec2>): Boolean {
        val n = points.size
        if (n < 4) return false
        val scale = points.maxOf { max(abs(it.x), abs(it.y)) }.coerceAtLeast(1e-9)
        val eps = scale * scale * 1e-12
        for (i in 0 until n) {
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue // these edges share point 0
                if (segmentsIntersect(points[i], points[(i + 1) % n], points[j], points[(j + 1) % n], eps)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(p1: Vec2, p2: Vec2, q1: Vec2, q2: Vec2, eps: Double): Boolean {
        val d1 = orientation(q1, q2, p1, eps)
        val d2 = orientation(q1, q2, p2, eps)
        val d3 = orientation(p1, p2, q1, eps)
        val d4 = orientation(p1, p2, q2, eps)
        if (d1 * d2 < 0 && d3 * d4 < 0) return true
        return (d1 == 0 && onSegment(q1, q2, p1)) || (d2 == 0 && onSegment(q1, q2, p2)) ||
            (d3 == 0 && onSegment(p1, p2, q1)) || (d4 == 0 && onSegment(p1, p2, q2))
    }

    private fun orientation(a: Vec2, b: Vec2, c: Vec2, eps: Double): Int {
        val cross = (b - a) cross (c - a)
        return when {
            cross > eps -> 1
            cross < -eps -> -1
            else -> 0
        }
    }

    private fun onSegment(a: Vec2, b: Vec2, p: Vec2): Boolean =
        p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)
}

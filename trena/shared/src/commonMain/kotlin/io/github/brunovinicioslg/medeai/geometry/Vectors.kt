package io.github.brunovinicioslg.medeai.geometry

import kotlin.math.sqrt

/** A point or direction in 3D, in meters. In AR world space +Y points up (against gravity). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length() = sqrt(this dot this)
    fun distanceTo(o: Vec3) = (this - o).length()

    fun normalized(): Vec3 {
        val l = length()
        require(l > 0.0) { "Cannot normalize a zero vector" }
        return this / l
    }

    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}

/** A point in 2D: plane coordinates in meters, or image coordinates in pixels. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)

    infix fun dot(o: Vec2) = x * o.x + y * o.y

    /** Z component of the 3D cross product; positive when [o] is counterclockwise from this. */
    infix fun cross(o: Vec2) = x * o.y - y * o.x

    fun length() = sqrt(this dot this)
    fun distanceTo(o: Vec2) = (this - o).length()
    fun isFinite() = x.isFinite() && y.isFinite()
}

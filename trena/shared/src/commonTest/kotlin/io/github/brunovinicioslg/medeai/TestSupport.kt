package io.github.brunovinicioslg.medeai

import io.github.brunovinicioslg.medeai.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Runs [block] with [cases] reproducible random generators; the seed goes in failure messages. */
fun forEachCase(cases: Int, baseSeed: Long = 20260928L, block: (seed: Long, rnd: Random) -> Unit) {
    repeat(cases) { i ->
        val seed = baseSeed + i
        block(seed, Random(seed))
    }
}

fun Random.unitVector(): Vec3 {
    val z = nextDouble(-1.0, 1.0)
    val theta = nextDouble(0.0, 2 * PI)
    val r = sqrt(1 - z * z)
    return Vec3(r * cos(theta), r * sin(theta), z)
}

fun Random.point(range: Double = 5.0) = Vec3(nextDouble(-range, range), nextDouble(-range, range), nextDouble(-range, range))

/** A random rigid motion (rotation + translation), as used to move shapes around in space. */
class RigidMotion(rnd: Random) {
    private val m: Array<DoubleArray>
    private val t = rnd.point()

    init {
        // Random unit quaternion -> rotation matrix.
        var q: DoubleArray
        do {
            q = DoubleArray(4) { rnd.nextDouble(-1.0, 1.0) }
        } while (q.sumOf { it * it } !in 0.01..1.0)
        val n = sqrt(q.sumOf { it * it })
        val (w, x, y, z) = q.map { it / n }
        m = arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)),
            doubleArrayOf(2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)),
            doubleArrayOf(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)),
        )
    }

    fun apply(p: Vec3) = Vec3(
        m[0][0] * p.x + m[0][1] * p.y + m[0][2] * p.z + t.x,
        m[1][0] * p.x + m[1][1] * p.y + m[1][2] * p.z + t.y,
        m[2][0] * p.x + m[2][1] * p.y + m[2][2] * p.z + t.z,
    )
}

package io.github.brunovinicioslg.medeai.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/** Eigenvalues in ascending order, with matching unit eigenvectors. */
internal class Eigen3(val values: DoubleArray, val vectors: List<Vec3>)

/**
 * Eigen decomposition of a symmetric 3x3 matrix with the cyclic Jacobi method: slower than closed
 * forms but numerically robust, and a 3x3 converges in a handful of sweeps.
 */
internal fun symmetricEigen3(matrix: Array<DoubleArray>): Eigen3 {
    require(matrix.size == 3 && matrix.all { it.size == 3 }) { "Expected a 3x3 matrix" }
    val a = Array(3) { i -> matrix[i].copyOf() }
    val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }

    val scale = (0 until 3).sumOf { i -> (0 until 3).sumOf { j -> a[i][j] * a[i][j] } }
    for (sweep in 0 until MAX_SWEEPS) {
        val offDiagonal = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2]
        if (offDiagonal <= scale * CONVERGENCE) break
        for (p in 0 until 2) {
            for (q in p + 1 until 3) {
                if (a[p][q] == 0.0) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until 3) {
                    val akp = a[k][p]
                    val akq = a[k][q]
                    a[k][p] = c * akp - s * akq
                    a[k][q] = s * akp + c * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p][k]
                    val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk
                    a[q][k] = s * apk + c * aqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k][p]
                    val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
                }
            }
        }
    }
    val order = (0 until 3).sortedBy { a[it][it] }
    return Eigen3(
        values = DoubleArray(3) { a[order[it]][order[it]] },
        vectors = order.map { col -> Vec3(v[0][col], v[1][col], v[2][col]).normalized() },
    )
}

private const val MAX_SWEEPS = 50
private const val CONVERGENCE = 1e-30

package io.github.brunovinicioslg.ladeira.matching

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.EdgePosition
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import kotlin.math.abs
import kotlin.math.max

data class GpsFix(
    val position: LatLon,
    val accuracyM: Double,
    val speedMps: Double? = null,
    val bearingDegrees: Double? = null,
    val timeMillis: Long = 0,
)

data class MatchResult(val position: EdgePosition, val distanceToRoad: Double)

/**
 * Online map matching (a pruned hidden Markov model): each fix produces candidate road positions
 * scored by distance to the fix and heading agreement; transitions favor candidates whose
 * driving distance from the previous position matches the straight-line distance between fixes.
 * This keeps the match on the right road at junctions, overpasses and parallel roads.
 */
class MapMatcher(private val network: RoadNetwork, private val config: Config = Config()) {

    data class Config(
        val searchRadiusM: Double = 50.0,
        /** Below this speed the GPS heading is noise and is ignored. */
        val minSpeedForHeadingMps: Double = 2.0,
        val transitionScaleM: Double = 25.0,
        val maxHypotheses: Int = 8,
    )

    private data class Hypothesis(val position: EdgePosition, val logScore: Double, val fix: GpsFix)

    private var hypotheses: List<Hypothesis> = emptyList()

    fun reset() {
        hypotheses = emptyList()
    }

    /** Null when no road is close enough (off road, or GPS far off). */
    fun update(fix: GpsFix): MatchResult? {
        if (!fix.position.isValid()) return null
        val sigma = fix.accuracyM.coerceIn(4.0, 30.0)
        val radius = max(config.searchRadiusM, 2.5 * sigma)
        val moving = (fix.speedMps ?: 0.0) >= config.minSpeedForHeadingMps && fix.bearingDegrees != null
        val previous = hypotheses

        val next = network.candidates(fix.position, radius).flatMap { c ->
            listOf(true, false).mapNotNull { forward ->
                if (!forward && c.edge.oneway) return@mapNotNull null
                val position = EdgePosition(c.edge, c.offset, forward)
                var logScore = -0.5 * (c.distance / sigma).let { it * it }
                if (moving) {
                    val turn = Geo.angleDifference(fix.bearingDegrees, position.bearing)
                    logScore -= (turn / 30).let { it * it }
                } else if (previous.isNotEmpty()) {
                    // Standing still: keep the previous travel direction.
                    val prevForward = previous.first().position
                    if (prevForward.edge.id == c.edge.id && prevForward.forward != forward) logScore -= 4.0
                }
                if (previous.isNotEmpty()) {
                    val best = previous.maxOf { h -> h.logScore + transitionLogProbability(h, position, fix) }
                    if (best == Double.NEGATIVE_INFINITY) return@mapNotNull null
                    logScore += best
                }
                Hypothesis(position, logScore, fix)
            }
        }.sortedByDescending { it.logScore }.take(config.maxHypotheses)

        if (next.isEmpty()) {
            // Either off road or the previous match was wrong: start over from this fix.
            hypotheses = emptyList()
            return if (previous.isNotEmpty()) update(fix) else null
        }
        val top = next.first().logScore
        hypotheses = next.map { it.copy(logScore = it.logScore - top) } // keeps numbers bounded
        val best = hypotheses.first()
        return MatchResult(best.position, distanceToRoad = Geo.distance(best.position.point, fix.position))
    }

    private fun transitionLogProbability(from: Hypothesis, to: EdgePosition, fix: GpsFix): Double {
        val straight = Geo.distance(from.fix.position, fix.position)
        val limit = straight * 2 + 100
        val driven = RouteDistance.between(network, from.position, to, limit) ?: return Double.NEGATIVE_INFINITY
        return -abs(driven - straight) / config.transitionScaleM
    }
}

/** Driving distance between two positions along the road graph, respecting one-way streets. */
object RouteDistance {

    /** Small backwards moves on the same edge are GPS jitter, not a U-turn. */
    private const val JITTER_TOLERANCE_M = 15.0

    fun between(network: RoadNetwork, from: EdgePosition, to: EdgePosition, limit: Double): Double? {
        if (from.edge.id == to.edge.id) {
            val delta = if (from.forward) to.offset - from.offset else from.offset - to.offset
            if (from.forward == to.forward && delta >= -JITTER_TOLERANCE_M) return abs(delta)
        }
        // Dijkstra over nodes, starting from the node `from` is heading to.
        val dist = HashMap<Int, Double>()
        val queue = ArrayList<Pair<Int, Double>>()
        val startNode = from.headingNode
        dist[startNode] = from.remaining
        queue += startNode to from.remaining
        var best: Double? = null
        while (queue.isNotEmpty()) {
            val idx = queue.indices.minBy { queue[it].second }
            val (node, d) = queue.removeAt(idx)
            if (d > (dist[node] ?: Double.MAX_VALUE) || d > limit) continue
            if (best != null && d >= best) break
            for (e in network.edgesAt(node)) {
                val forward = e.from == node
                if (!forward && e.oneway) continue
                // Arriving at the target on this edge?
                if (e.id == to.edge.id && to.forward == forward) {
                    val within = if (forward) to.offset else e.length - to.offset
                    val total = d + within
                    if (total <= limit && (best == null || total < best)) best = total
                }
                val other = if (forward) e.to else e.from
                val nd = d + e.length
                if (nd <= limit && nd < (dist[other] ?: Double.MAX_VALUE)) {
                    dist[other] = nd
                    queue += other to nd
                }
            }
        }
        return best
    }
}

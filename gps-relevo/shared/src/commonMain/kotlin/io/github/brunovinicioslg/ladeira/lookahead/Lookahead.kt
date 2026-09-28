package io.github.brunovinicioslg.ladeira.lookahead

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.profile.ProfilePoint
import io.github.brunovinicioslg.ladeira.road.Edge
import io.github.brunovinicioslg.ladeira.road.EdgePosition
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import kotlin.math.min

/** One edge of the predicted path, driven from [fromOffset] to [toOffset] (decreasing when backwards). */
data class PathStep(val edge: Edge, val forward: Boolean, val fromOffset: Double, val toOffset: Double) {
    val length: Double get() = kotlin.math.abs(toOffset - fromOffset)

    fun offsetAt(distanceInStep: Double) = if (forward) fromOffset + distanceInStep else fromOffset - distanceInStep
}

/** The road the vehicle will most likely follow, starting at its current position. */
class PathAhead(val steps: List<PathStep>) {

    val length: Double = steps.sumOf { it.length }

    /** Where each step starts, measured from the vehicle. */
    private val stepStarts: DoubleArray = DoubleArray(steps.size).also { starts ->
        for (i in 1 until steps.size) starts[i] = starts[i - 1] + steps[i - 1].length
    }

    fun locate(distance: Double): Pair<PathStep, Double>? {
        if (steps.isEmpty()) return null
        val d = distance.coerceIn(0.0, length)
        var i = steps.size - 1
        while (i > 0 && stepStarts[i] > d) i--
        return steps[i] to steps[i].offsetAt(d - stepStarts[i])
    }

    fun pointAt(distance: Double): LatLon? = locate(distance)?.let { (step, offset) -> step.edge.pointAt(offset) }

    /** Elevation every [spacing] meters from the vehicle to the end of the path. */
    fun profile(spacing: Double = Edge.ELEVATION_SPACING_M): List<ProfilePoint> {
        if (steps.isEmpty()) return emptyList()
        val out = mutableListOf<ProfilePoint>()
        var d = 0.0
        while (d <= length) {
            val (step, offset) = locate(d) ?: break
            step.edge.elevationAt(offset)?.let { out += ProfilePoint(d, it) }
            d += spacing
        }
        if (out.lastOrNull()?.distance != length) {
            locate(length)?.let { (step, offset) -> step.edge.elevationAt(offset)?.let { out += ProfilePoint(length, it) } }
        }
        return out
    }
}

/**
 * Predicts the road ahead without a planned route: at each junction it keeps to the same road
 * (same name or number) or, failing that, the straightest reasonable continuation. It stops at
 * junctions where no option is clearly the continuation (a T junction), to avoid warning about a
 * road the driver may not take.
 */
object Lookahead {

    /** Turns sharper than this are not a "continuation" unless the road name continues. */
    const val MAX_CONTINUATION_TURN_DEGREES = 60.0

    fun follow(network: RoadNetwork, start: EdgePosition, maxDistance: Double): PathAhead {
        val steps = mutableListOf<PathStep>()
        var edge = start.edge
        var forward = start.forward
        var offset = start.offset
        var total = 0.0
        val visited = HashSet<Int>()
        while (total < maxDistance) {
            val remainingInEdge = if (forward) edge.length - offset else offset
            val take = min(remainingInEdge, maxDistance - total)
            val toOffset = if (forward) offset + take else offset - take
            steps += PathStep(edge, forward, offset, toOffset)
            total += take
            if (total >= maxDistance || !visited.add(edge.id)) break
            val node = if (forward) edge.to else edge.from
            val arrivalBearing = if (forward) edge.bearingAt(edge.length) else Geo.normalizeDegrees(edge.bearingAt(0.0) + 180)
            val next = chooseContinuation(network, edge, node, arrivalBearing) ?: break
            edge = next.first
            forward = next.second
            offset = if (forward) 0.0 else edge.length
        }
        return PathAhead(steps)
    }

    /** The edge (and direction) that continues [arriving] through [node], or null when unclear. */
    fun chooseContinuation(network: RoadNetwork, arriving: Edge, node: Int, arrivalBearing: Double): Pair<Edge, Boolean>? {
        data class Option(val edge: Edge, val forward: Boolean, val turn: Double, val sameRoad: Boolean)
        val options = network.edgesAt(node).mapNotNull { e ->
            if (e.id == arriving.id) return@mapNotNull null
            val forward = e.from == node
            if (!forward && e.oneway) return@mapNotNull null // would drive against the one-way direction
            if (!forward && e.to != node) return@mapNotNull null
            val departure = if (forward) e.bearingAt(0.0) else Geo.normalizeDegrees(e.bearingAt(e.length) + 180)
            Option(e, forward, Geo.angleDifference(arrivalBearing, departure), e.sameRoadAs(arriving))
        }
        if (options.isEmpty()) return null
        // Degree-2 nodes (just a bend in the road) always continue.
        if (options.size == 1) return options.single().let { it.edge to it.forward }
        val sameRoad = options.filter { it.sameRoad && it.turn <= 120 }
        val pick = sameRoad.minByOrNull { it.turn }
            ?: options.filter { it.turn <= MAX_CONTINUATION_TURN_DEGREES }
                .maxByOrNull { it.edge.roadClass.rank * 20 - it.turn - (if (it.edge.link && !arriving.link) 50 else 0) }
            ?: return null
        return pick.edge to pick.forward
    }
}

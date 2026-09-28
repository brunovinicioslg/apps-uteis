package io.github.brunovinicioslg.ladeira

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.Edge
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.RoadClass
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import kotlin.math.min
import kotlin.random.Random

/** A point in Belo Horizonte, where the synthetic roads start. */
val BH = LatLon(-19.92, -43.94)

fun forEachCase(cases: Int, baseSeed: Long = 20260928L, block: (seed: Long, rnd: Random) -> Unit) {
    repeat(cases) { i -> block(baseSeed + i, Random(baseSeed + i)) }
}

/** Points every [step] meters along a straight line. */
fun line(start: LatLon, bearing: Double, length: Double, step: Double = 50.0): List<LatLon> {
    val n = kotlin.math.ceil(length / step).toInt()
    return (0..n).map { i -> Geo.destination(start, bearing, min(i * step, length)) }
}

/** Builds road graphs for tests; roads are split into edges like the pipeline does. */
class NetworkBuilder {
    private var nextNode = 1
    private var nextEdge = 1
    val edges = mutableListOf<Edge>()
    val pois = mutableListOf<Poi>()

    fun node() = nextNode++

    /**
     * Adds a road along [points]; returns the node ids at both ends. [elevation] receives the
     * distance from the start of this road.
     */
    fun road(
        points: List<LatLon>,
        startNode: Int = node(),
        endNode: Int? = null,
        elevation: (Double) -> Double = { 800.0 },
        roadClass: RoadClass = RoadClass.PRIMARY,
        name: String? = null,
        oneway: Boolean = false,
        bridge: Boolean = false,
        maxEdge: Double = 500.0,
    ): Pair<Int, Int> {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i])
        var pieceStart = 0
        var fromNode = startNode
        while (pieceStart < points.size - 1) {
            var pieceEnd = pieceStart + 1
            while (pieceEnd < points.size - 1 && cumulative[pieceEnd + 1] - cumulative[pieceStart] <= maxEdge) pieceEnd++
            val last = pieceEnd == points.size - 1
            val toNode = if (last) endNode ?: node() else node()
            val geometry = points.subList(pieceStart, pieceEnd + 1)
            val length = cumulative[pieceEnd] - cumulative[pieceStart]
            val count = Edge.sampleCount(length)
            val elevations = DoubleArray(count) { k -> elevation(cumulative[pieceStart] + Edge.sampleOffset(k, count, length)) }
            edges += Edge(nextEdge++, fromNode, toNode, geometry, elevations, roadClass, oneway, bridge, name = name)
            fromNode = toNode
            pieceStart = pieceEnd
        }
        return startNode to fromNode
    }

    fun network() = RoadNetwork(edges.toList(), pois.toList())
}

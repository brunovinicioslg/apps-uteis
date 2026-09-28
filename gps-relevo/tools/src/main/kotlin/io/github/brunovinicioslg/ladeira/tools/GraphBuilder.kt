package io.github.brunovinicioslg.ladeira.tools

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.Edge
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.RoadPackage
import kotlin.math.ceil

/**
 * Turns OSM roads into the package's edge graph:
 * 1. ways are split at every node shared with another way (junctions) and at their ends;
 * 2. each stretch gets an elevation profile from the terrain model, except bridges and tunnels,
 *    whose deck or bore is interpolated in a straight line between their two ends (the terrain
 *    model measures the valley under a bridge and the hill over a tunnel);
 * 3. stretches are cut into pieces of at most [RoadPackage.MAX_EDGE_LENGTH_M].
 */
class GraphBuilder(private val elevation: ElevationModel) {

    data class Result(val edges: List<Edge>, val pois: List<Poi>, val skippedWays: Int)

    private var nextNode = 1
    private var nextEdge = 1

    fun build(osm: OsmData): Result {
        val roads = osm.ways.filter { Osm.roadClass(it.tags) != null && it.nodes.size >= 2 }
        // A node splits a way when it is a way end or is shared by two or more ways.
        val usage = HashMap<Long, Int>()
        for (w in roads) for ((i, n) in w.nodes.withIndex()) {
            usage[n] = (usage[n] ?: 0) + if (i == 0 || i == w.nodes.lastIndex) 2 else 1
        }
        val nodeIds = HashMap<Long, Int>()
        fun graphNode(osmId: Long) = nodeIds.getOrPut(osmId) { nextNode++ }
        var skipped = 0
        val edges = mutableListOf<Edge>()

        for (way in roads) {
            val points = way.nodes.map { osm.nodes[it] }
            if (points.any { it == null }) {
                skipped++
                continue
            }
            val oneway = Osm.onewayDirection(way.tags)
            var start = 0
            for (i in 1 until way.nodes.size) {
                if (i != way.nodes.lastIndex && (usage[way.nodes[i]] ?: 0) < 2) continue
                var ids = way.nodes.subList(start, i + 1)
                var geometry = points.subList(start, i + 1).map { it!! }
                start = i
                if (oneway == -1) {
                    ids = ids.reversed()
                    geometry = geometry.reversed()
                }
                if (geometry.zipWithNext().all { (a, b) -> a == b }) continue // zero-length stretch
                edges += stretchEdges(geometry, graphNode(ids.first()), graphNode(ids.last()), way.tags, oneway != 0)
            }
        }
        val pois = osm.poiNodes.mapNotNull { (p, tags) -> Osm.poi(p, tags) }
        return Result(edges, pois, skipped)
    }

    /** One stretch between junctions, cut into pieces of at most MAX_EDGE_LENGTH_M. */
    private fun stretchEdges(g: List<LatLon>, fromNode: Int, toNode: Int, tags: Map<String, String>, oneway: Boolean): List<Edge> {
        val cumulative = DoubleArray(g.size)
        for (i in 1 until g.size) cumulative[i] = cumulative[i - 1] + Geo.distance(g[i - 1], g[i])
        val total = cumulative.last()
        val pieces = ceil(total / RoadPackage.MAX_EDGE_LENGTH_M).toInt().coerceAtLeast(1)
        val pieceLength = total / pieces
        val flat = Osm.isBridge(tags) || Osm.isTunnel(tags)
        val startHeight = elevation.elevation(g.first().lat, g.first().lon)
        val endHeight = elevation.elevation(g.last().lat, g.last().lon)

        fun pointAt(d: Double): LatLon {
            var i = 0
            while (i < g.size - 2 && cumulative[i + 1] < d) i++
            val seg = cumulative[i + 1] - cumulative[i]
            val t = if (seg > 0) ((d - cumulative[i]) / seg).coerceIn(0.0, 1.0) else 0.0
            return LatLon(g[i].lat + (g[i + 1].lat - g[i].lat) * t, g[i].lon + (g[i + 1].lon - g[i].lon) * t)
        }

        fun heightAt(d: Double): Double {
            if (flat && startHeight != null && endHeight != null) return startHeight + (endHeight - startHeight) * (d / total)
            val p = pointAt(d)
            return elevation.elevation(p.lat, p.lon) ?: startHeight ?: endHeight ?: 0.0
        }

        // Node ids along the stretch: its two junctions plus new nodes between pieces.
        val nodes = listOf(fromNode) + List(pieces - 1) { nextNode++ } + listOf(toNode)
        return (0 until pieces).map { k ->
            val from = k * pieceLength
            val to = if (k == pieces - 1) total else (k + 1) * pieceLength
            val inner = g.indices.filter { cumulative[it] > from + 1e-6 && cumulative[it] < to - 1e-6 }.map { g[it] }
            val length = to - from
            val count = Edge.sampleCount(length)
            Edge(
                id = nextEdge++,
                from = nodes[k],
                to = nodes[k + 1],
                geometry = listOf(pointAt(from)) + inner + listOf(pointAt(to)),
                elevations = DoubleArray(count) { s -> heightAt(from + Edge.sampleOffset(s, count, length)) },
                roadClass = Osm.roadClass(tags)!!,
                oneway = oneway,
                bridge = Osm.isBridge(tags),
                tunnel = Osm.isTunnel(tags),
                link = Osm.isLink(tags),
                name = tags["name"],
                ref = tags["ref"],
                maxSpeedKmh = Osm.maxSpeed(tags["maxspeed"]),
            )
        }
    }
}

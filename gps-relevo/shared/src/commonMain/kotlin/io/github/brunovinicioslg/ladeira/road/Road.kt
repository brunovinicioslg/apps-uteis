package io.github.brunovinicioslg.ladeira.road

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.geo.LocalProjection
import kotlin.math.floor
import kotlin.math.max

/** OSM highway classes that cars drive on, ranked by importance. */
enum class RoadClass(val rank: Int) {
    SERVICE(0),
    RESIDENTIAL(1),
    UNCLASSIFIED(2),
    TERTIARY(3),
    SECONDARY(4),
    PRIMARY(5),
    TRUNK(6),
    MOTORWAY(7),
}

enum class PoiType {
    SPEED_CAMERA,
    RED_LIGHT_CAMERA,
    SPEED_BUMP,
    POTHOLE,
    DANGEROUS_CURVE,
    FLOODING,
    TOLL,
    ROADWORK,
    OTHER,
}

/** A point of interest drivers should be warned about. */
data class Poi(
    val type: PoiType,
    val position: LatLon,
    /** Direction of travel it applies to (e.g. a camera facing one way); null for both ways. */
    val directionDegrees: Double? = null,
    val speedLimitKmh: Int? = null,
)

/**
 * A stretch of road between two nodes. [geometry] runs from [from] to [to]; [elevations] are
 * sampled every [ELEVATION_SPACING_M] meters along it, starting at [from] and ending exactly at [to].
 */
class Edge(
    val id: Int,
    val from: Int,
    val to: Int,
    val geometry: List<LatLon>,
    val elevations: DoubleArray,
    val roadClass: RoadClass,
    /** Only drivable from [from] to [to]. */
    val oneway: Boolean = false,
    val bridge: Boolean = false,
    val tunnel: Boolean = false,
    /** Ramp or slip road. */
    val link: Boolean = false,
    val name: String? = null,
    val ref: String? = null,
    val maxSpeedKmh: Int? = null,
) {
    init {
        require(geometry.size >= 2) { "An edge needs at least two points" }
    }

    /** Cumulative distance of each geometry point from [from], in meters. */
    val cumulative: DoubleArray = DoubleArray(geometry.size).also { acc ->
        for (i in 1 until geometry.size) acc[i] = acc[i - 1] + Geo.distance(geometry[i - 1], geometry[i])
    }

    val length: Double get() = cumulative.last()

    fun elevationAt(offset: Double): Double? {
        if (elevations.isEmpty()) return null
        if (elevations.size == 1) return elevations[0]
        val o = offset.coerceIn(0.0, length)
        // Samples every ELEVATION_SPACING_M, the last one exactly at the end.
        val position = o / ELEVATION_SPACING_M
        val i = floor(position).toInt().coerceAtMost(elevations.size - 2)
        val start = i * ELEVATION_SPACING_M
        val end = if (i + 1 == elevations.size - 1) length else (i + 1) * ELEVATION_SPACING_M
        val t = if (end > start) ((o - start) / (end - start)).coerceIn(0.0, 1.0) else 0.0
        return elevations[i] + (elevations[i + 1] - elevations[i]) * t
    }

    fun pointAt(offset: Double): LatLon {
        val o = offset.coerceIn(0.0, length)
        val i = segmentIndex(o)
        val segLength = cumulative[i + 1] - cumulative[i]
        val t = if (segLength > 0) (o - cumulative[i]) / segLength else 0.0
        val a = geometry[i]
        val b = geometry[i + 1]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Bearing of the road at [offset] when driving from [from] towards [to]. */
    fun bearingAt(offset: Double): Double {
        val i = segmentIndex(offset.coerceIn(0.0, length))
        return Geo.bearing(geometry[i], geometry[i + 1])
    }

    fun segmentIndex(offset: Double): Int {
        var lo = 0
        var hi = cumulative.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulative[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Same name, or a shared route number ("BR-040" continues on "BR-040;BR-356" where routes overlap). */
    fun sameRoadAs(other: Edge): Boolean {
        if (name != null && name == other.name) return true
        val mine = refs()
        return mine.isNotEmpty() && other.refs().any { it in mine }
    }

    private fun refs(): Set<String> = ref?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    companion object {
        const val ELEVATION_SPACING_M = 25.0

        /** Number of samples needed for an edge of [length] meters. */
        fun sampleCount(length: Double): Int = max(2, kotlin.math.ceil(length / ELEVATION_SPACING_M).toInt() + 1)

        /** Distance along the edge of sample [index] (the last sample sits exactly at [length]). */
        fun sampleOffset(index: Int, count: Int, length: Double): Double =
            if (index == count - 1) length else index * ELEVATION_SPACING_M
    }
}

/** Where a vehicle is on an edge and which way it is going. */
data class EdgePosition(val edge: Edge, val offset: Double, val forward: Boolean) {
    /** Meters left until the node the vehicle is driving towards. */
    val remaining: Double get() = if (forward) edge.length - offset else offset
    val headingNode: Int get() = if (forward) edge.to else edge.from
    val bearing: Double get() = if (forward) edge.bearingAt(offset) else Geo.normalizeDegrees(edge.bearingAt(offset) + 180)
    val point: LatLon get() = edge.pointAt(offset)
}

/** A projection of a point onto an edge. */
data class EdgeCandidate(val edge: Edge, val offset: Double, val distance: Double)

/** An in-memory slice of the road graph with a grid index for proximity queries. */
class RoadNetwork(val edges: List<Edge>, val pois: List<Poi> = emptyList()) {

    private val byNode: Map<Int, List<Edge>> = buildMap<Int, MutableList<Edge>> {
        for (e in edges) {
            getOrPut(e.from) { mutableListOf() }.add(e)
            if (e.to != e.from) getOrPut(e.to) { mutableListOf() }.add(e)
        }
    }

    private val grid: Map<Long, List<Edge>> = buildMap<Long, MutableList<Edge>> {
        for (e in edges) {
            val cells = mutableSetOf<Long>()
            // Sample densely enough that every cell a segment crosses is registered.
            for (i in 0 until e.geometry.size - 1) {
                val a = e.geometry[i]
                val b = e.geometry[i + 1]
                val steps = max(1, (Geo.distance(a, b) / (GRID_CELL_DEG * 111_000 / 4)).toInt())
                for (s in 0..steps) {
                    val t = s.toDouble() / steps
                    cells += cellKey(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                }
            }
            for (c in cells) getOrPut(c) { mutableListOf() }.add(e)
        }
    }

    fun edgesAt(node: Int): List<Edge> = byNode[node].orEmpty()

    /** Edges within [radiusM] of [point], each with its closest offset and distance, nearest first. */
    fun candidates(point: LatLon, radiusM: Double): List<EdgeCandidate> {
        val dLat = radiusM / 111_000
        val dLon = dLat / kotlin.math.cos(Geo.toRadians(point.lat)).coerceAtLeast(0.01)
        val seen = HashSet<Int>()
        val projection = LocalProjection(point)
        val result = mutableListOf<EdgeCandidate>()
        var lat = floor((point.lat - dLat) / GRID_CELL_DEG) * GRID_CELL_DEG
        while (lat <= point.lat + dLat) {
            var lon = floor((point.lon - dLon) / GRID_CELL_DEG) * GRID_CELL_DEG
            while (lon <= point.lon + dLon) {
                for (e in grid[cellKey(lat + GRID_CELL_DEG / 2, lon + GRID_CELL_DEG / 2)].orEmpty()) {
                    if (!seen.add(e.id)) continue
                    val c = project(e, projection)
                    if (c.distance <= radiusM) result += c
                }
                lon += GRID_CELL_DEG
            }
            lat += GRID_CELL_DEG
        }
        return result.sortedBy { it.distance }
    }

    /** Closest point of [edge] to the projection origin. */
    private fun project(edge: Edge, projection: LocalProjection): EdgeCandidate {
        var best = Double.MAX_VALUE
        var bestOffset = 0.0
        val pts = edge.geometry.map(projection::toXY)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val ab = pts[i + 1] - a
            val len2 = ab dot ab
            val t = if (len2 > 0) (((a * -1.0) dot ab) / len2).coerceIn(0.0, 1.0) else 0.0
            val closest = a + ab * t
            val d = closest.length()
            if (d < best) {
                best = d
                bestOffset = edge.cumulative[i] + (edge.cumulative[i + 1] - edge.cumulative[i]) * t
            }
        }
        return EdgeCandidate(edge, bestOffset, best)
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        val y = floor(lat / GRID_CELL_DEG).toLong()
        val x = floor(lon / GRID_CELL_DEG).toLong()
        return (y shl 32) xor (x and 0xffffffffL)
    }

    private companion object {
        /** About 220 m: a handful of edges per cell in dense cities. */
        const val GRID_CELL_DEG = 0.002
    }
}

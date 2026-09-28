package io.github.brunovinicioslg.ladeira.app

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.Edge
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.RoadClass
import io.github.brunovinicioslg.ladeira.road.RoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Test regions: a synthetic stretch of BR-040 and a matching (empty) PMTiles map file. */
object TestData {
    /** Where the synthetic road starts, just south of Belo Horizonte. */
    val START = LatLon(-20.05, -43.95)
    const val ROAD_LENGTH_M = 10_000.0
    const val CAMERA_AT_M = 7_000.0

    /** Point [meters] along the road, which runs due north. */
    fun along(meters: Double): LatLon = Geo.destination(START, 0.0, meters)

    /** 10 km heading north: flat, then a 3 km descent at 6 % from 3 km, flat again; a camera at 7 km. */
    fun serraPackage(): ByteArray {
        val step = 50.0
        val points = (0..(ROAD_LENGTH_M / step).toInt()).map { along(it * step) }
        val elevation = { d: Double -> 1_300 - (d - 3_000.0).coerceIn(0.0, 3_000.0) * 0.06 }
        val edges = points.chunked(10).mapIndexedNotNull { i, _ ->
            val from = i * 10
            val to = min(from + 10, points.size - 1)
            if (from >= to) return@mapIndexedNotNull null
            val length = (to - from) * step
            val count = Edge.sampleCount(length)
            Edge(
                id = i + 1, from = i + 1, to = i + 2,
                geometry = points.subList(from, to + 1),
                elevations = DoubleArray(count) { k -> elevation(from * step + Edge.sampleOffset(k, count, length)) },
                roadClass = RoadClass.TRUNK, name = "Rodovia BR-040", ref = "BR-040", maxSpeedKmh = 80,
            )
        }
        val camera = Poi(PoiType.SPEED_CAMERA, along(CAMERA_AT_M), directionDegrees = 0.0, speedLimitKmh = 80)
        return RoadPackage.write(edges, listOf(camera))
    }

    /** A PMTiles v3 file with a valid header covering the road and [dataBytes] of tile data. */
    fun pmtiles(
        minLon: Double = -44.02, minLat: Double = -20.08, maxLon: Double = -43.86, maxLat: Double = -19.90,
        center: LatLon? = LatLon(-19.99, -43.94), dataBytes: Int = 64, tileType: Int = 1, version: Int = 3,
    ): ByteArray {
        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray(Charsets.US_ASCII)).put(version.toByte())
        val rootLength = 16L
        header.putLong(127).putLong(rootLength) // root directory
        header.putLong(127 + rootLength).putLong(0) // metadata
        header.putLong(127 + rootLength).putLong(0) // leaf directories
        header.putLong(127 + rootLength).putLong(dataBytes.toLong()) // tile data
        header.putLong(1).putLong(1).putLong(1) // addressed tiles, entries, contents
        header.put(1).put(1).put(1).put(tileType.toByte()) // clustered, compressions, tile type
        header.put(0).put(15) // zooms
        header.putInt(e7(minLon)).putInt(e7(minLat)).putInt(e7(maxLon)).putInt(e7(maxLat))
        header.put(12).putInt(e7(center?.lon ?: 0.0)).putInt(e7(center?.lat ?: 0.0))
        return header.array() + ByteArray((rootLength + dataBytes).toInt())
    }

    private fun e7(v: Double) = Math.round(v * 1e7).toInt()
}

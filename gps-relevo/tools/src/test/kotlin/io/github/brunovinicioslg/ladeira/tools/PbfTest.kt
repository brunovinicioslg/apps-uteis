package io.github.brunovinicioslg.ladeira.tools

import com.google.protobuf.ByteString
import crosby.binary.Osmformat
import crosby.binary.file.BlockOutputStream
import crosby.binary.file.FileBlock
import io.github.brunovinicioslg.ladeira.geo.LatLon
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PbfTest {

    private val dir = Files.createTempDirectory("pbf").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Node(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String> = emptyMap())
    private class Way(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)

    /** A minimal extract, written the way OpenStreetMap writes them: dense nodes, then ways. */
    private fun writePbf(nodes: List<Node>, ways: List<Way>): File {
        val strings = mutableListOf("")
        fun s(text: String): Int = strings.indexOf(text).takeIf { it >= 0 } ?: strings.run { add(text); size - 1 }

        val dense = Osmformat.DenseNodes.newBuilder()
        var lastId = 0L
        var lastLat = 0L
        var lastLon = 0L
        for (n in nodes) {
            // Default granularity: 100 nanodegrees.
            val lat = Math.round(n.lat * 1e7)
            val lon = Math.round(n.lon * 1e7)
            dense.addId(n.id - lastId).addLat(lat - lastLat).addLon(lon - lastLon)
            lastId = n.id
            lastLat = lat
            lastLon = lon
            for ((k, v) in n.tags) dense.addKeysVals(s(k)).addKeysVals(s(v))
            dense.addKeysVals(0)
        }
        val wayGroup = Osmformat.PrimitiveGroup.newBuilder()
        for (w in ways) {
            val way = Osmformat.Way.newBuilder().setId(w.id)
            for ((k, v) in w.tags) way.addKeys(s(k)).addVals(s(v))
            var last = 0L
            for (id in w.nodes) {
                way.addRefs(id - last)
                last = id
            }
            wayGroup.addWays(way)
        }
        val table = Osmformat.StringTable.newBuilder()
        strings.forEach { table.addS(ByteString.copyFromUtf8(it)) }
        val block = Osmformat.PrimitiveBlock.newBuilder()
            .setStringtable(table)
            .addPrimitivegroup(Osmformat.PrimitiveGroup.newBuilder().setDense(dense))
            .addPrimitivegroup(wayGroup)
            .build()
        val header = Osmformat.HeaderBlock.newBuilder().addRequiredFeatures("OsmSchema-V0.6").addRequiredFeatures("DenseNodes").build()

        val file = File(dir, "test.osm.pbf")
        BlockOutputStream(file.outputStream()).use { out ->
            out.write(FileBlock.newInstance("OSMHeader", header.toByteString(), null))
            out.write(FileBlock.newInstance("OSMData", block.toByteString(), null))
        }
        return file
    }

    @Test
    fun readsRoadsTheirNodesAndWarningPoints() {
        val file = writePbf(
            nodes = listOf(
                Node(1, -19.9, -43.9),
                Node(2, -19.91, -43.91),
                Node(3, -19.92, -43.915, mapOf("highway" to "speed_camera", "maxspeed" to "60")),
                Node(4, -19.8, -43.8),
                Node(5, -19.81, -43.81),
                Node(6, -19.82, -43.82, mapOf("amenity" to "bench")),
            ),
            ways = listOf(
                Way(10, listOf(1, 2, 3), mapOf("highway" to "trunk", "ref" to "BR-040", "surface" to "asphalt")),
                Way(11, listOf(4, 5, 4), mapOf("building" to "yes")),
                Way(12, listOf(5, 6), mapOf("highway" to "footway")),
            ),
        )
        val osm = Pbf.read(file)

        // Only the drivable road; its useless tags are dropped.
        val road = osm.ways.single()
        assertEquals(10, road.id)
        assertEquals(listOf(1L, 2L, 3L), road.nodes)
        assertEquals(mapOf("highway" to "trunk", "ref" to "BR-040"), road.tags)

        // Only road nodes are kept, at full precision.
        assertEquals(3, osm.nodes.size)
        val first = assertNotNull(osm.nodes[1])
        assertEquals(-19.9, first.lat, 1e-7)
        assertEquals(-43.9, first.lon, 1e-7)
        assertNull(osm.nodes[4])

        // The speed camera, not the bench.
        val (position, tags) = osm.poiNodes.single()
        assertEquals(-19.92, position.lat, 1e-7)
        assertEquals("speed_camera", tags["highway"])
        assertEquals(60, GraphBuilder(ElevationModel { _, _ -> 800.0 }).build(osm).pois.single().speedLimitKmh)
    }

    @Test
    fun aRegionIsCutFromTheStateAndSortedForTheElevationCache() {
        val file = writePbf(
            nodes = listOf(Node(1, -19.9, -43.9), Node(2, -19.91, -43.91), Node(3, -21.0, -44.9), Node(4, -21.01, -44.91)),
            ways = listOf(
                Way(20, listOf(3, 4), mapOf("highway" to "primary")),
                Way(21, listOf(1, 2), mapOf("highway" to "residential")),
            ),
        )
        val osm = Pbf.read(file)
        // North first: tile rows go from north to south.
        assertEquals(listOf(21L, 20L), osm.spatiallySorted(12).ways.map { it.id })
        val bh = osm.within(Bbox(-20.0, -44.0, -19.8, -43.8))
        assertEquals(listOf(21L), bh.ways.map { it.id })
        assertEquals(2, osm.roadTiles(12).size)
    }

    @Test
    fun compactNodeMapStoresOnlyTheRequestedIds() {
        val map = CompactNodeMap(longArrayOf(5, 7, 9))
        map.set(7, -19.123_456_7, -43.765_432_1)
        map.set(8, 1.0, 1.0) // not requested: ignored
        assertEquals(1, map.size)
        assertEquals(LatLon(-19.1234567, -43.7654321), map[7])
        assertNull(map[5])
        assertNull(map[8])
        assertTrue(map.containsKey(7))
        assertEquals(listOf(7L), map.keys.toList())
    }
}

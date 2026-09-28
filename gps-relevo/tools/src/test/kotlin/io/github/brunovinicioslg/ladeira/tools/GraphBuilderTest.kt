package io.github.brunovinicioslg.ladeira.tools

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.RoadClass
import io.github.brunovinicioslg.ladeira.road.RoadPackage
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphBuilderTest {

    private val origin = LatLon(-19.92, -43.94)

    /** A valley: the terrain dips 40 m around 1 km east of the origin. */
    private val valley = ElevationModel { lat, lon ->
        val east = Geo.distance(origin, LatLon(origin.lat, lon)) * (if (lon >= origin.lon) 1 else -1)
        val north = Geo.distance(origin, LatLon(lat, origin.lon))
        800.0 - 40.0 * kotlin.math.exp(-((east - 1_000) / 150).let { it * it }) + north * 0.0
    }

    private fun east(meters: Double) = Geo.destination(origin, 90.0, meters)

    private fun data(ways: List<OsmWay>, nodes: Map<Long, LatLon>, pois: List<Pair<LatLon, Map<String, String>>> = emptyList()) =
        OsmData(nodes, ways, pois)

    @Test
    fun waysSplitAtJunctionsAndLongStretchesAreCut() {
        // Way 1 runs 2 km east through node 3 (shared with way 2, a side street).
        val nodes = mapOf(1L to east(0.0), 2L to east(600.0), 3L to east(1_200.0), 4L to east(2_000.0), 5L to Geo.destination(east(1_200.0), 0.0, 300.0))
        val ways = listOf(
            OsmWay(10, listOf(1, 2, 3, 4), mapOf("highway" to "primary", "name" to "Av. A")),
            OsmWay(11, listOf(3, 5), mapOf("highway" to "residential", "name" to "Rua B")),
        )
        val edges = GraphBuilder(valley).build(data(ways, nodes)).edges
        val avenue = edges.filter { it.name == "Av. A" }
        assertTrue(avenue.all { it.length <= RoadPackage.MAX_EDGE_LENGTH_M + 1e-6 })
        assertEquals(2_000.0, avenue.sumOf { it.length }, 1.0)
        avenue.zipWithNext { a, b -> assertEquals(a.to, b.from, "pieces are chained") }
        val side = edges.single { it.name == "Rua B" }
        val junctionNode = side.from
        assertEquals(2, avenue.count { it.from == junctionNode || it.to == junctionNode }, "the avenue is split at the side street")
        assertEquals(RoadClass.RESIDENTIAL, side.roadClass)
    }

    @Test
    fun terrainIsSampledButBridgesAreInterpolated() {
        val nodes = mapOf(1L to east(0.0), 2L to east(700.0), 3L to east(1_300.0), 4L to east(2_000.0))
        val ways = listOf(
            OsmWay(1, listOf(1, 2), mapOf("highway" to "trunk", "ref" to "BR-040")),
            OsmWay(2, listOf(2, 3), mapOf("highway" to "trunk", "ref" to "BR-040", "bridge" to "viaduct")),
            OsmWay(3, listOf(3, 4), mapOf("highway" to "trunk", "ref" to "BR-040")),
        )
        val edges = GraphBuilder(valley).build(data(ways, nodes)).edges
        val bridge = edges.filter { it.bridge }
        assertTrue(bridge.isNotEmpty())
        // On the viaduct the profile stays at deck level instead of following the 40 m dip.
        bridge.flatMap { it.elevations.toList() }.forEach { assertTrue(it > 795.0, "deck at $it") }
        // Off the bridge the terrain is followed.
        val before = edges.first { !it.bridge }
        assertEquals(800.0, before.elevations.first(), 0.5)
    }

    @Test
    fun oneWayRulesAndReversal() {
        val nodes = mapOf(1L to east(0.0), 2L to east(300.0))
        val built = GraphBuilder(valley).build(
            data(
                listOf(
                    OsmWay(1, listOf(1, 2), mapOf("highway" to "primary", "oneway" to "-1", "name" to "Contramão")),
                    OsmWay(2, listOf(1, 2), mapOf("highway" to "primary", "junction" to "roundabout", "name" to "Rotatória")),
                    OsmWay(3, listOf(1, 2), mapOf("highway" to "service")), // not kept
                    OsmWay(4, listOf(1, 2), mapOf("highway" to "residential", "access" to "private")), // not kept
                ),
                nodes,
            ),
        ).edges
        val reversed = built.single { it.name == "Contramão" }
        assertTrue(reversed.oneway)
        assertEquals(270.0, reversed.bearingAt(0.0), 1.0, "geometry flipped to the legal direction")
        assertTrue(built.single { it.name == "Rotatória" }.oneway)
        assertEquals(2, built.size)
    }

    @Test
    fun missingNodesSkipTheWay() {
        val result = GraphBuilder(valley).build(data(listOf(OsmWay(1, listOf(1, 99), mapOf("highway" to "primary"))), mapOf(1L to origin)))
        assertEquals(1, result.skippedWays)
        assertTrue(result.edges.isEmpty())
    }

    @Test
    fun tagParsing() {
        assertEquals(60, Osm.maxSpeed("60"))
        assertEquals(80, Osm.maxSpeed("80 km/h"))
        assertEquals(48, Osm.maxSpeed("30 mph"))
        assertNull(Osm.maxSpeed("BR:urban"))
        assertEquals(90.0, Osm.direction("E"))
        assertEquals(202.5, Osm.direction("ssw"))
        assertEquals(350.0, Osm.direction("-10"))
        assertNull(Osm.direction("forward"))
        val camera = Osm.poi(origin, mapOf("highway" to "speed_camera", "maxspeed" to "60", "direction" to "N"))!!
        assertEquals(PoiType.SPEED_CAMERA, camera.type)
        assertEquals(60, camera.speedLimitKmh)
        assertEquals(0.0, camera.directionDegrees)
        assertEquals(PoiType.SPEED_BUMP, Osm.poi(origin, mapOf("traffic_calming" to "hump"))!!.type)
        assertNull(Osm.poi(origin, mapOf("amenity" to "cafe")))
    }

    @Test
    fun overpassJsonIsParsed() {
        val json = """
            {"elements":[
              {"type":"way","id":7,"nodes":[1,2],"tags":{"highway":"primary","name":"Av. Brasil"}},
              {"type":"node","id":1,"lat":-19.9,"lon":-43.9},
              {"type":"node","id":2,"lat":-19.91,"lon":-43.9},
              {"type":"node","id":3,"lat":-19.92,"lon":-43.91,"tags":{"highway":"speed_camera"}}
            ]}
        """.trimIndent()
        val osm = Osm.parseOverpass(json)
        assertEquals(1, osm.ways.size)
        assertEquals("Av. Brasil", osm.ways.single().tags["name"])
        assertEquals(3, osm.nodes.size)
        assertEquals(1, osm.poiNodes.size)
    }

    @Test
    fun terrariumDecodingAndTileMath() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        // 800.5 m: 800.5 + 32768 = 33568.5 -> R = 131, G = 32, B = 128
        image.setRGB(0, 0, (131 shl 16) or (32 shl 8) or 128)
        assertEquals(800.5, TerrariumElevation.decode(image.getRGB(0, 0)), 1e-9)
        val (x, y) = TerrariumElevation.pixel(-19.92, -43.94, 12)
        assertEquals(1548, (x / 256).toInt(), "tile x of Belo Horizonte at zoom 12")
        // (1 + asinh(tan(19.92°)) / π) / 2 × 4096 = 2279.3
        assertEquals(2279, (y / 256).toInt(), "tile y of Belo Horizonte at zoom 12")
    }
}

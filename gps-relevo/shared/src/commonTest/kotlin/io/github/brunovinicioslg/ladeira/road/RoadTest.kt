package io.github.brunovinicioslg.ladeira.road

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.NetworkBuilder
import io.github.brunovinicioslg.ladeira.forEachCase
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoadTest {

    @Test
    fun edgeGeometryAndElevation() {
        val b = NetworkBuilder()
        b.road(line(BH, 90.0, 400.0), elevation = { 800 + it * 0.05 })
        val e = b.edges.single()
        assertEquals(400.0, e.length, 0.5)
        assertEquals(Edge.sampleCount(e.length), e.elevations.size)
        for (d in listOf(0.0, 12.5, 25.0, 199.0, 380.0, e.length)) {
            assertEquals(800 + d * 0.05, e.elevationAt(d)!!, 1e-6, "at $d")
        }
        assertEquals(90.0, e.bearingAt(200.0), 0.01)
        assertEquals(0.0, Geo.distance(e.pointAt(0.0), BH), 1e-6)
        assertEquals(200.0, Geo.distance(BH, e.pointAt(200.0)), 0.5)
    }

    @Test
    fun roadsAreSplitIntoShortEdges() {
        val b = NetworkBuilder()
        b.road(line(BH, 45.0, 2_300.0))
        assertTrue(b.edges.all { it.length <= RoadPackage.MAX_EDGE_LENGTH_M + 1e-6 })
        assertEquals(2_300.0, b.edges.sumOf { it.length }, 1.0)
        b.edges.zipWithNext { a, c -> assertEquals(a.to, c.from) }
    }

    @Test
    fun candidatesFindTheNearestPointOnAnyEdge() {
        val b = NetworkBuilder()
        b.road(listOf(BH, Geo.destination(BH, 90.0, 450.0)), name = "Long straight") // a single long segment
        b.road(line(Geo.destination(BH, 0.0, 300.0), 90.0, 450.0), name = "Parallel")
        val network = b.network()
        forEachCase(300) { seed, rnd ->
            val along = rnd.nextDouble(0.0, 450.0)
            val side = rnd.nextDouble(-40.0, 40.0)
            val p = Geo.destination(Geo.destination(BH, 90.0, along), 0.0, side)
            val best = network.candidates(p, 60.0).first()
            assertEquals("Long straight", best.edge.name, "seed=$seed")
            assertEquals(along, best.offset, 1.0, "seed=$seed")
            assertEquals(kotlin.math.abs(side), best.distance, 1.0, "seed=$seed")
        }
        assertTrue(network.candidates(Geo.destination(BH, 180.0, 500.0), 60.0).isEmpty())
    }

    @Test
    fun overlappingRouteNumbersAreTheSameRoad() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 100.0))
        val base = b.edges.single()
        fun edge(ref: String?, name: String? = null) =
            Edge(99, 1, 2, base.geometry, base.elevations, RoadClass.TRUNK, ref = ref, name = name)
        assertTrue(edge("BR-040").sameRoadAs(edge("BR-040;BR-356")))
        assertTrue(edge("BR-356; BR-040").sameRoadAs(edge("BR-040")))
        assertTrue(!edge("BR-040").sameRoadAs(edge("BR-356")))
        assertTrue(!edge(null).sameRoadAs(edge(null)), "unnamed roads are not the same road")
        assertTrue(edge(null, "Av. Raja Gabaglia").sameRoadAs(edge("MG-030", "Av. Raja Gabaglia")))
    }

    @Test
    fun edgesNeedTwoPoints() {
        assertFailsWith<IllegalArgumentException> {
            Edge(1, 1, 2, listOf(LatLon(0.0, 0.0)), DoubleArray(0), RoadClass.PRIMARY)
        }
    }
}

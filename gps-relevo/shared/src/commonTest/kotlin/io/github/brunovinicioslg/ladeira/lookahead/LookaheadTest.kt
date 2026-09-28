package io.github.brunovinicioslg.ladeira.lookahead

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.NetworkBuilder
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.line
import io.github.brunovinicioslg.ladeira.road.EdgePosition
import io.github.brunovinicioslg.ladeira.road.RoadClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LookaheadTest {

    @Test
    fun followsAStraightRoadAcrossEdges() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 3_000.0), elevation = { 800 + it * 0.04 })
        val network = b.network()
        val start = EdgePosition(b.edges.first(), 100.0, forward = true)
        val path = Lookahead.follow(network, start, 2_000.0)
        assertEquals(2_000.0, path.length, 1e-6)
        val profile = path.profile()
        assertEquals(804.0, profile.first().elevation, 0.01) // starts at 100 m
        assertEquals(884.0, profile.last().elevation, 0.5)
    }

    @Test
    fun drivingBackwardsReversesTheProfile() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 3_000.0), elevation = { 800 + it * 0.04 })
        val last = b.edges.last()
        val path = Lookahead.follow(b.network(), EdgePosition(last, last.length, forward = false), 1_000.0)
        assertEquals(1_000.0, path.length, 1e-6)
        assertTrue(path.profile().zipWithNext().all { (a, c) -> c.elevation < a.elevation }, "going down the road we came up")
    }

    @Test
    fun stopsAtATJunction() {
        val b = NetworkBuilder()
        val (_, junction) = b.road(line(BH, 0.0, 600.0), name = "Rua A")
        val t = Geo.destination(BH, 0.0, 600.0)
        b.road(line(t, 90.0, 800.0), startNode = junction, name = "Rua B")
        b.road(line(t, 270.0, 800.0), startNode = junction, name = "Rua C")
        val path = Lookahead.follow(b.network(), EdgePosition(b.edges.first(), 0.0, true), 2_000.0)
        assertEquals(600.0, path.length, 1.0, "no obvious continuation at the T")
    }

    @Test
    fun keepsToTheSameRoadThroughAFork() {
        val b = NetworkBuilder()
        val (_, fork) = b.road(line(BH, 0.0, 500.0), name = "BR-040", roadClass = RoadClass.TRUNK)
        val f = Geo.destination(BH, 0.0, 500.0)
        b.road(line(f, 10.0, 800.0), startNode = fork, name = "Acesso", roadClass = RoadClass.TERTIARY) // straighter
        b.road(line(f, 330.0, 800.0), startNode = fork, name = "BR-040", roadClass = RoadClass.TRUNK) // the road bends
        val path = Lookahead.follow(b.network(), EdgePosition(b.edges.first(), 0.0, true), 1_200.0)
        assertEquals(1_200.0, path.length, 1.0)
        assertTrue(path.steps.all { it.edge.name == "BR-040" })
    }

    @Test
    fun unnamedForkPrefersTheMoreImportantStraighterRoad() {
        val b = NetworkBuilder()
        val (_, fork) = b.road(line(BH, 0.0, 500.0), roadClass = RoadClass.PRIMARY)
        val f = Geo.destination(BH, 0.0, 500.0)
        b.road(line(f, 25.0, 800.0), startNode = fork, roadClass = RoadClass.RESIDENTIAL)
        b.road(line(f, 340.0, 800.0), startNode = fork, roadClass = RoadClass.PRIMARY)
        val path = Lookahead.follow(b.network(), EdgePosition(b.edges.first(), 0.0, true), 1_000.0)
        assertEquals(RoadClass.PRIMARY, path.steps.last().edge.roadClass)
    }

    @Test
    fun neverEntersAOneWayStreetAgainstTraffic() {
        val b = NetworkBuilder()
        val (_, junction) = b.road(line(BH, 0.0, 500.0))
        val j = Geo.destination(BH, 0.0, 500.0)
        val endOfOneWay = b.node()
        // One-way street pointing towards the junction (straight ahead, but the wrong way).
        b.road(line(Geo.destination(j, 0.0, 600.0), 180.0, 600.0), startNode = endOfOneWay, endNode = junction, oneway = true)
        b.road(line(j, 50.0, 600.0), startNode = junction)
        val path = Lookahead.follow(b.network(), EdgePosition(b.edges.first(), 0.0, true), 1_000.0)
        assertTrue(path.steps.none { it.edge.oneway }, "the only legal continuation turns right")
        assertEquals(1_000.0, path.length, 1.0)
    }

    @Test
    fun roundaboutLoopsTerminate() {
        val b = NetworkBuilder()
        val center = Geo.destination(BH, 0.0, 300.0)
        val ring = (0..12).map { Geo.destination(center, it * 30.0, 40.0) }
        val entry = b.node()
        b.road(ring, startNode = entry, endNode = entry, oneway = true, maxEdge = 50.0)
        val path = Lookahead.follow(b.network(), EdgePosition(b.edges.first(), 0.0, true), 5_000.0)
        assertTrue(path.length < 400.0, "stops after one lap instead of looping forever")
    }
}

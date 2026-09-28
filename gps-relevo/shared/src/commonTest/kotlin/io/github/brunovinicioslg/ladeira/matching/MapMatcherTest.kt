package io.github.brunovinicioslg.ladeira.matching

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.NetworkBuilder
import io.github.brunovinicioslg.ladeira.forEachCase
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.line
import io.github.brunovinicioslg.ladeira.road.EdgePosition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapMatcherTest {

    /** GPS fixes every [step] meters along [points], with Gaussian-ish noise of [noise] meters. */
    private fun drive(points: List<LatLon>, rnd: Random, noise: Double, step: Double = 15.0): List<Pair<LatLon, GpsFix>> {
        val out = mutableListOf<Pair<LatLon, GpsFix>>()
        var carry = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val len = Geo.distance(a, b)
            val bearing = Geo.bearing(a, b)
            var d = carry
            while (d < len) {
                val truth = Geo.destination(a, bearing, d)
                val err = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 2 * noise
                val measured = Geo.destination(truth, rnd.nextDouble(0.0, 360.0), kotlin.math.abs(err))
                val gpsBearing = bearing + (rnd.nextDouble() - 0.5) * 10
                out += truth to GpsFix(measured, accuracyM = noise, speedMps = 15.0, bearingDegrees = gpsBearing)
                d += step
            }
            carry = d - len
        }
        return out
    }

    @Test
    fun staysOnTheRoadBeingDriven() {
        val b = NetworkBuilder()
        b.road(line(BH, 30.0, 2_000.0), name = "Main")
        val matcher = MapMatcher(b.network())
        forEachCase(20) { seed, rnd ->
            matcher.reset()
            for ((truth, fix) in drive(line(BH, 30.0, 2_000.0), rnd, noise = 6.0)) {
                val m = assertNotNull(matcher.update(fix), "seed=$seed")
                assertEquals("Main", m.position.edge.name)
                assertTrue(m.position.forward)
                assertTrue(Geo.distance(m.position.point, truth) < 20.0, "seed=$seed")
            }
        }
    }

    @Test
    fun doesNotJumpToAParallelRoad() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 2_000.0), name = "A")
        // A service road 15 m to the east, not connected: with 8 m of GPS noise, distance alone
        // would pick it often; only driving continuity keeps the match on A.
        b.road(line(Geo.destination(BH, 90.0, 15.0), 0.0, 2_000.0), name = "B")
        val matcher = MapMatcher(b.network())
        forEachCase(20) { seed, rnd ->
            matcher.reset()
            val fixes = drive(line(BH, 0.0, 2_000.0), rnd, noise = 8.0)
            val onA = fixes.count { (_, fix) -> matcher.update(fix)?.position?.edge?.name == "A" }
            assertTrue(onA >= fixes.size * 0.98, "seed=$seed: $onA of ${fixes.size} on the right road")
        }
    }

    @Test
    fun ignoresARoadCrossingOnABridge() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 2_000.0), name = "Below")
        val crossStart = Geo.destination(Geo.destination(BH, 0.0, 1_000.0), 270.0, 500.0)
        b.road(line(crossStart, 90.0, 1_000.0), name = "Overpass", bridge = true) // no shared node
        val matcher = MapMatcher(b.network())
        forEachCase(20) { seed, rnd ->
            matcher.reset()
            for ((_, fix) in drive(line(BH, 0.0, 2_000.0), rnd, noise = 5.0)) {
                assertEquals("Below", matcher.update(fix)?.position?.edge?.name, "seed=$seed")
            }
        }
    }

    @Test
    fun oneWayStreetsAreNotMatchedAgainstTraffic() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 1_000.0), name = "One way", oneway = true)
        val matcher = MapMatcher(b.network())
        val end = Geo.destination(BH, 0.0, 1_000.0)
        val fix = GpsFix(Geo.destination(end, 180.0, 500.0), accuracyM = 5.0, speedMps = 15.0, bearingDegrees = 180.0)
        val m = matcher.update(fix)
        // Only the legal direction exists, so it is heavily penalized by heading but still the only road.
        assertTrue(m == null || m.position.forward)
    }

    @Test
    fun farFromAnyRoadThereIsNoMatch() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 1_000.0))
        val matcher = MapMatcher(b.network())
        assertNull(matcher.update(GpsFix(Geo.destination(BH, 90.0, 400.0), accuracyM = 5.0)))
        assertNull(matcher.update(GpsFix(LatLon(Double.NaN, 0.0), accuracyM = 5.0)))
    }

    @Test
    fun routeDistanceFollowsTheGraph() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 1_500.0))
        val network = b.network()
        val first = b.edges.first()
        val last = b.edges.last()
        val from = EdgePosition(first, 100.0, true)
        assertEquals(50.0, RouteDistance.between(network, from, EdgePosition(first, 150.0, true), 1_000.0)!!, 1e-6)
        assertEquals(1_300.0, RouteDistance.between(network, from, EdgePosition(last, last.length - 100.0, true), 2_000.0)!!, 1.0)
        assertNull(RouteDistance.between(network, from, EdgePosition(last, last.length - 100.0, true), 500.0), "beyond the limit")
        assertEquals(5.0, RouteDistance.between(network, from, EdgePosition(first, 95.0, true), 100.0)!!, 1e-6, "GPS jitter")
    }
}

package io.github.brunovinicioslg.ladeira.geo

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.forEachCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GeoTest {

    @Test
    fun distancesAndBearings() {
        assertEquals(111_195.0, Geo.distance(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 5.0)
        assertEquals(0.0, Geo.bearing(BH, LatLon(BH.lat + 0.01, BH.lon)), 1e-6)
        assertEquals(90.0, Geo.bearing(LatLon(0.0, 0.0), LatLon(0.0, 0.01)), 1e-6)
        assertEquals(180.0, Geo.bearing(BH, LatLon(BH.lat - 0.01, BH.lon)), 1e-6)
        assertEquals(270.0, Geo.bearing(LatLon(0.0, 0.0), LatLon(0.0, -0.01)), 1e-6)
    }

    @Test
    fun destinationInvertsDistanceAndBearing() {
        forEachCase(500) { seed, rnd ->
            val start = LatLon(rnd.nextDouble(-33.0, 5.0), rnd.nextDouble(-73.0, -35.0)) // Brazil
            val bearing = rnd.nextDouble(0.0, 360.0)
            val distance = rnd.nextDouble(1.0, 50_000.0)
            val end = Geo.destination(start, bearing, distance)
            assertEquals(distance, Geo.distance(start, end), distance * 1e-9 + 1e-6, "seed=$seed")
            assertEquals(0.0, Geo.angleDifference(bearing, Geo.bearing(start, end)), 1e-6, "seed=$seed")
        }
    }

    @Test
    fun angleDifferenceIsTheShortWayRound() {
        assertEquals(20.0, Geo.angleDifference(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDifference(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleDifference(-45.0, 45.0), 1e-9)
        assertEquals(0.0, Geo.angleDifference(720.0, 0.0), 1e-9)
    }

    @Test
    fun localProjectionIsAccurateNearby() {
        forEachCase(500) { seed, rnd ->
            val origin = LatLon(rnd.nextDouble(-33.0, 5.0), rnd.nextDouble(-73.0, -35.0))
            val projection = LocalProjection(origin)
            val p = Geo.destination(origin, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(0.0, 5_000.0))
            assertEquals(Geo.distance(origin, p), projection.toXY(p).length(), 5.0, "seed=$seed")
            val back = projection.toLatLon(projection.toXY(p))
            assertEquals(0.0, Geo.distance(back, p), 1e-6, "seed=$seed")
        }
    }

    @Test
    fun validation() {
        assertFalse(LatLon(Double.NaN, 0.0).isValid())
        assertFalse(LatLon(91.0, 0.0).isValid())
    }
}

package io.github.brunovinicioslg.ladeira.alerts

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.NetworkBuilder
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.line
import io.github.brunovinicioslg.ladeira.lookahead.Lookahead
import io.github.brunovinicioslg.ladeira.lookahead.PathAhead
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import io.github.brunovinicioslg.ladeira.road.EdgePosition
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertEngineTest {

    /** 6 km road heading north: flat 2 km, then a [grade] % slope for [slopeLength] m, then flat. */
    private fun road(grade: Double, slopeLength: Double = 1_500.0): Pair<RoadNetwork, NetworkBuilder> {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 6_000.0), elevation = { d ->
            800 + (d - 2_000.0).coerceIn(0.0, slopeLength) * grade / 100
        })
        return b.network() to b
    }

    /** The path seen by a vehicle [driven] meters from the start of the road. */
    private fun pathAt(network: RoadNetwork, driven: Double): PathAhead {
        val candidate = network.candidates(Geo.destination(BH, 0.0, driven), 5.0).first()
        return Lookahead.follow(network, EdgePosition(candidate.edge, candidate.offset, true), 5_000.0)
    }

    private fun AlertEngine.run(network: RoadNetwork, driven: Double, speed: Double = 20.0, now: Long = 0L): List<Alert> {
        val path = pathAt(network, driven)
        return evaluate(path, analyze(path, network.pois), speed, now)
    }

    @Test
    fun climbIsAnnouncedOnceWhenItComesWithinReach() {
        val (network, _) = road(grade = 7.0)
        val engine = AlertEngine(VehicleProfile.CAR)
        // At 20 m/s the lead is 500 m: nothing yet 1 km before the climb.
        assertTrue(engine.run(network, driven = 1_000.0).isEmpty())
        val alerts = engine.run(network, driven = 1_600.0, now = 30_000)
        val climb = alerts.single() as Alert.SlopeAhead
        assertEquals(SlopeKind.CLIMB, climb.kind)
        assertEquals(400.0, climb.distance, 120.0)
        assertEquals(7.0, climb.slope.averageGradePercent, 0.8)
        // Driving on, re-detected every second: never repeated.
        for (d in 1_650..3_400 step 50) {
            assertTrue(engine.run(network, driven = d.toDouble(), now = 31_000).isEmpty(), "repeated at $d")
        }
    }

    @Test
    fun trucksAreWarnedAboutGentleLongDescents() {
        val (network, _) = road(grade = -4.5, slopeLength = 2_500.0)
        assertTrue(AlertEngine(VehicleProfile.CAR).run(network, driven = 1_700.0).isEmpty(), "too gentle for a car")
        val truck = AlertEngine(VehicleProfile.TRUCK).run(network, driven = 1_700.0).single() as Alert.SlopeAhead
        assertEquals(SlopeKind.LONG_DESCENT, truck.kind)
    }

    @Test
    fun startingInsideASlopeAnnouncesIt() {
        val (network, _) = road(grade = -8.0, slopeLength = 3_000.0)
        // 2.5 km of the 3 km descent still ahead.
        val alert = AlertEngine(VehicleProfile.CAR).run(network, driven = 2_500.0).single() as Alert.SlopeAhead
        assertEquals(0.0, alert.distance)
        assertEquals(SlopeKind.LONG_DESCENT, alert.kind)
    }

    @Test
    fun walkingNeverSpeaksAboutSlopes() {
        val (network, _) = road(grade = 12.0)
        assertTrue(AlertEngine(VehicleProfile.WALKING).run(network, driven = 1_800.0).isEmpty())
    }

    @Test
    fun speedCamerasInTheDirectionOfTravel() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 3_000.0))
        b.pois += Poi(PoiType.SPEED_CAMERA, Geo.destination(BH, 0.0, 1_000.0), directionDegrees = 0.0, speedLimitKmh = 60)
        b.pois += Poi(PoiType.SPEED_CAMERA, Geo.destination(BH, 0.0, 1_100.0), directionDegrees = 180.0) // other lane
        b.pois += Poi(PoiType.POTHOLE, Geo.destination(Geo.destination(BH, 0.0, 1_200.0), 90.0, 80.0)) // off the road
        b.pois += Poi(PoiType.SPEED_BUMP, Geo.destination(BH, 0.0, 1_300.0)) // both directions
        val network = b.network()
        val engine = AlertEngine(VehicleProfile.CAR)
        assertTrue(engine.run(network, driven = 500.0).isEmpty(), "300 m lead at 20 m/s")
        val first = engine.run(network, driven = 750.0).map { it as Alert.PoiAhead }
        assertEquals(listOf(PoiType.SPEED_CAMERA), first.map { it.poi.type })
        assertEquals(250.0, first.single().distance, 15.0)
        val later = engine.run(network, driven = 1_050.0).map { (it as Alert.PoiAhead).poi.type }
        assertEquals(listOf(PoiType.SPEED_BUMP), later)
    }

    @Test
    fun alertsCanRepeatAfterTheMemoryExpires() {
        val (network, _) = road(grade = 7.0)
        val engine = AlertEngine(VehicleProfile.CAR)
        assertEquals(1, engine.run(network, driven = 1_600.0, now = 0).size)
        assertTrue(engine.run(network, driven = 1_600.0, now = 60_000).isEmpty())
        assertEquals(1, engine.run(network, driven = 1_600.0, now = AlertEngine.MEMORY_MILLIS + 1).size)
    }
}

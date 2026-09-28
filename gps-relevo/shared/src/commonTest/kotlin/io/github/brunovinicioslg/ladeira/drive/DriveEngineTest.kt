package io.github.brunovinicioslg.ladeira.drive

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.NetworkBuilder
import io.github.brunovinicioslg.ladeira.alerts.Alert
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.line
import io.github.brunovinicioslg.ladeira.matching.GpsFix
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.RoadClass
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriveEngineTest {

    /** 10 km of BR-040 heading north: flat 3 km, a 3 km descent at 6 %, then flat. */
    private fun serra(): RoadNetwork {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 10_000.0), roadClass = RoadClass.TRUNK, name = "Rodovia BR-040", elevation = { d ->
            1_300 - (d - 3_000.0).coerceIn(0.0, 3_000.0) * 0.06
        })
        b.pois += Poi(PoiType.SPEED_CAMERA, Geo.destination(BH, 0.0, 7_000.0), directionDegrees = 0.0, speedLimitKmh = 80)
        val net = b.network()
        return RoadNetwork(net.edges.map { e ->
            io.github.brunovinicioslg.ladeira.road.Edge(e.id, e.from, e.to, e.geometry, e.elevations, e.roadClass, e.oneway, name = e.name, ref = "BR-040", maxSpeedKmh = 80)
        }, net.pois)
    }

    /** One fix per second at [speedMps] along the road, starting [from] meters in. */
    private fun drive(engine: DriveEngine, from: Double, to: Double, speedMps: Double = 20.0): List<DriveUpdate> {
        val updates = mutableListOf<DriveUpdate>()
        var d = from
        var t = 0L
        while (d <= to) {
            updates += engine.update(GpsFix(Geo.destination(BH, 0.0, d), accuracyM = 5.0, speedMps = speedMps, bearingDegrees = 0.0, timeMillis = t))
            d += speedMps
            t += 1_000
        }
        return updates
    }

    @Test
    fun theLongDescentIsAnnouncedOnceAndShownAhead() {
        val net = serra()
        val engine = DriveEngine({ _, _ -> net }, VehicleProfile.TRUCK)
        val updates = drive(engine, from = 0.0, to = 9_000.0)
        assertTrue(updates.all { it.state.status == DriveState.Status.ON_ROAD })
        val slopeAlerts = updates.flatMap { it.alerts }.filterIsInstance<Alert.SlopeAhead>()
        assertEquals(1, slopeAlerts.size, "$slopeAlerts")
        assertEquals(SlopeKind.LONG_DESCENT, slopeAlerts.single().kind)
        // Announced with the lead distance for 20 m/s (25 s -> 500 m), not earlier.
        val announcedAt = updates.indexOfFirst { u -> u.alerts.any { it is Alert.SlopeAhead } } * 20.0
        assertTrue(announcedAt in 2_300.0..2_800.0, "announced at $announcedAt m")

        val before = updates[(2_000 / 20)].state
        assertEquals("BR-040", before.road?.ref)
        assertEquals(80, before.road?.maxSpeedKmh)
        assertEquals(SlopeKind.LONG_DESCENT, before.next?.second)
        assertEquals(1_000.0, before.next!!.first.start, 150.0)
        assertTrue(before.ahead.isNotEmpty() && before.ahead.minOf { it.gradePercent } < -5.0, "the drawing shows the descent")
        val inside = updates[(4_500 / 20)].state
        assertEquals(-6.0, assertNotNull(inside.gradePercent), 0.5)
        assertEquals(SlopeKind.LONG_DESCENT, inside.current?.second)
    }

    @Test
    fun speedCameraIsAnnouncedOnce() {
        val net = serra()
        val engine = DriveEngine({ _, _ -> net }, VehicleProfile.CAR)
        val cameras = drive(engine, from = 5_000.0, to = 8_000.0).flatMap { it.alerts }.filterIsInstance<Alert.PoiAhead>()
        assertEquals(1, cameras.size)
        assertEquals(80, cameras.single().poi.speedLimitKmh)
    }

    @Test
    fun userMarksAreAnnouncedAndReplaceTheMapsPointOnlyInTheirDirection() {
        val net = serra()
        val engine = DriveEngine({ _, _ -> net }, VehicleProfile.CAR)
        engine.userPois = listOf(
            // A pothole marked driving north, and one marked on the way back south.
            Poi(PoiType.POTHOLE, Geo.destination(BH, 0.0, 6_000.0), directionDegrees = 0.0),
            Poi(PoiType.POTHOLE, Geo.destination(BH, 0.0, 6_500.0), directionDegrees = 180.0),
            // The camera the map has, marked again with its new limit.
            Poi(PoiType.SPEED_CAMERA, Geo.destination(BH, 0.0, 7_010.0), directionDegrees = 0.0, speedLimitKmh = 60),
            // Far away: ignored.
            Poi(PoiType.POTHOLE, Geo.destination(BH, 90.0, 50_000.0)),
        )
        val alerts = drive(engine, from = 5_000.0, to = 8_000.0).flatMap { it.alerts }.filterIsInstance<Alert.PoiAhead>()
        assertEquals(listOf(PoiType.POTHOLE, PoiType.SPEED_CAMERA), alerts.map { it.poi.type })
        assertEquals(60, alerts.last().poi.speedLimitKmh, "the user's limit, announced once")
    }

    @Test
    fun aPointJustMarkedWhereTheVehicleStandsIsNotAnnounced() {
        val net = serra()
        val engine = DriveEngine({ _, _ -> net }, VehicleProfile.CAR)
        drive(engine, from = 1_000.0, to = 1_000.0)
        val mark = Poi(PoiType.OTHER, Geo.destination(BH, 0.0, 1_000.0), directionDegrees = 0.0)
        engine.userPois = listOf(mark)
        engine.markKnown(mark, nowMillis = 0)
        // Standing still on the mark for a while: silence.
        val stopped = (1..5).flatMap { t ->
            engine.update(GpsFix(Geo.destination(BH, 0.0, 1_000.0), 5.0, 0.0, 0.0, timeMillis = t * 1_000L)).alerts
        }
        assertTrue(stopped.isEmpty(), "$stopped")
    }

    @Test
    fun statusOutsideDataAndOffRoad() {
        val net = serra()
        val covered = DriveEngine({ center, _ -> if (Geo.distance(center, BH) < 50_000) net else null }, VehicleProfile.CAR)
        val far = covered.update(GpsFix(LatLon(-15.0, -47.0), accuracyM = 5.0))
        assertEquals(DriveState.Status.NO_MAP_DATA, far.state.status)
        val offRoad = covered.update(GpsFix(Geo.destination(BH, 90.0, 800.0), accuracyM = 5.0))
        assertEquals(DriveState.Status.OFF_ROAD, offRoad.state.status)
        assertEquals(DriveState.Status.WAITING_FOR_GPS, covered.update(GpsFix(LatLon(Double.NaN, 0.0), 5.0)).state.status)
    }

    @Test
    fun roadDataIsReloadedAsTheVehicleMoves() {
        val net = serra()
        var loads = 0
        val engine = DriveEngine({ _, _ -> loads++; net }, VehicleProfile.CAR)
        drive(engine, from = 0.0, to = 9_000.0)
        assertTrue(loads in 3..5, "reloads every ~2.5 km, got $loads")
    }

    @Test
    fun enteringACoveredAreaFindsTheRoadsWithinAFewHundredMeters() {
        val net = serra()
        // Data only north of the 2 km mark, as if a region border ran across the road there.
        val border = Geo.destination(BH, 0.0, 2_000.0).lat
        val engine = DriveEngine({ center, _ -> if (center.lat >= border) net else null }, VehicleProfile.CAR)
        val updates = drive(engine, from = 0.0, to = 3_000.0)
        val firstOnRoad = updates.indexOfFirst { it.state.status == DriveState.Status.ON_ROAD } * 20.0
        assertTrue(firstOnRoad in 2_000.0..2_400.0, "found the roads at $firstOnRoad m")
    }

    @Test
    fun invalidateReloadsOnTheNextFix() {
        val net = serra()
        var available = false
        val engine = DriveEngine({ _, _ -> if (available) net else null }, VehicleProfile.CAR)
        val at = GpsFix(Geo.destination(BH, 0.0, 1_000.0), accuracyM = 5.0)
        assertEquals(DriveState.Status.NO_MAP_DATA, engine.update(at).state.status)
        available = true // a region was just imported
        assertEquals(DriveState.Status.NO_MAP_DATA, engine.update(at).state.status, "no reload without moving")
        engine.invalidate()
        assertEquals(DriveState.Status.ON_ROAD, engine.update(at).state.status)
    }
}

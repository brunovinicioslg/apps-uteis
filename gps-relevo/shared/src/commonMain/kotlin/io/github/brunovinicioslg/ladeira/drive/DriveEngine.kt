package io.github.brunovinicioslg.ladeira.drive

import io.github.brunovinicioslg.ladeira.alerts.Alert
import io.github.brunovinicioslg.ladeira.alerts.AlertEngine
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.lookahead.Lookahead
import io.github.brunovinicioslg.ladeira.lookahead.PathAhead
import io.github.brunovinicioslg.ladeira.matching.GpsFix
import io.github.brunovinicioslg.ladeira.matching.MapMatcher
import io.github.brunovinicioslg.ladeira.profile.Slope
import io.github.brunovinicioslg.ladeira.profile.SlopeDetector
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import io.github.brunovinicioslg.ladeira.road.UserPoints

/** Where road data comes from (a downloaded region package on the phone). */
fun interface RoadSource {
    /** Null or empty when the area is not covered by any downloaded region. */
    fun networkAround(center: LatLon, radiusM: Double): RoadNetwork?
}

data class RoadInfo(val name: String?, val ref: String?, val maxSpeedKmh: Int?)

/** A point of the road ahead with the grade of the stretch starting there, for drawing. */
data class AheadPoint(val position: LatLon, val gradePercent: Double)

data class DriveState(
    val status: Status,
    val position: LatLon? = null,
    val speedKmh: Double? = null,
    val road: RoadInfo? = null,
    /** Grade of the next 100 m, signed (negative = downhill). */
    val gradePercent: Double? = null,
    val elevation: Double? = null,
    /** The slope the vehicle is on, if the vehicle profile cares about it. */
    val current: Pair<Slope, SlopeKind>? = null,
    /** The next slope ahead worth a warning; distances are from the vehicle. */
    val next: Pair<Slope, SlopeKind>? = null,
    val nextPoi: Pair<Poi, Double>? = null,
    val ahead: List<AheadPoint> = emptyList(),
) {
    enum class Status {
        WAITING_FOR_GPS,

        /** No downloaded region covers this place. */
        NO_MAP_DATA,

        /** Not on any known road (parking lot, off road, GPS far off). */
        OFF_ROAD,
        ON_ROAD,
    }
}

/** One GPS update's result: what to show and what to announce. */
data class DriveUpdate(val state: DriveState, val alerts: List<Alert>)

/**
 * Turns GPS fixes into the driving screen and spoken alerts: loads road data around the vehicle,
 * matches the fix to a road, predicts the road ahead and runs the alert engine. Not thread-safe:
 * feed it from one thread.
 */
class DriveEngine(private val source: RoadSource, profile: VehicleProfile, private val config: Config = Config()) {

    data class Config(
        val lookaheadM: Double = 5_000.0,
        val loadRadiusM: Double = 8_000.0,
        /** Reload when this far from the last load center, well before the lookahead runs off the data. */
        val reloadAfterM: Double = 2_500.0,
        /** Where there was no data, look again sooner: the vehicle may be entering a downloaded region. */
        val retryEmptyAfterM: Double = 300.0,
    )

    private val alerts = AlertEngine(profile)
    private var network: RoadNetwork? = null
    private var loadedAround: LatLon? = null
    private var matcher: MapMatcher? = null

    var profile: VehicleProfile = profile
        set(value) {
            field = value
            alerts.setProfile(value)
        }

    /** Points the user marked, all of them; those near the vehicle join the region's points. */
    var userPois: List<Poi> = emptyList()

    /** A point just marked where the vehicle is: not to be announced right after "marked". */
    fun markKnown(poi: Poi, nowMillis: Long) = alerts.markKnown(poi, nowMillis)

    /** Forgets the loaded roads, e.g. after a region was added or removed; the next fix reloads them. */
    fun invalidate() {
        network = null
        loadedAround = null
        matcher = null
    }

    fun update(fix: GpsFix): DriveUpdate {
        if (!fix.position.isValid()) return DriveUpdate(DriveState(DriveState.Status.WAITING_FOR_GPS), emptyList())
        val speedKmh = fix.speedMps?.let { it * 3.6 }
        ensureNetwork(fix.position)
        val net = network
        val m = matcher
        if (net == null || m == null || net.edges.isEmpty()) {
            return DriveUpdate(DriveState(DriveState.Status.NO_MAP_DATA, fix.position, speedKmh), emptyList())
        }
        val match = m.update(fix)
            ?: return DriveUpdate(DriveState(DriveState.Status.OFF_ROAD, fix.position, speedKmh), emptyList())

        val path = Lookahead.follow(net, match.position, config.lookaheadM)
        val pois = UserPoints.merge(net.pois, UserPoints.near(userPois, fix.position, config.lookaheadM + USER_POI_MARGIN_M))
        val road = alerts.analyze(path, pois)
        val newAlerts = alerts.evaluate(path, road, fix.speedMps ?: 0.0, fix.timeMillis)
        val edge = match.position.edge
        val profilePoints = SlopeDetector.smooth(SlopeDetector.resample(path.profile()))
        val state = DriveState(
            status = DriveState.Status.ON_ROAD,
            position = match.position.point,
            speedKmh = speedKmh,
            road = RoadInfo(edge.name, edge.ref, edge.maxSpeedKmh),
            gradePercent = gradeOverFirst(profilePoints, GRADE_WINDOW_M),
            elevation = profilePoints.firstOrNull()?.elevation,
            current = road.current,
            next = road.slopes.firstOrNull { (s, _) -> s.start > AlertEngine.START_TOLERANCE_M },
            nextPoi = road.pois.firstOrNull(),
            ahead = aheadPoints(path, profilePoints),
        )
        return DriveUpdate(state, newAlerts)
    }

    private fun ensureNetwork(position: LatLon) {
        val center = loadedAround
        val threshold = if (network?.edges.isNullOrEmpty()) config.retryEmptyAfterM else config.reloadAfterM
        if (center != null && Geo.distance(center, position) < threshold) return
        val loaded = source.networkAround(position, config.loadRadiusM)
        network = loaded
        loadedAround = position
        // A fresh matcher: its hypotheses point at edges of the previous load.
        matcher = loaded?.let { MapMatcher(it) }
    }

    private fun aheadPoints(path: PathAhead, profile: List<io.github.brunovinicioslg.ladeira.profile.ProfilePoint>): List<AheadPoint> =
        profile.zipWithNext().mapNotNull { (a, b) ->
            val position = path.pointAt(a.distance) ?: return@mapNotNull null
            AheadPoint(position, (b.elevation - a.elevation) / (b.distance - a.distance) * 100)
        }

    private fun gradeOverFirst(profile: List<io.github.brunovinicioslg.ladeira.profile.ProfilePoint>, meters: Double): Double? {
        val first = profile.firstOrNull() ?: return null
        val end = profile.lastOrNull { it.distance - first.distance <= meters } ?: return null
        if (end.distance - first.distance < meters / 2) return null
        return (end.elevation - first.elevation) / (end.distance - first.distance) * 100
    }

    private companion object {
        const val GRADE_WINDOW_M = 100.0
        const val USER_POI_MARGIN_M = 1_000.0
    }
}

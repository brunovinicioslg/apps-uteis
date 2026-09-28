package io.github.brunovinicioslg.ladeira.alerts

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.geo.LocalProjection
import io.github.brunovinicioslg.ladeira.lookahead.PathAhead
import io.github.brunovinicioslg.ladeira.profile.Slope
import io.github.brunovinicioslg.ladeira.profile.SlopeDetector
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import io.github.brunovinicioslg.ladeira.road.Poi
import kotlin.math.max
import kotlin.math.min

sealed interface Alert {
    /** Meters from the vehicle to where the alert applies (0 = already there). */
    val distance: Double

    data class SlopeAhead(val slope: Slope, val kind: SlopeKind, override val distance: Double) : Alert

    data class PoiAhead(val poi: Poi, override val distance: Double) : Alert
}

/** Everything the screen shows about the road ahead, recomputed on each position update. */
data class RoadAhead(
    val slopes: List<Pair<Slope, SlopeKind>>,
    val pois: List<Pair<Poi, Double>>,
    /** The slope the vehicle is on right now, if it is worth mentioning. */
    val current: Pair<Slope, SlopeKind>?,
)

/**
 * Decides when to speak. Each slope or point of interest is announced once, early enough to react:
 * the lead distance grows with speed (about 25 s ahead for slopes, 15 s for cameras and bumps).
 */
class AlertEngine(private var profile: VehicleProfile) {

    private val announced = HashMap<String, Long>()

    /**
     * Slopes are re-detected on every update and their ends drift by a few meters, so they are
     * de-duplicated by where they end rather than by an exact key. Not by kind: a long descent
     * becomes a plain descent once most of it is behind, and must not be announced again.
     */
    private data class AnnouncedSlope(val end: LatLon, val climb: Boolean, val time: Long)

    private val announcedSlopes = mutableListOf<AnnouncedSlope>()

    fun setProfile(value: VehicleProfile) {
        profile = value
        announced.clear()
        announcedSlopes.clear()
    }

    fun analyze(path: PathAhead, pois: List<Poi>): RoadAhead {
        val slopes = SlopeDetector.detectFor(path.profile(), profile)
        val current = slopes.firstOrNull { (s, _) -> s.start <= 0.0 + START_TOLERANCE_M && s.end > 0.0 }
        return RoadAhead(slopes, poisAlong(path, pois), current)
    }

    /** New alerts to announce now; each is returned only once. */
    fun evaluate(path: PathAhead, road: RoadAhead, speedMps: Double, nowMillis: Long): List<Alert> {
        expire(nowMillis)
        val result = mutableListOf<Alert>()
        val slopeLead = min(MAX_SLOPE_LEAD_M, max(MIN_SLOPE_LEAD_M, speedMps * SLOPE_LEAD_SECONDS))
        for ((slope, kind) in road.slopes) {
            val distance = max(0.0, slope.start)
            val inside = slope.start <= START_TOLERANCE_M && slope.end > 0
            if (distance > slopeLead && !inside) continue
            val end = path.pointAt(slope.end) ?: continue
            val known = announcedSlopes.any { it.climb == slope.isClimb && Geo.distance(it.end, end) <= SAME_SLOPE_M }
            if (!known) {
                announcedSlopes += AnnouncedSlope(end, slope.isClimb, nowMillis)
                result += Alert.SlopeAhead(slope, kind, distance)
            }
        }
        val poiLead = min(MAX_POI_LEAD_M, max(MIN_POI_LEAD_M, speedMps * POI_LEAD_SECONDS))
        for ((poi, distance) in road.pois) {
            if (distance > poiLead) continue
            if (markAnnounced(poiKey(poi), nowMillis)) result += Alert.PoiAhead(poi, distance)
        }
        return result
    }

    /** Treats [poi] as already announced: a point the user just marked where the vehicle is. */
    fun markKnown(poi: Poi, nowMillis: Long) {
        markAnnounced(poiKey(poi), nowMillis)
    }

    private fun poiKey(poi: Poi) = "poi:${poi.type}:${(poi.position.lat * 1e5).toLong()}:${(poi.position.lon * 1e5).toLong()}"

    private fun markAnnounced(key: String, now: Long): Boolean {
        if (key in announced) return false
        announced[key] = now
        return true
    }

    private fun expire(now: Long) {
        announced.entries.removeAll { now - it.value > MEMORY_MILLIS }
        announcedSlopes.removeAll { now - it.time > MEMORY_MILLIS }
    }

    companion object {
        const val MIN_SLOPE_LEAD_M = 300.0
        const val MAX_SLOPE_LEAD_M = 1_500.0
        const val SLOPE_LEAD_SECONDS = 25.0
        const val MIN_POI_LEAD_M = 200.0
        const val MAX_POI_LEAD_M = 800.0
        const val POI_LEAD_SECONDS = 15.0
        const val START_TOLERANCE_M = 25.0

        /** Two detections of the same kind ending this close are the same slope. */
        const val SAME_SLOPE_M = 200.0

        /** After this long an item may be announced again (e.g. driving the same road back). */
        const val MEMORY_MILLIS = 10 * 60 * 1000L

        /** Points of interest must be this close to the predicted path. */
        const val POI_MAX_OFFSET_M = 25.0
        const val POI_MAX_DIRECTION_DIFF = 60.0

        /** Points of interest on the path ahead, nearest first, with their distance along it. */
        fun poisAlong(path: PathAhead, pois: List<Poi>): List<Pair<Poi, Double>> {
            if (path.steps.isEmpty() || pois.isEmpty()) return emptyList()
            val origin = path.pointAt(0.0) ?: return emptyList()
            val projection = LocalProjection(origin)
            val samples = generateSequence(0.0) { it + POI_SAMPLE_M }.takeWhile { it <= path.length }
                .mapNotNull { d -> path.pointAt(d)?.let { d to projection.toXY(it) } }.toList()
            return pois.mapNotNull { poi ->
                val p = projection.toXY(poi.position)
                val (distance, gap) = samples.minByOrNull { (_, xy) -> (xy - p).length() }
                    ?.let { (d, xy) -> d to (xy - p).length() } ?: return@mapNotNull null
                if (gap > POI_MAX_OFFSET_M + POI_SAMPLE_M / 2) return@mapNotNull null
                val direction = poi.directionDegrees
                if (direction != null) {
                    val travel = travelBearing(path, distance) ?: return@mapNotNull null
                    if (Geo.angleDifference(direction, travel) > POI_MAX_DIRECTION_DIFF) return@mapNotNull null
                }
                poi to distance
            }.sortedBy { it.second }
        }

        private const val POI_SAMPLE_M = 10.0

        private fun travelBearing(path: PathAhead, distance: Double): Double? {
            val a: LatLon = path.pointAt(max(0.0, distance - 10)) ?: return null
            val b: LatLon = path.pointAt(min(path.length, distance + 10)) ?: return null
            return if (a == b) null else Geo.bearing(a, b)
        }
    }
}

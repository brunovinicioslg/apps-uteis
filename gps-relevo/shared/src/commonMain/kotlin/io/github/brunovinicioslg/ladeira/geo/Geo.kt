package io.github.brunovinicioslg.ladeira.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinates in degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    fun isValid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}

/** Meters east (x) and north (y) of a [LocalProjection] origin. */
data class XY(val x: Double, val y: Double) {
    operator fun plus(o: XY) = XY(x + o.x, y + o.y)
    operator fun minus(o: XY) = XY(x - o.x, y - o.y)
    operator fun times(s: Double) = XY(x * s, y * s)
    infix fun dot(o: XY) = x * o.x + y * o.y
    fun length() = sqrt(x * x + y * y)
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRadians(deg: Double) = deg * PI / 180
    fun toDegrees(rad: Double) = rad * 180 / PI

    /** Great-circle distance in meters (haversine). */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = toRadians(b.lat - a.lat)
        val dLon = toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(toRadians(a.lat)) * cos(toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from north in [0, 360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val lat1 = toRadians(a.lat)
        val lat2 = toRadians(b.lat)
        val dLon = toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(toDegrees(atan2(y, x)))
    }

    /** Point reached from [from] after [distanceM] meters on [bearingDeg]. */
    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val b = toRadians(bearingDeg)
        val lat1 = toRadians(from.lat)
        val lon1 = toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(toDegrees(lat2), normalizeLongitude(toDegrees(lon2)))
    }

    /** Smallest absolute difference between two bearings, in [0, 180]. */
    fun angleDifference(a: Double, b: Double): Double {
        val d = abs(normalizeDegrees(a) - normalizeDegrees(b))
        return if (d > 180) 360 - d else d
    }

    fun normalizeDegrees(deg: Double): Double {
        val r = deg % 360
        return if (r < 0) r + 360 else r
    }

    private fun normalizeLongitude(lon: Double): Double {
        var l = lon
        while (l > 180) l -= 360
        while (l < -180) l += 360
        return l
    }
}

/**
 * Flat (equirectangular) projection around [origin]: fast and accurate to about 0.1% within a few
 * kilometers, which covers map matching and look-ahead distances.
 */
class LocalProjection(val origin: LatLon) {
    private val metersPerDegLat = Geo.EARTH_RADIUS_M * PI / 180
    private val metersPerDegLon = metersPerDegLat * cos(Geo.toRadians(origin.lat))

    fun toXY(p: LatLon) = XY((p.lon - origin.lon) * metersPerDegLon, (p.lat - origin.lat) * metersPerDegLat)

    fun toLatLon(v: XY) = LatLon(origin.lat + v.y / metersPerDegLat, origin.lon + v.x / metersPerDegLon)
}

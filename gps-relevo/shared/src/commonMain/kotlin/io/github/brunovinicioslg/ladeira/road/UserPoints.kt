package io.github.brunovinicioslg.ladeira.road

import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * A point the user marked: a camera OpenStreetMap lacks, a pothole, a flooded stretch. Marked with
 * one tap while driving ([needsType]: the type is chosen later, with the car stopped).
 */
data class UserPoint(
    val id: String,
    val poi: Poi,
    val createdAtMillis: Long,
    val needsType: Boolean = false,
    val note: String? = null,
)

object UserPoints {

    /** Two points of the same type this close are the same thing (a mark on a camera the map already has). */
    const val SAME_POINT_M = 40.0

    /**
     * The points the alerts use: the region package's plus the user's. Where the user marked
     * something the package already has, the mark wins: it may carry a newer limit or direction.
     */
    fun merge(packagePois: List<Poi>, userPois: List<Poi>): List<Poi> {
        if (userPois.isEmpty()) return packagePois
        val kept = packagePois.filter { p ->
            userPois.none { u -> u.type == p.type && Geo.distance(u.position, p.position) <= SAME_POINT_M }
        }
        return kept + userPois
    }

    fun near(pois: List<Poi>, center: LatLon, radiusM: Double): List<Poi> =
        pois.filter { Geo.distance(it.position, center) <= radiusM }

    /**
     * Adds [incoming] to [existing]: the same id replaces the old version (a newer export of the
     * same list); the same type at the same place is skipped (a friend's list with the same camera).
     */
    fun importInto(existing: List<UserPoint>, incoming: List<UserPoint>): List<UserPoint> {
        val byId = LinkedHashMap<String, UserPoint>()
        existing.forEach { byId[it.id] = it }
        for (p in incoming) {
            if (p.id in byId) {
                byId[p.id] = p
                continue
            }
            val duplicate = byId.values.any { it.poi.type == p.poi.type && Geo.distance(it.poi.position, p.poi.position) <= SAME_PLACE_M }
            if (!duplicate) byId[p.id] = p
        }
        return byId.values.toList()
    }

    private const val SAME_PLACE_M = 10.0
}

/**
 * The user's points as GeoJSON, for sharing between phones and with other apps: a FeatureCollection
 * of Points with the type, direction and limit as properties. Reading is lenient: any GeoJSON point
 * is accepted, and unknown or missing types become [PoiType.OTHER].
 */
object PointsGeoJson {

    private val json = Json { prettyPrint = true }

    fun write(points: List<UserPoint>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("type", "FeatureCollection")
            put(
                "features",
                buildJsonArray {
                    for (p in points) add(feature(p))
                },
            )
        },
    )

    private fun feature(p: UserPoint) = buildJsonObject {
        put("type", "Feature")
        put(
            "geometry",
            buildJsonObject {
                put("type", "Point")
                put(
                    "coordinates",
                    buildJsonArray {
                        add(JsonPrimitive(round6(p.poi.position.lon)))
                        add(JsonPrimitive(round6(p.poi.position.lat)))
                    },
                )
            },
        )
        put(
            "properties",
            buildJsonObject {
                put("id", p.id)
                put("kind", p.poi.type.name.lowercase())
                p.poi.directionDegrees?.let { put("direction", it.roundToInt()) }
                p.poi.speedLimitKmh?.let { put("maxspeed", it) }
                put("created", p.createdAtMillis)
                if (p.needsType) put("needsType", true)
                p.note?.let { put("note", it) }
            },
        )
    }

    /** Throws [IllegalArgumentException] when the text is not GeoJSON. */
    fun read(text: String): List<UserPoint> {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a GeoJSON file", e)
        }
        val features = when (root["type"]?.jsonPrimitive?.content) {
            "FeatureCollection" -> (root["features"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            "Feature" -> listOf(root)
            else -> throw IllegalArgumentException("Not a GeoJSON FeatureCollection")
        }
        return features.mapNotNull(::point)
    }

    private fun point(feature: JsonObject): UserPoint? {
        val geometry = feature["geometry"] as? JsonObject ?: return null
        if (geometry["type"]?.jsonPrimitive?.content != "Point") return null
        val coordinates = (geometry["coordinates"] as? JsonArray) ?: return null
        val lon = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        val position = LatLon(lat, lon)
        if (!position.isValid()) return null
        val props = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
        fun text(key: String) = (props[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = text("kind") ?: text("type")
        val type = PoiType.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: PoiType.OTHER
        val direction = (props["direction"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.let { ((it % 360) + 360) % 360 }
        val limit = (props["maxspeed"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..250 }
        return UserPoint(
            // Points without an id get one from where they are, so importing the same file twice adds nothing.
            id = text("id") ?: "import-${round6(lat)},${round6(lon)},${type.name}",
            poi = Poi(type, position, direction, limit),
            createdAtMillis = (props["created"] as? JsonPrimitive)?.longOrNull ?: 0L,
            needsType = (props["needsType"] as? JsonPrimitive)?.booleanOrNull ?: false,
            note = text("note")?.take(MAX_NOTE),
        )
    }

    private fun round6(v: Double) = kotlin.math.round(v * 1e6) / 1e6

    private const val MAX_NOTE = 200
}

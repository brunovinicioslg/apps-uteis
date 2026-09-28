package io.github.brunovinicioslg.ladeira.tools

import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.RoadClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double

data class OsmWay(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)

data class OsmData(val nodes: Map<Long, LatLon>, val ways: List<OsmWay>, val poiNodes: List<Pair<LatLon, Map<String, String>>>)

object Osm {

    /** Car-drivable highway classes kept in the package. */
    private val HIGHWAY_CLASSES = mapOf(
        "motorway" to RoadClass.MOTORWAY, "motorway_link" to RoadClass.MOTORWAY,
        "trunk" to RoadClass.TRUNK, "trunk_link" to RoadClass.TRUNK,
        "primary" to RoadClass.PRIMARY, "primary_link" to RoadClass.PRIMARY,
        "secondary" to RoadClass.SECONDARY, "secondary_link" to RoadClass.SECONDARY,
        "tertiary" to RoadClass.TERTIARY, "tertiary_link" to RoadClass.TERTIARY,
        "unclassified" to RoadClass.UNCLASSIFIED,
        "residential" to RoadClass.RESIDENTIAL, "living_street" to RoadClass.RESIDENTIAL,
    )

    val HIGHWAY_REGEX = HIGHWAY_CLASSES.keys.joinToString("|", prefix = "^(", postfix = ")$")

    fun roadClass(tags: Map<String, String>): RoadClass? {
        val cls = HIGHWAY_CLASSES[tags["highway"]] ?: return null
        if (tags["area"] == "yes") return null
        if (tags["access"] in setOf("no", "private") || tags["motor_vehicle"] in setOf("no", "private")) return null
        return cls
    }

    fun isLink(tags: Map<String, String>) = tags["highway"]?.endsWith("_link") == true

    /** +1 drivable only along the node order, -1 only against it, 0 both ways. */
    fun onewayDirection(tags: Map<String, String>): Int = when (tags["oneway"]) {
        "yes", "true", "1" -> 1
        "-1", "reverse" -> -1
        "no", "false", "0" -> 0
        else -> if (tags["highway"] in setOf("motorway", "motorway_link") || tags["junction"] in setOf("roundabout", "circular")) 1 else 0
    }

    fun isBridge(tags: Map<String, String>) = tags["bridge"] in setOf("yes", "viaduct", "cantilever", "movable", "trestle")

    fun isTunnel(tags: Map<String, String>) = tags["tunnel"] in setOf("yes", "building_passage", "avalanche_protector")

    /** Leading number of a maxspeed tag in km/h ("60", "60 km/h"); mph converted; null otherwise. */
    fun maxSpeed(value: String?): Int? {
        val v = value?.trim() ?: return null
        val number = Regex("^(\\d{1,3})").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (v.contains("mph")) (number * 1.609).toInt() else number
    }

    /** Direction tag as degrees: numeric or compass point (N, NNE, ...); null when absent or relative. */
    fun direction(value: String?): Double? {
        val v = value?.trim()?.uppercase() ?: return null
        v.toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
        val points = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val i = points.indexOf(v)
        return if (i >= 0) i * 22.5 else null
    }

    fun poi(position: LatLon, tags: Map<String, String>): Poi? {
        val type = when {
            tags["highway"] == "speed_camera" -> PoiType.SPEED_CAMERA
            tags["enforcement"] == "maxspeed" -> PoiType.SPEED_CAMERA
            tags["enforcement"] == "traffic_signals" -> PoiType.RED_LIGHT_CAMERA
            tags["traffic_calming"] in setOf("bump", "hump", "table", "cushion", "yes") -> PoiType.SPEED_BUMP
            tags["barrier"] == "toll_booth" -> PoiType.TOLL
            tags["hazard"] in setOf("curve", "curves", "dangerous_curve") -> PoiType.DANGEROUS_CURVE
            tags["hazard"] == "flooding" -> PoiType.FLOODING
            else -> return null
        }
        return Poi(type, position, direction(tags["direction"] ?: tags["camera:direction"]), maxSpeed(tags["maxspeed"]))
    }

    /** Parses an Overpass JSON response (ways with tags, their nodes, and point-of-interest nodes). */
    fun parseOverpass(json: String): OsmData {
        val root = Json.parseToJsonElement(json).jsonObject
        val nodes = HashMap<Long, LatLon>()
        val ways = mutableListOf<OsmWay>()
        val pois = mutableListOf<Pair<LatLon, Map<String, String>>>()
        for (element in root["elements"]?.jsonArray.orEmpty()) {
            val o = element.jsonObject
            val tags = o.tags()
            when (o["type"]?.jsonPrimitive?.content) {
                "node" -> {
                    val p = LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double)
                    nodes[o["id"]!!.jsonPrimitive.long] = p
                    if (tags.isNotEmpty()) pois += p to tags
                }
                "way" -> ways += OsmWay(
                    o["id"]!!.jsonPrimitive.long,
                    o["nodes"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty(),
                    tags,
                )
            }
        }
        return OsmData(nodes, ways, pois)
    }

    private fun JsonObject.tags(): Map<String, String> =
        this["tags"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    /** Roads with their node coordinates, plus nodes worth warning about. */
    fun overpassQuery(south: Double, west: Double, north: Double, east: Double): String {
        val bbox = "$south,$west,$north,$east"
        return """
            [out:json][timeout:180];
            way["highway"~"$HIGHWAY_REGEX"]($bbox)->.roads;
            .roads out body;
            node(w.roads);
            out skel qt;
            (
              node["highway"="speed_camera"]($bbox);
              node["traffic_calming"]($bbox);
              node["barrier"="toll_booth"]($bbox);
              node["hazard"]($bbox);
            );
            out body qt;
        """.trimIndent()
    }
}

data class Bbox(val south: Double, val west: Double, val north: Double, val east: Double) {
    operator fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
}

/** The roads with at least one node inside [bbox] (kept whole), and the points inside it. */
fun OsmData.within(bbox: Bbox): OsmData = OsmData(
    nodes,
    ways.filter { w -> w.nodes.any { id -> nodes[id]?.let { it in bbox } == true } },
    poiNodes.filter { (p, _) -> p in bbox },
)

/** Web Mercator tile holding [p] at [zoom], as (x, y). */
fun tileOf(p: LatLon, zoom: Int): Pair<Int, Int> {
    val (px, py) = TerrariumElevation.pixel(p.lat, p.lon, zoom)
    return (px / TerrariumElevation.TILE_SIZE).toInt() to (py / TerrariumElevation.TILE_SIZE).toInt()
}

/**
 * Roads ordered by the elevation tile of their first node, row by row: neighboring roads are then
 * built one after the other and reuse the tiles in memory (a state has thousands of tiles).
 */
fun OsmData.spatiallySorted(zoom: Int): OsmData {
    val key = HashMap<Long, Long>(ways.size * 2)
    for (w in ways) {
        val (x, y) = nodes[w.nodes.first()]?.let { tileOf(it, zoom) } ?: (0 to 0)
        key[w.id] = (y.toLong() shl 32) or x.toLong()
    }
    return copy(ways = ways.sortedBy { key[it.id] })
}

/** Every elevation tile a road node falls in. */
fun OsmData.roadTiles(zoom: Int): Set<Pair<Int, Int>> {
    val tiles = HashSet<Pair<Int, Int>>()
    for (w in ways) for (id in w.nodes) nodes[id]?.let { tiles += tileOf(it, zoom) }
    return tiles
}

/** Merges tile downloads: ways crossing tile borders arrive in several tiles and are kept once. */
fun List<OsmData>.merged(): OsmData {
    val nodes = HashMap<Long, LatLon>()
    val ways = LinkedHashMap<Long, OsmWay>()
    val pois = LinkedHashSet<Pair<LatLon, Map<String, String>>>()
    for (d in this) {
        nodes.putAll(d.nodes)
        for (w in d.ways) ways.putIfAbsent(w.id, w)
        pois.addAll(d.poiNodes)
    }
    return OsmData(nodes, ways.values.toList(), pois.toList())
}

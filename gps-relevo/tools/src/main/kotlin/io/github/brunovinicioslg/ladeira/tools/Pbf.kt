package io.github.brunovinicioslg.ladeira.tools

import crosby.binary.BinaryParser
import crosby.binary.Osmformat
import crosby.binary.file.BlockInputStream
import io.github.brunovinicioslg.ladeira.geo.LatLon
import java.io.File
import kotlin.math.roundToInt

/**
 * Reads roads and warning points from an OpenStreetMap `.osm.pbf` extract (a whole state), in two
 * passes so that only road nodes are kept in memory: the first reads the ways and the tagged
 * points, the second the coordinates of the nodes those ways use.
 */
object Pbf {

    /** Way tags the pipeline reads; the others are dropped to save memory. */
    private val WAY_KEYS = setOf(
        "highway", "area", "access", "motor_vehicle", "oneway", "junction", "bridge", "tunnel", "maxspeed", "name", "ref",
    )

    fun read(file: File, log: (String) -> Unit = {}): OsmData {
        val strings = HashMap<String, String>()
        fun intern(s: String) = strings.getOrPut(s) { s }

        // Pass 1: roads and tagged points.
        val ways = ArrayList<OsmWay>()
        val pois = ArrayList<Pair<LatLon, Map<String, String>>>()
        var refs = LongArray(1 shl 20)
        var refCount = 0
        parse(file, object : Handler {
            override fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) {
                if (tags.isEmpty()) return
                val position = LatLon(lat, lon)
                if (Osm.poi(position, tags) != null) pois += position to tags
            }

            override fun way(id: Long, nodes: LongArray, tags: Map<String, String>) {
                if (nodes.size < 2 || Osm.roadClass(tags) == null) return
                val kept = HashMap<String, String>(8)
                for ((k, v) in tags) if (k in WAY_KEYS) kept[intern(k)] = intern(v)
                ways += OsmWay(id, nodes.asList(), kept)
                if (refCount + nodes.size > refs.size) refs = refs.copyOf(maxOf(refs.size * 2, refCount + nodes.size))
                nodes.copyInto(refs, refCount)
                refCount += nodes.size
            }
        })
        log("Pass 1: ${ways.size} roads, ${pois.size} tagged points")

        // Pass 2: coordinates of the road nodes.
        val ids = refs.copyOf(refCount).apply { sort() }.distinctSorted()
        val nodes = CompactNodeMap(ids)
        parse(file, object : Handler {
            override fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) {
                nodes.set(id, lat, lon)
            }
        })
        log("Pass 2: ${nodes.size} of ${ids.size} road nodes found")
        return OsmData(nodes, ways, pois)
    }

    private interface Handler {
        fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) {}
        fun way(id: Long, nodes: LongArray, tags: Map<String, String>) {}
    }

    private fun parse(file: File, handler: Handler) {
        val parser = object : BinaryParser() {
            override fun parseDense(nodes: Osmformat.DenseNodes) {
                var id = 0L
                var lat = 0L
                var lon = 0L
                var kv = 0
                val hasTags = nodes.keysValsCount > 0
                for (i in 0 until nodes.idCount) {
                    id += nodes.getId(i)
                    lat += nodes.getLat(i)
                    lon += nodes.getLon(i)
                    var tags = emptyMap<String, String>()
                    if (hasTags) {
                        // Keys and values alternate; a 0 ends each node's list.
                        while (nodes.getKeysVals(kv) != 0) {
                            if (tags.isEmpty()) tags = HashMap(4)
                            (tags as HashMap)[getStringById(nodes.getKeysVals(kv))] = getStringById(nodes.getKeysVals(kv + 1))
                            kv += 2
                        }
                        kv++
                    }
                    handler.node(id, parseLat(lat), parseLon(lon), tags)
                }
            }

            override fun parseNodes(nodes: List<Osmformat.Node>) {
                for (n in nodes) {
                    val tags = HashMap<String, String>(n.keysCount)
                    for (k in 0 until n.keysCount) tags[getStringById(n.getKeys(k))] = getStringById(n.getVals(k))
                    handler.node(n.id, parseLat(n.lat), parseLon(n.lon), tags)
                }
            }

            override fun parseWays(ways: List<Osmformat.Way>) {
                for (w in ways) {
                    val refs = LongArray(w.refsCount)
                    var ref = 0L
                    for (k in 0 until w.refsCount) {
                        ref += w.getRefs(k) // delta coded
                        refs[k] = ref
                    }
                    val tags = HashMap<String, String>(w.keysCount)
                    for (k in 0 until w.keysCount) tags[getStringById(w.getKeys(k))] = getStringById(w.getVals(k))
                    handler.way(w.id, refs, tags)
                }
            }

            override fun parseRelations(rels: List<Osmformat.Relation>) {}

            override fun parse(header: Osmformat.HeaderBlock) {
                val unsupported = header.requiredFeaturesList - setOf("OsmSchema-V0.6", "DenseNodes")
                require(unsupported.isEmpty()) { "Unsupported PBF features: $unsupported" }
            }

            override fun complete() {}
        }
        file.inputStream().buffered(1 shl 20).use { BlockInputStream(it, parser).process() }
    }

    private fun LongArray.distinctSorted(): LongArray {
        if (isEmpty()) return this
        var n = 1
        for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
        return copyOf(n)
    }
}

/**
 * Node coordinates for a known, sorted set of ids, in about 16 bytes per node (a HashMap of boxed
 * values takes several times that; a state has millions of road nodes). Only [get] is fast.
 */
class CompactNodeMap(private val ids: LongArray) : AbstractMap<Long, LatLon>() {
    private val lats = IntArray(ids.size) { MISSING }
    private val lons = IntArray(ids.size)
    private var found = 0

    fun set(id: Long, lat: Double, lon: Double) {
        val i = ids.binarySearch(id)
        if (i < 0) return
        if (lats[i] == MISSING) found++
        lats[i] = (lat * SCALE).roundToInt()
        lons[i] = (lon * SCALE).roundToInt()
    }

    override fun get(key: Long): LatLon? {
        val i = ids.binarySearch(key)
        if (i < 0 || lats[i] == MISSING) return null
        return LatLon(lats[i] / SCALE, lons[i] / SCALE)
    }

    override fun containsKey(key: Long) = get(key) != null

    override val size: Int get() = found

    override val entries: Set<Map.Entry<Long, LatLon>>
        get() = object : AbstractSet<Map.Entry<Long, LatLon>>() {
            override val size: Int get() = found
            override fun iterator(): Iterator<Map.Entry<Long, LatLon>> =
                ids.indices.asSequence().filter { lats[it] != MISSING }
                    .map { i -> java.util.AbstractMap.SimpleImmutableEntry(ids[i], LatLon(lats[i] / SCALE, lons[i] / SCALE)) }
                    .iterator()
        }

    private companion object {
        const val MISSING = Int.MIN_VALUE
        const val SCALE = 1e7 // OSM stores 7 decimal places
    }
}

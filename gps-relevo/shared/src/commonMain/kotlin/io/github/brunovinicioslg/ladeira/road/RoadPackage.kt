package io.github.brunovinicioslg.ladeira.road

import io.github.brunovinicioslg.ladeira.geo.LatLon
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Random access to a package file (a memory buffer in tests, a file on the phone). */
interface RandomAccessSource {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, length: Int): ByteArray = bytes.copyOfRange(position.toInt(), position.toInt() + length)
}

/**
 * Offline road package ("LDRP"): the road graph of a region with elevation profiles and points of
 * interest, split into grid cells so the phone reads only the area around the vehicle.
 *
 * Layout: header, string table, cell index (cell -> byte range), cell blobs. Coordinates are stored
 * in micro-degrees, elevations in decimeters, both delta + varint encoded. Each edge is stored in
 * the cell of its first point; edges are split by the pipeline to at most [MAX_EDGE_LENGTH_M], so
 * loading the cells within (radius + that length) always finds every nearby edge.
 */
object RoadPackage {
    const val MAGIC = 0x4C445250 // "LDRP"
    const val VERSION = 1
    const val CELL_SIZE_DEG = 0.05
    const val MAX_EDGE_LENGTH_M = 500.0

    fun write(edges: List<Edge>, pois: List<Poi>): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        fun stringIndex(s: String?): Int = if (s == null) -1 else strings.getOrPut(s) { strings.size }

        val edgeCells = edges.groupBy { cellOf(it.geometry.first()) }
        val poiCells = pois.groupBy { cellOf(it.position) }
        val cells = (edgeCells.keys + poiCells.keys).sortedWith(compareBy({ it.first }, { it.second }))

        val blobs = cells.map { cell ->
            ByteWriter().apply {
                val cellEdges = edgeCells[cell].orEmpty()
                varint(cellEdges.size.toLong())
                for (e in cellEdges) writeEdge(e, stringIndex(e.name), stringIndex(e.ref))
                val cellPois = poiCells[cell].orEmpty()
                varint(cellPois.size.toLong())
                for (p in cellPois) writePoi(p)
            }.toByteArray()
        }

        val stringBlock = ByteWriter().apply {
            varint(strings.size.toLong())
            for (s in strings.keys) string(s)
        }.toByteArray()

        val out = ByteWriter()
        out.int(MAGIC)
        out.int(VERSION)
        out.int(cells.size)
        out.int(stringBlock.size)
        out.bytes(stringBlock)
        val indexStart = out.size.toLong()
        val blobStart = indexStart + cells.size * INDEX_ENTRY_BYTES
        var offset = blobStart
        cells.forEachIndexed { i, (cy, cx) ->
            out.int(cy)
            out.int(cx)
            out.long(offset)
            out.int(blobs[i].size)
            offset += blobs[i].size
        }
        blobs.forEach(out::bytes)
        return out.toByteArray()
    }

    fun cellOf(p: LatLon): Pair<Int, Int> =
        floor(p.lat / CELL_SIZE_DEG).toInt() to floor(p.lon / CELL_SIZE_DEG).toInt()

    internal const val INDEX_ENTRY_BYTES = 4 + 4 + 8 + 4

    private fun ByteWriter.writeEdge(e: Edge, nameIdx: Int, refIdx: Int) {
        varint(e.id.toLong())
        varint(e.from.toLong())
        varint(e.to.toLong())
        byte(e.roadClass.ordinal)
        byte(
            (if (e.oneway) 1 else 0) or (if (e.bridge) 2 else 0) or (if (e.tunnel) 4 else 0) or (if (e.link) 8 else 0),
        )
        byte(e.maxSpeedKmh?.coerceIn(1, 250) ?: 0)
        signedVarint(nameIdx.toLong())
        signedVarint(refIdx.toLong())
        varint(e.geometry.size.toLong())
        var lastLat = 0L
        var lastLon = 0L
        for (p in e.geometry) {
            val lat = (p.lat * 1e6).roundToLong()
            val lon = (p.lon * 1e6).roundToLong()
            signedVarint(lat - lastLat)
            signedVarint(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        varint(e.elevations.size.toLong())
        var lastElevation = 0L
        for (h in e.elevations) {
            val dm = (h * 10).roundToLong()
            signedVarint(dm - lastElevation)
            lastElevation = dm
        }
    }

    private fun ByteWriter.writePoi(p: Poi) {
        byte(p.type.ordinal)
        signedVarint((p.position.lat * 1e6).roundToLong())
        signedVarint((p.position.lon * 1e6).roundToLong())
        signedVarint(p.directionDegrees?.roundToInt()?.toLong() ?: -1)
        varint((p.speedLimitKmh ?: 0).toLong())
    }
}

/** Reads the cells of a [RoadPackage] on demand. */
class RoadPackageReader(private val source: RandomAccessSource) {

    private val strings: List<String>
    private val index: Map<Pair<Int, Int>, Pair<Long, Int>>

    init {
        require(source.size >= 16) { "Not a road package" }
        val header = ByteReader(source.read(0, 16))
        require(header.int() == RoadPackage.MAGIC) { "Not a road package" }
        val version = header.int()
        require(version == RoadPackage.VERSION) { "Unsupported road package version $version" }
        val cellCount = header.int()
        val stringBytes = header.int()
        val indexStart = 16L + stringBytes
        require(cellCount >= 0 && stringBytes >= 0) { "Corrupt road package header" }
        require(indexStart + cellCount.toLong() * RoadPackage.INDEX_ENTRY_BYTES <= source.size) { "Truncated road package" }
        val stringReader = ByteReader(source.read(16, stringBytes))
        val stringCount = stringReader.varint()
        require(stringCount in 0..stringBytes.toLong()) { "Corrupt road package strings" }
        strings = List(stringCount.toInt()) { stringReader.string() }
        val indexReader = ByteReader(source.read(indexStart, cellCount * RoadPackage.INDEX_ENTRY_BYTES))
        index = HashMap<Pair<Int, Int>, Pair<Long, Int>>(cellCount * 2).apply {
            repeat(cellCount) {
                val cy = indexReader.int()
                val cx = indexReader.int()
                val offset = indexReader.long()
                val length = indexReader.int()
                require(offset >= indexStart && length >= 0 && offset + length <= source.size) { "Truncated road package" }
                put(cy to cx, offset to length)
            }
        }
    }

    val cellCount: Int get() = index.size

    /** Everything within [radiusM] of [center]. */
    fun load(center: LatLon, radiusM: Double): RoadNetwork {
        val reach = radiusM + RoadPackage.MAX_EDGE_LENGTH_M
        val dLat = reach / 111_000
        val dLon = dLat / cos(center.lat * kotlin.math.PI / 180).coerceAtLeast(0.01)
        val (y0, x0) = RoadPackage.cellOf(LatLon(center.lat - dLat, center.lon - dLon))
        val (y1, x1) = RoadPackage.cellOf(LatLon(center.lat + dLat, center.lon + dLon))
        val edges = LinkedHashMap<Int, Edge>()
        val pois = mutableListOf<Poi>()
        for (cy in y0..y1) for (cx in x0..x1) {
            val (offset, length) = index[cy to cx] ?: continue
            val r = ByteReader(source.read(offset, length))
            repeat(r.count()) { readEdge(r).let { edges[it.id] = it } }
            repeat(r.count()) { pois += readPoi(r) }
        }
        return RoadNetwork(edges.values.toList(), pois)
    }

    private fun readEdge(r: ByteReader): Edge {
        val id = r.varint().toInt()
        val from = r.varint().toInt()
        val to = r.varint().toInt()
        val roadClass = RoadClass.entries.getOrNull(r.byte()) ?: throw IllegalArgumentException("Corrupt road class")
        val flags = r.byte()
        val maxSpeed = r.byte().takeIf { it > 0 }
        val name = string(r.signedVarint())
        val ref = string(r.signedVarint())
        var lat = 0L
        var lon = 0L
        val geometry = List(r.count()) {
            lat += r.signedVarint()
            lon += r.signedVarint()
            LatLon(lat / 1e6, lon / 1e6).also { if (!it.isValid()) throw IllegalArgumentException("Corrupt coordinates") }
        }
        var dm = 0L
        val elevations = DoubleArray(r.count()) {
            dm += r.signedVarint()
            dm / 10.0
        }
        return Edge(
            id = id, from = from, to = to, geometry = geometry, elevations = elevations, roadClass = roadClass,
            oneway = flags and 1 != 0, bridge = flags and 2 != 0, tunnel = flags and 4 != 0, link = flags and 8 != 0,
            name = name, ref = ref, maxSpeedKmh = maxSpeed,
        )
    }

    private fun string(index: Long): String? = when {
        index < 0 -> null
        index < strings.size -> strings[index.toInt()]
        else -> throw IllegalArgumentException("Corrupt string reference")
    }

    private fun readPoi(r: ByteReader): Poi {
        val type = PoiType.entries.getOrNull(r.byte()) ?: throw IllegalArgumentException("Corrupt point of interest")
        val lat = r.signedVarint() / 1e6
        val lon = r.signedVarint() / 1e6
        val direction = r.signedVarint().takeIf { it >= 0 }?.toDouble()
        val speed = r.varint().toInt().takeIf { it > 0 }
        val position = LatLon(lat, lon)
        if (!position.isValid()) throw IllegalArgumentException("Corrupt coordinates")
        return Poi(type, position, direction, speed)
    }
}

/** Big-endian fixed-size values plus LEB128 varints (zigzag for signed). */
class ByteWriter {
    private var buffer = ByteArray(1024)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun byte(v: Int) {
        ensure(1)
        buffer[size++] = v.toByte()
    }

    fun int(v: Int) {
        for (shift in 24 downTo 0 step 8) byte(v ushr shift)
    }

    fun long(v: Long) {
        for (shift in 56 downTo 0 step 8) byte((v ushr shift).toInt())
    }

    fun varint(value: Long) {
        require(value >= 0) { "varint must not be negative" }
        var v = value
        while (v >= 0x80) {
            byte(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        byte(v.toInt())
    }

    fun signedVarint(v: Long) = varint((v shl 1) xor (v shr 63))

    fun string(s: String) {
        val b = s.encodeToByteArray()
        varint(b.size.toLong())
        bytes(b)
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buffer, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

class ByteReader(private val bytes: ByteArray) {
    private var pos = 0

    fun byte(): Int {
        if (pos >= bytes.size) throw IllegalArgumentException("Truncated road package")
        return bytes[pos++].toInt() and 0xff
    }

    fun int(): Int {
        var v = 0
        repeat(4) { v = (v shl 8) or byte() }
        return v
    }

    fun long(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or byte().toLong() }
        return v
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalArgumentException("Malformed varint")
        }
    }

    fun signedVarint(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    /** A count of following items; each takes at least one byte, so larger counts mean corruption. */
    fun count(): Int {
        val n = varint()
        if (n > bytes.size - pos) throw IllegalArgumentException("Corrupt count $n")
        return n.toInt()
    }

    fun string(): String {
        val length = varint().toInt()
        if (length < 0 || pos + length > bytes.size) throw IllegalArgumentException("Truncated road package")
        return bytes.decodeToString(pos, pos + length).also { pos += length }
    }
}

package io.github.brunovinicioslg.ladeira.app.region

import io.github.brunovinicioslg.ladeira.drive.RoadSource
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.RandomAccessSource
import io.github.brunovinicioslg.ladeira.road.RoadNetwork
import io.github.brunovinicioslg.ladeira.road.RoadPackageReader
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RegionProblem { MISSING_ROADS, MISSING_MAP, INVALID_ROADS, INVALID_MAP }

/** A downloaded area: its roads with elevation (for the alerts) and its map (for the screen). */
class Region(
    val name: String,
    val roads: RoadPackageReader?,
    val mapFile: File?,
    val map: PmTilesInfo?,
    val sizeBytes: Long,
    val problems: List<RegionProblem>,
)

sealed interface ImportResult {
    val fileName: String

    data class Imported(override val fileName: String, val region: String) : ImportResult

    data class Failed(override val fileName: String, val error: Error) : ImportResult

    enum class Error { UNKNOWN_TYPE, INVALID, IO }
}

/**
 * Region files live in [root]/<region>/{roads.ldrp, map.pmtiles}. The blocking methods touch the
 * disk: call them off the main thread. Road packages stay open while listed, so the drive engine
 * reads only the cells it needs.
 */
class RegionStore(private val root: File) {

    private val lock = Any()

    /** One import at a time: two copies of the same file would share a temporary file. */
    private val installLock = Any()
    private val openPackages = HashMap<String, OpenPackage>()
    private val _regions = MutableStateFlow<List<Region>>(emptyList())
    val regions: StateFlow<List<Region>> = _regions.asStateFlow()

    /** Road data for the drive engine, from whichever installed region covers the place best. */
    val roadSource = RoadSource { center, radiusM ->
        _regions.value.mapNotNull { region -> region.roads?.let { loadOrNull(it, center, radiusM) } }
            .filter { it.edges.isNotEmpty() }
            .maxByOrNull { it.edges.size }
    }

    /** The region whose map shows [position]; without a position, the first region with a map. */
    fun mapRegionFor(position: LatLon?): Region? {
        val withMap = _regions.value.filter { it.mapFile != null && it.map != null }
        return position?.let { p -> withMap.firstOrNull { p in it.map!! } } ?: withMap.firstOrNull()
    }

    /** Rescans [root] and publishes the result. Blocking. */
    fun refresh() {
        synchronized(lock) { rescan() }
    }

    private fun rescan() {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()
        val seen = HashSet<String>()
        val regions = dirs.mapNotNull { dir ->
            dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() } // interrupted imports
            val roadsFile = File(dir, ROADS_FILE).takeIf { it.isFile }
            val mapFile = File(dir, MAP_FILE).takeIf { it.isFile }
            if (roadsFile == null && mapFile == null) return@mapNotNull null
            val roads = roadsFile?.let { seen += it.path; openPackage(it) }
            val map = mapFile?.let { readMapInfo(it) }
            val problems = buildList {
                if (roadsFile == null) add(RegionProblem.MISSING_ROADS) else if (roads == null) add(RegionProblem.INVALID_ROADS)
                if (mapFile == null) add(RegionProblem.MISSING_MAP) else if (map == null) add(RegionProblem.INVALID_MAP)
            }
            Region(
                name = dir.name,
                roads = roads,
                mapFile = mapFile.takeIf { map != null },
                map = map,
                sizeBytes = (roadsFile?.length() ?: 0) + (mapFile?.length() ?: 0),
                problems = problems,
            )
        }
        openPackages.keys.filter { it !in seen }.forEach { openPackages.remove(it)?.close() }
        _regions.value = regions
    }

    /**
     * Copies one region file into place: `<region>.ldrp` or `<region>.pmtiles`. The file is
     * validated before it replaces anything, so a bad import never breaks a working region.
     * Blocking; refreshes the list on success.
     */
    fun install(displayName: String, input: InputStream): ImportResult = synchronized(installLock) {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val target = when (extension) {
            "ldrp" -> ROADS_FILE
            "pmtiles" -> MAP_FILE
            else -> return@synchronized ImportResult.Failed(displayName, ImportResult.Error.UNKNOWN_TYPE)
        }
        val region = regionNameFor(displayName)
        val dir = File(root, region)
        val part = File(dir, target + PART_SUFFIX)
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            part.outputStream().use { out -> input.copyTo(out) }
            val valid = if (target == ROADS_FILE) isValidPackage(part) else readMapInfo(part) != null
            if (!valid) {
                part.delete()
                deleteIfEmpty(dir)
                return@synchronized ImportResult.Failed(displayName, ImportResult.Error.INVALID)
            }
            synchronized(lock) {
                val destination = File(dir, target)
                // The old package is closed first: the new one is opened fresh by the refresh below.
                openPackages.remove(destination.path)?.close()
                Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: IOException) {
            part.delete()
            deleteIfEmpty(dir)
            return@synchronized ImportResult.Failed(displayName, ImportResult.Error.IO)
        }
        refresh()
        ImportResult.Imported(displayName, region)
    }

    /** Removes a region's files. Blocking. */
    fun delete(name: String) {
        synchronized(lock) {
            val dir = File(root, name)
            if (dir.canonicalFile.parentFile != root.canonicalFile) return // never outside root
            openPackages.keys.filter { File(it).parentFile?.name == name }.forEach { openPackages.remove(it)?.close() }
            dir.deleteRecursively()
        }
        refresh()
    }

    private fun openPackage(file: File): RoadPackageReader? {
        val existing = openPackages[file.path]
        if (existing != null && existing.length == file.length() && existing.modified == file.lastModified()) return existing.reader
        existing?.close()
        openPackages.remove(file.path)
        val channel = try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ)
        } catch (_: IOException) {
            return null
        }
        val reader = try {
            RoadPackageReader(ChannelSource(channel))
        } catch (_: IllegalArgumentException) {
            channel.close()
            return null
        } catch (_: IOException) {
            channel.close()
            return null
        }
        openPackages[file.path] = OpenPackage(file.length(), file.lastModified(), channel, reader)
        return reader
    }

    private fun isValidPackage(file: File): Boolean = try {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            RoadPackageReader(ChannelSource(channel)).verify() > 0
        }
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun readMapInfo(file: File): PmTilesInfo? = try {
        file.inputStream().use { input ->
            val header = input.readNBytesCompat(PmTilesInfo.HEADER_BYTES)
            PmTilesInfo.parse(header)?.takeIf { PmTilesInfo.fitsIn(header, file.length()) }
        }
    } catch (_: IOException) {
        null
    }

    private fun deleteIfEmpty(dir: File) {
        if (dir.list()?.isEmpty() == true) dir.delete()
    }

    private class OpenPackage(val length: Long, val modified: Long, val channel: FileChannel, val reader: RoadPackageReader) : Closeable {
        override fun close() = channel.close()
    }

    companion object {
        const val ROADS_FILE = "roads.ldrp"
        const val MAP_FILE = "map.pmtiles"
        private const val PART_SUFFIX = ".part"
        private const val MAX_NAME_LENGTH = 60

        /** "BH Sul (1).pmtiles" -> "BH-Sul-1": safe as a folder name, readable in the list. */
        fun regionNameFor(fileName: String): String {
            val base = fileName.substringAfterLast('/').substringBeforeLast('.')
            val cleaned = base.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-' }.joinToString("")
                .replace(Regex("-{2,}"), "-").trim('-', '_').take(MAX_NAME_LENGTH).trim('-', '_')
            return cleaned.ifEmpty { "regiao" }
        }

        private fun loadOrNull(reader: RoadPackageReader, center: LatLon, radiusM: Double): RoadNetwork? = try {
            reader.load(center, radiusM)
        } catch (_: IOException) {
            null // the file was replaced or removed while driving; the region list refresh follows
        } catch (_: IllegalArgumentException) {
            null // damaged on disk after installation
        }
    }
}

/** Positional reads are thread-safe on a [FileChannel], unlike seek-and-read on a RandomAccessFile. */
private class ChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val size: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) throw EOFException("Unexpected end of road package")
            at += n
        }
        return buffer.array()
    }
}

private fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = ByteArray(n)
    var total = 0
    while (total < n) {
        val read = read(out, total, n - total)
        if (read < 0) return out.copyOf(total)
        total += read
    }
    return out
}

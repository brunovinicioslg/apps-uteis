package io.github.brunovinicioslg.ladeira.tools

import io.github.brunovinicioslg.ladeira.alerts.Alert
import io.github.brunovinicioslg.ladeira.alerts.AlertEngine
import io.github.brunovinicioslg.ladeira.drive.DriveEngine
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.lookahead.Lookahead
import io.github.brunovinicioslg.ladeira.matching.GpsFix
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import io.github.brunovinicioslg.ladeira.road.EdgePosition
import io.github.brunovinicioslg.ladeira.road.RandomAccessSource
import io.github.brunovinicioslg.ladeira.road.RoadPackage
import io.github.brunovinicioslg.ladeira.road.RoadPackageReader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import kotlin.system.exitProcess

private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = args.drop(1).chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
    try {
        when (command) {
            "build" -> build(options)
            "profile" -> profile(options)
            "find" -> find(options)
            "track" -> track(options)
            "drive" -> drive(options)
            else -> {
                System.err.println(
                    """
                    Usage:
                      build   --bbox south,west,north,east --out region.ldrp [--cache dir] [--zoom 12] [--overpass url]
                      profile --package region.ldrp --at lat,lon --heading degrees [--distance 5000] [--vehicle CAR]
                      find    --package region.ldrp --road BR-040 [--near lat,lon] [--radius 20000]
                      track   --package region.ldrp --at lat,lon --heading degrees --out track.csv [--distance 10000] [--speed 72]
                      drive   --package region.ldrp --track track.csv [--vehicle CAR] [--no-heading true]
                    """.trimIndent(),
                )
                exitProcess(2)
            }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

private fun build(options: Map<String, String>) {
    val (south, west, north, east) = requireNotNull(options["bbox"]) { "--bbox is required" }.split(",").map { it.trim().toDouble() }
    require(south < north && west < east) { "bbox must be south,west,north,east" }
    val out = File(requireNotNull(options["out"]) { "--out is required" })
    val cache = File(options["cache"] ?: "build/cache")
    val zoom = options["zoom"]?.toInt() ?: 12
    val started = System.nanoTime()

    // Small tiles keep each Overpass query light; results are cached so reruns are free.
    val server = options["overpass"] ?: OVERPASS_URL
    val tileSize = options["tile"]?.toDouble() ?: 0.03
    val tiles = buildList {
        var s = south
        while (s < north) {
            var w = west
            while (w < east) {
                add(listOf(s, w, minOf(s + tileSize, north), minOf(w + tileSize, east)))
                w += tileSize
            }
            s += tileSize
        }
    }
    val osm = tiles.mapIndexed { i, (s, w, n, e) ->
        val query = Osm.overpassQuery(s, w, n, e)
        val json = cached(File(cache, "overpass/${sha1(query)}.json")) { fetchOverpass(query, server) }
        println("Tile ${i + 1}/${tiles.size}: ${json.length / 1_000} kB")
        Osm.parseOverpass(json)
    }.merged()
    println("Ways: ${osm.ways.size}, nodes: ${osm.nodes.size}, tagged points: ${osm.poiNodes.size}")

    val result = GraphBuilder(TerrariumElevation(File(cache, "terrarium"), zoom)).build(osm)
    val bytes = RoadPackage.write(result.edges, result.pois)
    out.absoluteFile.parentFile?.mkdirs()
    out.writeBytes(bytes)
    val km = result.edges.sumOf { it.length } / 1000
    val seconds = (System.nanoTime() - started) / 1e9
    println(
        String.format(
            Locale.ROOT,
            "Package %s: %d edges, %.0f km of road, %d points of interest, %.1f MB, %d ways skipped, %.0f s",
            out.name, result.edges.size, km, result.pois.size, bytes.size / 1e6, result.skippedWays, seconds,
        ),
    )
}

private fun profile(options: Map<String, String>) {
    val file = File(requireNotNull(options["package"]) { "--package is required" })
    val (lat, lon) = requireNotNull(options["at"]) { "--at lat,lon is required" }.split(",").map { it.trim().toDouble() }
    val heading = requireNotNull(options["heading"]) { "--heading is required" }.toDouble()
    val distance = options["distance"]?.toDouble() ?: 5_000.0
    val vehicle = VehicleProfile.valueOf(options["vehicle"] ?: "CAR")
    val at = LatLon(lat, lon)

    RandomAccessFile(file, "r").use { raf ->
        val network = RoadPackageReader(FileSource(raf)).load(at, distance + 1_000)
        val candidate = network.candidates(at, 60.0).firstOrNull() ?: throw IllegalArgumentException("No road within 60 m")
        val forward = Geo.angleDifference(candidate.edge.bearingAt(candidate.offset), heading) <= 90
        require(forward || !candidate.edge.oneway) { "That road is one-way in the other direction" }
        val path = Lookahead.follow(network, EdgePosition(candidate.edge, candidate.offset, forward), distance)
        val names = path.steps.map { it.edge.ref ?: it.edge.name ?: "(sem nome)" }
            .fold(mutableListOf<String>()) { acc, n -> acc.also { if (it.lastOrNull() != n) it += n } }
        val profile = path.profile()
        println("Road: ${names.joinToString(" -> ")}")
        println(String.format(Locale.ROOT, "Path: %.0f m, elevation %.0f m -> %.0f m", path.length, profile.first().elevation, profile.last().elevation))
        val road = AlertEngine(vehicle).analyze(path, network.pois)
        if (road.slopes.isEmpty()) println("No slopes worth warning a ${vehicle.name} about.")
        for ((slope, kind) in road.slopes) {
            println(
                String.format(
                    Locale.ROOT,
                    "  %-12s from %5.0f m to %5.0f m: %5.0f m long, %+.1f%% average, %.1f%% max, %+.0f m",
                    kind, slope.start, slope.end, slope.length, slope.averageGradePercent, slope.maxGradePercent, slope.elevationChange,
                ),
            )
        }
        for ((poi, d) in road.pois) println(String.format(Locale.ROOT, "  %-12s at %5.0f m", poi.type, d))
    }
}

/**
 * Writes a simulated drive along the road graph, one fix per second (lat,lon,elevation,bearing,speed
 * in m/s), for playing back into an emulator's GPS.
 */
private fun track(options: Map<String, String>) {
    val file = File(requireNotNull(options["package"]) { "--package is required" })
    val (lat, lon) = requireNotNull(options["at"]) { "--at lat,lon is required" }.split(",").map { it.trim().toDouble() }
    val heading = requireNotNull(options["heading"]) { "--heading is required" }.toDouble()
    val out = File(requireNotNull(options["out"]) { "--out is required" })
    val distance = options["distance"]?.toDouble() ?: 10_000.0
    val speed = (options["speed"]?.toDouble() ?: 72.0) / 3.6
    val at = LatLon(lat, lon)
    RandomAccessFile(file, "r").use { raf ->
        val network = RoadPackageReader(FileSource(raf)).load(at, distance + 1_000)
        val candidate = network.candidates(at, 60.0).firstOrNull() ?: throw IllegalArgumentException("No road within 60 m")
        val forward = Geo.angleDifference(candidate.edge.bearingAt(candidate.offset), heading) <= 90
        val path = Lookahead.follow(network, EdgePosition(candidate.edge, candidate.offset, forward), distance)
        val lines = generateSequence(0.0) { it + speed }.takeWhile { it <= path.length }.mapNotNull { d ->
            val (step, offset) = path.locate(d) ?: return@mapNotNull null
            val p = step.edge.pointAt(offset)
            val bearing = Geo.normalizeDegrees(step.edge.bearingAt(offset) + if (step.forward) 0.0 else 180.0)
            String.format(Locale.ROOT, "%.6f,%.6f,%.1f,%.0f,%.1f", p.lat, p.lon, step.edge.elevationAt(offset) ?: 0.0, bearing, speed)
        }.toList()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
        println(String.format(Locale.ROOT, "Track %s: %d fixes, %.1f km", out.name, lines.size, path.length / 1000))
    }
}

/**
 * Replays a track through the same engine the app runs and prints what it would say and when:
 * reproduces field reports without a phone. `--no-heading true` drops the GPS heading, as the
 * emulator does.
 */
private fun drive(options: Map<String, String>) {
    val file = File(requireNotNull(options["package"]) { "--package is required" })
    val track = File(requireNotNull(options["track"]) { "--track is required" })
    val vehicle = VehicleProfile.valueOf(options["vehicle"] ?: "CAR")
    val noHeading = options["no-heading"] == "true"
    RandomAccessFile(file, "r").use { raf ->
        val reader = RoadPackageReader(FileSource(raf))
        val engine = DriveEngine({ center, radius -> reader.load(center, radius) }, vehicle)
        var traveled = 0.0
        var previous: LatLon? = null
        track.readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            val (lat, lon, _, bearing, speed) = line.split(",").map { it.trim().toDouble() }
            val position = LatLon(lat, lon)
            previous?.let { traveled += Geo.distance(it, position) }
            previous = position
            val update = engine.update(
                GpsFix(position, accuracyM = 5.0, speedMps = speed, bearingDegrees = if (noHeading) 0.0 else bearing, timeMillis = i * 1_000L),
            )
            val state = update.state
            if (i % 25 == 0) {
                val next = state.next?.let { (s, k) -> String.format(Locale.ROOT, "%s %.0f..%.0f", k, s.start, s.end) }
                val current = state.current?.let { (s, k) -> String.format(Locale.ROOT, "%s %.0f..%.0f", k, s.start, s.end) }
                println(String.format(Locale.ROOT, "%5.0f m  %-9s current=%s next=%s", traveled, state.status, current, next))
            }
            for (alert in update.alerts) {
                val text = when (alert) {
                    is Alert.SlopeAhead -> String.format(
                        Locale.ROOT, "%s in %.0f m: %.0f m at %.1f%%", alert.kind, alert.distance, alert.slope.end - maxOf(0.0, alert.slope.start),
                        alert.slope.averageGradePercent,
                    )
                    is Alert.PoiAhead -> String.format(Locale.ROOT, "%s in %.0f m", alert.poi.type, alert.distance)
                }
                println(String.format(Locale.ROOT, "%5.0f m  ALERT %s", traveled, text))
            }
        }
    }
}

/** Lists the stretches of a road by name or number, south to north, with their elevations. */
private fun find(options: Map<String, String>) {
    val file = File(requireNotNull(options["package"]) { "--package is required" })
    val query = requireNotNull(options["road"]) { "--road is required" }
    val (lat, lon) = (options["near"] ?: "-19.99,-43.94").split(",").map { it.trim().toDouble() }
    RandomAccessFile(file, "r").use { raf ->
        val network = RoadPackageReader(FileSource(raf)).load(LatLon(lat, lon), options["radius"]?.toDouble() ?: 20_000.0)
        val matches = network.edges.filter { it.ref?.contains(query) == true || it.name?.contains(query) == true }
            .sortedBy { minOf(it.geometry.first().lat, it.geometry.last().lat) }
        println("${matches.size} stretches, ${"%.1f".format(Locale.ROOT, matches.sumOf { it.length } / 1000)} km")
        for (e in matches) {
            val a = e.geometry.first()
            println(
                String.format(
                    Locale.ROOT, "  %.5f,%.5f  bearing %3.0f  %4.0f m  %5.0f -> %5.0f m%s%s",
                    a.lat, a.lon, e.bearingAt(0.0), e.length, e.elevations.first(), e.elevations.last(),
                    if (e.oneway) "  one-way" else "", if (e.bridge) "  bridge" else "",
                ),
            )
        }
    }
}

private class FileSource(private val raf: RandomAccessFile) : RandomAccessSource {
    override val size: Long get() = raf.length()
    override fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        raf.seek(position)
        raf.readFully(buffer)
        return buffer
    }
}

private fun fetchOverpass(query: String, server: String): String {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8)
    var lastError: Exception? = null
    repeat(5) { attempt ->
        try {
            val request = HttpRequest.newBuilder(URI(server))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMinutes(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) return response.body()
            lastError = IOException("Overpass HTTP ${response.statusCode()}: ${response.body().take(300)}")
        } catch (e: IOException) {
            lastError = e
        }
        println("Overpass attempt ${attempt + 1} failed; retrying")
        Thread.sleep(10_000L * (attempt + 1))
    }
    throw IOException("Overpass failed", lastError)
}

private fun cached(file: File, produce: () -> String): String {
    if (file.exists()) return file.readText()
    val text = produce()
    file.parentFile.mkdirs()
    file.writeText(text)
    return text
}

private fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

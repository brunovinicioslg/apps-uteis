package io.github.brunovinicioslg.ladeira.tools

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Terrain height in meters above sea level, or null where there is no data. */
fun interface ElevationModel {
    fun elevation(lat: Double, lon: Double): Double?
}

/**
 * AWS Terrain Tiles in "terrarium" encoding: Web Mercator PNG tiles where each pixel stores
 * height = R * 256 + G + B / 256 - 32768. Tiles are cached on disk. At zoom 12 a pixel is about
 * 36 m wide in Minas Gerais, close to the ~30 m of the underlying SRTM data.
 */
class TerrariumElevation(
    private val cacheDir: File,
    private val zoom: Int = 12,
    private val baseUrl: String = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium",
) : ElevationModel {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val tiles = object : LinkedHashMap<Long, IntArray?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, IntArray?>?) = size > MAX_TILES_IN_MEMORY
    }

    override fun elevation(lat: Double, lon: Double): Double? {
        val (px, py) = pixel(lat, lon, zoom)
        // Pixel centers sit at +0.5: bilinear interpolation between the four around the point.
        val x = px - 0.5
        val y = py - 0.5
        val x0 = floor(x).toLong()
        val y0 = floor(y).toLong()
        val tx = x - x0
        val ty = y - y0
        val h00 = sample(x0, y0) ?: return null
        val h10 = sample(x0 + 1, y0) ?: return null
        val h01 = sample(x0, y0 + 1) ?: return null
        val h11 = sample(x0 + 1, y0 + 1) ?: return null
        return (h00 * (1 - tx) + h10 * tx) * (1 - ty) + (h01 * (1 - tx) + h11 * tx) * ty
    }

    /** Height at a global pixel; handles pixels on neighboring tiles. */
    private fun sample(px: Long, py: Long): Double? {
        val tx = Math.floorDiv(px, TILE_SIZE.toLong())
        val ty = Math.floorDiv(py, TILE_SIZE.toLong())
        val pixels = tile(tx.toInt(), ty.toInt()) ?: return null
        val ix = Math.floorMod(px, TILE_SIZE.toLong()).toInt()
        val iy = Math.floorMod(py, TILE_SIZE.toLong()).toInt()
        return decode(pixels[iy * TILE_SIZE + ix])
    }

    private fun tile(x: Int, y: Int): IntArray? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        if (tiles.containsKey(key)) return tiles[key]
        val file = File(cacheDir, "$zoom/$x/$y.png")
        if (!file.exists()) download("$baseUrl/$zoom/$x/$y.png", file)
        val image = ImageIO.read(file) ?: throw IOException("Unreadable tile $file")
        require(image.width == TILE_SIZE && image.height == TILE_SIZE) { "Unexpected tile size in $file" }
        val pixels = image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE)
        tiles[key] = pixels
        return pixels
    }

    /**
     * Downloads the tiles not on disk yet, several at a time (a state needs thousands; one by one,
     * while building, would take hours).
     */
    fun prefetch(tiles: Collection<Pair<Int, Int>>, threads: Int = 8, progress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val missing = tiles.filter { (x, y) -> !File(cacheDir, "$zoom/$x/$y.png").exists() }
        if (missing.isEmpty()) return
        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        try {
            missing.map { (x, y) ->
                pool.submit {
                    download("$baseUrl/$zoom/$x/$y.png", File(cacheDir, "$zoom/$x/$y.png"))
                    val n = done.incrementAndGet()
                    synchronized(this) { progress(n, missing.size) }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun download(url: String, target: File) {
        target.parentFile.mkdirs()
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() == 200) {
                    val tmp = File(target.path + ".part")
                    tmp.writeBytes(response.body())
                    if (!tmp.renameTo(target)) throw IOException("Cannot move $tmp")
                    return
                }
                lastError = IOException("HTTP ${response.statusCode()} for $url")
            } catch (e: IOException) {
                lastError = e
            }
            Thread.sleep(1_000L shl attempt)
        }
        throw IOException("Download failed: $url", lastError)
    }

    companion object {
        const val TILE_SIZE = 256
        private const val MAX_TILES_IN_MEMORY = 512

        fun decode(argb: Int): Double {
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            return r * 256.0 + g + b / 256.0 - 32768.0
        }

        /** Global Web Mercator pixel coordinates (not rounded) at [zoom]. */
        fun pixel(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
            val scale = TILE_SIZE * (1L shl zoom).toDouble()
            val x = (lon + 180) / 360 * scale
            val latRad = lat * PI / 180
            val y = (1 - ln(tan(latRad) + 1 / kotlin.math.cos(latRad)) / PI) / 2 * scale
            return x to y
        }
    }
}

const val USER_AGENT = "Ladeira-pipeline/0.1 (+https://github.com/brunovinicioslg/apps-uteis)"

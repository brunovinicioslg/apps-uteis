package io.github.brunovinicioslg.ladeira.app.region

import io.github.brunovinicioslg.ladeira.geo.LatLon
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the app needs from a PMTiles v3 map file header: where the map is and how far it zooms. */
data class PmTilesInfo(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val center: LatLon,
    val centerZoom: Int,
) {
    operator fun contains(p: LatLon): Boolean = p.lat in minLat..maxLat && p.lon in minLon..maxLon

    companion object {
        const val HEADER_BYTES = 127
        private const val TILE_TYPE_MVT = 1

        /** True when every section the header points to lies within the file: catches truncated copies. */
        fun fitsIn(header: ByteArray, fileSize: Long): Boolean {
            if (header.size < HEADER_BYTES) return false
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            // Root directory, metadata, leaf directories, tile data: (offset, length) pairs.
            return (8..56 step 16).all { at ->
                val offset = b.getLong(at)
                val length = b.getLong(at + 8)
                offset >= 0 && length >= 0 && offset <= fileSize - length
            }
        }

        /** Null unless [header] is a valid PMTiles v3 header of vector tiles. */
        fun parse(header: ByteArray): PmTilesInfo? {
            if (header.size < HEADER_BYTES) return null
            if (String(header, 0, 7, Charsets.US_ASCII) != "PMTiles" || header[7].toInt() != 3) return null
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (b.get(99).toInt() != TILE_TYPE_MVT) return null
            fun e7(offset: Int) = b.getInt(offset) / 1e7
            val info = PmTilesInfo(
                minLon = e7(102),
                minLat = e7(106),
                maxLon = e7(110),
                maxLat = e7(114),
                minZoom = b.get(100).toInt() and 0xFF,
                maxZoom = b.get(101).toInt() and 0xFF,
                center = LatLon(e7(123), e7(119)),
                centerZoom = b.get(118).toInt() and 0xFF,
            )
            val valid = info.minLat < info.maxLat && info.minLon < info.maxLon &&
                LatLon(info.minLat, info.minLon).isValid() && LatLon(info.maxLat, info.maxLon).isValid() &&
                info.minZoom <= info.maxZoom
            if (!valid) return null
            // Some tools leave the center empty; the middle of the bounds is a fine default.
            return if (info.center in info) info else info.copy(center = LatLon((info.minLat + info.maxLat) / 2, (info.minLon + info.maxLon) / 2))
        }
    }
}

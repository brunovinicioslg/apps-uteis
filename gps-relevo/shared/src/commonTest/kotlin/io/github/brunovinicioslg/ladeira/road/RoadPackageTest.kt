package io.github.brunovinicioslg.ladeira.road

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.NetworkBuilder
import io.github.brunovinicioslg.ladeira.forEachCase
import io.github.brunovinicioslg.ladeira.geo.Geo
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoadPackageTest {

    private fun randomNetwork(seed: Long): NetworkBuilder {
        val rnd = kotlin.random.Random(seed)
        val b = NetworkBuilder()
        repeat(rnd.nextInt(1, 8)) {
            val start = Geo.destination(BH, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(0.0, 20_000.0))
            b.road(
                line(start, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(30.0, 3_000.0), step = rnd.nextDouble(10.0, 200.0)),
                elevation = { d -> 700 + 50 * kotlin.math.sin(d / 300) },
                roadClass = RoadClass.entries.random(rnd),
                name = if (rnd.nextBoolean()) "Rua ${rnd.nextInt(100)} – Ação" else null,
                oneway = rnd.nextBoolean(),
                bridge = rnd.nextBoolean(),
            )
        }
        repeat(rnd.nextInt(0, 5)) {
            b.pois += Poi(
                PoiType.entries.random(rnd),
                Geo.destination(BH, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(0.0, 20_000.0)),
                directionDegrees = if (rnd.nextBoolean()) rnd.nextInt(0, 360).toDouble() else null,
                speedLimitKmh = if (rnd.nextBoolean()) rnd.nextInt(20, 120) else null,
            )
        }
        return b
    }

    @Test
    fun everythingSurvivesARoundTrip() {
        forEachCase(100) { seed, _ ->
            val b = randomNetwork(seed)
            val reader = RoadPackageReader(ByteArraySource(RoadPackage.write(b.edges, b.pois)))
            val loaded = reader.load(BH, 30_000.0)
            val byId = loaded.edges.associateBy { it.id }
            assertEquals(b.edges.size, loaded.edges.size, "seed=$seed")
            for (e in b.edges) {
                val r = byId.getValue(e.id)
                assertEquals(e.from, r.from)
                assertEquals(e.to, r.to)
                assertEquals(e.roadClass, r.roadClass)
                assertEquals(e.oneway, r.oneway)
                assertEquals(e.bridge, r.bridge)
                assertEquals(e.name, r.name, "seed=$seed")
                assertEquals(e.geometry.size, r.geometry.size)
                e.geometry.zip(r.geometry).forEach { (p, q) -> assertTrue(Geo.distance(p, q) < 0.2, "seed=$seed") }
                e.elevations.zip(r.elevations.toList()).forEach { (h, g) -> assertEquals(h, g, 0.051) }
            }
            assertEquals(b.pois.size, loaded.pois.size, "seed=$seed")
            b.pois.forEach { p ->
                val q = loaded.pois.minBy { Geo.distance(it.position, p.position) }
                assertEquals(p.type, q.type)
                assertEquals(p.directionDegrees, q.directionDegrees)
                assertEquals(p.speedLimitKmh, q.speedLimitKmh)
            }
        }
    }

    @Test
    fun onlyNearbyCellsAreLoaded() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 400.0), name = "Near")
        b.road(line(LatLon(-21.0, -45.0), 0.0, 400.0), name = "Far") // ~160 km away
        val reader = RoadPackageReader(ByteArraySource(RoadPackage.write(b.edges, emptyList())))
        assertEquals(2, reader.cellCount)
        assertEquals(listOf("Near"), reader.load(BH, 2_000.0).edges.map { it.name }.distinct())
    }

    @Test
    fun corruptFilesAreRejected() {
        val b = NetworkBuilder()
        b.road(line(BH, 0.0, 400.0))
        val bytes = RoadPackage.write(b.edges, emptyList())
        assertFailsWith<IllegalArgumentException> { RoadPackageReader(ByteArraySource(bytes.copyOf().also { it[0] = 0 })) }
        assertFailsWith<IllegalArgumentException> { RoadPackageReader(ByteArraySource(ByteArray(3))) }
        assertFailsWith<IllegalArgumentException> {
            RoadPackageReader(ByteArraySource(bytes.copyOf(bytes.size - 10))).load(BH, 1_000.0)
        }
    }

    @Test
    fun randomCorruptionIsRejectedCleanly() {
        val b = randomNetwork(7)
        val bytes = RoadPackage.write(b.edges, b.pois)
        forEachCase(2_000) { _, rnd ->
            val corrupted = bytes.copyOf()
            repeat(rnd.nextInt(1, 5)) { corrupted[rnd.nextInt(corrupted.size)] = rnd.nextInt(256).toByte() }
            try {
                RoadPackageReader(ByteArraySource(corrupted)).load(BH, 30_000.0)
            } catch (_: IllegalArgumentException) {
                // Rejected as corrupt: the only acceptable failure.
            }
        }
    }

    @Test
    fun verifyReadsTheWholeFile() {
        val b = randomNetwork(11)
        val bytes = RoadPackage.write(b.edges, b.pois)
        assertEquals(b.edges.size, RoadPackageReader(ByteArraySource(bytes)).verify())
        // Damage inside the last cell's data: the header still parses, only a full read notices.
        val damaged = bytes.copyOf().also { it[it.size - 3] = 0xFF.toByte(); it[it.size - 2] = 0xFF.toByte(); it[it.size - 1] = 0xFF.toByte() }
        val reader = RoadPackageReader(ByteArraySource(damaged))
        assertFailsWith<IllegalArgumentException> { reader.verify() }
    }

    @Test
    fun varintsRoundTrip() {
        val values = listOf(0L, 1L, -1L, 63L, -64L, 1L shl 40, -(1L shl 40), Long.MAX_VALUE / 2, Int.MIN_VALUE.toLong())
        val w = ByteWriter()
        values.forEach(w::signedVarint)
        w.string("Avenida Afonso Pena – ç ã é")
        val r = ByteReader(w.toByteArray())
        assertEquals(values, values.map { r.signedVarint() })
        assertEquals("Avenida Afonso Pena – ç ã é", r.string())
    }
}

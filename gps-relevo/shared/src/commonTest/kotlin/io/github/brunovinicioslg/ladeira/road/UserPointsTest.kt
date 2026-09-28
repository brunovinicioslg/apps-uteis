package io.github.brunovinicioslg.ladeira.road

import io.github.brunovinicioslg.ladeira.BH
import io.github.brunovinicioslg.ladeira.geo.Geo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserPointsTest {

    private val camera = Poi(PoiType.SPEED_CAMERA, BH, directionDegrees = 90.0, speedLimitKmh = 80)

    @Test
    fun aMarkReplacesTheSamePointOfTheMapButNotOthers() {
        val bump = Poi(PoiType.SPEED_BUMP, Geo.destination(BH, 0.0, 20.0))
        val mark = Poi(PoiType.SPEED_CAMERA, Geo.destination(BH, 0.0, 30.0), directionDegrees = 90.0, speedLimitKmh = 60)
        val far = Poi(PoiType.SPEED_CAMERA, Geo.destination(BH, 0.0, 300.0))
        assertEquals(listOf(bump, far, mark), UserPoints.merge(listOf(camera, bump, far), listOf(mark)))
        assertEquals(listOf(camera), UserPoints.merge(listOf(camera), emptyList()))
    }

    @Test
    fun geoJsonRoundTrip() {
        val points = listOf(
            UserPoint("a1", camera, createdAtMillis = 1_727_000_000_000, note = "Radar novo \"móvel\""),
            UserPoint("a2", Poi(PoiType.OTHER, Geo.destination(BH, 45.0, 500.0)), createdAtMillis = 5, needsType = true),
        )
        val text = PointsGeoJson.write(points)
        assertTrue(text.contains("\"FeatureCollection\""))
        val read = PointsGeoJson.read(text)
        assertEquals(points.map { it.id }, read.map { it.id })
        assertEquals(points[0].copy(poi = read[0].poi), read[0])
        assertEquals(BH.lat, read[0].poi.position.lat, 1e-6)
        assertEquals(90.0, read[0].poi.directionDegrees)
        assertEquals(80, read[0].poi.speedLimitKmh)
        assertTrue(read[1].needsType)
        assertNull(read[1].poi.directionDegrees)
    }

    @Test
    fun geoJsonFromOtherAppsIsReadLeniently() {
        val text = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-43.9,-19.9]},"properties":{"type":"pothole"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-43.9,-19.9]},"properties":null},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[200,95]}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-43.8,-19.8]},"properties":{"kind":"radar!","direction":-90,"maxspeed":999}}
            ]}
        """.trimIndent()
        val read = PointsGeoJson.read(text)
        assertEquals(listOf(PoiType.POTHOLE, PoiType.OTHER, PoiType.OTHER), read.map { it.poi.type })
        assertEquals(270.0, read[2].poi.directionDegrees)
        assertNull(read[2].poi.speedLimitKmh)
        // Without ids, the same file imported twice adds nothing.
        assertEquals(read, PointsGeoJson.read(text))
        assertFailsWith<IllegalArgumentException> { PointsGeoJson.read("não é json") }
        assertFailsWith<IllegalArgumentException> { PointsGeoJson.read("""{"type":"Point","coordinates":[0,0]}""") }
    }

    @Test
    fun importReplacesByIdAndSkipsDuplicatesFromOtherLists() {
        val mine = UserPoint("m1", camera, 1)
        val newer = mine.copy(poi = camera.copy(speedLimitKmh = 60), createdAtMillis = 2)
        val friendsSameCamera = UserPoint("f1", camera.copy(position = Geo.destination(BH, 0.0, 5.0)), 3)
        val friendsPothole = UserPoint("f2", Poi(PoiType.POTHOLE, BH), 4)
        val result = UserPoints.importInto(listOf(mine), listOf(newer, friendsSameCamera, friendsPothole))
        assertEquals(listOf("m1", "f2"), result.map { it.id })
        assertEquals(60, result[0].poi.speedLimitKmh)
    }
}

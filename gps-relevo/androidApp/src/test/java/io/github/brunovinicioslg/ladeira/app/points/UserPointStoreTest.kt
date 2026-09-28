package io.github.brunovinicioslg.ladeira.app.points

import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.UserPoint
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserPointStoreTest {

    private val dir = Files.createTempDirectory("points").toFile()
    private val file = File(dir, "points.geojson")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val pothole = UserPoint("a", Poi(PoiType.POTHOLE, LatLon(-19.9, -43.9), directionDegrees = 10.0), createdAtMillis = 1)

    @Test
    fun changesAreSavedAndSurviveARestart() {
        val store = UserPointStore(file).apply { load() }
        store.add(pothole)
        store.add(pothole.copy(id = "b", poi = pothole.poi.copy(type = PoiType.SPEED_BUMP, position = LatLon(-19.8, -43.8))))
        store.update(pothole.copy(note = "fundo"))
        store.delete("b")

        val again = UserPointStore(file).apply { load() }
        assertThat(again.points.value).containsExactly(pothole.copy(note = "fundo"))
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly("points.geojson")
    }

    @Test
    fun importAddsOnlyNewPointsAndRejectsOtherFiles() {
        val store = UserPointStore(file).apply { load() }
        store.add(pothole)
        val shared = store.export()
        assertThat(store.import(shared)).isEqualTo(0)
        val other = UserPointStore(File(dir, "other.geojson")).apply { load() }
        assertThat(other.import(shared)).isEqualTo(1)
        assertThat(other.points.value).containsExactly(pothole)
        assertThrows(IllegalArgumentException::class.java) { store.import("<gpx></gpx>") }
        assertThat(store.points.value).containsExactly(pothole)
    }

    @Test
    fun aDamagedFileIsSetAsideNotOverwritten() {
        file.writeText("{ not json")
        val store = UserPointStore(file).apply { load() }
        assertThat(store.points.value).isEmpty()
        assertThat(dir.listFiles()!!.any { it.name.startsWith("points.geojson.damaged-") }).isTrue()
        store.add(pothole)
        assertThat(UserPointStore(file).apply { load() }.points.value).containsExactly(pothole)
    }
}

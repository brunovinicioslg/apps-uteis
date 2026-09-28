package io.github.brunovinicioslg.ladeira.app.region

import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.ladeira.app.TestData
import io.github.brunovinicioslg.ladeira.geo.LatLon
import org.junit.Test

class PmTilesInfoTest {

    @Test
    fun readsBoundsZoomsAndCenter() {
        val info = PmTilesInfo.parse(TestData.pmtiles())!!
        assertThat(info.minLon).isWithin(1e-7).of(-44.02)
        assertThat(info.maxLat).isWithin(1e-7).of(-19.90)
        assertThat(info.maxZoom).isEqualTo(15)
        assertThat(info.center.lat).isWithin(1e-7).of(-19.99)
        assertThat(LatLon(-20.0, -43.95) in info).isTrue()
        assertThat(LatLon(-21.0, -43.95) in info).isFalse()
    }

    @Test
    fun anEmptyCenterFallsBackToTheMiddleOfTheBounds() {
        val info = PmTilesInfo.parse(TestData.pmtiles(center = null))!!
        assertThat(info.center.lat).isWithin(1e-6).of(-19.99)
        assertThat(info.center.lon).isWithin(1e-6).of(-43.94)
    }

    @Test
    fun rejectsOtherFiles() {
        assertThat(PmTilesInfo.parse(ByteArray(10))).isNull()
        assertThat(PmTilesInfo.parse(TestData.pmtiles(version = 2))).isNull()
        assertThat(PmTilesInfo.parse(TestData.pmtiles(tileType = 2))).isNull() // raster PNG tiles
        assertThat(PmTilesInfo.parse(TestData.pmtiles(minLat = -19.0, maxLat = -20.0))).isNull()
        assertThat(PmTilesInfo.parse("x".repeat(200).toByteArray())).isNull()
    }

    @Test
    fun truncatedFilesDoNotFit() {
        val file = TestData.pmtiles(dataBytes = 1_000)
        assertThat(PmTilesInfo.fitsIn(file, file.size.toLong())).isTrue()
        assertThat(PmTilesInfo.fitsIn(file, file.size - 1L)).isFalse()
    }
}

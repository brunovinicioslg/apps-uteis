package io.github.brunovinicioslg.ladeira.app.region

import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.ladeira.app.TestData
import io.github.brunovinicioslg.ladeira.geo.LatLon
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RegionStoreTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var root: File
    private lateinit var store: RegionStore

    @Before
    fun setUp() {
        root = folder.newFolder("regions")
        store = RegionStore(root)
    }

    private fun install(name: String, bytes: ByteArray) = store.install(name, bytes.inputStream())

    @Test
    fun bothFilesMakeAUsableRegion() {
        assertThat(install("BH Sul.ldrp", TestData.serraPackage())).isEqualTo(ImportResult.Imported("BH Sul.ldrp", "BH-Sul"))
        assertThat(install("BH Sul.pmtiles", TestData.pmtiles())).isInstanceOf(ImportResult.Imported::class.java)

        val region = store.regions.value.single()
        assertThat(region.name).isEqualTo("BH-Sul")
        assertThat(region.problems).isEmpty()
        assertThat(File(root, "BH-Sul/roads.ldrp").isFile).isTrue()
        assertThat(File(root, "BH-Sul/map.pmtiles").isFile).isTrue()
        val network = store.roadSource.networkAround(TestData.along(1_000.0), 3_000.0)
        assertThat(network!!.edges).isNotEmpty()
        assertThat(store.mapRegionFor(TestData.along(1_000.0))?.name).isEqualTo("BH-Sul")
        assertThat(store.mapRegionFor(null)?.name).isEqualTo("BH-Sul")
    }

    @Test
    fun aMissingFileIsReported() {
        install("serra.ldrp", TestData.serraPackage())
        assertThat(store.regions.value.single().problems).containsExactly(RegionProblem.MISSING_MAP)
        assertThat(store.mapRegionFor(null)).isNull()
    }

    @Test
    fun noRoadsOutsideTheInstalledArea() {
        install("serra.ldrp", TestData.serraPackage())
        assertThat(store.roadSource.networkAround(LatLon(-15.8, -47.9), 8_000.0)).isNull()
    }

    @Test
    fun badFilesNeverReplaceGoodOnes() {
        install("serra.ldrp", TestData.serraPackage())
        val corrupt = TestData.serraPackage().also {
            it[it.size - 1] = 0xFF.toByte()
            it[it.size - 2] = 0xFF.toByte()
        }
        assertThat(install("serra.ldrp", corrupt)).isEqualTo(ImportResult.Failed("serra.ldrp", ImportResult.Error.INVALID))
        assertThat(install("serra.ldrp", "hello".toByteArray())).isEqualTo(ImportResult.Failed("serra.ldrp", ImportResult.Error.INVALID))
        val truncatedMap = TestData.pmtiles().let { it.copyOf(it.size - 10) }
        assertThat(install("serra.pmtiles", truncatedMap)).isEqualTo(ImportResult.Failed("serra.pmtiles", ImportResult.Error.INVALID))

        assertThat(store.regions.value.single().problems).containsExactly(RegionProblem.MISSING_MAP)
        assertThat(store.roadSource.networkAround(TestData.along(500.0), 2_000.0)).isNotNull()
        assertThat(File(root, "serra").list()!!.toList()).containsExactly("roads.ldrp")
    }

    @Test
    fun otherFileTypesAreRejected() {
        assertThat(install("foto.jpg", ByteArray(10))).isEqualTo(ImportResult.Failed("foto.jpg", ImportResult.Error.UNKNOWN_TYPE))
        assertThat(install("sem-extensao", ByteArray(10))).isEqualTo(ImportResult.Failed("sem-extensao", ImportResult.Error.UNKNOWN_TYPE))
        assertThat(root.list()!!.toList()).isEmpty()
    }

    @Test
    fun aFailedCopyLeavesNothingBehind() {
        val failing = object : InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 1_000) 0 else throw IOException("card removed")
        }
        assertThat(store.install("serra.ldrp", failing)).isEqualTo(ImportResult.Failed("serra.ldrp", ImportResult.Error.IO))
        assertThat(root.list()!!.toList()).isEmpty()
    }

    @Test
    fun replacingThePackageKeepsTheRoadsAvailable() {
        install("serra.ldrp", TestData.serraPackage())
        val before = store.regions.value.single().roads
        install("serra.ldrp", TestData.serraPackage())
        val after = store.regions.value.single().roads
        assertThat(after).isNotSameInstanceAs(before)
        assertThat(store.roadSource.networkAround(TestData.along(500.0), 2_000.0)).isNotNull()
    }

    @Test
    fun deleteRemovesTheRegionAndStaysInsideTheFolder() {
        install("serra.ldrp", TestData.serraPackage())
        val outside = folder.newFile("keep.txt")
        store.delete("..")
        assertThat(outside.exists()).isTrue()
        assertThat(root.exists()).isTrue()

        store.delete("serra")
        assertThat(store.regions.value).isEmpty()
        assertThat(File(root, "serra").exists()).isFalse()
        assertThat(store.roadSource.networkAround(TestData.along(500.0), 2_000.0)).isNull()
    }

    @Test
    fun refreshFindsFilesAndCleansUpInterruptedImports() {
        val dir = File(root, "manual").apply { mkdirs() }
        File(dir, "roads.ldrp").writeBytes(TestData.serraPackage())
        File(dir, "map.pmtiles.part").writeBytes(ByteArray(5))
        File(root, "empty").mkdirs()
        File(root, "broken").apply { mkdirs() }.let { File(it, "map.pmtiles").writeBytes(ByteArray(200)) }

        store.refresh()
        val regions = store.regions.value.associateBy { it.name }
        assertThat(regions.keys).containsExactly("broken", "manual")
        assertThat(regions.getValue("broken").problems).containsExactly(RegionProblem.MISSING_ROADS, RegionProblem.INVALID_MAP)
        assertThat(File(dir, "map.pmtiles.part").exists()).isFalse()
    }

    @Test
    fun regionNamesAreSafeFolderNames() {
        assertThat(RegionStore.regionNameFor("BH Sul (1).pmtiles")).isEqualTo("BH-Sul-1")
        assertThat(RegionStore.regionNameFor("minas_gerais.ldrp")).isEqualTo("minas_gerais")
        assertThat(RegionStore.regionNameFor("São João del-Rei.ldrp")).isEqualTo("São-João-del-Rei")
        assertThat(RegionStore.regionNameFor("../../etc.ldrp")).isEqualTo("etc")
        assertThat(RegionStore.regionNameFor("....ldrp")).isEqualTo("regiao")
        assertThat(RegionStore.regionNameFor("a".repeat(200) + ".ldrp")).hasLength(60)
    }
}

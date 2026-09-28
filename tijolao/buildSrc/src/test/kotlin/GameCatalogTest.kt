import java.io.File
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameCatalogTest {

    private val dir = Files.createTempDirectory("games").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)

    /** A J2ME jar with the given MIDlet manifest attributes. */
    private fun jar(folder: String, name: String, attrs: Map<String, String> = mapOf("MIDlet-1" to "Jogo, /icon.png, Main"), icon: Boolean = true, bytes: Int = 0): File {
        val file = File(File(dir, folder).apply { mkdirs() }, name)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            attrs.forEach { (k, v) -> mainAttributes.putValue(k, v) }
        }
        JarOutputStream(file.outputStream(), manifest).use { out ->
            if (icon) {
                out.putNextEntry(ZipEntry("icon.png"))
                out.write(png)
            }
            out.putNextEntry(ZipEntry("Main.class"))
            out.write(ByteArray(bytes))
        }
        return file
    }

    @Test
    fun titlesReadLikeTheGame() {
        assertEquals("Asphalt: Urban GT 2", GameCatalog.titleOf("Asphalt_ Urban GT 2 128x160 [BR].jar"))
        assertEquals("Doom 2 RPG", GameCatalog.titleOf("Doom 2 RPG Multiresolução [BR].jar"))
        assertEquals("Blades e Magic 3D", GameCatalog.titleOf("Blades e Magic 128x160 3D [BR].jar"))
        assertEquals("E.T.", GameCatalog.titleOf("E.T. 128x160 [BR].jar"))
        assertEquals(GameCatalog.keyOf("Midnight Pool 3D"), GameCatalog.keyOf("Midnight Pool"))
        assertEquals(GameCatalog.keyOf("Bob Esponja: Perseguição"), GameCatalog.keyOf("Bob Esponja_ Perseguicão"))
        assertEquals("batman-o-cavaleiro-das-trevas", GameCatalog.slugOf("Batman: O Cavaleiro das Trevas"))
        // The size tag in any case.
        assertEquals("Driver: San Francisco", GameCatalog.titleOf("Driver_ San Francisco 240X320 [BR].jar"))
        assertEquals("240x320", GameCatalog.resolutionOf(File("Driver_ San Francisco 240X320 [BR].jar")))
    }

    @Test
    fun oneEntryPerGameWithTheSharpestPortraitVersion() {
        jar("JOGOS 128x160", "Asphalt_ Urban GT 2 128x160 [BR].jar")
        jar("JOGOS 240x320", "Asphalt_ Urban GT 2 240x320 [BR].jar")
        jar("JOGOS 320x240", "Asphalt_ Urban GT 2 320x240 [BR].jar")
        jar("JOGOS 320x240", "Pinball 320x240 [BR].jar")
        jar("JOGOS 176x220", "Tetris Multiresolução [BR].jar")
        jar("JOGOS 176x220", "Tetris 176x220 [BR].jar")

        val games = GameCatalog.scan(dir).associateBy { it.title }
        assertEquals(setOf("Asphalt: Urban GT 2", "Pinball", "Tetris"), games.keys)
        val asphalt = games.getValue("Asphalt: Urban GT 2")
        assertEquals("240x320", asphalt.resolution)
        assertEquals(listOf("128x160", "320x240"), asphalt.otherResolutions.sorted())
        assertFalse(asphalt.landscape)
        // Only a landscape version exists: kept, and opened sideways.
        val pinball = games.getValue("Pinball")
        assertTrue(pinball.landscape)
        assertEquals(320 to 240, pinball.screenWidth to pinball.screenHeight)
        // A multi-resolution build adapts to any screen, so it beats a small fixed one.
        assertEquals("multi", games.getValue("Tetris").resolution)
        assertEquals(240 to 320, games.getValue("Tetris").let { it.screenWidth to it.screenHeight })
    }

    @Test
    fun brokenJarsAreSkippedOrReplacedByAnotherVersion() {
        File(dir, "JOGOS 240x320").mkdirs()
        File(dir, "JOGOS 240x320/Quebrado 240x320 [BR].jar").writeText("isto não é um jar")
        jar("JOGOS 176x220", "Quebrado 176x220 [BR].jar")
        File(dir, "JOGOS 240x320/Sozinho 240x320 [BR].jar").writeText("também não")
        jar("JOGOS 240x320", "Sem Midlet 240x320 [BR].jar", attrs = mapOf("Main-Class" to "Desktop"))

        val games = GameCatalog.scan(dir)
        assertEquals(listOf("Quebrado"), games.map { it.title })
        assertEquals("176x220", games.single().resolution)
    }

    @Test
    fun iconsComeFromTheManifestAndMustBePng() {
        val withIcon = jar("J 240x320", "Com Icone 240x320 [BR].jar", attrs = mapOf("MIDlet-1" to "Jogo, /icon.png, Main", "MIDlet-Vendor" to "Gameloft"))
        val game = GameCatalog.scan(dir).single()
        assertEquals("icon.png", game.iconPath)
        assertEquals("Gameloft", game.vendor)
        assertTrue(GameCatalog.readIcon(withIcon, "icon.png")!!.isNotEmpty())
        assertNull(GameCatalog.readIcon(withIcon, "falta.png"))
        assertEquals("i.png", GameCatalog.iconPathOf(mapOf("MIDlet-Icon" to "/i.png", "MIDlet-1" to "A, /b.png, C")))
        assertNull(GameCatalog.iconPathOf(mapOf("MIDlet-1" to "A, , C")))
    }

    @Test
    fun manifestsAreReadAsLooselyAsPhonesDid() {
        // Byte-order mark, a header name Java rejects, CRLF, a continuation line, a repeated name.
        val text = "﻿Manifest-Version: 1.0\r\nmpower.provider.username: x\r\nMIDlet-1: Jogo, /i.png, \r\n Main\r\n" +
            "MIDlet-Vendor: Um\r\nMIDlet-Vendor: Dois\r\nsem dois pontos\r\n"
        val m = GameCatalog.parseManifest(text.toByteArray())
        assertEquals("Jogo, /i.png, Main", m["MIDlet-1"])
        assertEquals("Um", m["MIDlet-Vendor"])
        assertEquals("x", m["mpower.provider.username"])
        assertEquals("1.0", m["Manifest-Version"])
    }

    @Test
    fun aJarWithABrokenIndexIsReadAndRepaired() {
        val good = jar("J 240x320", "Indice Quebrado 240x320 [BR].jar", attrs = mapOf("MIDlet-1" to "Jogo, /icon.png, Main", "MIDlet-Name" to "Jogo"))
        // Break the central directory signatures: strict readers fail, entry-by-entry reading still works.
        val bytes = good.readBytes()
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) bytes[i + 2] = 9
        }
        good.writeBytes(bytes)
        assertFalse(GameCatalog.isCleanZip(good))
        assertEquals("Jogo", GameCatalog.readManifest(good)!!["MIDlet-Name"])
        assertTrue(GameCatalog.readIcon(good, "icon.png") != null)

        val fixed = File(dir, "consertado.jar")
        assertTrue(GameCatalog.repack(good, fixed))
        assertTrue(GameCatalog.isCleanZip(fixed))
        assertEquals("Jogo", GameCatalog.readManifest(fixed)!!["MIDlet-Name"])
        assertFalse(GameCatalog.repack(File(dir, "J 240x320").resolve("nao-existe.jar"), File(dir, "x.jar")))
    }

    @Test
    fun catalogJsonIsValidAndEscaped() {
        jar("J 240x320", "Pac's Aventura Mágica 240x320 [BR].jar")
        val json = GameCatalog.toJson(GameCatalog.scan(dir), emptySet())
        assertTrue(json.contains("\"title\":\"Pac's Aventura Mágica\""), json)
        // Quotes, backslashes and control characters (Windows file names cannot hold them).
        assertEquals("\"a\\\"b\\\\c\\u000ad\"", GameCatalog.quote("a\"b\\c\nd"))
        assertTrue(json.trim().startsWith("[") && json.trim().endsWith("]"))
        assertEquals("[\n]\n", GameCatalog.toJson(emptyList(), emptySet()))
    }

    @Test
    fun renamedCopiesOfOneGameAreMergedButEditionsAreNot() {
        fun game(name: String, vendor: String, main: String = "Main") =
            mapOf("MIDlet-1" to "$name, /icon.png, $main", "MIDlet-Name" to name, "MIDlet-Vendor" to vendor)
        // The sharpest version is misspelled: the title comes from the other one, closer to the manifest's.
        // A re-packed edition renamed itself, but kept its main class.
        jar("JOGOS 240x320", "A Lenda de Beowlf 240x320 [BR].jar", game("Beowulf by fulano", "Gameloft by fulano", "GloftBEOW"))
        jar("JOGOS 128x160", "A Lenda De Beowulf 128x160 [BR].jar", game("Beowulf", "Gameloft", "GloftBEOW"))
        // A main class used by several titles is an engine: it proves nothing.
        jar("JOGOS 240x320", "Tetris Deluxe 240x320 [BR].jar", game("TD", "EA", "com.ea.sdk.SDKMIDlet"))
        jar("JOGOS 240x320", "Tetris Deluxo 240x320 [BR].jar", game("TX", "EA", "com.ea.sdk.SDKMIDlet"))
        jar("JOGOS 240x320", "Tetris Deluxa 240x320 [BR].jar", game("TY", "EA", "com.ea.sdk.SDKMIDlet"))
        val asphalt = game("Asphalt 2", "Gameloft")
        jar("JOGOS 128x160", "Asphalt_ Urban GT 2 128x160 [BR].jar", asphalt)
        jar("JOGOS 240x320", "Asphalt 2_ Urban GT 240x320 [BR].jar", asphalt)
        // Other editions sharing the manifest name: a year, 2D/3D.
        val tennis = game("Pro Tennis", "Gameloft")
        jar("JOGOS 240x320", "Pro Tênis 2015 240x320 [BR].jar", tennis)
        jar("JOGOS 240x320", "Pro Tênis 2021 240x320 [BR].jar", tennis)
        val blades = game("Blades", "Gameloft")
        jar("JOGOS 128x160", "Blades e Magic 128x160 3D [BR].jar", blades)
        jar("JOGOS 240x320", "Blades e Magic 2D 240x320 [BR].jar", blades)
        // A translated title is too different to be sure.
        val spider = game("Spider-Man", "Gameloft")
        jar("JOGOS 240x320", "Homem Aranha 240x320 [BR].jar", spider)
        jar("JOGOS 240x320", "Spider Man 240x320 [BR].jar", spider)
        // Similar titles, but the jars say they are different games.
        jar("JOGOS 240x320", "Jhonny Bravo 240x320 [BR].jar", game("Johnny Bravo", "Glu"))
        jar("JOGOS 176x220", "Johnny Bravo 176x220 [BR].jar", game("Johnny Bravo", "Outra"))

        val merged = mutableListOf<String>()
        val games = GameCatalog.scan(dir) { if (it.startsWith("Same game")) merged += it }.associateBy { it.title }
        assertEquals(
            setOf(
                "A Lenda De Beowulf", "Asphalt 2: Urban GT", "Pro Tênis 2015", "Pro Tênis 2021", "Blades e Magic 3D",
                "Blades e Magic 2D", "Homem Aranha", "Spider Man", "Jhonny Bravo", "Johnny Bravo",
                "Tetris Deluxe", "Tetris Deluxo", "Tetris Deluxa",
            ),
            games.keys,
        )
        assertEquals("240x320", games.getValue("A Lenda De Beowulf").resolution)
        assertEquals(listOf("128x160"), games.getValue("A Lenda De Beowulf").otherResolutions)
        assertEquals(listOf("128x160"), games.getValue("Asphalt 2: Urban GT").otherResolutions)
        assertEquals(2, merged.size, "$merged")

        assertTrue(GameCatalog.sameGameTitles("Diamond Island", "Diamond Islands"))
        assertTrue(GameCatalog.sameGameTitles("Might and Magic II", "Might and Magic 2"))
        assertFalse(GameCatalog.sameGameTitles("Might and Magic II", "Might and Magic 3"))
        assertFalse(GameCatalog.sameGameTitles("Nitro Street Racing 2", "Nitro Street Racing Touch"))
        assertFalse(GameCatalog.sameGameTitles("Stranded 2", "Stranded 2D"))
        assertTrue(GameCatalog.sameGameTitles("Pica Pau: Cataratas", "Pica Pau nas Cataratas"))
        assertTrue(GameCatalog.sameGameTitles("Jhonny Bravo", "Johnny Bravo"))
        assertTrue(GameCatalog.sameGameTitles("Topa ou Não Topa", "Topa ou No Topa"))
        // Long titles look alike overall; a whole different word still makes another game.
        assertFalse(GameCatalog.sameGameTitles("Aventura Muito Longa Primeira", "Aventura Muito Longa Segunda"))
        assertFalse(GameCatalog.sameGameTitles("The Sims 3: Ambitions", "The Sims 3: World Adventures"))
    }

    @Test
    fun sameTitleNamesGetDistinctIds() {
        // Ids are cut at 60 characters: two long titles differing only at the end must not clash.
        val prefix = "Aventura " + "muito ".repeat(10)
        jar("A 240x320", "${prefix}Primeira 240x320 [BR].jar")
        jar("A 240x320", "${prefix}Segunda 240x320 [BR].jar")
        val ids = GameCatalog.scan(dir).map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size, "$ids")
    }
}

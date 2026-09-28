import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Turns a folder of J2ME games into the catalog embedded in Tijolão. The same game often comes in
 * several screen sizes ("Asphalt 128x160 [BR].jar", "Asphalt 240x320 [BR].jar"...): they are
 * grouped by title and the version that looks best on a modern phone is kept.
 */
object GameCatalog {

    private const val MANIFEST = "META-INF/MANIFEST.MF"

    /**
     * Phones of the time wrote file names inside jars in their own encoding, often not UTF-8.
     * Latin-1 decodes any byte, so reading never fails; ASCII names (all the ones looked up here)
     * come out the same.
     */
    private val LEGACY_NAMES = Charsets.ISO_8859_1

    /** Best first: the sharpest portrait version; landscape ones only when nothing else exists. */
    val RESOLUTION_PREFERENCE = listOf("240x320", "multi", "176x220", "128x160", "320x240")

    data class Variant(val file: File, val resolution: String)

    data class Game(
        val id: String,
        val title: String,
        val file: File,
        val resolution: String,
        val vendor: String?,
        val midletName: String?,
        val iconPath: String?,
        val otherResolutions: List<String>,
    ) {
        val landscape: Boolean get() = resolution == "320x240"
        val screenWidth: Int get() = sizeOf(resolution).first
        val screenHeight: Int get() = sizeOf(resolution).second
    }

    /** Width and height the emulator should report; multi-resolution games get the common 240x320. */
    fun sizeOf(resolution: String): Pair<Int, Int> {
        val m = Regex("""(\d+)x(\d+)""").matchEntire(resolution) ?: return 240 to 320
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** "Asphalt_ Urban GT 2 128x160 [BR].jar" -> "Asphalt: Urban GT 2". */
    fun titleOf(fileName: String): String {
        var t = fileName.removeSuffix(".jar").removeSuffix(".JAR")
        t = t.replace(Regex("""\[[A-Za-z]{2}]"""), " ")
        t = t.replace(Regex("""(?i)\b\d{3}x\d{3}\b"""), " ")
        t = t.replace(Regex("""(?i)multi-?resolu[çc][ãa]o"""), " ")
        t = t.replace("_ ", ": ").replace('_', ' ')
        return t.replace(Regex("""\s+"""), " ").trim(' ', '-')
    }

    /** Titles that differ only in case, accents, punctuation or a "3D" tag are the same game. */
    fun keyOf(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "").lowercase()
        return ascii.replace(Regex("""\b3d\b"""), " ").replace(Regex("""[^a-z0-9]"""), "")
    }

    fun resolutionOf(file: File): String {
        val name = file.name
        Regex("""(?i)\b(\d{3}x\d{3})\b""").find(name)?.let { return it.groupValues[1].lowercase() }
        if (Regex("""(?i)multi-?resolu""").containsMatchIn(name)) return "multi"
        // Otherwise the folder tells ("... RESOLUÇÃO 176x220").
        Regex("""(\d{3}x\d{3})""").find(file.parentFile?.name.orEmpty())?.let { return it.groupValues[1] }
        return "multi"
    }

    private fun rank(resolution: String) = RESOLUTION_PREFERENCE.indexOf(resolution).let { if (it < 0) RESOLUTION_PREFERENCE.size else it }

    /** Every game in [dir] (searched recursively), one entry per title, sorted by title. */
    fun scan(dir: File, log: (String) -> Unit = {}): List<Game> {
        val jars = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }.sortedBy { it.path }.toList()
        val groups = LinkedHashMap<String, MutableList<Variant>>()
        for (jar in jars) {
            val key = keyOf(titleOf(jar.name))
            if (key.isEmpty()) continue
            groups.getOrPut(key) { mutableListOf() } += Variant(jar, resolutionOf(jar))
        }
        val usedIds = HashSet<String>()
        return mergeRenamed(groups.values.toList(), log).mapNotNull { variants ->
            // Best resolution first; among equals, the bigger file (usually the complete one).
            val ordered = variants.sortedWith(compareBy<Variant>({ rank(it.resolution) }, { -it.file.length() }))
            val chosen = ordered.firstOrNull { readManifest(it.file, log) != null } ?: run {
                log("Skipped (no valid jar): ${variants.first().file.name}")
                return@mapNotNull null
            }
            val manifest = readManifest(chosen.file, log)!!
            val title = bestTitle(variants, titleOf(chosen.file.name), manifest["MIDlet-Name"])
            Game(
                id = uniqueId(slugOf(title), usedIds),
                title = title,
                file = chosen.file,
                resolution = chosen.resolution,
                vendor = manifest["MIDlet-Vendor"],
                midletName = manifest["MIDlet-Name"],
                iconPath = iconPathOf(manifest),
                otherResolutions = ordered.map { it.resolution }.filter { it != chosen.resolution }.distinct(),
            )
        }.sortedBy { keyOf(it.title) }
    }

    /**
     * The same game is sometimes named differently in each folder ("Jhonny Bravo" / "Johnny Bravo",
     * "Asphalt 2: Urban GT" / "Asphalt: Urban GT 2"). Groups are merged when their jars say they are
     * the same game (MIDlet-Name and vendor) and the titles differ only in spelling or word order.
     * Never when a number, "2D"/"3D" or "Touch" differs: those are other editions, and merging would
     * drop one of them.
     */
    fun mergeRenamed(groups: List<MutableList<Variant>>, log: (String) -> Unit = {}): List<MutableList<Variant>> {
        val claimed = groups.map { group ->
            group.flatMap { v -> readManifest(v.file)?.let(::identitiesOf).orEmpty() }.toSet()
        }
        // A main class shared by many titles is an engine or an emulator (EA's SDKMIDlet, MeBoy), not a game.
        val titlesPerClass = claimed.flatten().filter { it.startsWith(CLASS) }.groupingBy { it }.eachCount()
        val identities = claimed.map { ids -> ids.filter { !it.startsWith(CLASS) || titlesPerClass.getValue(it) <= 2 }.toSet() }
        val titles = groups.map { titleOf(it.first().file.name) }
        val parent = IntArray(groups.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in groups.indices) for (j in i + 1 until groups.size) {
            if (identities[i].intersect(identities[j]).isEmpty()) continue
            if (!sameGameTitles(titles[i], titles[j])) continue
            val a = find(i)
            val b = find(j)
            if (a != b) {
                parent[b] = a
                log("Same game: ${titles[i]} = ${titles[j]}")
            }
        }
        return groups.indices.groupBy(::find).values.map { members -> members.flatMap { groups[it] }.toMutableList() }
    }

    /**
     * Among the spellings of one game's files, the one closest to the name the jar gives itself
     * ("Requiem" for "AVP-Requiem", not "Requien"); on a tie, the chosen version's.
     */
    private fun bestTitle(variants: List<Variant>, chosen: String, midletName: String?): String {
        val name = keyOf(midletName.orEmpty())
        if (name.isEmpty()) return chosen
        return variants.map { titleOf(it.file.name) }.distinct()
            .maxWithOrNull(compareBy<String> { commonLetters(keyOf(it), name) }.thenBy { it == chosen }) ?: chosen
    }

    /** Length of the longest common subsequence. */
    private fun commonLetters(a: String, b: String): Int {
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            for (j in 1..b.length) cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * What a jar says it is: its name and vendor, and its main class. Re-packed editions often
     * change the name ("Beowulf by ...") but keep the class (Gameloft's "GloftBEOW").
     */
    private fun identitiesOf(manifest: Map<String, String>): List<String> {
        val ids = ArrayList<String>(2)
        val name = keyOf(manifest["MIDlet-Name"].orEmpty())
        if (name.isNotEmpty()) ids += "name:" + name + "|" + keyOf(manifest["MIDlet-Vendor"].orEmpty())
        val mainClass = manifest["MIDlet-1"]?.split(',')?.getOrNull(2)?.trim().orEmpty()
        if (mainClass.isNotEmpty()) ids += CLASS + mainClass
        return ids
    }

    private const val CLASS = "class:"

    private val EDITION_WORDS = setOf("2d", "3d", "touch", "hd", "teclado")

    /** Words that come and go between spellings of one title ("Pica Pau nas Cataratas"). */
    private val LITTLE_WORDS = setOf("a", "as", "o", "os", "e", "de", "do", "da", "dos", "das", "em", "na", "nas", "no", "nos", "the", "of", "and", "in", "las", "los", "el", "la")

    /**
     * Whether two titles name the same game: the same words in any order, where a word may be
     * misspelled ("Beowlf") or a little word added ("nas"). A different number or edition word
     * ("2", "2D", "Touch") always means another game.
     */
    fun sameGameTitles(a: String, b: String): Boolean {
        val ta = wordsOf(a)
        val tb = wordsOf(b)
        val onlyA = (ta - tb).toMutableList()
        val onlyB = (tb - ta).toMutableList()
        if ((onlyA + onlyB).any { w -> w.any(Char::isDigit) || w in EDITION_WORDS }) return false
        for (w in onlyA.toList()) {
            val partner = onlyB.maxByOrNull { similarity(w, it) } ?: break
            if (similarity(w, partner) < SAME_WORD) continue
            onlyA.remove(w)
            onlyB.remove(partner)
        }
        return (onlyA + onlyB).all { it in LITTLE_WORDS }
    }

    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4")

    /** The title's words, accents dropped and "II" read as "2". */
    private fun wordsOf(title: String): Set<String> =
        Regex("[a-z0-9]+").findAll(Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "").lowercase())
            .map { ROMAN[it.value] ?: it.value }.toSet()

    /**
     * 1 minus the edit distance over the longer length: 1 for equal strings. A swap of two
     * neighbours counts as one edit ("Jhonny" / "Johnny").
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    private const val SAME_WORD = 0.6

    fun slugOf(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "").lowercase()
        return ascii.replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(60).trim('-').ifEmpty { "jogo" }
    }

    private fun uniqueId(base: String, used: MutableSet<String>): String {
        var id = base
        var n = 2
        while (!used.add(id)) id = "$base-${n++}"
        return id
    }

    /** The jar's MIDlet attributes, or null when it is not a readable J2ME game. */
    fun readManifest(file: File, log: (String) -> Unit = {}): Map<String, String>? {
        val bytes = readEntry(file, MANIFEST) ?: run {
            log("No readable manifest in ${file.name}")
            return null
        }
        val manifest = parseManifest(bytes)
        return manifest.takeIf { m -> m.keys.any { it.startsWith("MIDlet-1") } }
    }

    /**
     * Phones read manifests loosely, and many games rely on it: a byte-order mark at the start,
     * header names Java rejects, lines longer than 72 bytes, missing final newline. This reads
     * them the same way. The first value of a repeated name wins.
     */
    fun parseManifest(bytes: ByteArray): Map<String, String> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val lines = mutableListOf<String>()
        for (raw in text.split(Regex("""\r\n|\n|\r"""))) {
            if (raw.startsWith(" ") && lines.isNotEmpty()) lines[lines.lastIndex] += raw.substring(1) else lines += raw
        }
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            if (key.isNotEmpty() && key !in map) map[key] = line.substring(colon + 1).trim()
        }
        return map
    }

    /** Whether Java (and so the build) can read the jar's index as it is. */
    fun isCleanZip(file: File): Boolean = try {
        ZipFile(file).use { zip -> zip.entries().asSequence().count() > 0 }
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * Rewrites a jar whose central directory is damaged, from its entries read one by one: the
     * same files, in a clean zip every reader accepts. False when even that fails.
     */
    fun repack(file: File, dest: File): Boolean = try {
        var count = 0
        ZipInputStream(file.inputStream().buffered(), LEGACY_NAMES).use { input ->
            ZipOutputStream(dest.outputStream().buffered()).use { output ->
                val seen = HashSet<String>()
                while (true) {
                    val entry = try {
                        input.nextEntry
                    } catch (_: ZipException) {
                        null
                    } ?: break
                    if (entry.isDirectory || !seen.add(entry.name)) continue
                    output.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(output)
                    output.closeEntry()
                    count++
                }
            }
        }
        count > 0 && readManifest(dest) != null
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false // names that cannot be written back
    }

    /** One entry's bytes; falls back to reading entry by entry when the zip index is damaged. */
    fun readEntry(file: File, name: String): ByteArray? {
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(name) ?: zip.entries().asSequence().firstOrNull { it.name.equals(name, ignoreCase = true) }
                return entry?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
            }
        } catch (_: IOException) {
            // Damaged central directory: the local entries are usually fine.
        } catch (_: IllegalArgumentException) {
            // Entry names in an old non-UTF-8 encoding.
        }
        return try {
            ZipInputStream(file.inputStream().buffered(), LEGACY_NAMES).use { input ->
                while (true) {
                    val entry = try {
                        input.nextEntry
                    } catch (_: ZipException) {
                        null
                    } ?: return null
                    if (entry.name.equals(name, ignoreCase = true)) return input.readBytes()
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** MIDlet-Icon, or the icon of the first MIDlet ("MIDlet-1: Name, /icon.png, Main"). */
    fun iconPathOf(manifest: Map<String, String>): String? {
        manifest["MIDlet-Icon"]?.takeIf { it.isNotBlank() }?.let { return it.trim().removePrefix("/") }
        val midlet1 = manifest["MIDlet-1"] ?: return null
        return midlet1.split(',').getOrNull(1)?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() }
    }

    /** The icon bytes when they are a real PNG (some jars point at missing or non-PNG files). */
    fun readIcon(file: File, path: String): ByteArray? =
        readEntry(file, path)?.takeIf { it.size > 8 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte() }

    fun toJson(games: List<Game>, iconIds: Set<String>): String = buildString {
        append("[\n")
        games.forEachIndexed { i, g ->
            append("  {")
            append("\"id\":").append(quote(g.id))
            append(",\"title\":").append(quote(g.title))
            append(",\"vendor\":").append(g.vendor?.let(::quote) ?: "null")
            append(",\"resolution\":").append(quote(g.resolution))
            append(",\"width\":").append(g.screenWidth)
            append(",\"height\":").append(g.screenHeight)
            append(",\"landscape\":").append(g.landscape)
            append(",\"size\":").append(g.file.length())
            append(",\"icon\":").append(g.id in iconIds)
            append("}")
            append(if (i < games.size - 1) ",\n" else "\n")
        }
        append("]\n")
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

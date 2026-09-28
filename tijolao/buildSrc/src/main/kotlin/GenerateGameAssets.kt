import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Builds the app's embedded game library from a folder of .jar files: assets/games/<id>.jar,
 * assets/icons/<id>.png and assets/catalog.json. Without the folder the app ships with no games.
 */
@CacheableTask
abstract class GenerateGameAssets : DefaultTask() {

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gamesDir: DirectoryProperty

    /** Only the first N titles (alphabetically): small APKs for testing. 0 = all. */
    @get:Input
    abstract val limit: Property<Int>

    /** Only titles containing one of these comma-separated words (for testing given games). Empty = all. */
    @get:Input
    abstract val only: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val gamesOut = File(out, "games").apply { mkdirs() }
        val iconsOut = File(out, "icons").apply { mkdirs() }
        val source = gamesDir.orNull?.asFile
        var games = if (source != null && source.isDirectory) GameCatalog.scan(source) { if (it.startsWith("Same game")) logger.info(it) else logger.warn(it) } else emptyList()
        val wanted = only.get().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (wanted.isNotEmpty()) games = games.filter { game -> wanted.any { it in game.title.lowercase() } }
        if (limit.get() > 0) games = games.take(limit.get())

        val withIcon = HashSet<String>()
        var repacked = 0
        games = games.filter { game ->
            val target = File(gamesOut, "${game.id}.jar")
            when {
                GameCatalog.isCleanZip(game.file) -> game.file.copyTo(target, overwrite = true)
                GameCatalog.repack(game.file, target) -> repacked++
                else -> {
                    logger.warn("Dropped (unreadable): ${game.file.name}")
                    target.delete()
                    return@filter false
                }
            }
            true
        }
        for (game in games) {
            val icon = game.iconPath?.let { GameCatalog.readIcon(game.file, it) }
            if (icon != null) {
                File(iconsOut, "${game.id}.png").writeBytes(icon)
                withIcon += game.id
            }
        }
        File(out, "catalog.json").writeText(GameCatalog.toJson(games, withIcon))
        val megabytes = games.sumOf { it.file.length() } / 1_000_000
        logger.lifecycle("Tijolão: ${games.size} games embedded ($megabytes MB), ${withIcon.size} with icons, $repacked repaired")
    }
}

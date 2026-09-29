package io.github.brunovinicioslg.tijolao

import android.content.Context
import com.android.dx.command.dexer.Main
import java.io.File
import java.io.IOException
import javax.microedition.lcdui.keyboard.VirtualKeyboard
import net.lingala.zip4j.ZipFile
import ru.playsoftware.j2meloader.config.Config
import ru.playsoftware.j2meloader.config.ProfileModel
import ru.playsoftware.j2meloader.config.ProfilesManager
import ru.woesss.j2me.jar.Descriptor

/**
 * Prepares an embedded game to run: the first time, its jar is converted to Android code (as the
 * emulator does for any installed game) and given settings that fit it (its screen size, and
 * landscape when it is a landscape game). Later calls return at once.
 */
object GameInstaller {

    /** Where the converted game lives; the emulator is started with this path. */
    fun appDir(game: Game): File = File(Config.getAppDir(), game.id)

    fun isReady(game: Game): Boolean {
        val dir = appDir(game)
        val marker = File(dir, MARKER)
        return File(dir, Config.MIDLET_DEX_ARCH).isFile && File(dir, Config.MIDLET_MANIFEST_FILE).isFile &&
            marker.isFile && marker.readText().trim() == game.size.toString()
    }

    /** Blocking (seconds on first run): call off the main thread. Returns the game's path. */
    @Throws(IOException::class)
    fun prepare(context: Context, game: Game): String {
        if (!isReady(game)) install(context, game)
        ensureConfig(game)
        return appDir(game).absolutePath
    }

    private fun install(context: Context, game: Game) {
        val cache = File(context.cacheDir, "tijolao").apply { mkdirs() }
        val jar = File(cache, "${game.id}.jar")
        context.assets.open(game.jarAsset).use { input -> jar.outputStream().use { input.copyTo(it) } }
        val appsDir = File(Config.getAppDir()).apply { mkdirs() }
        val tmp = File(appsDir, ".tmp-${game.id}")
        try {
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) throw IOException("Cannot create $tmp")
            try {
                Main.main(arrayOf("--no-optimize", "--output=" + tmp + Config.MIDLET_DEX_ARCH, jar.absolutePath))
            } catch (e: Throwable) {
                throw IOException("Converting ${game.id} failed", e)
            }
            jar.copyTo(File(tmp, Config.MIDLET_RES_FILE), overwrite = true)
            val descriptor = Descriptor(readManifest(jar), false)
            descriptor.icon?.let { icon -> extract(jar, icon, File(tmp, Config.MIDLET_ICON_FILE)) }
            descriptor.writeTo(File(tmp, Config.MIDLET_MANIFEST_FILE))
            File(tmp, MARKER).writeText(game.size.toString())
            val target = appDir(game)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IOException("Cannot move $tmp to $target")
        } finally {
            tmp.deleteRecursively()
            jar.delete()
        }
    }

    /** First run only: the user may change them later in the game's settings. */
    private fun ensureConfig(game: Game) {
        val dir = File(Config.getConfigsDir(), game.id)
        if (File(dir, Config.MIDLET_CONFIG_FILE).isFile) {
            moveToClassicKeypad(dir)
            return
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val profile = ProfileModel(dir) // with the classic keypad
        profile.screenWidth = game.width
        profile.screenHeight = game.height
        profile.orientation = if (game.landscape) ORIENTATION_LANDSCAPE else ORIENTATION_PORTRAIT
        if (!ProfilesManager.saveConfig(profile)) throw IOException("Cannot save the settings of ${game.id}")
        File(dir, KEYPAD_MARKER).writeText(KEYPAD_VERSION)
    }

    /**
     * Games first played before the classic keypad existed move to it, once. A keypad the user
     * picked stays: one chosen in the game's menu is kept in its own file, which wins over this
     * setting, and the old default (0) is the only value moved.
     */
    private fun moveToClassicKeypad(dir: File) {
        val marker = File(dir, KEYPAD_MARKER)
        if (marker.isFile) return
        val profile = ProfilesManager.loadConfig(dir) ?: return
        if (profile.vkType == VirtualKeyboard.TYPE_CUSTOM) {
            profile.vkType = VirtualKeyboard.TYPE_CLASSIC
            profile.vkFeedback = true
            if (!ProfilesManager.saveConfig(profile)) return // tried again next time
        }
        marker.writeText(KEYPAD_VERSION)
    }

    /** Deletes the game's saves (records written by the game), keeping the game itself. */
    fun deleteProgress(game: Game) {
        File(Config.getDataDir(), game.id).deleteRecursively()
    }

    private fun readManifest(jar: File): String {
        ZipFile(jar).use { zip ->
            val header = zip.getFileHeader("META-INF/MANIFEST.MF") ?: throw IOException("No manifest")
            return zip.getInputStream(header).use { it.readBytes() }.toString(Charsets.UTF_8)
        }
    }

    private fun extract(jar: File, path: String, dest: File) {
        try {
            ZipFile(jar).use { zip ->
                val header = zip.getFileHeader(path.removePrefix("/")) ?: return
                zip.getInputStream(header).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        } catch (_: IOException) {
            dest.delete() // no icon is fine
        }
    }

    private const val MARKER = "tijolao.version"
    /** In a game's settings folder once it has been given the classic keypad (or kept its own). */
    private const val KEYPAD_MARKER = "tijolao.keypad"
    private const val KEYPAD_VERSION = "1"

    // The emulator's orientation values (MicroActivity): 2 portrait, 3 landscape.
    private const val ORIENTATION_PORTRAIT = 2
    private const val ORIENTATION_LANDSCAPE = 3
}

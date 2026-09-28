package io.github.brunovinicioslg.tijolao

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** One game of the embedded library (assets/catalog.json, made at build time). */
data class Game(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("vendor") val vendor: String?,
    @SerializedName("resolution") val resolution: String,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("landscape") val landscape: Boolean,
    @SerializedName("size") val size: Long,
    @SerializedName("icon") val hasIcon: Boolean,
) {
    val jarAsset: String get() = "games/$id.jar"
    val iconAsset: String get() = "icons/$id.png"
}

object Catalog {
    fun load(context: Context): List<Game> = try {
        context.assets.open("catalog.json").bufferedReader().use { Gson().fromJson(it, Array<Game>::class.java)?.toList() }.orEmpty()
    } catch (_: java.io.IOException) {
        emptyList()
    }
}

/** What this phone remembers about each game: the user's stars (1 to 5) and when it was last played. */
data class GameStats(
    val stars: Map<String, Int> = emptyMap(),
    val lastPlayed: Map<String, Long> = emptyMap(),
) {
    fun starsOf(id: String): Int = stars[id] ?: 0
}

enum class SortOrder { RATING, NAME, RECENT }

object Library {
    /**
     * The list as shown: filtered by [query] (ignoring case and accents) and ordered. By rating:
     * 5 stars first, down to 1, then the games without a rating; ties in title order.
     */
    fun arrange(games: List<Game>, stats: GameStats, order: SortOrder, query: String, locale: Locale = Locale.getDefault()): List<Game> {
        val needle = fold(query.trim())
        val found = if (needle.isEmpty()) games else games.filter { fold(it.title).contains(needle) || fold(it.vendor.orEmpty()).contains(needle) }
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val byTitle = Comparator<Game> { a, b -> collator.compare(a.title, b.title) }
        return when (order) {
            SortOrder.RATING -> found.sortedWith(compareByDescending<Game> { stats.starsOf(it.id) }.then(byTitle))
            SortOrder.NAME -> found.sortedWith(byTitle)
            SortOrder.RECENT -> found.sortedWith(compareByDescending<Game> { stats.lastPlayed[it.id] ?: 0L }.then(byTitle))
        }
    }

    /** The last games played, newest first. */
    fun recent(games: List<Game>, stats: GameStats, count: Int = 10): List<Game> =
        games.filter { it.id in stats.lastPlayed }.sortedByDescending { stats.lastPlayed[it.id] }.take(count)

    private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
}

/** Stars and play history, kept on this phone only. */
class StatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun load(): GameStats {
        val stars = HashMap<String, Int>()
        val played = HashMap<String, Long>()
        for ((key, value) in prefs.all) {
            when {
                key.startsWith(STARS) && value is Int -> stars[key.removePrefix(STARS)] = value
                key.startsWith(PLAYED) && value is Long -> played[key.removePrefix(PLAYED)] = value
            }
        }
        return GameStats(stars, played)
    }

    /** 1 to 5; 0 removes the rating. */
    fun rate(id: String, stars: Int) {
        prefs.edit { if (stars in 1..5) putInt(STARS + id, stars) else remove(STARS + id) }
    }

    fun markPlayed(id: String, now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(PLAYED + id, now) }
    }

    private companion object {
        const val STARS = "stars:"
        const val PLAYED = "played:"
    }
}

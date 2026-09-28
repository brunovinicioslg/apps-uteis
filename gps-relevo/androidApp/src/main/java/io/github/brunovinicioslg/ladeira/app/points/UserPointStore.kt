package io.github.brunovinicioslg.ladeira.app.points

import android.util.Log
import io.github.brunovinicioslg.ladeira.road.PointsGeoJson
import io.github.brunovinicioslg.ladeira.road.UserPoint
import io.github.brunovinicioslg.ladeira.road.UserPoints
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The points the user marked, kept in one GeoJSON file (the same format as export, so a backup is
 * just a copy). Every change is written at once, atomically: a crash or a dead battery never loses
 * the list. Blocking: call off the main thread.
 */
class UserPointStore(private val file: File) {

    private val lock = Any()
    private val _points = MutableStateFlow<List<UserPoint>>(emptyList())
    val points: StateFlow<List<UserPoint>> = _points.asStateFlow()

    fun load() = synchronized(lock) {
        _points.value = try {
            if (file.exists()) PointsGeoJson.read(file.readText()) else emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Cannot read the points", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            // Keep the damaged file for inspection instead of overwriting it with an empty list.
            Log.e(TAG, "Damaged points file; set aside", e)
            file.renameTo(File(file.path + ".damaged-" + System.currentTimeMillis()))
            emptyList()
        }
    }

    fun add(point: UserPoint) = change { it + point }

    fun update(point: UserPoint) = change { list -> list.map { if (it.id == point.id) point else it } }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Returns how many points were new; throws [IllegalArgumentException] when the text is not GeoJSON. */
    fun import(text: String): Int {
        val incoming = PointsGeoJson.read(text)
        var added = 0
        change { list ->
            val merged = UserPoints.importInto(list, incoming)
            added = merged.count { p -> list.none { it.id == p.id } }
            merged
        }
        return added
    }

    fun export(): String = PointsGeoJson.write(points.value)

    private fun change(transform: (List<UserPoint>) -> List<UserPoint>) = synchronized(lock) {
        val next = transform(_points.value)
        write(next)
        _points.value = next
    }

    private fun write(points: List<UserPoint>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(PointsGeoJson.write(points))
        if (!tmp.renameTo(file)) {
            // Renaming replaces the old file on Android; this only runs where a filesystem refuses.
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot save $file")
        }
    }

    private companion object {
        const val TAG = "UserPointStore"
    }
}

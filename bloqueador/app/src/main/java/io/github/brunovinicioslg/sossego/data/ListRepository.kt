package io.github.brunovinicioslg.sossego.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.ListMatcher
import io.github.brunovinicioslg.sossego.core.lists.Match
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The block and allow lists. Every method touches the database: call them off the main thread
 * (the call screening runs on its own thread; the screens use a background dispatcher).
 */
class ListRepository(private val db: SossegoDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val _entries = MutableStateFlow<List<ListEntry>?>(null)

    /** Null until first loaded. */
    val entries: StateFlow<List<ListEntry>?> = _entries.asStateFlow()

    @Volatile
    private var matcher: ListMatcher? = null

    /** What happened to an added entry. */
    enum class Added {
        NEW,

        /** Already on this list: only its name changed (if a new one was given). */
        UPDATED,

        /** Was on the other list and moved here: the latest choice wins. */
        MOVED,
    }

    /** Reads the lists again from the database. */
    fun load(): List<ListEntry> = synchronized(lock) {
        val all = query()
        matcher = ListMatcher(all)
        _entries.value = all
        all
    }

    /** The lists for deciding a call, read once and then kept up to date by every change. */
    fun matcher(): ListMatcher = matcher ?: synchronized(lock) {
        matcher ?: run {
            load()
            matcher ?: ListMatcher.EMPTY
        }
    }

    fun add(entry: ListEntry): Added = synchronized(lock) {
        val result = db.writableDatabase.inTransaction { addIn(this, entry) }
        load()
        result
    }

    /** Changes an entry (number, kind, list or name). */
    fun replace(id: Long, entry: ListEntry): Added = synchronized(lock) {
        val result = db.writableDatabase.inTransaction {
            delete(TABLE, "id = ?", arrayOf(id.toString()))
            addIn(this, entry)
        }
        load()
        result
    }

    fun delete(id: Long) = synchronized(lock) {
        db.writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
        load()
    }

    /** Adds entries from a file; returns how many were new to their list. */
    fun import(entries: List<ListEntry>): Int = synchronized(lock) {
        val added = db.writableDatabase.inTransaction { entries.count { addIn(this, it) != Added.UPDATED } }
        load()
        added
    }

    fun all(): List<ListEntry> = entries.value ?: load()

    private fun addIn(database: SQLiteDatabase, entry: ListEntry): Added {
        val other = if (entry.list == ListKind.BLOCK) ListKind.ALLOW else ListKind.BLOCK
        val moved = database.delete(TABLE, WHERE_SAME, arrayOf(other.name, entry.match.name, entry.pattern)) > 0
        val exists = database.query(TABLE, arrayOf("id"), WHERE_SAME, arrayOf(entry.list.name, entry.match.name, entry.pattern), null, null, null)
            .use { it.moveToFirst() }
        if (exists) {
            if (entry.label.isNotBlank()) {
                database.update(TABLE, ContentValues().apply { put("label", entry.label) }, WHERE_SAME, arrayOf(entry.list.name, entry.match.name, entry.pattern))
            }
            return if (moved) Added.MOVED else Added.UPDATED
        }
        database.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("list", entry.list.name)
                put("match_kind", entry.match.name)
                put("pattern", entry.pattern)
                put("label", entry.label)
                put("created_at", if (entry.createdAt > 0) entry.createdAt else clock())
            },
        )
        return if (moved) Added.MOVED else Added.NEW
    }

    private fun query(): List<ListEntry> =
        db.readableDatabase.query(TABLE, arrayOf("id", "list", "match_kind", "pattern", "label", "created_at"), null, null, null, null, "created_at DESC, id DESC")
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val list = enumValueOrNull<ListKind>(c.getString(1)) ?: continue
                        val match = enumValueOrNull<Match>(c.getString(2)) ?: continue
                        add(ListEntry(c.getLong(0), list, match, c.getString(3), c.getString(4), c.getLong(5)))
                    }
                }
            }

    private companion object {
        const val TABLE = "entries"
        const val WHERE_SAME = "list = ? AND match_kind = ? AND pattern = ?"
    }
}

internal inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

internal inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }

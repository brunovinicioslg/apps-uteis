package io.github.brunovinicioslg.sossego.data

import android.content.ContentValues
import io.github.brunovinicioslg.sossego.core.rules.Reason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A blocked call, kept only on the phone.
 *
 * @param key the caller's number key; empty when hidden or not known (a contact's call declined
 *   by "block everything", whose number the app does not get)
 */
data class BlockedCall(
    val id: Long = 0,
    val time: Long,
    val key: String,
    val reason: Reason,
    /** Let through without ringing (the "silence" action) instead of declined. */
    val silenced: Boolean = false,
)

/** The latest blocked calls. Every method touches the database: call them off the main thread. */
class HistoryRepository(private val db: SossegoDatabase) {

    private val lock = Any()
    private val _recent = MutableStateFlow<List<BlockedCall>?>(null)

    /** Newest first; null until first loaded. */
    val recent: StateFlow<List<BlockedCall>?> = _recent.asStateFlow()

    fun load(): List<BlockedCall> = synchronized(lock) {
        val calls = db.readableDatabase.query(TABLE, arrayOf("id", "time", "number_key", "reason", "silenced"), null, null, null, null, "time DESC, id DESC", MAX.toString())
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val reason = enumValueOrNull<Reason>(c.getString(3)) ?: continue
                        add(BlockedCall(c.getLong(0), c.getLong(1), c.getString(2), reason, c.getInt(4) != 0))
                    }
                }
            }
        _recent.value = calls
        calls
    }

    fun record(call: BlockedCall) = synchronized(lock) {
        db.writableDatabase.inTransaction {
            insertOrThrow(
                TABLE,
                null,
                ContentValues().apply {
                    put("time", call.time)
                    put("number_key", call.key)
                    put("reason", call.reason.name)
                    put("silenced", if (call.silenced) 1 else 0)
                },
            )
            // Keeps the newest ones only.
            execSQL("DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY time DESC, id DESC LIMIT $MAX)")
        }
        load()
    }

    /** When [key] was last blocked, for letting through someone who insists. */
    fun lastBlockedAt(key: String): Long? {
        if (key.isEmpty()) return null
        return db.readableDatabase.rawQuery("SELECT MAX(time) FROM $TABLE WHERE number_key = ?", arrayOf(key))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }

    /** Whether the same block was just recorded (the phone announces a ringing call more than once). */
    fun recordedSince(key: String, reason: Reason, since: Long): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE number_key = ? AND reason = ? AND time >= ? LIMIT 1",
            arrayOf(key, reason.name, since.toString()),
        ).use { it.moveToFirst() }

    fun countSince(time: Long): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE time >= ?", arrayOf(time.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun delete(id: Long) = synchronized(lock) {
        db.writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
        load()
    }

    fun clear() = synchronized(lock) {
        db.writableDatabase.delete(TABLE, null, null)
        load()
    }

    private companion object {
        const val TABLE = "history"
        const val MAX = 500
    }
}

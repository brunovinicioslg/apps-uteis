package io.github.brunovinicioslg.sigilo.app.inbox

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.brunovinicioslg.sigilo.sms.SmsCodec

/** What happened to one SMS of a message the app sent. */
enum class SmsEvent(val code: Int) {
    SENT(0),
    SEND_FAILED(1),
    DELIVERED(2),
    DELIVERY_FAILED(3),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: SEND_FAILED
    }
}

sealed interface InboxItem {
    val id: Long

    /**
     * An SMS that arrived. For encrypted conversations [body] is ciphertext; ordinary SMS are also
     * in the system's SMS database ([providerId]), which every SMS app shares.
     */
    data class Sms(override val id: Long, val address: String, val body: String, val subscriptionId: Int, val timestamp: Long, val providerId: Long?) :
        InboxItem

    /** Delivery news for message [messageId] of the encrypted database. No content. */
    data class Status(override val id: Long, val messageId: Long, val event: SmsEvent) : InboxItem

    data class Mms(override val id: Long, val address: String, val timestamp: Long, val providerId: Long?) : InboxItem

    /** An SMS sent from outside the app (quick reply from the phone app while a call rings). */
    data class Sent(override val id: Long, val address: String, val body: String, val subscriptionId: Int, val timestamp: Long, val providerId: Long?) :
        InboxItem
}

/**
 * A small unencrypted queue in front of the encrypted database. SMS arrive at any time, also while
 * the app is locked with a password and the database cannot be opened: they wait here, safe from
 * crashes, until the message engine takes them. Nothing here is secret: encrypted messages are
 * still ciphertext, and ordinary SMS are in the system's SMS database anyway.
 */
class Inbox(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, type INTEGER NOT NULL, address TEXT, body TEXT, " +
                "subscription_id INTEGER NOT NULL DEFAULT -1, timestamp INTEGER NOT NULL DEFAULT 0, provider_id INTEGER, " +
                "message_id INTEGER, event INTEGER, attempts INTEGER NOT NULL DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE items ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
    }

    fun addSms(address: String, body: String, subscriptionId: Int, timestamp: Long, providerId: Long?) = add(TYPE_SMS) {
        put("address", address)
        put("body", body)
        put("subscription_id", subscriptionId)
        put("timestamp", timestamp)
        providerId?.let { put("provider_id", it) }
    }

    fun addStatus(messageId: Long, event: SmsEvent) = add(TYPE_STATUS) {
        put("message_id", messageId)
        put("event", event.code)
    }

    fun addMms(address: String, timestamp: Long, providerId: Long?) = add(TYPE_MMS) {
        put("address", address)
        put("timestamp", timestamp)
        providerId?.let { put("provider_id", it) }
    }

    fun addSent(address: String, body: String, subscriptionId: Int, timestamp: Long, providerId: Long?) = add(TYPE_SENT) {
        put("address", address)
        put("body", body)
        put("subscription_id", subscriptionId)
        put("timestamp", timestamp)
        providerId?.let { put("provider_id", it) }
    }

    private fun add(type: Int, fill: ContentValues.() -> Unit): Long =
        writableDatabase.insertOrThrow("items", null, ContentValues().apply { put("type", type); fill() })

    /** Oldest first. */
    fun items(limit: Int = 200): List<InboxItem> =
        readableDatabase.rawQuery("SELECT id, type, address, body, subscription_id, timestamp, provider_id, message_id, event FROM items ORDER BY id LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) itemOf(c)?.let(::add) } }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** How many SMS are waiting (an encrypted message counts once, however many SMS it took). */
    fun waitingMessages(): Int = items(limit = Int.MAX_VALUE).mapNotNull { item ->
        when (item) {
            // Only the part header is read (sender's message number); the content stays sealed.
            is InboxItem.Sms -> SmsCodec.parse(item.body)?.let { "${item.address}#${it.messageId}" } ?: "sms#${item.id}"
            is InboxItem.Mms -> "mms#${item.id}"
            else -> null
        }
    }.distinct().size

    /** Counts a failed attempt to process [id]; returns how many there were so far. */
    fun recordFailure(id: Long): Int {
        writableDatabase.execSQL("UPDATE items SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
        return readableDatabase.rawQuery("SELECT attempts FROM items WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun remove(id: Long) {
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    }

    private fun itemOf(c: Cursor): InboxItem? {
        val id = c.getLong(0)
        val providerId = if (c.isNull(6)) null else c.getLong(6)
        return when (c.getInt(1)) {
            TYPE_SMS -> InboxItem.Sms(id, c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), providerId)
            TYPE_STATUS -> InboxItem.Status(id, c.getLong(7), SmsEvent.of(c.getInt(8)))
            TYPE_MMS -> InboxItem.Mms(id, c.getString(2), c.getLong(5), providerId)
            TYPE_SENT -> InboxItem.Sent(id, c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), providerId)
            else -> null
        }
    }

    private companion object {
        const val NAME = "inbox.db"
        const val VERSION = 2
        const val TYPE_SMS = 1
        const val TYPE_STATUS = 2
        const val TYPE_MMS = 3
        const val TYPE_SENT = 4
    }
}

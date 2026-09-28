package io.github.brunovinicioslg.sigilo.app.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Conversations, messages and the other app records in the encrypted database. */
class MessageRepository(private val db: SigiloDatabase) {

    // Conversations.

    fun conversation(id: Long): Conversation? = db.queryOne("$CONVERSATION_SELECT WHERE c.id = ?", id, map = ::conversationOf)

    fun conversationByAddress(address: String): Conversation? =
        db.queryOne("$CONVERSATION_SELECT WHERE c.address = ?", address, map = ::conversationOf)

    fun conversationFor(address: String): Conversation {
        conversationByAddress(address)?.let { return it }
        db.insert("conversations", ContentValues().apply { put("address", address) }, SQLiteDatabase.CONFLICT_IGNORE)
        return conversationByAddress(address)!!
    }

    /** Newest first; conversations without any message are left out. */
    fun conversations(): List<Conversation> =
        db.query("$CONVERSATION_SELECT WHERE c.last_at > 0 ORDER BY c.last_at DESC", map = ::conversationOf)

    fun setEncryption(conversationId: Long, encryption: Encryption) {
        db.update("conversations", ContentValues().apply { put("encryption", encryption.code) }, "id = ?", conversationId)
    }

    fun setExpireSeconds(conversationId: Long, seconds: Int) {
        db.update("conversations", ContentValues().apply { put("expire_seconds", seconds) }, "id = ?", conversationId)
    }

    fun setKeyChanged(conversationId: Long, changed: Boolean) {
        db.update("conversations", ContentValues().apply { put("key_changed", if (changed) 1 else 0) }, "id = ?", conversationId)
    }

    fun setSubscription(conversationId: Long, subscriptionId: Int) {
        db.update("conversations", ContentValues().apply { put("subscription_id", subscriptionId) }, "id = ?", conversationId)
    }

    fun noteEncryptedSent(conversationId: Long, at: Long) {
        db.update("conversations", ContentValues().apply { put("last_encrypted_sent_at", at) }, "id = ?", conversationId)
    }

    fun deleteConversation(conversationId: Long): List<Long> {
        val providerIds = db.query("SELECT provider_id FROM messages WHERE conversation_id = ? AND provider_id IS NOT NULL", conversationId) {
            it.getLong(0)
        }
        db.delete("conversations", "id = ?", conversationId)
        return providerIds
    }

    // Messages.

    fun insertMessage(
        conversationId: Long,
        outgoing: Boolean,
        kind: MessageKind,
        body: String,
        encrypted: Boolean,
        status: MessageStatus,
        sentAt: Long,
        receivedAt: Long,
        read: Boolean,
        parts: Int = 1,
        remoteId: Long? = null,
        providerId: Long? = null,
        expireSeconds: Int = 0,
        expireAt: Long? = null,
    ): Long {
        val id = db.insert("messages", ContentValues().apply {
            put("conversation_id", conversationId)
            put("outgoing", if (outgoing) 1 else 0)
            put("kind", kind.code)
            put("body", body)
            put("encrypted", if (encrypted) 1 else 0)
            put("status", status.code)
            put("sent_at", sentAt)
            put("received_at", receivedAt)
            put("parts", parts)
            put("read", if (read) 1 else 0)
            remoteId?.let { put("remote_id", it) }
            providerId?.let { put("provider_id", it) }
            put("expire_seconds", expireSeconds)
            expireAt?.let { put("expire_at", it) }
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) refreshSummary(conversationId)
        return id
    }

    fun message(id: Long): Message? = db.queryOne("$MESSAGE_SELECT WHERE id = ?", id, map = ::messageOf)

    fun hasProviderMessage(providerId: Long): Boolean = db.longOrNull("SELECT 1 FROM messages WHERE provider_id = ?", providerId) != null

    /** Oldest first, without messages whose disappearing timer ran out (they may not be deleted yet). */
    fun messages(conversationId: Long, now: Long): List<Message> =
        db.query("$MESSAGE_SELECT WHERE conversation_id = ? AND (expire_at IS NULL OR expire_at > ?) ORDER BY received_at, id", conversationId, now, map = ::messageOf)

    fun unreadMessages(conversationId: Long): List<Message> =
        db.query("$MESSAGE_SELECT WHERE conversation_id = ? AND read = 0 ORDER BY received_at, id", conversationId, map = ::messageOf)

    fun setParts(messageId: Long, parts: Int) {
        db.update("messages", ContentValues().apply { put("parts", parts) }, "id = ?", messageId)
    }

    fun setStatus(messageId: Long, status: MessageStatus) {
        db.update("messages", ContentValues().apply { put("status", status.code) }, "id = ?", messageId)
    }

    fun setProviderId(messageId: Long, providerId: Long) {
        db.update("messages", ContentValues().apply { put("provider_id", providerId) }, "id = ?", messageId)
    }

    /** One SMS of the message went out; the message counts as sent when all of them did. */
    fun partSent(messageId: Long): Message? {
        db.sql.execSQL("UPDATE messages SET parts_sent = parts_sent + 1 WHERE id = ?", arrayOf<Any>(messageId))
        db.sql.execSQL(
            "UPDATE messages SET status = ? WHERE id = ? AND status = ? AND parts_sent >= parts",
            arrayOf<Any>(MessageStatus.SENT.code, messageId, MessageStatus.PENDING.code),
        )
        return message(messageId)
    }

    fun partDelivered(messageId: Long): Message? {
        db.sql.execSQL("UPDATE messages SET parts_delivered = parts_delivered + 1 WHERE id = ?", arrayOf<Any>(messageId))
        db.sql.execSQL(
            "UPDATE messages SET status = ? WHERE id = ? AND status IN (?, ?) AND parts_delivered >= parts",
            arrayOf<Any>(MessageStatus.DELIVERED.code, messageId, MessageStatus.PENDING.code, MessageStatus.SENT.code),
        )
        return message(messageId)
    }

    /**
     * Marks everything read. Received disappearing messages start their timer now: they count from
     * the moment they are read. Returns the provider ids of the SMS that were unread.
     */
    fun markRead(conversationId: Long, now: Long): List<Long> {
        val providerIds = db.query("SELECT provider_id FROM messages WHERE conversation_id = ? AND read = 0 AND provider_id IS NOT NULL", conversationId) {
            it.getLong(0)
        }
        db.sql.execSQL(
            "UPDATE messages SET expire_at = ? + expire_seconds * 1000 WHERE conversation_id = ? AND read = 0 AND expire_seconds > 0 AND expire_at IS NULL",
            arrayOf<Any>(now, conversationId),
        )
        db.sql.execSQL("UPDATE messages SET read = 1 WHERE conversation_id = ? AND read = 0", arrayOf<Any>(conversationId))
        db.update("conversations", ContentValues().apply { put("unread", 0) }, "id = ?", conversationId)
        return providerIds
    }

    fun deleteMessage(messageId: Long): Long? {
        val message = message(messageId) ?: return null
        db.delete("messages", "id = ?", messageId)
        refreshSummary(message.conversationId)
        return message.providerId
    }

    /** Deletes messages whose timer ran out; returns their provider ids (normally none: they are encrypted). */
    fun deleteExpired(now: Long): List<Long> {
        val expired = db.query("SELECT id, conversation_id, provider_id FROM messages WHERE expire_at IS NOT NULL AND expire_at <= ?", now) {
            Triple(it.getLong(0), it.getLong(1), if (it.isNull(2)) null else it.getLong(2))
        }
        if (expired.isEmpty()) return emptyList()
        db.delete("messages", "expire_at IS NOT NULL AND expire_at <= ?", now)
        expired.map { it.second }.distinct().forEach(::refreshSummary)
        return expired.mapNotNull { it.third }
    }

    fun nextExpiry(): Long? = db.longOrNull("SELECT MIN(expire_at) FROM messages WHERE expire_at IS NOT NULL")

    /** Keeps the list screen's line (last message, time, unread count) in step with the messages. */
    private fun refreshSummary(conversationId: Long) {
        val last = db.queryOne(
            "SELECT body, kind, received_at FROM messages WHERE conversation_id = ? ORDER BY received_at DESC, id DESC LIMIT 1",
            conversationId,
        ) { Triple(it.getString(0), it.getInt(1), it.getLong(2)) }
        val unread = db.longOrNull("SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND read = 0", conversationId) ?: 0
        db.update("conversations", ContentValues().apply {
            if (last == null) {
                putNull("snippet")
                put("snippet_kind", MessageKind.TEXT.code)
            } else {
                put("snippet", last.first)
                put("snippet_kind", last.second)
                put("last_at", last.third)
            }
            put("unread", unread)
        }, "id = ?", conversationId)
    }

    // Parts of encrypted messages waiting for the rest.

    fun addFragment(sender: String, messageId: Int, text: String, receivedAt: Long) {
        db.insert("fragments", ContentValues().apply {
            put("sender", sender)
            put("message_id", messageId)
            put("text", text)
            put("received_at", receivedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    data class StoredFragment(val sender: String, val text: String, val receivedAt: Long)

    fun fragments(): List<StoredFragment> =
        db.query("SELECT sender, text, received_at FROM fragments ORDER BY received_at") { StoredFragment(it.getString(0), it.getString(1), it.getLong(2)) }

    fun deleteFragments(sender: String, messageId: Int) {
        db.delete("fragments", "sender = ? AND message_id = ?", sender, messageId)
    }

    fun deleteFragmentsBefore(time: Long) {
        db.delete("fragments", "received_at < ?", time)
    }

    // Messages held after an identity change.

    data class Held(val id: Long, val envelope: ByteArray, val identity: ByteArray)

    fun hold(address: String, envelope: ByteArray, identity: ByteArray, receivedAt: Long) {
        db.insert("held_envelopes", ContentValues().apply {
            put("address", address)
            put("envelope", envelope)
            put("identity", identity)
            put("received_at", receivedAt)
        })
    }

    fun held(address: String): List<Held> =
        db.query("SELECT id, envelope, identity FROM held_envelopes WHERE address = ? ORDER BY received_at, id", address) {
            Held(it.getLong(0), it.getBlob(1), it.getBlob(2))
        }

    fun releaseHeld(id: Long) {
        db.delete("held_envelopes", "id = ?", id)
    }

    // Settings.

    fun setting(key: String): String? = db.queryOne("SELECT value FROM settings WHERE key = ?", key) { it.getString(0) }

    fun setSetting(key: String, value: String) {
        db.insert("settings", ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun conversationOf(c: Cursor) = Conversation(
        id = c.getLong(0),
        address = c.getString(1),
        encryption = Encryption.of(c.getInt(2)),
        expireSeconds = c.getInt(3),
        unread = c.getInt(4),
        lastAt = c.getLong(5),
        snippet = if (c.isNull(6)) null else c.getString(6),
        snippetKind = MessageKind.of(c.getInt(7)),
        subscriptionId = c.getInt(8),
        keyChanged = c.getInt(9) == 1,
        lastEncryptedSentAt = c.getLong(10),
    )

    private fun messageOf(c: Cursor) = Message(
        id = c.getLong(0),
        conversationId = c.getLong(1),
        outgoing = c.getInt(2) == 1,
        kind = MessageKind.of(c.getInt(3)),
        body = c.getString(4),
        encrypted = c.getInt(5) == 1,
        status = MessageStatus.of(c.getInt(6)),
        sentAt = c.getLong(7),
        receivedAt = c.getLong(8),
        parts = c.getInt(9),
        expireSeconds = c.getInt(10),
        expireAt = if (c.isNull(11)) null else c.getLong(11),
        read = c.getInt(12) == 1,
        providerId = if (c.isNull(13)) null else c.getLong(13),
    )

    private companion object {
        const val CONVERSATION_SELECT = "SELECT c.id, c.address, c.encryption, c.expire_seconds, c.unread, c.last_at, c.snippet, " +
            "c.snippet_kind, c.subscription_id, c.key_changed, c.last_encrypted_sent_at FROM conversations c"
        const val MESSAGE_SELECT = "SELECT id, conversation_id, outgoing, kind, body, encrypted, status, sent_at, received_at, parts, " +
            "expire_seconds, expire_at, read, provider_id FROM messages"
    }
}

package io.github.brunovinicioslg.sigilo.app.db

/** Where a conversation stands with end-to-end encryption. */
enum class Encryption(val code: Int) {
    NONE(0),

    /** We sent our keys; waiting for the contact's app to answer. */
    INVITE_SENT(1),

    /** The contact sent their keys; the user decides whether to accept (it costs ~16 SMS). */
    INVITE_RECEIVED(2),
    ACTIVE(3),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Messages the user wrote, and events the app records in the conversation. */
enum class MessageKind(val code: Int) {
    TEXT(0),
    INVITE_SENT(1),
    INVITE_RECEIVED(2),
    ENCRYPTION_ON(3),

    /** The contact's safety number changed; body is empty. */
    KEY_CHANGED(4),

    /** Body: the new timer in seconds (0 = off). */
    TIMER_CHANGED(5),

    /** An encrypted message that could not be opened. */
    UNREADABLE(6),

    /** A message type from a newer app version. */
    UNSUPPORTED(7),

    /** A multimedia message (MMS); viewing them arrives in a later version. */
    MMS(8),
    KEY_ACCEPTED(9),
    INVITE_DECLINED(10),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNSUPPORTED
    }
}

enum class MessageStatus(val code: Int) {
    RECEIVED(0),
    PENDING(1),
    SENT(2),
    DELIVERED(3),
    FAILED(4),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: RECEIVED
    }
}

data class Conversation(
    val id: Long,
    /** Normalized phone number (E.164 when possible), also the Signal address. */
    val address: String,
    val encryption: Encryption,
    val expireSeconds: Int,
    val unread: Int,
    val lastAt: Long,
    val snippet: String?,
    val snippetKind: MessageKind,
    val subscriptionId: Int,
    val keyChanged: Boolean,
    val lastEncryptedSentAt: Long,
)

data class Message(
    val id: Long,
    val conversationId: Long,
    val outgoing: Boolean,
    val kind: MessageKind,
    val body: String,
    val encrypted: Boolean,
    val status: MessageStatus,
    /** Sender's clock, milliseconds. */
    val sentAt: Long,
    /** This phone's clock, milliseconds; the conversation is ordered by it. */
    val receivedAt: Long,
    val parts: Int,
    val expireSeconds: Int,
    /** Null until the timer starts (when sent, or when read). */
    val expireAt: Long?,
    val read: Boolean,
    val providerId: Long?,
)

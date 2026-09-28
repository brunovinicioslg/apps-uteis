package io.github.brunovinicioslg.sigilo.app.engine

import android.telephony.PhoneNumberUtils
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent

/** The phone's SMS radio. Sending results come back later through the inbox, per SMS. */
interface SmsGateway {
    /** Sends each text as its own SMS (encrypted fragments must not be merged by the carrier). */
    fun sendEach(address: String, texts: List<String>, subscriptionId: Int, messageId: Long)

    /** Sends an ordinary text, split into as many SMS as it needs; returns that number. */
    fun sendText(address: String, text: String, subscriptionId: Int, messageId: Long): Int

    /** How many SMS an ordinary text takes. */
    fun partsOf(text: String): Int
}

/** One row of the system SMS database. */
data class SystemSmsRow(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val failed: Boolean,
    val subscriptionId: Int,
)

/**
 * The system SMS database that every SMS app shares. Ordinary SMS go there too, so switching to
 * another SMS app keeps them; encrypted messages never do.
 */
interface SystemSms {
    fun insertOutgoing(address: String, body: String, subscriptionId: Int, timestamp: Long): Long?

    /** Sent, failed or delivered, as the radio reported it. */
    fun updateStatus(providerId: Long, event: SmsEvent)

    fun markRead(providerIds: List<Long>)

    fun delete(providerIds: List<Long>)

    /** Ordered by id, after [afterId]. */
    fun history(afterId: Long, limit: Int): List<SystemSmsRow>
}

/** What the engine tells the rest of the app. */
interface EngineEvents {
    /** New incoming messages worth a notification. */
    fun onNewMessages(conversation: Conversation, messages: List<Message>)

    /** Something visible changed. */
    fun onChanged()

    /** When the next disappearing message is due, to wake up and delete it; null when none. */
    fun onNextExpiry(at: Long?)
}

/**
 * Phone numbers as the conversation and the Signal session know them: E.164 ("+5531999998888")
 * when the number is valid for [region], so "31 99999-8888", "(31) 99999-8888" and
 * "+55 31 99999-8888" are one contact. Short codes and names ("BANCO") stay as they are.
 */
class AddressNormalizer(private val region: () -> String) {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.any { it.isLetter() }) return trimmed
        PhoneNumberUtils.formatNumberToE164(trimmed, region())?.let { return it }
        val digits = trimmed.filter { it.isDigit() || it == '+' }
        return digits.ifEmpty { trimmed }
    }
}

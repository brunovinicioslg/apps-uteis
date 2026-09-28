package io.github.brunovinicioslg.sigilo

import io.github.brunovinicioslg.sigilo.crypto.KeyBundle
import io.github.brunovinicioslg.sigilo.crypto.Opened
import io.github.brunovinicioslg.sigilo.crypto.Sealed
import io.github.brunovinicioslg.sigilo.crypto.SessionManager
import io.github.brunovinicioslg.sigilo.sms.Reassembler
import io.github.brunovinicioslg.sigilo.sms.SmsCodec
import io.github.brunovinicioslg.sigilo.wire.ByteReader
import io.github.brunovinicioslg.sigilo.wire.ByteWriter
import io.github.brunovinicioslg.sigilo.wire.Content
import io.github.brunovinicioslg.sigilo.wire.MalformedException
import java.security.SecureRandom
import org.signal.libsignal.protocol.IdentityKey

/** What arrived, once an SMS completed an envelope. */
sealed interface Received {
    /**
     * [acknowledgeSetup]: the message carried a session setup; reply soon (with anything, or
     * [Messenger.composeAck]) so the sender stops repeating the 16-SMS setup.
     */
    data class Message(val content: Content, val acknowledgeSetup: Boolean = false) : Received

    /** A contact sent their keys; a session now exists and our replies will be encrypted. */
    data object InviteAccepted : Received

    /** An invite with a different identity than we knew; needs the user's confirmation. */
    class InviteWithNewIdentity(val bundle: KeyBundle, val identity: IdentityKey) : Received

    /** The message is kept; it can be opened after the user accepts [identity]. */
    class IdentityChanged(val envelope: ByteArray, val identity: IdentityKey) : Received

    data object Duplicate : Received

    class Failed(val reason: String) : Received
}

/**
 * Glue between the protocol and SMS: builds the SMS texts for a message or an invite, and turns
 * incoming SMS texts back into messages. Envelope = 1 byte kind + payload.
 */
class Messenger(
    private val sessions: SessionManager,
    private val reassembler: Reassembler = Reassembler(),
    private val random: SecureRandom = SecureRandom(),
) {
    /** SMS texts carrying our public keys; the receiver can then write to us encrypted. */
    fun composeInvite(): List<String> = split(ByteWriter().byte(KIND_INVITE).raw(sessions.createBundle().encode()).toByteArray())

    fun composeText(contact: String, text: String, expireSeconds: Int, now: Long): List<String> =
        composeContent(contact, Content.Text(newId(), now / 1000, text, expireSeconds))

    fun composeTimerChange(contact: String, expireSeconds: Int, now: Long): List<String> =
        composeContent(contact, Content.TimerChange(newId(), now / 1000, expireSeconds))

    /** One small SMS confirming a received session setup (see [Received.Message.acknowledgeSetup]). */
    fun composeAck(contact: String, now: Long): List<String> = composeContent(contact, Content.Ack(newId(), now / 1000))

    private fun composeContent(contact: String, content: Content): List<String> {
        val sealed = sessions.encrypt(contact, Content.encode(content))
        val kind = if (sealed.preKey) KIND_PREKEY_MESSAGE else KIND_MESSAGE
        return split(ByteWriter().byte(kind).raw(sealed.bytes).toByteArray())
    }

    /** Whether [text] is a Sigilo fragment (the app stores it apart from normal SMS). */
    fun isSigilo(text: String) = SmsCodec.parse(text) != null

    /** Null while more parts are expected, or for texts that are not Sigilo fragments. */
    fun receive(sender: String, text: String, now: Long): Received? {
        val fragment = SmsCodec.parse(text) ?: return null
        val envelope = reassembler.add(sender, fragment, now) ?: return null
        return open(sender, envelope)
    }

    /** Opens a complete envelope (also used to retry one kept after an identity change). */
    fun open(sender: String, envelope: ByteArray): Received = try {
        val r = ByteReader(envelope)
        when (val kind = r.byte()) {
            KIND_INVITE -> {
                val bundle = KeyBundle.decode(r.rest())
                val changed = sessions.acceptBundle(sender, bundle)
                if (changed == null) Received.InviteAccepted else Received.InviteWithNewIdentity(bundle, changed)
            }
            KIND_MESSAGE, KIND_PREKEY_MESSAGE -> when (val opened = sessions.decrypt(sender, Sealed(kind == KIND_PREKEY_MESSAGE, r.rest()))) {
                is Opened.Plaintext -> Received.Message(Content.decode(opened.bytes), acknowledgeSetup = kind == KIND_PREKEY_MESSAGE)
                is Opened.Duplicate -> Received.Duplicate
                is Opened.IdentityChanged -> Received.IdentityChanged(envelope, opened.newIdentity)
                is Opened.NoSession -> Received.Failed("no session; ask the contact for a new invite")
                is Opened.Invalid -> Received.Failed(opened.reason)
            }
            else -> Received.Failed("unknown envelope kind $kind")
        }
    } catch (e: MalformedException) {
        Received.Failed("malformed: ${e.message}")
    }

    private fun split(envelope: ByteArray) = SmsCodec.split(envelope, random.nextInt(1 shl 16))

    private fun newId(): Long = random.nextInt().toLong() and Content.MAX_ID

    private companion object {
        const val KIND_INVITE = 1
        const val KIND_MESSAGE = 2
        const val KIND_PREKEY_MESSAGE = 3
    }
}

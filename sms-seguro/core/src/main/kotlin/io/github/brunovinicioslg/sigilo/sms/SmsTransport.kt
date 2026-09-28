package io.github.brunovinicioslg.sigilo.sms

import io.github.brunovinicioslg.sigilo.wire.ByteReader
import io.github.brunovinicioslg.sigilo.wire.ByteWriter
import io.github.brunovinicioslg.sigilo.wire.MalformedException
import java.util.Base64
import kotlin.math.ceil

/** One SMS worth of an encrypted envelope. */
data class Fragment(val messageId: Int, val index: Int, val count: Int, val chunk: ByteArray) {
    override fun equals(other: Any?) = other is Fragment && messageId == other.messageId &&
        index == other.index && count == other.count && chunk.contentEquals(other.chunk)

    override fun hashCode() = (messageId * 31 + index) * 31 + chunk.contentHashCode()
}

/**
 * Encrypted envelopes travel as ordinary text SMS, split by Sigilo itself rather than by the
 * carrier's "long SMS" concatenation, so parts can arrive in any order and a lost part does not
 * silently drop the rest.
 *
 * Each SMS is "SG1:" followed by Base64 (no padding). Base64's alphabet and the prefix use only the
 * GSM-7 basic character set, so the phone sends 7-bit SMS of up to 160 characters (UCS-2 would
 * allow only 70) and no carrier needs to translate any character.
 */
object SmsCodec {
    const val PREFIX = "SG1:"
    const val MAX_SMS_CHARS = 160
    private const val HEADER_BYTES = 5
    private const val MAGIC = 0x51 // high nibble "Sigilo", low nibble format version 1

    /** Bytes of envelope per SMS: (160 - 4) chars x 6 bits = 117 bytes, minus the header. */
    val CHUNK_BYTES: Int = (MAX_SMS_CHARS - PREFIX.length) * 6 / 8 - HEADER_BYTES
    const val MAX_FRAGMENTS = 255

    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    fun smsCount(envelopeBytes: Int): Int = ceil(envelopeBytes.toDouble() / CHUNK_BYTES).toInt().coerceAtLeast(1)

    fun split(envelope: ByteArray, messageId: Int): List<String> {
        val count = smsCount(envelope.size)
        require(count <= MAX_FRAGMENTS) { "message too large for SMS: $count parts" }
        return (0 until count).map { i ->
            val chunk = envelope.copyOfRange(i * CHUNK_BYTES, minOf(envelope.size, (i + 1) * CHUNK_BYTES))
            val bytes = ByteWriter()
                .byte(MAGIC)
                .byte(messageId shr 8 and 0xff).byte(messageId and 0xff)
                .byte(i).byte(count)
                .raw(chunk)
                .toByteArray()
            PREFIX + encoder.encodeToString(bytes)
        }
    }

    /** Null for anything that is not a Sigilo fragment (normal SMS from anyone). Never throws. */
    fun parse(text: String): Fragment? {
        if (!text.startsWith(PREFIX) || text.length > MAX_SMS_CHARS * 2) return null
        val bytes = try {
            decoder.decode(text.substring(PREFIX.length))
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            val r = ByteReader(bytes)
            if (r.byte() != MAGIC) return null
            val id = (r.byte() shl 8) or r.byte()
            val index = r.byte()
            val count = r.byte()
            if (count == 0 || index >= count) return null
            Fragment(id, index, count, r.rest())
        } catch (e: MalformedException) {
            null
        }
    }
}

/**
 * Collects fragments per sender until an envelope is complete. Tolerates any order, duplicates and
 * interleaving of several messages. Incomplete messages expire; the app persists fragments so a
 * restart does not lose them and replays them through [add].
 */
class Reassembler(private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS, private val maxPendingPerSender: Int = 32) {

    private data class Key(val sender: String, val messageId: Int)

    private class Partial(val count: Int, val firstSeen: Long) {
        val chunks = arrayOfNulls<ByteArray>(count)
        val received: Int get() = chunks.count { it != null }
    }

    private val pending = LinkedHashMap<Key, Partial>()

    val pendingCount: Int get() = pending.size

    /** The complete envelope when [fragment] was the last missing part, otherwise null. */
    fun add(sender: String, fragment: Fragment, now: Long): ByteArray? {
        // Defense in depth: SmsCodec already rejects these, but a bad index here would crash.
        if (fragment.count !in 1..SmsCodec.MAX_FRAGMENTS || fragment.index !in 0 until fragment.count) return null
        expire(now)
        val key = Key(sender, fragment.messageId)
        var partial = pending[key]
        if (partial != null && partial.count != fragment.count) {
            // Same id but a different message (ids are random 16-bit): start over with the newer one.
            pending.remove(key)
            partial = null
        }
        if (partial == null) {
            evictOldest(sender)
            partial = Partial(fragment.count, now)
            pending[key] = partial
        }
        if (partial.chunks[fragment.index] == null) partial.chunks[fragment.index] = fragment.chunk
        if (partial.received < partial.count) return null
        pending.remove(key)
        val out = ByteWriter()
        partial.chunks.forEach { out.raw(it!!) }
        return out.toByteArray()
    }

    fun expire(now: Long) {
        pending.entries.removeAll { now - it.value.firstSeen > maxAgeMillis }
    }

    /** A flood of junk fragments from one number cannot grow memory without bound. */
    private fun evictOldest(sender: String) {
        val mine = pending.entries.filter { it.key.sender == sender }
        if (mine.size >= maxPendingPerSender) {
            mine.minByOrNull { it.value.firstSeen }?.let { pending.remove(it.key) }
        }
    }

    companion object {
        /** SMS can be delayed for days when a phone is off; after a week a part is not coming. */
        const val DEFAULT_MAX_AGE_MILLIS = 7L * 24 * 3600 * 1000
    }
}

package io.github.brunovinicioslg.sigilo.wire

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What travels inside an encrypted message. Kept tiny because every byte costs SMS: ids and times
 * are varints, and text is compressed when that makes it shorter.
 */
sealed interface Content {
    /** Random id chosen by the sender, used to spot duplicates and to reference the message. */
    val id: Long

    /** Sender's clock, seconds since the epoch. */
    val sentAt: Long

    data class Text(
        override val id: Long,
        override val sentAt: Long,
        val body: String,
        /** Delete on both phones this many seconds after reading; 0 keeps it. */
        val expireSeconds: Int = 0,
    ) : Content

    /** The sender changed the disappearing-messages timer of the conversation. */
    data class TimerChange(override val id: Long, override val sentAt: Long, val expireSeconds: Int) : Content

    /**
     * Sent once after receiving a session setup. Until the sender hears back, the Signal protocol
     * repeats the whole setup (about 1.8 kB, 16 SMS) in every message; this 1-SMS reply stops that.
     * Not shown in the conversation.
     */
    data class Ack(override val id: Long, override val sentAt: Long) : Content

    /** A type from a newer app version; shown as "update the app to see this message". */
    data class Unknown(override val id: Long, override val sentAt: Long, val type: Int) : Content

    companion object {
        // Every byte counts: after the Signal overhead (~90 bytes, 37 of them post-quantum ratchet)
        // an SMS has little room, and AES pads the content to 16-byte blocks. Hence version and type
        // share one byte, ids are 31-bit, and the timer is only written when set.
        private const val VERSION = 1
        private const val TYPE_TEXT = 1
        private const val TYPE_TIMER = 2
        private const val TYPE_ACK = 3
        private const val FLAG_DEFLATED = 1
        private const val FLAG_EXPIRES = 2
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_EXPIRE_SECONDS = 365 * 24 * 3600
        const val MAX_ID = Int.MAX_VALUE.toLong()

        fun encode(content: Content): ByteArray {
            require(content.id in 0..MAX_ID) { "id must fit in 31 bits" }
            val w = ByteWriter()
            fun header(type: Int) = w.byte((VERSION shl 4) or type).varint(content.id).varint(content.sentAt)
            when (content) {
                is Text -> {
                    val utf8 = content.body.encodeToByteArray()
                    require(utf8.size <= MAX_BODY_BYTES) { "message too long" }
                    val deflated = deflate(utf8)
                    val compressed = deflated.size < utf8.size
                    val flags = (if (compressed) FLAG_DEFLATED else 0) or (if (content.expireSeconds > 0) FLAG_EXPIRES else 0)
                    header(TYPE_TEXT).byte(flags)
                    if (content.expireSeconds > 0) w.varint(content.expireSeconds.toLong())
                    w.raw(if (compressed) deflated else utf8)
                }
                is TimerChange -> header(TYPE_TIMER).varint(content.expireSeconds.toLong())
                is Ack -> header(TYPE_ACK)
                is Unknown -> throw IllegalArgumentException("cannot send unknown content")
            }
            return w.toByteArray()
        }

        fun decode(bytes: ByteArray): Content {
            val r = ByteReader(bytes)
            val head = r.byte()
            if (head shr 4 != VERSION) throw MalformedException("content version ${head shr 4}")
            val type = head and 0x0f
            val id = r.varint(0L..MAX_ID)
            val sentAt = r.varint()
            return when (type) {
                TYPE_TEXT -> {
                    val flags = r.byte()
                    val expire = if (flags and FLAG_EXPIRES != 0) r.varint(1L..MAX_EXPIRE_SECONDS.toLong()).toInt() else 0
                    val payload = r.rest()
                    val utf8 = if (flags and FLAG_DEFLATED != 0) inflate(payload) else payload
                    Text(id, sentAt, utf8.decodeToString(), expire)
                }
                TYPE_TIMER -> TimerChange(id, sentAt, r.varint(0L..MAX_EXPIRE_SECONDS.toLong()).toInt())
                TYPE_ACK -> Ack(id, sentAt)
                else -> Unknown(id, sentAt, type)
            }
        }

        private fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /** Bounded: a malicious "zip bomb" cannot expand beyond MAX_BODY_BYTES. */
        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (!inflater.finished()) {
                    val n = try {
                        inflater.inflate(buffer)
                    } catch (e: DataFormatException) {
                        throw MalformedException("bad compressed text: ${e.message}")
                    }
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw MalformedException("truncated compressed text")
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BODY_BYTES) throw MalformedException("text too long")
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}

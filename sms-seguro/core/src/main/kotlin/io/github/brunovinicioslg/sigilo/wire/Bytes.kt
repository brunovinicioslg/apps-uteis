package io.github.brunovinicioslg.sigilo.wire

/** Thrown for any malformed input from the network; the only exception parsers may raise. */
class MalformedException(message: String) : Exception(message)

/** Big-endian fixed-size values plus LEB128 varints and length-prefixed byte strings. */
class ByteWriter {
    private var buffer = ByteArray(256)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun byte(v: Int): ByteWriter {
        ensure(1)
        buffer[size++] = v.toByte()
        return this
    }

    fun varint(value: Long): ByteWriter {
        require(value >= 0) { "varint must not be negative" }
        var v = value
        while (v >= 0x80) {
            byte(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        return byte(v.toInt())
    }

    /** Length-prefixed bytes. */
    fun blob(b: ByteArray): ByteWriter {
        varint(b.size.toLong())
        return raw(b)
    }

    fun raw(b: ByteArray): ByteWriter {
        ensure(b.size)
        b.copyInto(buffer, size)
        size += b.size
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

class ByteReader(private val bytes: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = bytes.size - pos

    fun byte(): Int {
        if (pos >= bytes.size) throw MalformedException("truncated")
        return bytes[pos++].toInt() and 0xff
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 56) throw MalformedException("varint too long")
        }
    }

    /** A varint that must fit in [range]. */
    fun varint(range: LongRange): Long = varint().also { if (it !in range) throw MalformedException("value $it out of range") }

    fun blob(maxLength: Int = remaining): ByteArray {
        val length = varint()
        if (length > maxLength || length > remaining) throw MalformedException("blob too long")
        return raw(length.toInt())
    }

    fun raw(length: Int): ByteArray {
        if (length < 0 || length > remaining) throw MalformedException("truncated")
        return bytes.copyOfRange(pos, pos + length).also { pos += length }
    }

    fun rest(): ByteArray = raw(remaining)

    fun expectEnd() {
        if (remaining != 0) throw MalformedException("$remaining trailing bytes")
    }
}

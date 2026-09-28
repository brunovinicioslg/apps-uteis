package io.github.brunovinicioslg.sigilo.sms

import io.github.brunovinicioslg.sigilo.forEachCase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsTransportTest {

    /** Base64 plus the prefix: all in the GSM-7 basic table, so no carrier translation or UCS-2. */
    private val gsmSafe = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/', ':')).toSet()

    @Test
    fun splitAndParseRoundTrip() {
        forEachCase(500) { seed, rnd ->
            val envelope = ByteArray(rnd.nextInt(1, 3_000)).also { rnd.nextBytes(it) }
            val id = rnd.nextInt(1 shl 16)
            val sms = SmsCodec.split(envelope, id)
            assertEquals(SmsCodec.smsCount(envelope.size), sms.size)
            sms.forEach { text ->
                assertTrue(text.length <= SmsCodec.MAX_SMS_CHARS, "seed=$seed: ${text.length} chars")
                assertTrue(text.all { it in gsmSafe }, "seed=$seed: non GSM-7 character")
            }
            val fragments = sms.map { SmsCodec.parse(it)!! }
            assertTrue(fragments.all { it.messageId == id && it.count == sms.size })
            val joined = fragments.sortedBy { it.index }.flatMap { it.chunk.toList() }.toByteArray()
            assertContentEquals(envelope, joined, "seed=$seed")
        }
    }

    @Test
    fun ordinarySmsAreNotFragments() {
        listOf(
            "Oi, tudo bem?", "", "SG1", "SG1:", "SG1:!!!", "SG1:AAAA", "sg1:UQAAAQE",
            "Seu código de verificação é 123456", "SG1:" + "A".repeat(500),
        ).forEach { assertNull(SmsCodec.parse(it), it) }
    }

    @Test
    fun garbageNeverThrows() {
        forEachCase(5_000) { _, rnd ->
            val body = String(CharArray(rnd.nextInt(0, 200)) { (32 + rnd.nextInt(95)).toChar() })
            SmsCodec.parse(SmsCodec.PREFIX + body)
            SmsCodec.parse(body)
        }
    }

    @Test
    fun reassemblesInAnyOrderWithDuplicatesAndInterleaving() {
        forEachCase(300) { seed, rnd ->
            val r = Reassembler()
            val messages = (0 until rnd.nextInt(1, 6)).map { k ->
                val sender = if (rnd.nextBoolean()) "+5531900000001" else "+5531900000002"
                Triple(sender, ByteArray(rnd.nextInt(1, 1_500)).also { rnd.nextBytes(it) }, k)
            }
            val arrivals = messages.flatMap { (sender, envelope, k) ->
                SmsCodec.split(envelope, 1000 + k).map { sender to SmsCodec.parse(it)!! }
            }.let { all -> (all + all.filter { rnd.nextDouble() < 0.3 }).shuffled(rnd) }
            val completed = mutableListOf<Pair<String, ByteArray>>()
            arrivals.forEach { (sender, fragment) -> r.add(sender, fragment, now = 0)?.let { completed += sender to it } }
            for ((sender, envelope, _) in messages) {
                assertTrue(completed.any { it.first == sender && it.second.contentEquals(envelope) }, "seed=$seed")
            }
        }
    }

    @Test
    fun missingPartsKeepAMessagePendingUntilItExpires() {
        val r = Reassembler(maxAgeMillis = 1_000)
        val parts = SmsCodec.split(ByteArray(400) { it.toByte() }, 7).map { SmsCodec.parse(it)!! }
        parts.drop(1).forEach { assertNull(r.add("+55", it, now = 0)) }
        assertEquals(1, r.pendingCount)
        r.expire(now = 5_000)
        assertEquals(0, r.pendingCount)
        assertNull(r.add("+55", parts.first(), now = 5_000), "the rest expired")
    }

    @Test
    fun aFloodOfJunkCannotGrowMemory() {
        val r = Reassembler(maxPendingPerSender = 8)
        repeat(1_000) { i -> r.add("+5531999999999", Fragment(i, 0, 2, byteArrayOf(1)), now = i.toLong()) }
        assertTrue(r.pendingCount <= 8)
    }
}

package io.github.brunovinicioslg.sigilo.crypto

import io.github.brunovinicioslg.sigilo.Phone
import io.github.brunovinicioslg.sigilo.forEachCase
import io.github.brunovinicioslg.sigilo.wire.MalformedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionManagerTest {

    private val alice = Phone("+5531911110000")
    private val bob = Phone("+5531922220000")

    /** Bob accepts Alice's invite and sends the first message, which sets up Alice's side. */
    private fun connect(a: Phone = alice, b: Phone = bob) {
        val bundle = KeyBundle.decode(a.sessions.createBundle().encode())
        assertEquals(null, b.sessions.acceptBundle(a.number, bundle))
        val first = b.sessions.encrypt(a.number, "oi".encodeToByteArray())
        assertTrue(first.preKey)
        val opened = assertIs<Opened.Plaintext>(a.sessions.decrypt(b.number, first))
        assertEquals("oi", opened.bytes.decodeToString())
    }

    @Test
    fun conversationBothWays() {
        connect()
        repeat(200) { i ->
            val (from, to) = if (i % 3 == 0) bob to alice else alice to bob
            val text = "mensagem $i ç ã é 🙂"
            val sealed = from.sessions.encrypt(to.number, text.encodeToByteArray())
            val opened = assertIs<Opened.Plaintext>(to.sessions.decrypt(from.number, sealed), "message $i")
            assertEquals(text, opened.bytes.decodeToString())
        }
    }

    @Test
    fun smsDisorderLossAndDuplicates() {
        connect()
        forEachCase(10) { seed, rnd ->
            val sent = (0 until 60).map { i -> i to bob.sessions.encrypt(alice.number, "m$seed-$i".encodeToByteArray()) }
            // The network drops 20 %, shuffles, and duplicates some.
            val delivered = sent.filter { rnd.nextDouble() > 0.2 }
            val arrivals = (delivered + delivered.filter { rnd.nextDouble() < 0.2 }).shuffled(rnd)
            val seen = HashSet<Int>()
            for ((i, sealed) in arrivals) {
                when (val opened = alice.sessions.decrypt(bob.number, sealed)) {
                    is Opened.Plaintext -> {
                        assertTrue(seen.add(i), "decrypted twice: $i")
                        assertEquals("m$seed-$i", opened.bytes.decodeToString())
                    }
                    is Opened.Duplicate -> assertTrue(i in seen, "duplicate before first delivery: $i")
                    else -> error("seed=$seed message $i: $opened")
                }
            }
            assertEquals(delivered.map { it.first }.toSet(), seen)
        }
    }

    /** Flips one random byte of [sealed] and returns what the receiver makes of it. */
    private fun tamperedDelivery(sealed: Sealed, rnd: kotlin.random.Random): Opened {
        val bytes = sealed.bytes.copyOf()
        val position = rnd.nextInt(bytes.size)
        bytes[position] = (bytes[position].toInt() xor (1 + rnd.nextInt(255))).toByte()
        return alice.sessions.decrypt(bob.number, Sealed(sealed.preKey, bytes))
    }

    @Test
    fun tamperingNeverChangesWhatIsRead() {
        // Until Alice replies, Bob's messages repeat the session setup, which Alice already used and
        // ignores; a flipped byte there changes nothing that is read. The property that matters:
        // a tampered message is rejected or reads exactly as sent, never with altered content.
        connect()
        forEachCase(300) { seed, rnd ->
            val text = "valor: R$ 1.000,00 #$seed"
            val sealed = bob.sessions.encrypt(alice.number, text.encodeToByteArray())
            assertTrue(sealed.preKey)
            val result = tamperedDelivery(sealed, rnd)
            if (result is Opened.Plaintext) assertEquals(text, result.bytes.decodeToString(), "seed=$seed: altered content")
            val genuine = alice.sessions.decrypt(bob.number, sealed)
            assertTrue(genuine is Opened.Plaintext || genuine is Opened.Duplicate, "seed=$seed: $genuine")
        }
    }

    @Test
    fun anyTamperingOfAnOrdinaryMessageIsRejected() {
        connect()
        // Alice's reply confirms the session, so Bob sends ordinary (setup-free) messages from now on.
        bob.sessions.decrypt(alice.number, alice.sessions.encrypt(bob.number, "ok".encodeToByteArray()))
        forEachCase(500) { seed, rnd ->
            val sealed = bob.sessions.encrypt(alice.number, "valor: R$ 1.000,00".encodeToByteArray())
            assertTrue(!sealed.preKey)
            assertTrue(tamperedDelivery(sealed, rnd) !is Opened.Plaintext, "seed=$seed: forged message accepted")
            assertIs<Opened.Plaintext>(alice.sessions.decrypt(bob.number, sealed), "seed=$seed: genuine one still opens")
        }
    }

    @Test
    fun reinstallChangesTheIdentityAndNeedsConfirmation() {
        connect()
        val bobAgain = Phone(bob.number) // same number, new keys
        val bundle = alice.sessions.createBundle()
        assertEquals(null, bobAgain.sessions.acceptBundle(alice.number, bundle))
        val first = bobAgain.sessions.encrypt(alice.number, "reinstalei".encodeToByteArray())
        val changed = assertIs<Opened.IdentityChanged>(alice.sessions.decrypt(bob.number, first))
        assertContentEquals(bobAgain.store.identityKeyPair.publicKey.serialize(), changed.newIdentity.serialize())
        alice.sessions.trustIdentity(bob.number, changed.newIdentity)
        val opened = assertIs<Opened.Plaintext>(alice.sessions.decrypt(bob.number, first))
        assertEquals("reinstalei", opened.bytes.decodeToString())
    }

    @Test
    fun safetyNumbersMatchOnlyWithoutAManInTheMiddle() {
        connect()
        val onAlice = alice.sessions.safetyNumber(bob.number)!!
        val onBob = bob.sessions.safetyNumber(alice.number)!!
        assertEquals(60, onAlice.length)
        assertEquals(onAlice, onBob)
        // Mallory intercepts Alice's invite and gives Bob her own keys instead.
        val mallory = Phone(alice.number)
        val bobFooled = Phone("+5531933330000")
        bobFooled.sessions.acceptBundle(alice.number, mallory.sessions.createBundle())
        val real = Phone("+5531933330000")
        assertNotEquals(bobFooled.sessions.safetyNumber(alice.number), connectAndGet(alice, real))
    }

    private fun connectAndGet(a: Phone, b: Phone): String? {
        b.sessions.acceptBundle(a.number, a.sessions.createBundle())
        return b.sessions.safetyNumber(a.number)
    }

    @Test
    fun forgedBundlesAreRejected() {
        val bundle = alice.sessions.createBundle()
        val forgedKey = bundle.signedPreKey.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val forged = KeyBundle(
            bundle.registrationId, bundle.identityKey, bundle.signedPreKeyId, forgedKey, bundle.signedPreKeySignature,
            bundle.preKeyId, bundle.preKey, bundle.kyberPreKeyId, bundle.kyberPreKey, bundle.kyberPreKeySignature,
        )
        assertFailsWith<MalformedException> { bob.sessions.acceptBundle(alice.number, forged) }
        val truncated = bundle.encode().copyOf(bundle.encode().size - 3)
        assertFailsWith<MalformedException> { KeyBundle.decode(truncated) }
    }

    @Test
    fun withoutSessionThereIsNothingToOpen() {
        val stranger = Phone("+5531944440000")
        connect()
        val message = bob.sessions.encrypt(alice.number, "x".encodeToByteArray())
        val result = stranger.sessions.decrypt(bob.number, message)
        assertTrue(result is Opened.NoSession || result is Opened.Invalid, "$result")
        assertTrue(!stranger.sessions.hasSession(bob.number))
    }
}

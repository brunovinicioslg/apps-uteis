package io.github.brunovinicioslg.sigilo

import io.github.brunovinicioslg.sigilo.sms.SmsCodec
import io.github.brunovinicioslg.sigilo.wire.Content
import io.github.brunovinicioslg.sigilo.wire.MalformedException
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessengerTest {

    /** Delivers SMS texts one by one; returns what the receiver produced. */
    private fun deliver(from: Phone, to: Phone, sms: List<String>, now: Long = 0): List<Received> =
        sms.mapNotNull { to.messenger.receive(from.number, it, now) }

    private fun connect(alice: Phone, bob: Phone) {
        assertEquals(listOf<Received>(Received.InviteAccepted), deliver(alice, bob, alice.messenger.composeInvite()))
    }

    @Test
    fun inviteThenConversationOverSms() {
        val alice = Phone("+5531911110000")
        val bob = Phone("+5531922220000")
        connect(alice, bob)
        val first = deliver(bob, alice, bob.messenger.composeText(alice.number, "Oi Alice!", 0, now = 1_000_000))
        val text = assertIs<Content.Text>(assertIs<Received.Message>(first.single()).content)
        assertEquals("Oi Alice!", text.body)
        assertEquals(1_000L, text.sentAt)
        val reply = deliver(alice, bob, alice.messenger.composeText(bob.number, "Oi Bob! Vamos no sábado?", 3_600, now = 2_000_000))
        val replyText = assertIs<Content.Text>(assertIs<Received.Message>(reply.single()).content)
        assertEquals(3_600, replyText.expireSeconds)
        val timer = deliver(bob, alice, bob.messenger.composeTimerChange(alice.number, 86_400, now = 3_000_000))
        assertEquals(86_400, assertIs<Content.TimerChange>(assertIs<Received.Message>(timer.single()).content).expireSeconds)
    }

    @Test
    fun shuffledMultiPartMessagesArriveIntact() {
        val alice = Phone("+5531911110000")
        val bob = Phone("+5531922220000")
        connect(alice, bob)
        deliver(bob, alice, bob.messenger.composeText(alice.number, "primeira", 0, 0)) // sets up Alice's side
        forEachCase(20) { seed, rnd ->
            val texts = (0 until 5).map { i -> "Mensagem $seed.$i: " + "Lorem ipsum dolor sit amet, ação e emoção. ".repeat(rnd.nextInt(1, 30)) }
            val sms = texts.flatMap { bob.messenger.composeText(alice.number, it, 0, 0) }
            val arrivals = (sms + sms.filter { rnd.nextDouble() < 0.2 }).shuffled(rnd)
            val bodies = deliver(bob, alice, arrivals).filterIsInstance<Received.Message>().map { (it.content as Content.Text).body }
            assertEquals(texts.toSet(), bodies.toSet(), "seed=$seed")
        }
    }

    @Test
    fun ordinarySmsPassThrough() {
        val alice = Phone("+5531911110000")
        assertNull(alice.messenger.receive("+5531900000000", "Seu pedido saiu para entrega", 0))
        assertTrue(!alice.messenger.isSigilo("Promoção imperdível!"))
    }

    @Test
    fun garbageAfterThePrefixNeverCrashes() {
        val alice = Phone("+5531911110000")
        forEachCase(2_000) { _, rnd ->
            val bytes = ByteArray(rnd.nextInt(1, 110)).also { rnd.nextBytes(it) }
            // Valid framing, random content: exercises every parser behind the transport.
            bytes[0] = 0x51
            bytes.getOrNull(3)?.let { bytes[3] = 0 }
            bytes.getOrNull(4)?.let { bytes[4] = 1 }
            val text = SmsCodec.PREFIX + java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
            val result = alice.messenger.receive("+5531900000000", text, 0)
            assertTrue(result == null || result is Received.Failed || result is Received.Duplicate, "$result")
        }
    }

    @Test
    fun randomHeadersNeverCrash() {
        // Any index/count combination, including "part 9 of 3" from a malicious sender.
        val alice = Phone("+5531911110000")
        forEachCase(5_000) { _, rnd ->
            val bytes = ByteArray(rnd.nextInt(5, 60)).also { rnd.nextBytes(it) }
            bytes[0] = 0x51
            val text = SmsCodec.PREFIX + java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
            alice.messenger.receive("+5531900000000", text, 0)
        }
    }

    @Test
    fun contentCompressionAndLimits() {
        val long = Content.Text(1, 2, "Amanhã vamos à reunião às 14h. Não esqueça os documentos. ".repeat(10))
        val encoded = Content.encode(long)
        assertTrue(encoded.size < long.body.encodeToByteArray().size / 2, "repetitive text compresses")
        assertEquals(long, Content.decode(encoded))
        val short = Content.Text(3, 4, "ok", 60)
        assertEquals(short, Content.decode(Content.encode(short)))
        assertIs<Content.Unknown>(Content.decode(byteArrayOf(0x1F, 5, 6)))

        // A compressed "bomb" that would expand to 1 MB is refused.
        val bomb = Deflater(Deflater.BEST_COMPRESSION, true).run {
            setInput(ByteArray(1_000_000))
            finish()
            val out = ByteArray(10_000)
            val n = deflate(out)
            end()
            out.copyOf(n)
        }
        assertFailsWith<MalformedException> { Content.decode(byteArrayOf(0x11, 7, 8, 1) + bomb) }
    }

    @Test
    fun acknowledgementStopsRepeatingTheSetup() {
        val alice = Phone("+5531911110000")
        val bob = Phone("+5531922220000")
        connect(alice, bob)
        // Before Alice answers, each of Bob's messages carries the full setup.
        val before = (1..2).map { bob.messenger.composeText(alice.number, "msg $it", 0, 0) }
        assertTrue(before.all { it.size > 10 }, "setup repeated: ${before.map { it.size }}")
        val received = before.flatMap { deliver(bob, alice, it) }.map { assertIs<Received.Message>(it) }
        assertTrue(received.all { it.acknowledgeSetup })
        // Alice's app answers with one small SMS...
        val ack = alice.messenger.composeAck(bob.number, 0)
        assertEquals(1, ack.size, "the acknowledgement fits in one SMS")
        assertIs<Content.Ack>(assertIs<Received.Message>(deliver(alice, bob, ack).single()).content)
        // ...and Bob's next messages are small again (2 SMS for a short text: see SmsCodec).
        val after = bob.messenger.composeText(alice.number, "agora sim", 0, 0)
        assertTrue(after.size <= 2, "no more 16-SMS setup: ${after.size} SMS")
        val message = assertIs<Received.Message>(deliver(bob, alice, after).single())
        assertTrue(!message.acknowledgeSetup)
    }

    @Test
    fun smsCostOfEachKindOfMessage() {
        val alice = Phone("+5531911110000")
        val bob = Phone("+5531922220000")
        val invite = alice.messenger.composeInvite()
        deliver(alice, bob, invite)
        val first = bob.messenger.composeText(alice.number, "Oi, tudo bem?", 0, 0)
        deliver(bob, alice, first)
        val short = alice.messenger.composeText(bob.number, "Tudo ótimo, e você?", 0, 0)
        val medium = alice.messenger.composeText(bob.number, "Te encontro às 18h na praça, perto da padaria. Leva o documento, por favor!", 0, 0)
        println("SMS per kind: invite=${invite.size} first=${first.size} short=${short.size} medium=${medium.size}")
        // Budgets from the plan: key exchange about 10-20 SMS once; everyday messages 1-3 SMS.
        assertTrue(invite.size <= 20, "invite: ${invite.size}")
        assertTrue(first.size <= 20, "first message: ${first.size}")
        assertTrue(short.size <= 3, "short message: ${short.size}")
        assertTrue(medium.size <= 3, "medium message: ${medium.size}")
    }
}

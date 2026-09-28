package io.github.brunovinicioslg.sigilo.app.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.brunovinicioslg.sigilo.app.FakeNetwork
import io.github.brunovinicioslg.sigilo.app.LibsignalTestRunner
import io.github.brunovinicioslg.sigilo.app.TestPhone
import io.github.brunovinicioslg.sigilo.app.db.Encryption
import io.github.brunovinicioslg.sigilo.app.db.MessageKind
import io.github.brunovinicioslg.sigilo.app.db.MessageStatus
import io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent
import io.github.brunovinicioslg.sigilo.sms.SmsCodec
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LibsignalTestRunner::class)
class MessageEngineTest {

    private var now = 1_790_000_000_000L
    private val network = FakeNetwork()
    private val phones = mutableListOf<TestPhone>()

    private fun phone(number: String) = TestPhone(number, network, { now }).also { phones += it }

    private val alice = phone("+5531911110000")
    private val bob = phone("+5531922220000")

    @After
    fun tearDown() = phones.forEach(TestPhone::close)

    /** Text that does not compress: every character counts, as in real messages. */
    private fun varied(length: Int): String {
        val words = listOf("sol", "chuva", "amanhã", "cedo", "estrada", "ponte", "casa", "rio", "pão", "café", "noite", "vento")
        val rnd = kotlin.random.Random(length)
        return buildString { while (this.length < length) append(words[rnd.nextInt(words.size)]).append(rnd.nextInt(1000)).append(' ') }.take(length)
    }

    /** Delivers everything [from] sent to [to], in order. */
    private fun deliver(from: TestPhone, to: TestPhone) {
        now += 1_000
        to.receive(network.take(from, to))
    }

    /** The whole handshake: invite, acceptance, and the automatic confirmation back. */
    private fun pair() {
        alice.engine.startEncryption(alice.conversationWith(bob).id)
        deliver(alice, bob)
        bob.engine.acceptInvite(bob.conversationWith(alice).id)
        deliver(bob, alice)
        deliver(alice, bob) // the automatic acknowledgement
    }

    @Test
    fun ordinarySmsInAndOut() {
        val providerId = alice.system.addIncoming("+5531922220000", "Oi, tudo bem?", now)
        alice.inbox.addSms("031 92222-0000", "Oi, tudo bem?", 1, now, providerId)
        alice.engine.drain()

        val conversation = alice.conversationWith(bob)
        assertThat(conversation.unread).isEqualTo(1)
        assertThat(conversation.encryption).isEqualTo(Encryption.NONE)
        val received = alice.messagesWith(bob).single()
        assertThat(received.body).isEqualTo("Oi, tudo bem?")
        assertThat(received.encrypted).isFalse()
        assertThat(alice.events.notified.single().second.single().body).isEqualTo("Oi, tudo bem?")

        alice.engine.markRead(conversation.id)
        assertThat(alice.system.read).containsExactly(providerId)
        alice.engine.sendText(conversation.id, "Tudo ótimo!")
        val sent = alice.sent.single()
        assertThat(sent.text).isEqualTo("Tudo ótimo!")
        assertThat(sent.to).isEqualTo("+5531922220000")
        assertThat(alice.system.rows.last().body).isEqualTo("Tudo ótimo!")
        val outgoing = alice.messagesWith(bob).last()
        assertThat(outgoing.status).isEqualTo(MessageStatus.PENDING)

        alice.inbox.addStatus(outgoing.id, SmsEvent.SENT)
        alice.engine.drain()
        assertThat(alice.system.statuses).containsExactly(outgoing.providerId!! to SmsEvent.SENT)
    }

    @Test
    fun handshakeThenEncryptedConversation() {
        alice.engine.startEncryption(alice.conversationWith(bob).id)
        val inviteSms = network.inFlight.size
        assertThat(inviteSms).isIn(10..20)
        assertThat(alice.conversationWith(bob).encryption).isEqualTo(Encryption.INVITE_SENT)

        deliver(alice, bob)
        assertThat(bob.conversationWith(alice).encryption).isEqualTo(Encryption.INVITE_RECEIVED)
        assertThat(bob.messagesWith(alice).last().kind).isEqualTo(MessageKind.INVITE_RECEIVED)

        bob.engine.acceptInvite(bob.conversationWith(alice).id)
        assertThat(bob.conversationWith(alice).encryption).isEqualTo(Encryption.ACTIVE)
        deliver(bob, alice)
        assertThat(alice.conversationWith(bob).encryption).isEqualTo(Encryption.ACTIVE)
        assertThat(alice.messagesWith(bob).map { it.kind }).contains(MessageKind.ENCRYPTION_ON)

        // Alice confirmed automatically, so Bob's messages no longer carry the 16-SMS setup.
        deliver(alice, bob)
        bob.sent.clear()
        bob.engine.sendText(bob.conversationWith(alice).id, "Agora ninguém lê isto.")
        assertThat(bob.sent.size).isAtMost(3)
        assertThat(bob.sent.all { it.text.startsWith("SG1:") }).isTrue()
        assertThat(bob.sent.none { "ninguém" in it.text }).isTrue()
        deliver(bob, alice)

        val text = alice.messagesWith(bob).last()
        assertThat(text.body).isEqualTo("Agora ninguém lê isto.")
        assertThat(text.encrypted).isTrue()
        assertThat(text.read).isFalse()
        // Encrypted messages never go to the system SMS database.
        assertThat(alice.system.rows).isEmpty()
        assertThat(bob.system.rows).isEmpty()
    }

    @Test
    fun partsArriveShuffledDuplicatedAndAcrossARestart() {
        pair()
        val long = varied(500)
        alice.engine.sendText(alice.conversationWith(bob).id, long)
        val parts = network.take(alice, bob).shuffled(kotlin.random.Random(7))
        assertThat(parts.size).isAtLeast(3)

        bob.receive(parts.take(2) + parts.take(1))
        assertThat(bob.messagesWith(alice).none { it.body == long }).isTrue()
        bob.restart() // the phone rebooted before the rest arrived
        bob.receive(parts.drop(2) + parts.last())

        assertThat(bob.messagesWith(alice).count { it.body == long }).isEqualTo(1)
    }

    @Test
    fun smsWaitWhileTheAppIsLockedAndKeepTheirOrder() {
        pair()
        val conversation = alice.conversationWith(bob).id
        val first = "primeira: " + varied(150)
        val second = "segunda: " + varied(160)
        alice.engine.sendText(conversation, first)
        alice.engine.sendText(conversation, second)
        val sms = network.take(alice, bob)
        bob.receive(sms, process = false)
        bob.inbox.addSms("+5531955550000", "SMS comum", 1, now, null)
        bob.inbox.addStatus(1, io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent.SENT)
        // Two encrypted messages (however many SMS each) and one ordinary SMS; reports do not count.
        assertThat(sms.size).isAtLeast(4)
        assertThat(bob.inbox.waitingMessages()).isEqualTo(3)

        bob.engine.drain() // unlocked
        assertThat(bob.inbox.count()).isEqualTo(0)
        assertThat(bob.messagesWith(alice).filter { it.kind == MessageKind.TEXT }.map { it.body }).containsExactly(first, second).inOrder()
        assertThat(bob.inbox.waitingMessages()).isEqualTo(0)
    }

    @Test
    fun deliveryReportsCountEverySms() {
        pair()
        alice.sent.clear()
        val id = alice.engine.sendText(alice.conversationWith(bob).id, varied(400))!!
        val parts = alice.sent.count { it.messageId == id }
        assertThat(parts).isAtLeast(2)

        alice.inbox.addStatus(id, SmsEvent.SENT)
        alice.engine.drain()
        assertThat(alice.engine.repository.message(id)!!.status).isEqualTo(MessageStatus.PENDING)
        repeat(parts - 1) { alice.inbox.addStatus(id, SmsEvent.SENT) }
        alice.engine.drain()
        assertThat(alice.engine.repository.message(id)!!.status).isEqualTo(MessageStatus.SENT)
        repeat(parts) { alice.inbox.addStatus(id, SmsEvent.DELIVERED) }
        alice.engine.drain()
        assertThat(alice.engine.repository.message(id)!!.status).isEqualTo(MessageStatus.DELIVERED)

        val failed = alice.engine.sendText(alice.conversationWith(bob).id, "falha")!!
        alice.inbox.addStatus(failed, SmsEvent.SEND_FAILED)
        alice.engine.drain()
        assertThat(alice.engine.repository.message(failed)!!.status).isEqualTo(MessageStatus.FAILED)
    }

    @Test
    fun disappearingMessagesFollowTheTimerOnBothPhones() {
        pair()
        alice.engine.setTimer(alice.conversationWith(bob).id, 60)
        deliver(alice, bob)
        assertThat(bob.conversationWith(alice).expireSeconds).isEqualTo(60)
        assertThat(bob.messagesWith(alice).last().kind).isEqualTo(MessageKind.TIMER_CHANGED)

        val sentAt = now
        alice.engine.sendText(alice.conversationWith(bob).id, "Isto some em um minuto")
        deliver(alice, bob)
        val received = bob.messagesWith(alice).last()
        assertThat(received.expireSeconds).isEqualTo(60)
        assertThat(received.expireAt).isNull() // counts from reading

        now += 30_000
        val readAt = now
        bob.engine.markRead(bob.conversationWith(alice).id)
        assertThat(bob.engine.repository.message(received.id)!!.expireAt).isEqualTo(readAt + 60_000)
        assertThat(bob.events.nextExpiry).isEqualTo(readAt + 60_000)

        now = sentAt + 61_000
        alice.engine.deleteExpired()
        assertThat(alice.messagesWith(bob).none { it.body == "Isto some em um minuto" }).isTrue()
        bob.engine.deleteExpired()
        assertThat(bob.messagesWith(alice).any { it.body == "Isto some em um minuto" }).isTrue()

        now = readAt + 60_000
        // Hidden on time even if the deletion job is late, and then deleted for good.
        assertThat(bob.messagesWith(alice).none { it.body == "Isto some em um minuto" }).isTrue()
        bob.engine.deleteExpired()
        assertThat(bob.engine.repository.message(received.id)).isNull()
    }

    @Test
    fun aReinstalledContactNeedsConfirmationBeforeAnythingIsRead() {
        pair()
        // Bob reinstalls the app: new keys, same number.
        val bobAgain = phone(bob.number)
        bobAgain.engine.startEncryption(bobAgain.conversationWith(alice).id)
        deliver(bobAgain, alice)

        val conversation = alice.conversationWith(bob)
        assertThat(conversation.keyChanged).isTrue()
        assertThat(alice.messagesWith(bob).last().kind).isEqualTo(MessageKind.KEY_CHANGED)
        assertThat(alice.events.notified.last().second.single().kind).isEqualTo(MessageKind.KEY_CHANGED)

        alice.engine.acceptNewKey(conversation.id)
        assertThat(alice.conversationWith(bob).keyChanged).isFalse()
        assertThat(alice.conversationWith(bob).encryption).isEqualTo(Encryption.INVITE_RECEIVED)

        alice.engine.acceptInvite(alice.conversationWith(bob).id)
        deliver(alice, bobAgain)
        deliver(bobAgain, alice)
        bobAgain.engine.sendText(bobAgain.conversationWith(alice).id, "Voltei!")
        deliver(bobAgain, alice)
        assertThat(alice.messagesWith(bob).last().body).isEqualTo("Voltei!")
    }

    @Test
    fun anEncryptedMessageWithoutASessionSaysSoOnce() {
        pair()
        val stranger = phone("+5531933330000")
        // Pretends to be Bob but has no session with Alice: nothing can be opened.
        stranger.engine.startEncryption(stranger.conversationWith(alice).id)
        network.take(stranger, alice)
        alice.engine.sendText(alice.conversationWith(bob).id, "para o Bob")
        val toBob = network.take(alice, bob)
        stranger.receive(toBob)
        stranger.receive(toBob.map { it.copy(text = it.text) })
        assertThat(stranger.messagesWith(alice).count { it.kind == MessageKind.UNREADABLE }).isEqualTo(1)
    }

    @Test
    fun importingTheSystemHistoryOnceAndThenOnlyWhatIsNew() {
        alice.system.addIncoming("+5531922220000", "antiga 1", now - 10_000)
        alice.system.insertOutgoing("+5531922220000", "antiga 2", -1, now - 5_000)
        // A part of an encrypted message left by an earlier install is not a message.
        alice.system.addIncoming("+5531922220000", SmsCodec.split(ByteArray(300), 7).first(), now - 4_000)
        assertThat(alice.engine.importSystemSms()).isEqualTo(2)
        assertThat(alice.engine.importSystemSms()).isEqualTo(0)
        alice.system.addIncoming("+5531944440000", "nova", now)
        assertThat(alice.engine.importSystemSms()).isEqualTo(1)

        val history = alice.messagesWith(bob)
        assertThat(history.map { it.body }).containsExactly("antiga 1", "antiga 2").inOrder()
        assertThat(history[1].outgoing).isTrue()
        assertThat(alice.engine.conversations().map { it.address }).containsExactly("+5531922220000", "+5531944440000")
    }

    @Test
    fun theSmsEstimateMatchesWhatIsSent() {
        pair()
        val conversation = bob.conversationWith(alice).id
        // Alice's confirmation arrived, so the setup is no longer repeated.
        for (length in listOf(1, 10, 20, 40, 80, 120, 200, 300, 450, 700)) {
            val text = varied(length)
            val estimate = bob.engine.estimateSms(conversation, text)
            bob.sent.clear()
            bob.engine.sendText(conversation, text)
            // Never fewer than really sent; one more at most, when the random message id is short.
            assertWithMessage("SMS for length $length").that(estimate).isIn(bob.sent.size..bob.sent.size + 1)
            network.take(bob, alice)
        }
        assertThat(alice.engine.estimateSms(alice.conversationWith(bob).id, "oi")).isEqualTo(1)
        val plain = phone("+5531955550000")
        assertThat(plain.engine.estimateSms(plain.conversationWith(alice).id, "x".repeat(170))).isEqualTo(2)
    }

    @Test
    fun anItemThatKeepsFailingIsRetriedThenGivenUpWithoutBlockingOthers() {
        alice.failOn = "quebrado"
        alice.inbox.addSent("+5531922220000", "quebrado", -1, now, null)
        alice.inbox.addSms("+5531922220000", "depois", 1, now, null)
        alice.engine.drain()
        assertThat(alice.messagesWith(bob).map { it.body }).containsExactly("depois")
        assertThat(alice.inbox.count()).isEqualTo(1) // kept for another try

        alice.failOn = null // e.g. a fixed transient problem: the retry succeeds
        alice.engine.drain()
        assertThat(alice.inbox.count()).isEqualTo(0)
        assertThat(alice.messagesWith(bob).map { it.body }).containsExactly("depois", "quebrado")

        alice.failOn = "sempre"
        alice.inbox.addSent("+5531922220000", "sempre", -1, now, null)
        repeat(MessageEngine.MAX_ATTEMPTS - 1) { alice.engine.drain() }
        assertThat(alice.inbox.count()).isEqualTo(1)
        alice.engine.drain()
        assertThat(alice.inbox.count()).isEqualTo(0) // given up after the last try
        assertThat(alice.messagesWith(bob).none { it.body == "sempre" }).isTrue()
    }

    @Test
    fun deletingAConversationAlsoDeletesItsSystemSms() {
        val providerId = alice.system.addIncoming("+5531922220000", "apague", now)
        alice.inbox.addSms("+5531922220000", "apague", 1, now, providerId)
        alice.engine.drain()
        alice.engine.deleteConversation(alice.conversationWith(bob).id)
        assertThat(alice.system.deleted).containsExactly(providerId)
        assertThat(alice.engine.conversations()).isEmpty()
    }
}

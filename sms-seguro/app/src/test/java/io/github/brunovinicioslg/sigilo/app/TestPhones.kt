package io.github.brunovinicioslg.sigilo.app

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.db.SigiloDatabase
import io.github.brunovinicioslg.sigilo.app.engine.AddressNormalizer
import io.github.brunovinicioslg.sigilo.app.engine.EngineEvents
import io.github.brunovinicioslg.sigilo.app.engine.MessageEngine
import io.github.brunovinicioslg.sigilo.app.engine.SmsGateway
import io.github.brunovinicioslg.sigilo.app.engine.SystemSms
import io.github.brunovinicioslg.sigilo.app.engine.SystemSmsRow
import io.github.brunovinicioslg.sigilo.app.inbox.Inbox
import io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * libsignal's native library can be loaded only once per JVM, but Robolectric loads classes anew
 * for each sandbox. Letting the JVM's own class loader take libsignal avoids a second load.
 */
class LibsignalTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method)).doNotAcquirePackage("org.signal.libsignal").build()
}

/** An SMS on its way: who sent it, to whom, the text and the app's message id for status reports. */
data class SentSms(val from: String, val to: String, val text: String, val messageId: Long)

/** SMS that phones sent and that the test delivers when and how it wants (in order, shuffled, twice). */
class FakeNetwork {
    val inFlight = mutableListOf<SentSms>()

    fun take(from: TestPhone, to: TestPhone): List<SentSms> {
        val mine = inFlight.filter { it.from == from.number && it.to == to.number }
        inFlight.removeAll(mine)
        return mine
    }
}

class FakeSystemSms : SystemSms {
    val rows = mutableListOf<SystemSmsRow>()
    val read = mutableSetOf<Long>()
    val deleted = mutableSetOf<Long>()
    private var nextId = 1L

    fun addIncoming(address: String, body: String, date: Long): Long {
        val id = nextId++
        rows += SystemSmsRow(id, address, body, date, date, outgoing = false, read = false, failed = false, subscriptionId = -1)
        return id
    }

    override fun insertOutgoing(address: String, body: String, subscriptionId: Int, timestamp: Long): Long {
        val id = nextId++
        rows += SystemSmsRow(id, address, body, timestamp, timestamp, outgoing = true, read = true, failed = false, subscriptionId = subscriptionId)
        return id
    }

    val statuses = mutableListOf<Pair<Long, SmsEvent>>()

    override fun updateStatus(providerId: Long, event: SmsEvent) {
        statuses += providerId to event
    }

    override fun markRead(providerIds: List<Long>) {
        read += providerIds
    }

    override fun delete(providerIds: List<Long>) {
        deleted += providerIds
        rows.removeAll { it.id in providerIds }
    }

    override fun history(afterId: Long, limit: Int): List<SystemSmsRow> = rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
}

class RecordingEvents : EngineEvents {
    val notified = mutableListOf<Pair<Conversation, List<Message>>>()
    var nextExpiry: Long? = null

    override fun onNewMessages(conversation: Conversation, messages: List<Message>) {
        notified += conversation to messages
    }

    override fun onChanged() = Unit

    override fun onNextExpiry(at: Long?) {
        nextExpiry = at
    }
}

/**
 * A phone with the app installed: its own encrypted-database schema (on plain SQLite in tests),
 * inbox and SMS radio connected to [network]. [clock] is shared test time.
 */
class TestPhone(
    val number: String,
    private val network: FakeNetwork,
    private val clock: () -> Long,
    private val dbName: String = "phone-${number.filter(Char::isDigit)}-${System.nanoTime()}.db",
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val inbox = Inbox(context, null)
    val system = FakeSystemSms()
    val events = RecordingEvents()

    /** Processing anything with this text throws, to test failures. */
    var failOn: String? = null

    /** SMS this phone sent, one entry per SMS. */
    val sent = mutableListOf<SentSms>()

    private val gateway = object : SmsGateway {
        override fun sendEach(address: String, texts: List<String>, subscriptionId: Int, messageId: Long) {
            texts.forEach { record(SentSms(number, address, it, messageId)) }
        }

        override fun sendText(address: String, text: String, subscriptionId: Int, messageId: Long): Int {
            val parts = partsOf(text)
            record(SentSms(number, address, text, messageId))
            return parts
        }

        override fun partsOf(text: String): Int {
            check(text != failOn) { "simulated failure" }
            return maxOf(1, (text.length + 152) / 153)
        }
    }

    private var db = open()
    var engine = newEngine()
        private set

    private fun record(sms: SentSms) {
        sent += sms
        network.inFlight += sms
    }

    private fun open() = SigiloDatabase.open(context, FrameworkSQLiteOpenHelperFactory(), dbName)

    private fun newEngine() = MessageEngine(db, inbox, gateway, system, events, AddressNormalizer { "BR" }, clock)

    /** The app process died and started again: same files, fresh memory. */
    fun restart() {
        db.close()
        db = open()
        engine = newEngine()
    }

    /** The SMS arrive (queued like the real receiver does) and the engine takes them. */
    fun receive(messages: List<SentSms>, process: Boolean = true) {
        for (m in messages) inbox.addSms(m.from, m.text, subscriptionId = 1, timestamp = clock(), providerId = null)
        if (process) engine.drain()
    }

    /** Radio reports for everything this phone sent: each SMS left, and was delivered. */
    fun reportAll(event: SmsEvent) {
        sent.filter { it.messageId > 0 }.forEach { inbox.addStatus(it.messageId, event) }
        engine.drain()
    }

    fun conversationWith(other: TestPhone): Conversation = engine.conversationFor(other.number)

    fun messagesWith(other: TestPhone): List<Message> = engine.messages(conversationWith(other).id)

    fun close() {
        inbox.close()
        db.close()
    }
}

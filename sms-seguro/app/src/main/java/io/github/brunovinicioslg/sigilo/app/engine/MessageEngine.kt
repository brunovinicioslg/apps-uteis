package io.github.brunovinicioslg.sigilo.app.engine

import android.util.Log
import io.github.brunovinicioslg.sigilo.Messenger
import io.github.brunovinicioslg.sigilo.Received
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.DbSignalStore
import io.github.brunovinicioslg.sigilo.app.db.Encryption
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.db.MessageKind
import io.github.brunovinicioslg.sigilo.app.db.MessageRepository
import io.github.brunovinicioslg.sigilo.app.db.MessageStatus
import io.github.brunovinicioslg.sigilo.app.db.SigiloDatabase
import io.github.brunovinicioslg.sigilo.app.inbox.Inbox
import io.github.brunovinicioslg.sigilo.app.inbox.InboxItem
import io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent
import io.github.brunovinicioslg.sigilo.crypto.SessionManager
import io.github.brunovinicioslg.sigilo.sms.Reassembler
import io.github.brunovinicioslg.sigilo.sms.SmsCodec
import io.github.brunovinicioslg.sigilo.wire.Content
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress

/**
 * Everything that happens to messages once the database is open: takes the SMS waiting in the
 * inbox, opens encrypted ones, records ordinary ones, sends, and runs the encryption handshake.
 *
 * Not thread-safe: the app calls it from one thread only. Each incoming SMS is handled in one
 * database transaction, and only then removed from the inbox, so a crash in between replays it
 * harmlessly (the Signal protocol recognizes the repeat and it is ignored).
 */
class MessageEngine(
    private val db: SigiloDatabase,
    private val inbox: Inbox,
    private val sms: SmsGateway,
    private val system: SystemSms,
    private val events: EngineEvents,
    private val addresses: AddressNormalizer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val repository = MessageRepository(db)
    private val store = DbSignalStore(db, clock)
    private val sessions = SessionManager(store)
    private val messenger = Messenger(sessions, Reassembler())

    /** Work that must wait until the current transaction is committed (sending SMS). */
    private val afterCommit = mutableListOf<() -> Unit>()

    /** New incoming messages of the current batch, per conversation, for notifications. */
    private val fresh = LinkedHashMap<Long, MutableList<Long>>()

    init {
        // Parts of encrypted messages that arrived before the app last stopped.
        repository.deleteFragmentsBefore(clock() - Reassembler.DEFAULT_MAX_AGE_MILLIS)
        for (f in repository.fragments()) {
            val received = messenger.receive(f.sender, f.text, f.receivedAt) ?: continue
            // Only after a crash between completing and recording a message.
            inTransaction { completeEnvelope(f.sender, f.text, received, f.receivedAt) }
        }
    }

    // Incoming.

    /** Processes everything waiting in the inbox; returns how many items were handled. */
    fun drain(): Int {
        var handled = 0
        // Items that failed stay for a later drain; each is tried once per drain.
        val tried = HashSet<Long>()
        while (true) {
            val batch = inbox.items().filter { it.id !in tried }
            if (batch.isEmpty()) break
            for (item in batch) {
                tried += item.id
                try {
                    inTransaction { process(item) }
                    inbox.remove(item.id)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Could not process inbox item ${item.id}", e)
                    // Kept for another try, but a message that always fails must not wait forever.
                    if (inbox.recordFailure(item.id) >= MAX_ATTEMPTS) {
                        inbox.remove(item.id)
                        noteUnprocessable(item)
                    }
                }
                handled++
            }
        }
        if (handled > 0) {
            publishFresh()
            events.onChanged()
            events.onNextExpiry(repository.nextExpiry())
        }
        return handled
    }

    /** An SMS that could not be handled at all: at least the conversation says something arrived. */
    private fun noteUnprocessable(item: InboxItem) {
        val address = when (item) {
            is InboxItem.Sms -> item.address
            is InboxItem.Mms -> item.address
            else -> return
        }
        try {
            inTransaction {
                val conversation = repository.conversationFor(addresses.normalize(address))
                addIncoming(conversation, MessageKind.UNREADABLE, "", encrypted = true, sentAt = clock(), receivedAt = clock())
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not even record the failure", e)
        }
    }

    private fun process(item: InboxItem) {
        when (item) {
            is InboxItem.Sms -> receiveSms(item)
            is InboxItem.Status -> applyStatus(item.messageId, item.event)
            is InboxItem.Mms -> {
                val conversation = repository.conversationFor(addresses.normalize(item.address))
                addIncoming(conversation, MessageKind.MMS, "", encrypted = false, sentAt = item.timestamp, receivedAt = item.timestamp, providerId = item.providerId)
            }
            is InboxItem.Sent -> {
                val conversation = repository.conversationFor(addresses.normalize(item.address))
                repository.insertMessage(
                    conversation.id, outgoing = true, kind = MessageKind.TEXT, body = item.body, encrypted = false, status = MessageStatus.SENT,
                    sentAt = item.timestamp, receivedAt = item.timestamp, read = true, parts = sms.partsOf(item.body), providerId = item.providerId,
                )
            }
        }
    }

    private fun receiveSms(item: InboxItem.Sms) {
        val address = addresses.normalize(item.address)
        val conversation = repository.conversationFor(address)
        if (conversation.subscriptionId == NO_SUBSCRIPTION && item.subscriptionId != NO_SUBSCRIPTION) {
            repository.setSubscription(conversation.id, item.subscriptionId)
        }
        if (!messenger.isSigilo(item.body)) {
            addIncoming(conversation, MessageKind.TEXT, item.body, encrypted = false, sentAt = item.timestamp, receivedAt = item.timestamp, providerId = item.providerId)
            return
        }
        val received = messenger.receive(address, item.body, item.timestamp)
        if (received == null) {
            // More parts to come: keep this one, in case the app stops before they arrive.
            SmsCodec.parse(item.body)?.let { repository.addFragment(address, it.messageId, item.body, item.timestamp) }
            return
        }
        completeEnvelope(address, item.body, received, item.timestamp)
    }

    private fun completeEnvelope(address: String, lastPart: String, received: Received, at: Long) {
        SmsCodec.parse(lastPart)?.let { repository.deleteFragments(address, it.messageId) }
        handleReceived(repository.conversationFor(address), received, at)
    }

    private fun handleReceived(conversation: Conversation, received: Received, at: Long) {
        when (received) {
            is Received.Message -> handleContent(conversation, received.content, received.acknowledgeSetup, at)
            Received.InviteAccepted -> {
                if (conversation.encryption != Encryption.INVITE_RECEIVED) {
                    repository.setEncryption(conversation.id, Encryption.INVITE_RECEIVED)
                    addIncoming(conversation, MessageKind.INVITE_RECEIVED, "", encrypted = true, sentAt = at, receivedAt = at)
                }
            }
            is Received.InviteWithNewIdentity -> hold(conversation, received.envelope, received.identity, at)
            is Received.IdentityChanged -> hold(conversation, received.envelope, received.identity, at)
            Received.Duplicate -> Unit
            is Received.Failed -> {
                Log.w(TAG, "Unreadable encrypted message: ${received.reason}")
                // One notice is enough: a burst of broken messages should not flood the conversation.
                val last = repository.messages(conversation.id, clock()).lastOrNull()
                if (last?.kind != MessageKind.UNREADABLE) {
                    addIncoming(conversation, MessageKind.UNREADABLE, "", encrypted = true, sentAt = at, receivedAt = at)
                }
            }
        }
    }

    private fun handleContent(conversation: Conversation, content: Content, acknowledgeSetup: Boolean, at: Long) {
        if (conversation.encryption != Encryption.ACTIVE) {
            repository.setEncryption(conversation.id, Encryption.ACTIVE)
            repository.insertMessage(
                conversation.id, outgoing = false, kind = MessageKind.ENCRYPTION_ON, body = "", encrypted = true,
                status = MessageStatus.RECEIVED, sentAt = at, receivedAt = at, read = true,
            )
        }
        when (content) {
            is Content.Text -> {
                if (content.expireSeconds != conversation.expireSeconds) {
                    // The sender's timer wins, as it does in the conversation on their phone.
                    changeTimer(conversation, content.expireSeconds, outgoing = false, at = at)
                }
                addIncoming(
                    conversation, MessageKind.TEXT, content.body, encrypted = true, sentAt = content.sentAt * 1000, receivedAt = at,
                    remoteId = content.id, expireSeconds = content.expireSeconds,
                )
            }
            is Content.TimerChange -> changeTimer(conversation, content.expireSeconds, outgoing = false, at = at)
            is Content.Ack -> Unit
            is Content.Unknown -> addIncoming(conversation, MessageKind.UNSUPPORTED, "", encrypted = true, sentAt = content.sentAt * 1000, receivedAt = at)
        }
        if (acknowledgeSetup && at - conversation.lastEncryptedSentAt > ACK_INTERVAL_MS) {
            // Until the sender hears from us, each of its messages repeats the 16-SMS setup.
            afterCommit += { sendAck(conversation.id) }
        }
    }

    private fun hold(conversation: Conversation, envelope: ByteArray, identity: IdentityKey, at: Long) {
        repository.hold(conversation.address, envelope, identity.serialize(), at)
        if (!conversation.keyChanged) {
            repository.setKeyChanged(conversation.id, true)
            addIncoming(conversation, MessageKind.KEY_CHANGED, "", encrypted = true, sentAt = at, receivedAt = at)
        }
    }

    private fun addIncoming(
        conversation: Conversation,
        kind: MessageKind,
        body: String,
        encrypted: Boolean,
        sentAt: Long,
        receivedAt: Long,
        remoteId: Long? = null,
        providerId: Long? = null,
        expireSeconds: Int = 0,
    ) {
        val id = repository.insertMessage(
            conversation.id, outgoing = false, kind = kind, body = body, encrypted = encrypted, status = MessageStatus.RECEIVED,
            sentAt = sentAt, receivedAt = receivedAt, read = false, remoteId = remoteId, providerId = providerId, expireSeconds = expireSeconds,
        )
        if (id != -1L) fresh.getOrPut(conversation.id) { mutableListOf() } += id
    }

    private fun applyStatus(messageId: Long, event: SmsEvent) {
        if (messageId <= 0) return // acknowledgements have no row
        val message = when (event) {
            SmsEvent.SENT -> repository.partSent(messageId)
            SmsEvent.DELIVERED -> repository.partDelivered(messageId)
            SmsEvent.SEND_FAILED, SmsEvent.DELIVERY_FAILED -> {
                repository.setStatus(messageId, MessageStatus.FAILED)
                repository.message(messageId)
            }
        } ?: return
        message.providerId?.let { providerId ->
            // The system database learns the outcome once the whole SMS is through (or any part failed).
            val settled = when (event) {
                SmsEvent.SENT -> message.status == MessageStatus.SENT
                SmsEvent.DELIVERED -> message.status == MessageStatus.DELIVERED
                else -> true
            }
            if (settled) afterCommit += { system.updateStatus(providerId, event) }
        }
    }

    private fun publishFresh() {
        for ((conversationId, ids) in fresh) {
            val conversation = repository.conversation(conversationId) ?: continue
            val messages = ids.mapNotNull(repository::message)
            if (messages.isNotEmpty()) events.onNewMessages(conversation, messages)
        }
        fresh.clear()
    }

    // Outgoing.

    /** Encrypted when the conversation is, as an ordinary SMS otherwise. Returns the message id. */
    fun sendText(conversationId: Long, text: String): Long? {
        val conversation = repository.conversation(conversationId) ?: return null
        if (text.isBlank()) return null
        val now = clock()
        val id = if (conversation.encryption == Encryption.ACTIVE && sessions.hasSession(conversation.address)) {
            sendEncrypted(conversation, MessageKind.TEXT, text, expireSeconds = conversation.expireSeconds) {
                messenger.composeText(conversation.address, text, conversation.expireSeconds, now)
            }
        } else {
            val providerId = system.insertOutgoing(conversation.address, text, conversation.subscriptionId, now)
            val id = inTransaction {
                repository.insertMessage(
                    conversation.id, outgoing = true, kind = MessageKind.TEXT, body = text, encrypted = false, status = MessageStatus.PENDING,
                    sentAt = now, receivedAt = now, read = true, parts = sms.partsOf(text), providerId = providerId,
                )
            }
            transmit(id) {
                val parts = sms.sendText(conversation.address, text, conversation.subscriptionId, id)
                repository.setParts(id, parts)
            }
            id
        }
        events.onChanged()
        events.onNextExpiry(repository.nextExpiry())
        return id
    }

    /** Sends our public keys (about 17 SMS); the contact's app can then start the encrypted conversation. */
    fun startEncryption(conversationId: Long) {
        val conversation = repository.conversation(conversationId) ?: return
        val now = clock()
        val (id, parts) = inTransaction {
            val parts = messenger.composeInvite()
            val id = repository.insertMessage(
                conversation.id, outgoing = true, kind = MessageKind.INVITE_SENT, body = "", encrypted = true, status = MessageStatus.PENDING,
                sentAt = now, receivedAt = now, read = true, parts = parts.size,
            )
            if (conversation.encryption == Encryption.NONE) repository.setEncryption(conversation.id, Encryption.INVITE_SENT)
            id to parts
        }
        transmit(id) { sms.sendEach(conversation.address, parts, conversation.subscriptionId, id) }
        events.onChanged()
    }

    /** Accepts the contact's invite: our first encrypted message completes the setup on their side. */
    fun acceptInvite(conversationId: Long) {
        val conversation = repository.conversation(conversationId) ?: return
        if (conversation.encryption != Encryption.INVITE_RECEIVED || !sessions.hasSession(conversation.address)) return
        val now = clock()
        sendEncrypted(conversation, MessageKind.ENCRYPTION_ON, "", expireSeconds = 0) {
            repository.setEncryption(conversation.id, Encryption.ACTIVE)
            messenger.composeAck(conversation.address, now)
        }
        events.onChanged()
    }

    fun declineInvite(conversationId: Long) {
        val conversation = repository.conversation(conversationId) ?: return
        if (conversation.encryption != Encryption.INVITE_RECEIVED) return
        val now = clock()
        inTransaction {
            store.deleteSession(SignalProtocolAddress(conversation.address, SESSION_DEVICE))
            repository.setEncryption(conversation.id, Encryption.NONE)
            repository.insertMessage(
                conversation.id, outgoing = true, kind = MessageKind.INVITE_DECLINED, body = "", encrypted = false,
                status = MessageStatus.SENT, sentAt = now, receivedAt = now, read = true,
            )
        }
        events.onChanged()
    }

    /**
     * The user checked the contact's new safety number and accepts it: the held messages are
     * opened now, in the order they arrived.
     */
    fun acceptNewKey(conversationId: Long) {
        val conversation = repository.conversation(conversationId) ?: return
        val now = clock()
        inTransaction {
            val held = repository.held(conversation.address)
            // Trust each new key once: trusting again would drop the session its first message built.
            var trusted: ByteArray? = null
            for (h in held) {
                if (trusted == null || !trusted.contentEquals(h.identity)) {
                    sessions.trustIdentity(conversation.address, IdentityKey(h.identity))
                    trusted = h.identity
                }
                repository.releaseHeld(h.id)
                handleReceived(repository.conversation(conversation.id)!!, messenger.open(conversation.address, h.envelope), now)
            }
            repository.setKeyChanged(conversation.id, false)
            store.setVerified(conversation.address, false)
            repository.insertMessage(
                conversation.id, outgoing = true, kind = MessageKind.KEY_ACCEPTED, body = "", encrypted = true,
                status = MessageStatus.SENT, sentAt = now, receivedAt = now, read = true,
            )
        }
        publishFresh()
        events.onChanged()
    }

    /** Disappearing messages for the conversation; the contact's app follows when it is encrypted. */
    fun setTimer(conversationId: Long, seconds: Int) {
        val conversation = repository.conversation(conversationId) ?: return
        if (seconds == conversation.expireSeconds || seconds < 0) return
        val now = clock()
        if (conversation.encryption == Encryption.ACTIVE && sessions.hasSession(conversation.address)) {
            sendEncrypted(conversation, MessageKind.TIMER_CHANGED, seconds.toString(), expireSeconds = 0) {
                repository.setExpireSeconds(conversation.id, seconds)
                messenger.composeTimerChange(conversation.address, seconds, now)
            }
        } else {
            inTransaction { changeTimer(conversation, seconds, outgoing = true, at = now) }
        }
        events.onChanged()
    }

    private fun changeTimer(conversation: Conversation, seconds: Int, outgoing: Boolean, at: Long) {
        repository.setExpireSeconds(conversation.id, seconds)
        repository.insertMessage(
            conversation.id, outgoing = outgoing, kind = MessageKind.TIMER_CHANGED, body = seconds.toString(), encrypted = true,
            status = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED, sentAt = at, receivedAt = at, read = true,
        )
    }

    private fun sendAck(conversationId: Long) {
        val conversation = repository.conversation(conversationId) ?: return
        if (clock() - conversation.lastEncryptedSentAt <= ACK_INTERVAL_MS) return
        val now = clock()
        val parts = inTransaction {
            repository.noteEncryptedSent(conversation.id, now)
            messenger.composeAck(conversation.address, now)
        }
        transmit(null) { sms.sendEach(conversation.address, parts, conversation.subscriptionId, NO_MESSAGE) }
    }

    /**
     * Encrypts and records in one transaction (the Signal ratchet moves forward only together with
     * the message it encrypted), then sends.
     */
    private fun sendEncrypted(conversation: Conversation, kind: MessageKind, body: String, expireSeconds: Int, compose: () -> List<String>): Long {
        val now = clock()
        val (id, parts) = inTransaction {
            val parts = compose()
            val id = repository.insertMessage(
                conversation.id, outgoing = true, kind = kind, body = body, encrypted = true, status = MessageStatus.PENDING,
                sentAt = now, receivedAt = now, read = true, parts = parts.size, expireSeconds = expireSeconds,
                // Sent disappearing messages count from the moment they are sent.
                expireAt = if (expireSeconds > 0) now + expireSeconds * 1000L else null,
            )
            repository.noteEncryptedSent(conversation.id, now)
            id to parts
        }
        transmit(id) { sms.sendEach(conversation.address, parts, conversation.subscriptionId, id) }
        return id
    }

    /** Sends now; if the radio refuses right away (no SIM, not the default SMS app), the message is marked failed. */
    private fun transmit(messageId: Long?, send: () -> Unit) {
        try {
            send()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Sending failed", e)
            messageId?.let { repository.setStatus(it, MessageStatus.FAILED) }
        }
    }

    /** How many SMS [text] would take in this conversation, shown while typing. */
    fun estimateSms(conversationId: Long, text: String): Int {
        val conversation = repository.conversation(conversationId)
        if (conversation?.encryption != Encryption.ACTIVE) return sms.partsOf(text)
        val content = try {
            Content.encode(Content.Text(Content.MAX_ID, clock() / 1000, text, conversation.expireSeconds)).size
        } catch (_: IllegalArgumentException) {
            return SmsCodec.MAX_FRAGMENTS
        }
        // AES-CBC pads to whole 16-byte blocks, always adding at least one byte.
        val padded = (content / AES_BLOCK_BYTES + 1) * AES_BLOCK_BYTES
        return SmsCodec.smsCount(padded + ENCRYPTED_OVERHEAD_BYTES)
    }

    // Reading, deleting, keys.

    fun markRead(conversationId: Long) {
        val providerIds = inTransaction { repository.markRead(conversationId, clock()) }
        if (providerIds.isNotEmpty()) system.markRead(providerIds)
        events.onChanged()
        events.onNextExpiry(repository.nextExpiry())
    }

    /** Deletes disappearing messages whose time came. */
    fun deleteExpired() {
        val providerIds = inTransaction { repository.deleteExpired(clock()) }
        if (providerIds.isNotEmpty()) system.delete(providerIds)
        events.onChanged()
        events.onNextExpiry(repository.nextExpiry())
    }

    fun deleteMessage(messageId: Long) {
        val providerId = inTransaction { repository.deleteMessage(messageId) }
        providerId?.let { system.delete(listOf(it)) }
        events.onChanged()
    }

    fun deleteConversation(conversationId: Long) {
        val providerIds = inTransaction { repository.deleteConversation(conversationId) }
        if (providerIds.isNotEmpty()) system.delete(providerIds)
        events.onChanged()
    }

    fun conversationFor(rawAddress: String): Conversation = inTransaction { repository.conversationFor(addresses.normalize(rawAddress)) }

    fun safetyNumber(conversationId: Long): String? = repository.conversation(conversationId)?.let { sessions.safetyNumber(it.address) }

    fun isVerified(conversationId: Long): Boolean = repository.conversation(conversationId)?.let { store.isVerified(it.address) } ?: false

    fun setVerified(conversationId: Long, verified: Boolean) {
        repository.conversation(conversationId)?.let { store.setVerified(it.address, verified) }
        events.onChanged()
    }

    /**
     * Copies SMS from the system database that the app has not seen: the whole history the first
     * time, then whatever arrived while another app was the default SMS app.
     */
    fun importSystemSms(): Int {
        var afterId = repository.setting(SETTING_IMPORTED_UNTIL)?.toLongOrNull() ?: 0L
        var imported = 0
        while (true) {
            val rows = system.history(afterId, IMPORT_BATCH)
            if (rows.isEmpty()) break
            inTransaction {
                for (row in rows) {
                    if (!messenger.isSigilo(row.body) && !repository.hasProviderMessage(row.id)) {
                        val conversation = repository.conversationFor(addresses.normalize(row.address))
                        repository.insertMessage(
                            conversation.id, outgoing = row.outgoing, kind = MessageKind.TEXT, body = row.body, encrypted = false,
                            status = when {
                                !row.outgoing -> MessageStatus.RECEIVED
                                row.failed -> MessageStatus.FAILED
                                else -> MessageStatus.SENT
                            },
                            sentAt = if (row.dateSent > 0) row.dateSent else row.date, receivedAt = row.date,
                            read = row.read || row.outgoing, parts = 1, providerId = row.id,
                        )
                        imported++
                    }
                    afterId = maxOf(afterId, row.id)
                }
                repository.setSetting(SETTING_IMPORTED_UNTIL, afterId.toString())
            }
        }
        if (imported > 0) events.onChanged()
        return imported
    }

    fun conversations(): List<Conversation> = repository.conversations()

    fun messages(conversationId: Long): List<Message> = repository.messages(conversationId, clock())

    fun conversation(conversationId: Long): Conversation? = repository.conversation(conversationId)

    private fun <T> inTransaction(block: () -> T): T {
        val result = try {
            db.transaction(block)
        } catch (e: RuntimeException) {
            // Rolled back: whatever it meant to send must not go out.
            afterCommit.clear()
            throw e
        }
        val pending = afterCommit.toList()
        afterCommit.clear()
        pending.forEach { it() }
        return result
    }

    companion object {
        private const val TAG = "MessageEngine"
        const val NO_SUBSCRIPTION = -1

        /** Tries (one per drain) before an inbox item that keeps failing is given up. */
        const val MAX_ATTEMPTS = 3
        private const val NO_MESSAGE = 0L
        private const val SESSION_DEVICE = 1

        private const val AES_BLOCK_BYTES = 16

        /**
         * Bytes an encrypted message adds around its padded content: envelope kind, Signal message
         * header (ratchet key, counters), post-quantum ratchet data and MAC. Measured: constant over
         * 60 messages in both directions with libsignal 0.103.
         */
        const val ENCRYPTED_OVERHEAD_BYTES = 90

        /** At most one automatic acknowledgement per conversation in this time. */
        const val ACK_INTERVAL_MS = 10 * 60 * 1000L
        private const val IMPORT_BATCH = 500
        private const val SETTING_IMPORTED_UNTIL = "system_sms_imported_until"
    }
}

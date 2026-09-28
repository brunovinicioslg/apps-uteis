package io.github.brunovinicioslg.sigilo.app.sms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.net.toUri
import io.github.brunovinicioslg.sigilo.app.BuildConfig
import io.github.brunovinicioslg.sigilo.app.engine.SmsGateway
import io.github.brunovinicioslg.sigilo.app.engine.SystemSms
import io.github.brunovinicioslg.sigilo.app.engine.SystemSmsRow
import io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent
import java.util.concurrent.atomic.AtomicInteger

/** Sends through the phone's SmsManager, with a report per SMS to [SmsStatusReceiver]. */
class AndroidSmsGateway(private val context: Context) : SmsGateway {

    private fun manager(subscriptionId: Int): SmsManager {
        val default = context.getSystemService(SmsManager::class.java)
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return default
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            default.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    override fun sendEach(address: String, texts: List<String>, subscriptionId: Int, messageId: Long) {
        val manager = manager(subscriptionId)
        // Test builds only: lets the emulator test read what left (always ciphertext here).
        if (BuildConfig.DEBUG) texts.forEach { Log.d(DEBUG_TAG, "OUT $address $it") }
        for (text in texts) {
            manager.sendTextMessage(address, null, text, report(messageId, SmsEvent.SENT, subscriptionId), report(messageId, SmsEvent.DELIVERED, subscriptionId))
        }
    }

    override fun sendText(address: String, text: String, subscriptionId: Int, messageId: Long): Int {
        val manager = manager(subscriptionId)
        val parts = manager.divideMessage(text)
        if (parts.size <= 1) {
            manager.sendTextMessage(address, null, text, report(messageId, SmsEvent.SENT, subscriptionId), report(messageId, SmsEvent.DELIVERED, subscriptionId))
            return 1
        }
        val sent = ArrayList(parts.map { report(messageId, SmsEvent.SENT, subscriptionId) })
        val delivered = ArrayList(parts.map { report(messageId, SmsEvent.DELIVERED, subscriptionId) })
        manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
        return parts.size
    }

    override fun partsOf(text: String): Int = SmsMessage.calculateLength(text, false)[0].coerceAtLeast(1)

    /** A quick reply sent on the phone app's behalf: its reports update only the system database. */
    fun sendExternal(address: String, text: String, providerId: Long?) {
        val manager = manager(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val parts = manager.divideMessage(text)
        val sent = ArrayList(parts.map { report(0, SmsEvent.SENT, SubscriptionManager.INVALID_SUBSCRIPTION_ID, providerId) })
        val delivered = ArrayList(parts.map { report(0, SmsEvent.DELIVERED, SubscriptionManager.INVALID_SUBSCRIPTION_ID, providerId) })
        manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
    }

    private fun report(messageId: Long, event: SmsEvent, subscriptionId: Int, providerId: Long? = null): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(if (event == SmsEvent.SENT) SmsStatusReceiver.ACTION_SENT else SmsStatusReceiver.ACTION_DELIVERED)
            // A unique data URI keeps every SMS's report apart; nothing about the content is in it.
            .setData("sigilo://sms/$messageId/${requestCodes.incrementAndGet()}".toUri())
            .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(SmsStatusReceiver.EXTRA_SUBSCRIPTION, subscriptionId)
        providerId?.let { intent.putExtra(SmsStatusReceiver.EXTRA_PROVIDER_ID, it) }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        val requestCodes = AtomicInteger()
        const val DEBUG_TAG = "SigiloSms"
    }
}

/**
 * The system SMS database. As the default SMS app, Sigilo writes every ordinary SMS there (the
 * system no longer does it), so other apps and a later switch of default app keep them.
 */
class AndroidSystemSms(private val context: Context) : SystemSms {

    private val resolver get() = context.contentResolver

    fun insertIncoming(address: String, body: String, subscriptionId: Int, sentAt: Long, receivedAt: Long): Long? = insert(
        Telephony.Sms.Inbox.CONTENT_URI,
        ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, receivedAt)
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        },
    )

    override fun insertOutgoing(address: String, body: String, subscriptionId: Int, timestamp: Long): Long? = insert(
        Telephony.Sms.Outbox.CONTENT_URI,
        ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        },
    )

    /** Moves an outgoing SMS to "sent" or "failed", or records its delivery. */
    override fun updateStatus(providerId: Long, event: SmsEvent) {
        val values = ContentValues()
        when (event) {
            SmsEvent.SENT -> values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            SmsEvent.SEND_FAILED -> values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            SmsEvent.DELIVERED -> values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            SmsEvent.DELIVERY_FAILED -> values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED)
        }
        safely { resolver.update(uri(providerId), values, null, null) }
    }

    override fun markRead(providerIds: List<Long>) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        providerIds.forEach { id -> safely { resolver.update(uri(id), values, null, null) } }
    }

    override fun delete(providerIds: List<Long>) {
        providerIds.forEach { id -> safely { resolver.delete(uri(id), null, null) } }
    }

    override fun history(afterId: Long, limit: Int): List<SystemSmsRow> = safely {
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
                Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
            ),
            "${Telephony.Sms._ID} > ?", arrayOf(afterId.toString()), "${Telephony.Sms._ID} LIMIT $limit",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val address = c.getString(1) ?: continue
                    val type = c.getInt(5)
                    // Drafts and queued messages are not history.
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    add(
                        SystemSmsRow(
                            id = c.getLong(0), address = address, body = c.getString(2).orEmpty(), date = c.getLong(3), dateSent = c.getLong(4),
                            outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX, read = c.getInt(6) == 1,
                            failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED, subscriptionId = c.getInt(7),
                        ),
                    )
                }
            }
        }
    } ?: emptyList()

    private fun insert(table: Uri, values: ContentValues): Long? = safely { resolver.insert(table, values)?.lastPathSegment?.toLongOrNull() }

    private fun uri(id: Long): Uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())

    /** Only the default SMS app may write here; after the user picks another app, calls fail quietly. */
    private fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "No access to the system SMS database", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "System SMS database refused the call", e)
        null
    }

    private companion object {
        const val TAG = "AndroidSystemSms"
    }
}

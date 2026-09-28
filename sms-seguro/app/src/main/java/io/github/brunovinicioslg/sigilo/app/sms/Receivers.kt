package io.github.brunovinicioslg.sigilo.app.sms

import android.app.Activity
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.BlockedNumberContract
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import io.github.brunovinicioslg.sigilo.app.appContainer
import io.github.brunovinicioslg.sigilo.app.inbox.SmsEvent
import io.github.brunovinicioslg.sigilo.sms.SmsCodec
import kotlinx.coroutines.launch

/** Whether Sigilo is the phone's SMS app; without that Android lets it neither receive nor send. */
object DefaultSmsApp {
    fun isDefault(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    /** The system screen that asks the user to make Sigilo the SMS app. */
    fun requestIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
    } else {
        Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
    }

    /** Adds to the system block list (calls and messages). */
    fun block(context: Context, address: String): Boolean = try {
        context.contentResolver.insert(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address) },
        ) != null
    } catch (e: SecurityException) {
        Log.w("DefaultSmsApp", "Cannot block", e)
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    fun unblock(context: Context, address: String): Boolean = try {
        BlockedNumberContract.unblock(context, address) > 0
    } catch (e: SecurityException) {
        Log.w("DefaultSmsApp", "Cannot unblock", e)
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** The system's block list (shared with the phone app); only the default SMS app may read it. */
    fun isBlocked(context: Context, address: String): Boolean = try {
        BlockedNumberContract.isBlocked(context, address)
    } catch (e: SecurityException) {
        Log.w("DefaultSmsApp", "Block list not readable", e)
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}

/**
 * Every incoming SMS, delivered only to the default SMS app. Ordinary SMS go to the system SMS
 * database (as every SMS app does); all of them wait in the inbox until the engine takes them.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val address = parts.first().originatingAddress ?: return
        if (DefaultSmsApp.isBlocked(context, address)) return
        // A long ordinary SMS arrives as several parts in one broadcast; Sigilo parts come one by one.
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val subscriptionId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val now = System.currentTimeMillis()
        val container = context.appContainer
        val providerId = if (SmsCodec.parse(body) == null) {
            container.systemSms.insertIncoming(address, body, subscriptionId, parts.first().timestampMillis, now)
        } else {
            null // encrypted: never in the system database
        }
        container.inbox.addSms(address, body, subscriptionId, now, providerId)
        val pending = goAsync()
        container.scope.launch {
            try {
                container.processInbox()
            } finally {
                pending.finish()
            }
        }
    }
}

/** The radio's report for each SMS sent: it left the phone, and later, it was delivered. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        val event = when (intent.action) {
            ACTION_SENT -> if (resultCode == Activity.RESULT_OK) SmsEvent.SENT else SmsEvent.SEND_FAILED
            ACTION_DELIVERED -> deliveryOutcome(intent) ?: return
            else -> return
        }
        val container = context.appContainer
        val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, 0)
        if (providerId > 0) {
            // Sent from outside the app (quick reply): only the system database tracks it.
            container.systemSms.updateStatus(providerId, event)
            return
        }
        if (messageId <= 0) return
        container.inbox.addStatus(messageId, event)
        val pending = goAsync()
        container.scope.launch {
            try {
                container.processInbox()
            } finally {
                pending.finish()
            }
        }
    }

    /** Null while the network is still trying (a temporary error). */
    private fun deliveryOutcome(intent: Intent): SmsEvent? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return SmsEvent.DELIVERED
        val report = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")) ?: return SmsEvent.DELIVERED
        val status = report.status
        return when {
            status == Telephony.Sms.STATUS_COMPLETE -> SmsEvent.DELIVERED
            status >= Telephony.Sms.STATUS_FAILED -> SmsEvent.DELIVERY_FAILED
            else -> null
        }
    }

    companion object {
        const val ACTION_SENT = "io.github.brunovinicioslg.sigilo.action.SMS_SENT"
        const val ACTION_DELIVERED = "io.github.brunovinicioslg.sigilo.action.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_SUBSCRIPTION = "subscription"
    }
}

/**
 * Multimedia messages. Downloading and showing MMS arrives in the next version; for now the
 * conversation records that one arrived, so it is not missed.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        val from = MmsNotification.from(pdu) ?: return
        if (DefaultSmsApp.isBlocked(context, from)) return
        val container = context.appContainer
        container.inbox.addMms(from, System.currentTimeMillis(), null)
        val pending = goAsync()
        container.scope.launch {
            try {
                container.processInbox()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reads the sender of an MMS notification (m-notification-ind, OMA MMS encapsulation). */
object MmsNotification {
    private const val FROM = 0x89
    private const val ADDRESS_PRESENT = 0x80

    fun from(pdu: ByteArray): String? {
        // From: header byte, value length, address-present token, then a NUL-terminated string
        // such as "+5531999998888/TYPE=PLMN". Scanned for, since other headers come first.
        var i = 0
        while (i + 3 < pdu.size) {
            if ((pdu[i].toInt() and 0xff) == FROM && (pdu[i + 2].toInt() and 0xff) == ADDRESS_PRESENT) {
                val length = pdu[i + 1].toInt() and 0xff
                val start = i + 3
                val end = (start until minOf(pdu.size, i + 2 + length)).firstOrNull { pdu[it].toInt() == 0 }
                if (length in 2..30 && end != null) {
                    val text = String(pdu, start, end - start, Charsets.UTF_8).substringBefore('/').trim()
                    if (text.isNotEmpty() && text.all { it.isLetterOrDigit() || it in "+-.@ " }) return text
                }
            }
            i++
        }
        return null
    }
}

/** "Reply with a message" when declining a call; the phone app asks the default SMS app to send it. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyActions.RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val recipients = intent.data?.schemeSpecificPart?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (!text.isNullOrBlank() && recipients.isNotEmpty()) {
                val container = applicationContext.appContainer
                for (address in recipients) {
                    val now = System.currentTimeMillis()
                    val providerId = container.systemSms.insertOutgoing(address, text, SubscriptionManager.INVALID_SUBSCRIPTION_ID, now)
                    try {
                        container.gateway.sendExternal(address, text, providerId)
                        container.inbox.addSent(address, text, SubscriptionManager.INVALID_SUBSCRIPTION_ID, now, providerId)
                    } catch (e: RuntimeException) {
                        Log.w("RespondViaMessage", "Could not send", e)
                        providerId?.let { container.systemSms.updateStatus(it, SmsEvent.SEND_FAILED) }
                    }
                }
                container.scope.launch { container.processInbox() }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

object TelephonyActions {
    const val RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
}

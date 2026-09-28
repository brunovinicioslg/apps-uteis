package io.github.brunovinicioslg.sigilo.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.brunovinicioslg.sigilo.app.engine.SimCard
import io.github.brunovinicioslg.sigilo.app.engine.SimCards

/** The phone's SIM cards, from Android. Listing them needs the phone permission; the rest does not. */
class AndroidSimCards(private val context: Context) : SimCards {

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Slots for SIM cards, known without any permission: more than one means choosing matters. */
    val slots: Int
        get() {
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                telephony.activeModemCount
            } else {
                @Suppress("DEPRECATION")
                telephony.phoneCount
            }
        }

    override fun active(): List<SimCard> {
        if (!hasPermission) return emptyList()
        return try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map { info -> SimCard(info.subscriptionId, info.simSlotIndex, (info.displayName ?: info.carrierName)?.toString().orEmpty()) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Phone permission revoked", e)
            emptyList()
        }
    }

    override fun defaultForSms(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    private companion object {
        const val TAG = "AndroidSimCards"
    }
}

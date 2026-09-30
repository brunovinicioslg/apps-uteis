package io.github.brunovinicioslg.sossego.screening

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import io.github.brunovinicioslg.sossego.appContainer
import io.github.brunovinicioslg.sossego.device.DeviceStatus
import kotlin.concurrent.thread

/**
 * "Block everything" for the calls the screening never sees (contacts, or every call if the app
 * is not the call screener): declines a call as soon as the phone says it is ringing.
 *
 * Does nothing in any other mode, and never touches a call the user is on: it only acts while
 * the phone is ringing.
 */
class RingingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        // Only there when the app may read the call log, which it does not ask for.
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val appContext = context.applicationContext
        val pending = goAsync()
        thread(name = "SossegoRinging") {
            try {
                declineIfBlockingEverything(appContext, number)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not decline the ringing call", e)
            } finally {
                pending.finish()
            }
        }
    }

    // endCall() is deprecated in favor of the call screening, which never sees contacts' calls.
    @Suppress("DEPRECATION")
    private fun declineIfBlockingEverything(context: Context, number: String?) {
        val gate = context.appContainer.gate
        val settings = gate.mustDeclineRinging() ?: return
        if (!DeviceStatus.canDeclineContacts(context)) return
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        val declined = try {
            // Checked again right before acting: the call may have been answered meanwhile. The
            // device-wide state (deprecated for the per-SIM one) covers a call on either SIM.
            telephony.callState == TelephonyManager.CALL_STATE_RINGING && telecom.endCall()
        } catch (e: SecurityException) {
            // A permission taken back in the meantime.
            false
        }
        if (declined) gate.recordDeclined(number, settings)
    }

    private companion object {
        const val TAG = "SossegoRinging"
    }
}

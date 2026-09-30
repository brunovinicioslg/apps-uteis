package io.github.brunovinicioslg.sossego.device

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/** A SIM to call from, as the phone names it. */
data class SimOption(val handle: PhoneAccountHandle, val label: String)

/**
 * Places calls through the phone: the call itself shows on the phone app's own call screen
 * (Samsung's, Xiaomi's...), with everything it offers.
 */
class Caller(private val context: Context) {

    private val telecom: TelecomManager? get() = context.getSystemService(TelecomManager::class.java)

    fun canCall(): Boolean = DeviceStatus.granted(context, Manifest.permission.CALL_PHONE)

    /**
     * The SIMs to choose from, only when the phone asks every time; empty otherwise (one SIM, a
     * default SIM, or not allowed to know: then the phone decides, asking if it must).
     */
    fun simChoices(): List<SimOption> {
        val telecom = telecom ?: return emptyList()
        if (!DeviceStatus.granted(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return try {
            val accounts = telecom.callCapablePhoneAccounts
            if (accounts.size < 2 || telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL) != null) return emptyList()
            accounts.mapNotNull { handle -> telecom.getPhoneAccount(handle)?.let { SimOption(handle, it.label.toString()) } }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Each SIM account's name, to show which SIM a call used; empty with a single SIM. */
    fun simLabels(): Map<String, String> {
        val telecom = telecom ?: return emptyMap()
        if (!DeviceStatus.granted(context, Manifest.permission.READ_PHONE_STATE)) return emptyMap()
        return try {
            val accounts = telecom.callCapablePhoneAccounts
            if (accounts.size < 2) return emptyMap()
            accounts.mapNotNull { handle -> telecom.getPhoneAccount(handle)?.let { handle.id to it.label.toString() } }.toMap()
        } catch (e: SecurityException) {
            emptyMap()
        }
    }

    /** Calls [number] (from [sim] if given). False when the phone refused: see [openInPhoneApp]. */
    fun call(number: String, sim: PhoneAccountHandle? = null): Boolean =
        place(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), sim)

    /** Calls the voicemail. */
    fun callVoicemail(): Boolean = place(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), null)

    /** The phone app with [number] typed in, for the user to press call: works without permission. */
    fun openInPhoneApp(number: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun place(uri: Uri, sim: PhoneAccountHandle?): Boolean {
        val telecom = telecom ?: return false
        if (!canCall()) return false
        val extras = Bundle()
        if (sim != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim)
        return try {
            telecom.placeCall(uri, extras)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "The phone refused the call", e)
            false
        }
    }

    private companion object {
        const val TAG = "SossegoCaller"
    }
}

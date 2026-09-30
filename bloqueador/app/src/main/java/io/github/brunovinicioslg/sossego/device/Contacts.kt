package io.github.brunovinicioslg.sossego.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/** A number picked from the contacts app. */
data class PickedContact(val number: String, val name: String)

/** Whether a number is in the phone's contacts. */
fun interface ContactChecker {
    /** False when it cannot tell (no permission, error). */
    fun isContact(number: String): Boolean
}

class ContactLookup(private val context: Context) : ContactChecker {

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun isContact(number: String): Boolean = name(number) != null

    /** The contact's name for [number], or null (not a contact, or no permission). */
    fun name(number: String): String? {
        if (number.isBlank() || !canRead()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else null }
        } catch (e: RuntimeException) {
            // A contacts provider that misbehaves must never stop a call from being screened.
            Log.w(TAG, "Contact lookup failed", e)
            null
        }
    }

    /**
     * Reads the number chosen in the contacts app. The pick grants access to that one number, so
     * this needs no contacts permission.
     */
    fun picked(uri: Uri): PickedContact? = try {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) PickedContact(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot read the picked contact", e)
        null
    }

    private companion object {
        const val TAG = "SossegoContacts"
    }
}

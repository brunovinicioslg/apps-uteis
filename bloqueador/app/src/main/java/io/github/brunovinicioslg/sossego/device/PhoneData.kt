package io.github.brunovinicioslg.sossego.device

import android.Manifest
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import io.github.brunovinicioslg.sossego.BuildConfig
import io.github.brunovinicioslg.sossego.core.dial.CallKind
import io.github.brunovinicioslg.sossego.core.dial.Contact
import io.github.brunovinicioslg.sossego.core.dial.ContactSearch
import io.github.brunovinicioslg.sossego.core.dial.LoggedCall
import io.github.brunovinicioslg.sossego.core.dial.PhoneRow

/** The phone's contacts, read only. Blocking: call off the main thread. */
class ContactsReader(private val context: Context) {

    fun canRead(): Boolean = DeviceStatus.granted(context, Manifest.permission.READ_CONTACTS)

    /** Every contact with a number, sorted by name; null without permission or on error. */
    fun all(): List<Contact>? {
        if (!canRead()) return null
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.STARRED,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
            )?.use { c ->
                val rows = ArrayList<PhoneRow>(c.count)
                while (c.moveToNext()) {
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                    rows += PhoneRow(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), label, c.getInt(5) != 0)
                }
                ContactSearch.group(rows)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read the contacts", e)
            null
        }
    }

    private companion object {
        const val TAG = "SossegoContacts"
    }
}

/** The phone's call log, read only. Blocking: call off the main thread. */
class CallLogReader(private val context: Context) {

    /** Never in the Google Play build, which does not ask for the call log. */
    fun canRead(): Boolean = BuildConfig.CALL_LOG && DeviceStatus.granted(context, Manifest.permission.READ_CALL_LOG)

    /**
     * The latest calls, newest first; null without permission or on error.
     *
     * @param simLabels the phone's name for each SIM account id (empty with a single SIM)
     */
    fun recent(simLabels: Map<String, String>): List<LoggedCall>? {
        if (!canRead()) return null
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, LIMIT.toString()).build()
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.NUMBER_PRESENTATION,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val shown = c.getInt(7) == CallLog.Calls.PRESENTATION_ALLOWED
                        add(
                            LoggedCall(
                                id = c.getLong(0),
                                time = c.getLong(3),
                                number = if (shown) c.getString(1).orEmpty() else "",
                                kind = kindOf(c.getInt(2)),
                                name = c.getString(5)?.takeIf { it.isNotBlank() },
                                durationSeconds = c.getLong(4),
                                sim = c.getString(6)?.let(simLabels::get),
                            ),
                        )
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read the call log", e)
            null
        }
    }

    companion object {
        private const val TAG = "SossegoCallLog"
        private const val LIMIT = 300

        fun kindOf(type: Int): CallKind = when (type) {
            CallLog.Calls.INCOMING_TYPE -> CallKind.INCOMING
            CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
            CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
            CallLog.Calls.REJECTED_TYPE -> CallKind.REJECTED
            CallLog.Calls.BLOCKED_TYPE -> CallKind.BLOCKED
            CallLog.Calls.VOICEMAIL_TYPE -> CallKind.VOICEMAIL
            else -> CallKind.OTHER
        }
    }
}

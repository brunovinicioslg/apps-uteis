package io.github.brunovinicioslg.sigilo.app.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Names from the phone's contacts, when the user allowed reading them. */
class ContactNames(private val context: Context) {

    private val cache = ConcurrentHashMap<String, String>()
    private val unknown = ConcurrentHashMap.newKeySet<String>()

    fun canRead(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun nameOf(address: String): String? {
        if (!canRead()) return null
        cache[address]?.let { return it }
        if (address in unknown) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        val name = try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: IllegalArgumentException) {
            null // numbers the lookup cannot parse (letters, short codes)
        } catch (_: SecurityException) {
            null
        }
        if (name == null) unknown += address else cache[address] = name
        return name
    }

    /** The user edited contacts or granted the permission: look again. */
    fun forget() {
        cache.clear()
        unknown.clear()
    }

    data class Contact(val name: String, val number: String)

    /** Everyone with a phone number, for starting a conversation. */
    fun all(): List<Contact> {
        if (!canRead()) return emptyList()
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        add(Contact(name, number))
                    }
                }.distinctBy { it.name to it.number.filter(Char::isDigit) }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}

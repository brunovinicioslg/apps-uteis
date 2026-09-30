package io.github.brunovinicioslg.sossego.core.dial

import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import java.text.Normalizer

data class ContactPhone(val number: String, val label: String = "")

data class Contact(
    val id: Long,
    val name: String,
    val phones: List<ContactPhone>,
    val starred: Boolean = false,
)

/** One phone row of the contacts database (a contact with two numbers has two rows). */
data class PhoneRow(
    val contactId: Long,
    val name: String,
    val number: String,
    val label: String = "",
    val starred: Boolean = false,
)

/** A contact's number that matches what was typed on the keypad. */
data class Suggestion(val contact: Contact, val phone: ContactPhone)

object ContactSearch {

    /**
     * Contacts from phone rows, in the rows' order (already sorted by name): one per contact, each
     * number once even when saved twice in different ways.
     */
    fun group(rows: List<PhoneRow>): List<Contact> {
        val byId = LinkedHashMap<Long, MutableList<PhoneRow>>()
        for (row in rows) {
            if (row.number.isBlank()) continue
            byId.getOrPut(row.contactId) { mutableListOf() } += row
        }
        return byId.map { (id, phones) ->
            val first = phones.first()
            Contact(
                id = id,
                name = first.name.ifBlank { first.number },
                phones = phones.distinctBy { PhoneNumbers.key(it.number).ifEmpty { it.number } }
                    .map { ContactPhone(it.number, it.label) },
                starred = phones.any { it.starred },
            )
        }
    }

    /** Contacts whose name (ignoring case and accents) or number contains [query]. */
    fun byText(contacts: List<Contact>, query: String): List<Contact> {
        val text = plain(query.trim())
        if (text.isEmpty()) return contacts
        val digits = query.filter { it in '0'..'9' }
        return contacts.filter { contact ->
            plain(contact.name).contains(text) ||
                (digits.length >= 3 && contact.phones.any { Dialpad.numberMatches(it.number, digits) })
        }
    }

    /**
     * What typed keys point to: names spelled by the letters and numbers that contain the digits.
     * Favorites first, then names, then numbers; at most [limit].
     */
    fun byKeys(contacts: List<Contact>, typed: String, limit: Int = 5): List<Suggestion> {
        val keys = typed.filter { it in '0'..'9' }
        if (keys.isEmpty() || keys != typed) return emptyList()
        val byName = contacts.filter { Dialpad.nameMatches(it.name, keys) }.map { Suggestion(it, it.phones.first()) }
        val byNumber = contacts.flatMap { contact ->
            contact.phones.filter { Dialpad.numberMatches(it.number, keys) }.map { Suggestion(contact, it) }
        }
        return (byName + byNumber)
            .distinctBy { it.contact.id to it.phone.number }
            .sortedBy { if (it.contact.starred) 0 else 1 } // stable: keeps names before numbers
            .take(limit)
    }

    private fun plain(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}

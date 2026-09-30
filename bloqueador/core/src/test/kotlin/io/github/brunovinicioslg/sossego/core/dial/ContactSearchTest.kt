package io.github.brunovinicioslg.sossego.core.dial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactSearchTest {

    private val contacts = ContactSearch.group(
        listOf(
            PhoneRow(1, "Ana Paula", "(21) 99999-1111", "Celular"),
            PhoneRow(2, "José Antônio", "+55 31 3333-2222", "Casa", starred = true),
            PhoneRow(2, "José Antônio", "31 3333-2222", "Trabalho"), // the same number again
            PhoneRow(2, "José Antônio", "(31) 98888-7777", "Celular"),
            PhoneRow(3, "Maria Souza", "(11) 98765-4321"),
            PhoneRow(4, "", "0800 123 4567"),
            PhoneRow(5, "Sem número", ""),
        ),
    )

    @Test
    fun `rows become one contact each, numbers once`() {
        assertEquals(listOf("Ana Paula", "José Antônio", "Maria Souza", "0800 123 4567"), contacts.map { it.name })
        val jose = contacts[1]
        assertEquals(listOf("+55 31 3333-2222", "(31) 98888-7777"), jose.phones.map { it.number })
        assertTrue(jose.starred)
    }

    @Test
    fun `text search ignores accents and case and finds numbers`() {
        assertEquals(listOf("José Antônio"), ContactSearch.byText(contacts, "jose").map { it.name })
        assertEquals(listOf("José Antônio"), ContactSearch.byText(contacts, "ANTONIO").map { it.name })
        assertEquals(listOf("Maria Souza"), ContactSearch.byText(contacts, "8765").map { it.name })
        assertEquals(contacts, ContactSearch.byText(contacts, "  "))
    }

    @Test
    fun `keypad keys suggest names and numbers, favorites first`() {
        assertEquals(listOf("Maria Souza"), ContactSearch.byKeys(contacts, "6274").map { it.contact.name })
        val byNumber = ContactSearch.byKeys(contacts, "98888")
        assertEquals("(31) 98888-7777", byNumber.single().phone.number)
        // "2" spells A(na) and also appears in numbers; José is a favorite and comes first.
        val many = ContactSearch.byKeys(contacts, "2")
        assertEquals("José Antônio", many.first().contact.name)
        assertTrue(many.size <= 5)
    }

    @Test
    fun `symbols and nothing typed suggest nothing`() {
        assertTrue(ContactSearch.byKeys(contacts, "").isEmpty())
        assertTrue(ContactSearch.byKeys(contacts, "*#06#").isEmpty())
        assertTrue(ContactSearch.byKeys(contacts, "+55").isEmpty())
    }
}

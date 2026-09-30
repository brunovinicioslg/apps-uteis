package io.github.brunovinicioslg.sossego.core.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneNumbersTest {

    @Test
    fun `every way of writing a mobile number gives the same key`() {
        val ways = listOf(
            "(11) 98765-4321",
            "11987654321",
            "+55 11 98765-4321",
            "+5511987654321",
            "5511987654321",
            "011 98765-4321",
            "0 21 11 98765-4321", // carrier code 21
            "0XX11 98765-4321",
            "tel:+55-11-98765-4321",
        )
        ways.forEach { assertEquals("11987654321", PhoneNumbers.key(it), it) }
    }

    @Test
    fun `landlines keep their 8 digits`() {
        listOf("(21) 3456-7890", "021 3456-7890", "0 15 21 3456-7890", "+55 21 3456-7890", "552134567890")
            .forEach { assertEquals("2134567890", PhoneNumbers.key(it), it) }
    }

    @Test
    fun `area code 55 is not mistaken for the country code`() {
        assertEquals("55991234567", PhoneNumbers.key("(55) 99123-4567"))
        assertEquals("5532123456", PhoneNumbers.key("(55) 3212-3456"))
        assertEquals("55991234567", PhoneNumbers.key("+55 55 99123-4567"))
    }

    @Test
    fun `service numbers keep their zero and have no area code`() {
        assertEquals("08001234567", PhoneNumbers.key("0800 123 4567"))
        assertEquals("08001234567", PhoneNumbers.key("800 123 4567"))
        assertEquals("03031234567", PhoneNumbers.key("0303 123 4567"))
        assertEquals("03031234567", PhoneNumbers.key("+55 0303 123 4567"))
        assertEquals("03001234567", PhoneNumbers.key("300 123 4567"))
    }

    @Test
    fun `short and company numbers stay as they are`() {
        assertEquals("40041234", PhoneNumbers.key("4004-1234"))
        assertEquals("190", PhoneNumbers.key("190"))
        assertEquals("144", PhoneNumbers.key("*144#"))
    }

    @Test
    fun `other countries keep plus and country code`() {
        assertEquals("+15551234567", PhoneNumbers.key("+1 555 123 4567"))
        assertEquals("+351912345678", PhoneNumbers.key("+351 912 345 678"))
        // Dialed from Brazil: 00 + carrier code + country code.
        assertEquals("+15551234567", PhoneNumbers.key("00 21 1 555 123 4567"))
        assertEquals("11987654321", PhoneNumbers.key("00 21 55 11 98765-4321"))
    }

    @Test
    fun `no number means an empty key`() {
        listOf(null, "", "   ", "Privado", "anonymous", "+").forEach { assertEquals("", PhoneNumbers.key(it), "$it") }
    }

    @Test
    fun `prefixes follow the same rules`() {
        assertEquals("0303", PhoneNumbers.prefixKey("0303"))
        assertEquals("08", PhoneNumbers.prefixKey("08"))
        assertEquals("11", PhoneNumbers.prefixKey("011"))
        assertEquals("11", PhoneNumbers.prefixKey("+55 11"))
        assertEquals("114003", PhoneNumbers.prefixKey("(11) 4003"))
        assertEquals("+1", PhoneNumbers.prefixKey("+1"))
        assertEquals("", PhoneNumbers.prefixKey("  "))
    }

    @Test
    fun `old mobile numbers without the ninth digit also match`() {
        assertEquals(listOf("11987654321"), PhoneNumbers.alternateKeys("1187654321"))
        assertEquals(listOf("1187654321"), PhoneNumbers.alternateKeys("11987654321"))
        assertEquals(emptyList(), PhoneNumbers.alternateKeys("2134567890")) // landline
        assertEquals(emptyList(), PhoneNumbers.alternateKeys("08001234567"))
        assertEquals(emptyList(), PhoneNumbers.alternateKeys("+15551234567"))
    }

    @Test
    fun `telemarketing and foreign numbers are recognized`() {
        assertTrue(PhoneNumbers.isTelemarketing(PhoneNumbers.key("0303 555 1234")))
        assertTrue(PhoneNumbers.isTelemarketing(PhoneNumbers.key("303 555 1234")))
        assertFalse(PhoneNumbers.isTelemarketing(PhoneNumbers.key("(31) 3555-1234")))
        assertTrue(PhoneNumbers.isInternational(PhoneNumbers.key("+1 555 123 4567")))
        assertFalse(PhoneNumbers.isInternational(PhoneNumbers.key("+55 11 98765-4321")))
    }

    @Test
    fun `numbers are shown the Brazilian way`() {
        assertEquals("(11) 98765-4321", PhoneNumbers.format("11987654321"))
        assertEquals("(21) 3456-7890", PhoneNumbers.format("2134567890"))
        assertEquals("0800 123 4567", PhoneNumbers.format("08001234567"))
        assertEquals("4004-1234", PhoneNumbers.format("40041234"))
        assertEquals("+15551234567", PhoneNumbers.format("+15551234567"))
        assertEquals("190", PhoneNumbers.format("190"))
    }
}

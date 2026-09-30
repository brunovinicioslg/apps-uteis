package io.github.brunovinicioslg.sossego.core.dial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialpadTest {

    @Test
    fun `letters on the keys find names`() {
        assertTrue(Dialpad.nameMatches("Maria Souza", "6274")) // MARI
        assertTrue(Dialpad.nameMatches("Maria Souza", "768")) // SOU(za)
        assertTrue(Dialpad.nameMatches("José Antônio", "5673")) // JOSE, without the accent
        assertTrue(Dialpad.nameMatches("Ana Paula", "2627")) // ANAP(aula), across words
        assertFalse(Dialpad.nameMatches("Maria Souza", "8888"))
        assertFalse(Dialpad.nameMatches("Maria", "")) // nothing typed
        assertFalse(Dialpad.nameMatches("Maria", "62*")) // symbols spell no letter
    }

    @Test
    fun `typed digits find numbers however they were saved`() {
        assertTrue(Dialpad.numberMatches("(11) 98765-4321", "8765"))
        assertTrue(Dialpad.numberMatches("+55 11 98765-4321", "11987"))
        assertFalse(Dialpad.numberMatches("(11) 98765-4321", "5555"))
        assertFalse(Dialpad.numberMatches("(11) 98765-4321", ""))
    }

    @Test
    fun `numbers are grouped as they grow`() {
        assertEquals("190", Dialpad.format("190"))
        assertEquals("9876", Dialpad.format("9876"))
        assertEquals("9876-5", Dialpad.format("98765"))
        assertEquals("3456-7890", Dialpad.format("34567890"))
        assertEquals("98765-4321", Dialpad.format("987654321"))
        assertEquals("(21) 3456-7890", Dialpad.format("2134567890"))
        assertEquals("(11) 98765-4321", Dialpad.format("11987654321"))
        assertEquals("119876543210", Dialpad.format("119876543210")) // too long to be one number
    }

    @Test
    fun `service numbers codes and foreign numbers keep their shape`() {
        assertEquals("0800 123 4567", Dialpad.format("08001234567"))
        assertEquals("0303 12", Dialpad.format("030312"))
        assertEquals("011987654321", Dialpad.format("011987654321"))
        assertEquals("*#06#", Dialpad.format("*#06#"))
        assertEquals("+15551234567", Dialpad.format("+15551234567"))
    }

    @Test
    fun `only what a keypad can type is kept`() {
        assertEquals("+5511987654321", Dialpad.clean("+55 (11) 98765-4321"))
        assertEquals("5511", Dialpad.clean("55+11")) // a plus in the middle is dropped
        assertEquals("*144#", Dialpad.clean("*144#"))
        assertEquals("", Dialpad.clean("abc"))
    }
}

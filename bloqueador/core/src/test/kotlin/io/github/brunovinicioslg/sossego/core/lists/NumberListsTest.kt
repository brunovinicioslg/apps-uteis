package io.github.brunovinicioslg.sossego.core.lists

import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NumberListsTest {

    private fun entry(list: ListKind, match: Match, typed: String) = assertNotNull(ListEntry.of(list, match, typed))

    private fun ListMatcher.listOf(typed: String) = match(PhoneNumbers.key(typed))?.list

    @Test
    fun `an entry is found however the number is written`() {
        val lists = ListMatcher(listOf(entry(ListKind.BLOCK, Match.EXACT, "(11) 98765-4321")))
        assertEquals(ListKind.BLOCK, lists.listOf("+55 11 98765-4321"))
        assertEquals(ListKind.BLOCK, lists.listOf("0 41 11 98765-4321"))
        assertNull(lists.listOf("(11) 98765-4322"))
    }

    @Test
    fun `an old number without the ninth digit matches today's`() {
        val lists = ListMatcher(listOf(entry(ListKind.BLOCK, Match.EXACT, "11 8765-4321")))
        assertEquals(ListKind.BLOCK, lists.listOf("11 98765-4321"))
    }

    @Test
    fun `prefixes match every number that starts with them`() {
        val lists = ListMatcher(listOf(entry(ListKind.BLOCK, Match.PREFIX, "4003"), entry(ListKind.BLOCK, Match.PREFIX, "+1")))
        assertEquals(ListKind.BLOCK, lists.listOf("4003-1234"))
        assertEquals(ListKind.BLOCK, lists.listOf("+1 555 123 4567"))
        assertNull(lists.listOf("4004-1234"))
    }

    @Test
    fun `the most specific entry wins`() {
        val lists = ListMatcher(
            listOf(
                entry(ListKind.BLOCK, Match.PREFIX, "11"),
                entry(ListKind.ALLOW, Match.PREFIX, "11 3003"),
                entry(ListKind.BLOCK, Match.EXACT, "11 3003-9999"),
            ),
        )
        assertEquals(ListKind.BLOCK, lists.listOf("(11) 98765-4321")) // only "11" matches
        assertEquals(ListKind.ALLOW, lists.listOf("(11) 3003-1234")) // longer prefix
        assertEquals(ListKind.BLOCK, lists.listOf("(11) 3003-9999")) // exact beats prefixes
    }

    @Test
    fun `on both lists at the same level, allowing wins`() {
        val number = "(21) 99999-0000"
        val exact = ListMatcher(listOf(entry(ListKind.BLOCK, Match.EXACT, number), entry(ListKind.ALLOW, Match.EXACT, number)))
        assertEquals(ListKind.ALLOW, exact.listOf(number))
        val prefix = ListMatcher(listOf(entry(ListKind.BLOCK, Match.PREFIX, "0800"), entry(ListKind.ALLOW, Match.PREFIX, "0800")))
        assertEquals(ListKind.ALLOW, prefix.listOf("0800 123 4567"))
    }

    @Test
    fun `hidden numbers are on no list`() {
        val lists = ListMatcher(listOf(entry(ListKind.BLOCK, Match.PREFIX, "1")))
        assertNull(lists.match(""))
    }

    @Test
    fun `entries without digits are refused`() {
        assertNull(ListEntry.of(ListKind.BLOCK, Match.EXACT, "abc"))
        assertNull(ListEntry.of(ListKind.BLOCK, Match.PREFIX, " + "))
        assertNull(ListEntry.of(ListKind.ALLOW, Match.PREFIX, ""))
    }

    @Test
    fun `labels are trimmed`() {
        assertEquals("Banco", ListEntry.of(ListKind.ALLOW, Match.EXACT, "4004-1234", "  Banco ")?.label)
    }
}

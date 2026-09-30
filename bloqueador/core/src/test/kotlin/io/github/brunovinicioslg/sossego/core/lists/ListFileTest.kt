package io.github.brunovinicioslg.sossego.core.lists

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListFileTest {

    @Test
    fun `what is written reads back the same`() {
        val entries = listOfNotNull(
            ListEntry.of(ListKind.BLOCK, Match.EXACT, "(11) 98765-4321", "Spam; do cartão"),
            ListEntry.of(ListKind.BLOCK, Match.PREFIX, "0303"),
            ListEntry.of(ListKind.ALLOW, Match.PREFIX, "011"),
            ListEntry.of(ListKind.ALLOW, Match.EXACT, "+1 555 123 4567", "Tia\nnos EUA"),
            ListEntry.of(ListKind.ALLOW, Match.EXACT, "0800 123 4567"),
        )
        val parsed = ListFile.read(ListFile.write(entries))
        assertEquals(0, parsed.skipped)
        assertEquals(
            entries.map { Triple(it.list, it.match, it.pattern) },
            parsed.entries.map { Triple(it.list, it.match, it.pattern) },
        )
        assertEquals("Spam, do cartão", parsed.entries[0].label)
        assertEquals("Tia nos EUA", parsed.entries[3].label)
    }

    @Test
    fun `a hand-made file is accepted`() {
        val text = """
            Negra,Exato,(21) 3456-7890,Loja
            BRANCA;Começa com;0800
            bloquear;prefixo;+1

            nonsense;line
            negra;exato;;sem número
        """.trimIndent()
        val parsed = ListFile.read(text)
        assertEquals(3, parsed.entries.size)
        assertEquals(2, parsed.skipped)
        assertEquals("2134567890", parsed.entries[0].pattern)
        assertEquals("Loja", parsed.entries[0].label)
        assertEquals(ListKind.ALLOW, parsed.entries[1].list)
        assertEquals(Match.PREFIX, parsed.entries[1].match)
        assertEquals("+1", parsed.entries[2].pattern)
    }

    @Test
    fun `repeated lines count once and a spreadsheet's byte order mark is ignored`() {
        val text = "\uFEFF${ListFile.HEADER}\nnegra;exato;11987654321;\nnegra;exato;+55 11 98765-4321;\n"
        val parsed = ListFile.read(text)
        assertEquals(1, parsed.entries.size)
        assertTrue(parsed.skipped == 0)
    }
}

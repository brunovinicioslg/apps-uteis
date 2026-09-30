package io.github.brunovinicioslg.sossego.core.dial

import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.core.rules.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentsTest {

    private fun merge(log: List<LoggedCall>, blocks: List<BlockEvent>) = Recents.merge(log, blocks, PhoneNumbers::key)

    @Test
    fun `calls from the log come newest first`() {
        val lines = merge(
            listOf(
                LoggedCall(1, 1_000, "11987654321", CallKind.INCOMING, "Maria", 60),
                LoggedCall(2, 5_000, "2134567890", CallKind.OUTGOING),
            ),
            emptyList(),
        )
        assertEquals(listOf(2L, 1L), lines.map { it.logId })
        assertEquals("Maria", lines[1].name)
        assertNull(lines[0].reason)
    }

    @Test
    fun `a block replaces the line the phone wrote for it`() {
        val lines = merge(
            listOf(LoggedCall(1, 100_000, "+5511333344 44", CallKind.BLOCKED, sim = "Chip 2")),
            listOf(BlockEvent(9, 101_000, "1133334444", Reason.BLOCK_LISTED)),
        )
        val line = lines.single()
        assertEquals(CallKind.BLOCKED, line.kind)
        assertEquals(Reason.BLOCK_LISTED, line.reason)
        assertEquals(9L, line.blockId)
        assertEquals(1L, line.logId)
        assertEquals("Chip 2", line.sim) // what the phone knew is kept
    }

    @Test
    fun `a contact declined by block everything shows once, with its number from the log`() {
        val lines = merge(
            listOf(LoggedCall(1, 50_000, "31988887777", CallKind.REJECTED, "Teste")),
            listOf(BlockEvent(3, 50_300, "", Reason.BLOCK_ALL)),
        )
        val line = lines.single()
        assertEquals(Reason.BLOCK_ALL, line.reason)
        assertEquals("31988887777", line.number)
        assertEquals("Teste", line.name)
    }

    @Test
    fun `a silenced call replaces the missed call the phone logged`() {
        val lines = merge(
            listOf(LoggedCall(1, 10_000, "03035551234", CallKind.MISSED)),
            listOf(BlockEvent(2, 9_000, "03035551234", Reason.TELEMARKETING, silenced = true)),
        )
        assertEquals(Reason.TELEMARKETING, lines.single().reason)
    }

    @Test
    fun `calls far apart in time or from other numbers are not merged`() {
        val lines = merge(
            listOf(
                LoggedCall(1, 10_000, "1133334444", CallKind.BLOCKED), // blocked by Android's own list
                LoggedCall(2, 500_000, "1133334444", CallKind.MISSED),
            ),
            listOf(BlockEvent(5, 500_500, "2199990000", Reason.BLOCK_LISTED, silenced = true)),
        )
        assertEquals(3, lines.size)
        assertEquals(listOf(CallKind.BLOCKED, CallKind.MISSED, CallKind.BLOCKED), lines.map { it.kind })
        assertNull(lines.last().reason) // the phone's own block, which this app did not make
    }

    @Test
    fun `blocks the phone did not log still show`() {
        // "Keep in the phone's call history" off: only this app knows.
        val lines = merge(emptyList(), listOf(BlockEvent(1, 1_000, "", Reason.HIDDEN)))
        assertEquals(Reason.HIDDEN, lines.single().reason)
        assertEquals("", lines.single().number)
    }

    @Test
    fun `each block replaces one line only`() {
        val lines = merge(
            listOf(
                LoggedCall(1, 1_000, "1133334444", CallKind.BLOCKED),
                LoggedCall(2, 2_000, "1133334444", CallKind.BLOCKED),
            ),
            listOf(BlockEvent(7, 1_500, "1133334444", Reason.BLOCK_LISTED)),
        )
        assertEquals(2, lines.size)
        assertEquals(1, lines.count { it.reason != null })
    }

    @Test
    fun `filters pick the kinds`() {
        val lines = merge(
            listOf(
                LoggedCall(1, 1, "1", CallKind.INCOMING),
                LoggedCall(2, 2, "2", CallKind.OUTGOING),
                LoggedCall(3, 3, "3", CallKind.MISSED),
            ),
            listOf(BlockEvent(4, 4, "4", Reason.BLOCK_LISTED)),
        )
        assertEquals(4, Recents.filter(lines, RecentsFilter.ALL).size)
        assertEquals("3", Recents.filter(lines, RecentsFilter.MISSED).single().number)
        assertEquals("1", Recents.filter(lines, RecentsFilter.INCOMING).single().number)
        assertEquals("2", Recents.filter(lines, RecentsFilter.OUTGOING).single().number)
        assertEquals("4", Recents.filter(lines, RecentsFilter.BLOCKED).single().number)
    }
}

package io.github.brunovinicioslg.sossego.core.rules

import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.ListMatcher
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallRulesTest {

    private val now = 1_000_000_000L
    private val spam = "(11) 3333-4444"
    private val friend = "(21) 99999-1111"
    private val stranger = "(31) 98888-7777"

    private val lists = ListMatcher(
        listOfNotNull(
            ListEntry.of(ListKind.BLOCK, Match.EXACT, spam),
            ListEntry.of(ListKind.ALLOW, Match.EXACT, friend),
        ),
    )

    private fun call(number: String, contact: Boolean = false) = IncomingCall(PhoneNumbers.key(number), isContact = contact)

    private fun decide(
        call: IncomingCall,
        mode: Mode,
        settings: Settings = Settings(),
        lastBlockedAt: Long? = null,
    ) = CallRules.decide(call, mode, settings, lists, lastBlockedAt, now)

    @Test
    fun `off lets everything through`() {
        assertEquals(Reason.OFF, decide(call(spam), Mode.OFF).reason)
    }

    @Test
    fun `block list mode blocks only the list`() {
        assertEquals(Reason.BLOCK_LISTED, decide(call(spam), Mode.BLOCKLIST).reason)
        assertEquals(Reason.NOT_LISTED, decide(call(stranger), Mode.BLOCKLIST).reason)
        assertEquals(Reason.ALLOW_LISTED, decide(call(friend), Mode.BLOCKLIST).reason)
    }

    @Test
    fun `allow list mode blocks everyone else but contacts and the list`() {
        assertEquals(Reason.NOT_ALLOWED, decide(call(stranger), Mode.ALLOWLIST).reason)
        assertEquals(Reason.ALLOW_LISTED, decide(call(friend), Mode.ALLOWLIST).reason)
        assertEquals(Reason.CONTACT, decide(call(stranger, contact = true), Mode.ALLOWLIST).reason)
        assertEquals(Reason.HIDDEN, decide(IncomingCall(""), Mode.ALLOWLIST).reason)
    }

    @Test
    fun `block everything has no exceptions`() {
        for (c in listOf(call(friend), call(stranger, contact = true), call(spam), IncomingCall(""))) {
            assertEquals(Reason.BLOCK_ALL, decide(c, Mode.BLOCK_ALL).reason, c.toString())
        }
    }

    @Test
    fun `emergency call-backs always ring, except with blocking off`() {
        val callback = call(stranger).copy(emergencyCallback = true)
        assertEquals(Reason.EMERGENCY, decide(callback, Mode.ALLOWLIST).reason)
        assertEquals(Reason.EMERGENCY, decide(callback, Mode.BLOCK_ALL).reason)
        assertFalse(decide(callback, Mode.OFF).block)
    }

    @Test
    fun `a contact on the block list is blocked when the phone lets the app see it`() {
        // Only the default phone app gets contacts' calls; the list must still apply then.
        assertEquals(Reason.BLOCK_LISTED, decide(call(spam, contact = true), Mode.BLOCKLIST).reason)
        assertEquals(Reason.CONTACT, decide(call(stranger, contact = true), Mode.BLOCKLIST).reason)
    }

    @Test
    fun `extra filters apply in block list mode`() {
        val all = Settings(blockHidden = true, blockTelemarketing = true, blockInternational = true)
        assertEquals(Reason.HIDDEN, decide(IncomingCall(""), Mode.BLOCKLIST, all).reason)
        assertEquals(Reason.TELEMARKETING, decide(call("0303 555 1234"), Mode.BLOCKLIST, all).reason)
        assertEquals(Reason.INTERNATIONAL, decide(call("+1 555 123 4567"), Mode.BLOCKLIST, all).reason)
        val none = Settings(blockHidden = false, blockTelemarketing = false, blockInternational = false)
        assertFalse(decide(IncomingCall(""), Mode.BLOCKLIST, none).block)
        assertFalse(decide(call("0303 555 1234"), Mode.BLOCKLIST, none).block)
        assertFalse(decide(call("+1 555 123 4567"), Mode.BLOCKLIST, none).block)
    }

    @Test
    fun `the allow list beats the extra filters`() {
        val lists = ListMatcher(listOfNotNull(ListEntry.of(ListKind.ALLOW, Match.PREFIX, "+351")))
        val settings = Settings(blockInternational = true)
        val decision = CallRules.decide(call("+351 912 345 678"), Mode.BLOCKLIST, settings, lists, null, now)
        assertEquals(Reason.ALLOW_LISTED, decision.reason)
    }

    @Test
    fun `someone who calls again within three minutes gets through the allow list`() {
        val repeated = decide(call(stranger), Mode.ALLOWLIST, lastBlockedAt = now - 2 * 60_000)
        assertEquals(Reason.REPEATED, repeated.reason)
        val tooLate = decide(call(stranger), Mode.ALLOWLIST, lastBlockedAt = now - 4 * 60_000)
        assertEquals(Reason.NOT_ALLOWED, tooLate.reason)
        val turnedOff = decide(call(stranger), Mode.ALLOWLIST, Settings(repeatCallers = false), lastBlockedAt = now - 60_000)
        assertEquals(Reason.NOT_ALLOWED, turnedOff.reason)
    }

    @Test
    fun `a clock that went back does not count as a repeat`() {
        assertEquals(Reason.NOT_ALLOWED, decide(call(stranger), Mode.ALLOWLIST, lastBlockedAt = now + 60_000).reason)
    }

    @Test
    fun `the deciding entry comes with the decision`() {
        val decision = decide(call(spam), Mode.BLOCKLIST)
        assertTrue(decision.block)
        assertEquals(PhoneNumbers.key(spam), decision.entry?.pattern)
    }
}

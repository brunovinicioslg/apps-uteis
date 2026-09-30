package io.github.brunovinicioslg.sossego.core.rules

import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.ListMatcher
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers

/** An incoming call, as far as the rules need it. */
data class IncomingCall(
    /** [PhoneNumbers.key] of the caller; empty when the number is hidden. */
    val key: String,
    val hidden: Boolean = key.isEmpty(),
    /** The caller is in the phone's contacts (as far as the app could tell). */
    val isContact: Boolean = false,
    /** A call back after the user called emergency services: never blocked. */
    val emergencyCallback: Boolean = false,
)

/** Why a call was let through or blocked; shown in the history. */
enum class Reason(val blocks: Boolean) {
    OFF(false),
    EMERGENCY(false),
    ALLOW_LISTED(false),
    CONTACT(false),
    REPEATED(false),
    NOT_LISTED(false),
    BLOCK_ALL(true),
    BLOCK_LISTED(true),
    NOT_ALLOWED(true),
    HIDDEN(true),
    TELEMARKETING(true),
    INTERNATIONAL(true),
}

data class Decision(val reason: Reason, val entry: ListEntry? = null) {
    val block: Boolean get() = reason.blocks
}

/**
 * Decides one call. The order matters and is part of the contract (see the tests):
 * off, emergency call-backs, "block everything", the lists (allow before block), contacts, then
 * the mode's own rule.
 */
object CallRules {

    /**
     * @param lastBlockedAt when this number was last blocked (for [Settings.repeatCallers])
     */
    fun decide(
        call: IncomingCall,
        mode: Mode,
        settings: Settings,
        lists: ListMatcher,
        lastBlockedAt: Long?,
        now: Long,
    ): Decision {
        if (mode == Mode.OFF) return Decision(Reason.OFF)
        if (call.emergencyCallback) return Decision(Reason.EMERGENCY)
        if (mode == Mode.BLOCK_ALL) return Decision(Reason.BLOCK_ALL)

        val entry = if (call.hidden) null else lists.match(call.key)
        when (entry?.list) {
            ListKind.ALLOW -> return Decision(Reason.ALLOW_LISTED, entry)
            ListKind.BLOCK -> return Decision(Reason.BLOCK_LISTED, entry)
            null -> Unit
        }
        if (call.isContact) return Decision(Reason.CONTACT)

        return when (mode) {
            Mode.ALLOWLIST -> when {
                call.hidden -> Decision(Reason.HIDDEN)
                settings.repeatCallers && lastBlockedAt != null && now - lastBlockedAt in 0..Settings.REPEAT_WINDOW_MS ->
                    Decision(Reason.REPEATED)
                else -> Decision(Reason.NOT_ALLOWED)
            }
            else -> when {
                call.hidden && settings.blockHidden -> Decision(Reason.HIDDEN)
                settings.blockTelemarketing && PhoneNumbers.isTelemarketing(call.key) -> Decision(Reason.TELEMARKETING)
                settings.blockInternational && PhoneNumbers.isInternational(call.key) -> Decision(Reason.INTERNATIONAL)
                else -> Decision(Reason.NOT_LISTED)
            }
        }
    }
}

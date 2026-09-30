package io.github.brunovinicioslg.sossego.screening

import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.core.rules.BlockAction
import io.github.brunovinicioslg.sossego.core.rules.CallRules
import io.github.brunovinicioslg.sossego.core.rules.Decision
import io.github.brunovinicioslg.sossego.core.rules.IncomingCall
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.data.HistoryRepository
import io.github.brunovinicioslg.sossego.data.ListRepository
import io.github.brunovinicioslg.sossego.data.SettingsRepository
import io.github.brunovinicioslg.sossego.device.ContactChecker
import io.github.brunovinicioslg.sossego.notify.BlockNotifier
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What the caller's side of the phone tells about an incoming call. */
data class RingingCall(
    /** The number as the network gave it; null or blank when hidden. */
    val number: String?,
    /** The network withheld the number (private, unknown, payphone). */
    val withheld: Boolean,
    val emergencyCallback: Boolean = false,
)

/** A decision, and the settings it was made with (the response depends on them too). */
data class Verdict(val decision: Decision, val settings: Settings)

/**
 * Decides incoming calls and records the blocked ones. Shared by the call screening service and
 * the ringing receiver. Blocking: call off the main thread.
 */
class CallGate(
    private val settingsRepository: SettingsRepository,
    private val lists: ListRepository,
    private val history: HistoryRepository,
    private val contacts: ContactChecker,
    private val notifier: BlockNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    fun screen(call: RingingCall): Verdict {
        val settings = settingsRepository.current()
        val now = clock()
        val mode = settings.effectiveMode(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone()))
        if (mode == Mode.OFF) return Verdict(Decision(Reason.OFF), settings)

        val key = if (call.withheld) "" else PhoneNumbers.key(call.number)
        // Contacts' calls normally never reach the screening; checked anyway (when allowed to read
        // them), so no phone that sends them after all can make "only the allow list" block a contact.
        val isContact = mode == Mode.ALLOWLIST && key.isNotEmpty() && contacts.isContact(call.number.orEmpty())
        val incoming = IncomingCall(key, isContact = isContact, emergencyCallback = call.emergencyCallback)
        val decision = CallRules.decide(incoming, mode, settings, lists.matcher(), history.lastBlockedAt(key), now)
        if (decision.block) record(BlockedCall(time = now, key = key, reason = decision.reason, silenced = settings.action == BlockAction.SILENCE), settings)
        return Verdict(decision, settings)
    }

    /**
     * For "block everything": whether a call that is ringing (a contact's, which the screening did
     * not see) must be declined now.
     */
    fun mustDeclineRinging(): Settings? {
        val settings = settingsRepository.current()
        val mode = settings.effectiveMode(LocalDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone()))
        return settings.takeIf { mode == Mode.BLOCK_ALL }
    }

    /** Records a ringing call declined by "block everything", once (the phone announces it more than once). */
    fun recordDeclined(number: String?, settings: Settings) {
        val now = clock()
        val key = PhoneNumbers.key(number)
        if (history.recordedSince(key, Reason.BLOCK_ALL, now - DUPLICATE_WINDOW_MS)) return
        record(BlockedCall(time = now, key = key, reason = Reason.BLOCK_ALL), settings)
    }

    private fun record(call: BlockedCall, settings: Settings) {
        history.record(call)
        notifier.onBlocked(call, settings.notifications)
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 10_000L
    }
}

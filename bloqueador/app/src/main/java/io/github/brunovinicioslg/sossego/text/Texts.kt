package io.github.brunovinicioslg.sossego.text

import android.content.Context
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.core.rules.Reason

/** Why a call was blocked, for the history and the notifications. */
fun reasonText(context: Context, reason: Reason): String = context.getString(
    when (reason) {
        Reason.BLOCK_ALL -> R.string.reason_block_all
        Reason.BLOCK_LISTED -> R.string.reason_block_listed
        Reason.NOT_ALLOWED -> R.string.reason_not_allowed
        Reason.HIDDEN -> R.string.reason_hidden
        Reason.TELEMARKETING -> R.string.reason_telemarketing
        Reason.INTERNATIONAL -> R.string.reason_international
        // Calls that got through are not recorded; named anyway so no reason is ever blank.
        Reason.OFF, Reason.EMERGENCY, Reason.ALLOW_LISTED, Reason.CONTACT, Reason.REPEATED, Reason.NOT_LISTED -> R.string.reason_allowed
    },
)

/**
 * The caller as shown: the formatted number, "hidden number", or "a contact" for a contact's call
 * declined by "block everything" (the app does not get those numbers).
 */
fun callerText(context: Context, key: String, reason: Reason): String = when {
    key.isNotEmpty() -> PhoneNumbers.format(key)
    reason == Reason.BLOCK_ALL -> context.getString(R.string.caller_contact)
    else -> context.getString(R.string.caller_hidden)
}

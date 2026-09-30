package io.github.brunovinicioslg.sossego.screening

import android.telecom.CallScreeningService.CallResponse
import io.github.brunovinicioslg.sossego.core.rules.BlockAction

/** What to tell the phone about a call; plain values, so the choice can be tested anywhere. */
data class ResponsePlan(
    val disallow: Boolean = false,
    val reject: Boolean = false,
    val silence: Boolean = false,
    val skipCallLog: Boolean = false,
    val skipNotification: Boolean = false,
)

/** The answer given to the phone for a [Verdict]. */
object ScreeningResponses {

    val ALLOW: CallResponse get() = ResponsePlan().toCallResponse()

    fun plan(verdict: Verdict): ResponsePlan {
        if (!verdict.decision.block) return ResponsePlan()
        val settings = verdict.settings
        return when (settings.action) {
            // Rings without sound and ends as a missed call; the call log and the missed-call
            // notification cannot be skipped for a call that was let through.
            BlockAction.SILENCE -> ResponsePlan(silence = true)
            BlockAction.REJECT -> ResponsePlan(
                disallow = true,
                reject = true,
                skipCallLog = !settings.keepInCallLog,
                skipNotification = true,
            )
        }
    }

    fun of(verdict: Verdict): CallResponse = plan(verdict).toCallResponse()

    private fun ResponsePlan.toCallResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(disallow)
        .setRejectCall(reject)
        .setSilenceCall(silence)
        .setSkipCallLog(skipCallLog)
        .setSkipNotification(skipNotification)
        .build()
}

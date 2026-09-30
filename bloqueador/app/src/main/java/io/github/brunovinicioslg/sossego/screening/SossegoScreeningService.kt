package io.github.brunovinicioslg.sossego.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import io.github.brunovinicioslg.sossego.appContainer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Called by the phone for every incoming call from a number that is not in the contacts (all
 * calls, if this app were the phone app). The answer must come quickly: the phone lets the call
 * ring anyway if none comes in a few seconds.
 *
 * Any failure lets the call through: a bug here must never make calls disappear.
 */
class SossegoScreeningService : CallScreeningService() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onScreenCall(callDetails: Call.Details) {
        // Outgoing calls are shown to screening apps too: they are never touched.
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respond(callDetails, ScreeningResponses.ALLOW)
            return
        }
        val call = try {
            ringingCallOf(callDetails)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unreadable call details", e)
            respond(callDetails, ScreeningResponses.ALLOW)
            return
        }
        // The database and the settings are read off the main thread.
        executor.execute {
            val response = try {
                ScreeningResponses.of(appContainer.gate.screen(call))
            } catch (e: Throwable) {
                Log.e(TAG, "Screening failed; letting the call through", e)
                ScreeningResponses.ALLOW
            }
            respond(callDetails, response)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun respond(details: Call.Details, response: CallResponse) {
        try {
            respondToCall(details, response)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot answer the phone", e)
        }
    }

    private companion object {
        const val TAG = "SossegoScreening"

        fun ringingCallOf(details: Call.Details): RingingCall {
            val number = details.handle?.schemeSpecificPart
            val withheld = details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || number.isNullOrBlank()
            val emergency = details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
                details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
            return RingingCall(number, withheld, emergency)
        }
    }
}

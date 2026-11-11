package com.datecs.simplecallblocker

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log

/**
 * Bound by Telecom for each incoming call (only while this app holds ROLE_CALL_SCREENING).
 * Runs without the UI and without any foreground service. All checks are local and fast,
 * so the answer is given well within Telecom's response timeout.
 */
class BlockerScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val reason = try {
            decide(callDetails, number)
        } catch (t: Throwable) {
            // Fail open: an error must never turn into blocking all calls.
            Log.e(TAG, "Screening failed, allowing call", t)
            null
        }
        Log.i(TAG, "onScreenCall number=$number reason=$reason")

        val response = if (reason != null) {
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .build()
        } else {
            CallResponse.Builder().build()
        }
        // Answer first, then do the (non time-critical) logging.
        respondToCall(callDetails, response)

        if (reason != null) {
            try {
                Settings(this).addLog(number.ifBlank { "(hidden)" }, reason)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not write log", t)
            }
        }
    }

    /** Returns the block reason, or null to allow the call. */
    private fun decide(details: Call.Details, number: String): String? {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return null
        val settings = Settings(this)
        if (!settings.enabled) return null

        val hidden = number.isBlank() || details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        if (hidden) return if (settings.blockUnknown) "hidden" else null

        val iso = PhoneNumbers.countryIso(this)
        val normalized = PhoneNumbers.normalize(number, iso)
        if (normalized.isEmpty()) return null

        if (settings.blacklist.any { PhoneNumbers.normalize(it, iso) == normalized }) return "blacklist"
        if (settings.prefixes.any { PhoneNumbers.matchesPrefix(number, normalized, it, iso) }) return "prefix"

        // isContact() returns null when contacts cannot be read -> do not block (fail open).
        if (settings.blockUnknown && PhoneNumbers.isContact(this, number) == false) return "unknown"
        return null
    }

    companion object {
        const val TAG = "SimpleCallBlocker"
    }
}

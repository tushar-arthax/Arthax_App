package ai.arthax.app.callerid

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives every incoming call, with its number, before the phone rings — but only while
 * the rep has made Arthax the phone's call-screening app ([CallerIdSetup]).
 *
 * This is the same hook Truecaller uses. It is the one place Android hands an app the
 * caller's number without the app being the dialler, and it is delivered on every OEM
 * because the system binds to the service directly rather than broadcasting.
 *
 * It never blocks, rejects or silences a call. The response sent back is "allow, do
 * nothing", and it is sent before the lookup starts, so a slow network can never delay
 * the ring.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@AndroidEntryPoint
class CallerIdScreeningService : CallScreeningService() {

    @Inject lateinit var presenter: CallerIdPresenter

    override fun onScreenCall(details: Call.Details) {
        // Answered first: the system expects a response promptly, and nothing below may
        // stand between it and the ring.
        respondToCall(details, CallResponse.Builder().build())

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return

        val number = details.handle
            ?.takeIf { it.scheme == "tel" }
            ?.schemeSpecificPart

        presenter.onRinging(number, source = "call screening")
    }
}

package ai.arthax.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import ai.arthax.app.callerid.CallerIdPresenter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


/**
 * Notices that *a* call ended, and asks for the call log to be reconciled.
 *
 * For attribution it deliberately learns nothing about the call itself: for outgoing calls
 * the broadcast never carried the number, so rather than guess, the end of a call is only
 * a nudge, and [CallLogReconciler] reads the facts from the call log.
 *
 * For caller ID it is the fallback source. With READ_CALL_LOG granted the RINGING broadcast
 * still carries the incoming number on Android 10+ (delivered twice: once without it, once
 * with), which is enough to name a lead on phones where the rep has not granted the
 * call-screening role. [CallerIdPresenter] collapses the duplicates.
 *
 * Manifest-registered so it fires even when the app has been evicted from memory, which on
 * Xiaomi and Oppo is the common case rather than the edge case. That is what allows a call
 * dialled straight from the phone's own dialler, with Arthax closed, to still be logged.
 * ACTION_PHONE_STATE_CHANGED is exempt from the Android 8 implicit-broadcast restrictions.
 */
@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {

    @Inject lateinit var callerId: CallerIdPresenter

    override fun onReceive(context: Context, intent: Intent) {
        // No super.onReceive(): the Hilt Gradle plugin rewrites this class's superclass
        // after compilation and inserts the injecting call itself. `callerId` is set by
        // the time the body runs.
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                // The first of the two Android 10+ deliveries has no number; it is simply
                // not a ring we can name yet, and the second one follows at once.
                if (!number.isNullOrBlank()) callerId.onRinging(number, source = "phone state")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> callerId.onCallEnded()

            TelephonyManager.EXTRA_STATE_IDLE -> {
                callerId.onCallEnded()
                // The call log row is written just after the line is released, so the
                // reconcile is given a moment to let the platform catch up.
                // A backstop only. Where this broadcast is delivered it restarts the
                // watcher if an OEM killed it; where it is not (Xiaomi without autostart)
                // the watcher's own call log observer has already noticed the call.
                CallMonitorService.start(context, "a call ended")
            }
            // Whether anyone answers is not known here; the call log says so afterwards.
        }
    }
}

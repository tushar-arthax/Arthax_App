package com.example.arthax.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager


/**
 * Notices that *a* call ended, and asks for the call log to be reconciled.
 *
 * It deliberately learns nothing about the call itself. On Android 10+ the broadcast no
 * longer carries the number, and for outgoing calls it never did — so rather than guess,
 * this is only a nudge, and [CallLogReconciler] reads the facts from the call log.
 *
 * Manifest-registered so it fires even when the app has been evicted from memory, which on
 * Xiaomi and Oppo is the common case rather than the edge case. That is what allows a call
 * dialled straight from the phone's own dialler, with Arthax closed, to still be logged.
 * ACTION_PHONE_STATE_CHANGED is exempt from the Android 8 implicit-broadcast restrictions.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // The call log row is written just after the line is released, so the
                // reconcile is given a moment to let the platform catch up.
                // A backstop only. Where this broadcast is delivered it restarts the
                // watcher if an OEM killed it; where it is not (Xiaomi without autostart)
                // the watcher's own call log observer has already noticed the call.
                CallMonitorService.start(context, "a call ended")
            }
            // OFFHOOK and RINGING carry nothing we can act on: the number is withheld and
            // whether anyone answers is not yet known.
        }
    }
}

package com.example.arthax.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.CallMode
import com.example.arthax.domain.model.Lead
import com.example.arthax.domain.model.LogStage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Places a call from the lead list.
 *
 * Deliberately thin. It no longer records "which lead is being called", because that is no
 * longer how calls are attributed — [CallLogReconciler] matches every call, however it was
 * started, against the lead directory by phone number. Remembering an intended lead here
 * would only be a second, less reliable source of truth.
 *
 * What it still does is start the foreground service, so the process survives the post-call
 * recording scan on OEMs that would otherwise freeze it.
 */
@Singleton
class CallTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: EventLogger,
) {

    data class Launch(val intent: Intent, val mode: CallMode)

    fun beginCall(lead: Lead, mode: CallMode): Launch {
        logger.info(
            LogStage.CALL,
            "Dialling ${lead.name} on ${lead.phoneNumber}",
            leadId = lead.id,
            leadName = lead.name,
            detail = if (mode == CallMode.DIRECT) "Direct dial" else "Handed to the phone dialler",
        )

        // The watcher is already running and will notice this call in the call log
        // like any other; started here only in case an OEM killed it.
        CallMonitorService.start(context, "dialling a lead")

        val action = if (mode == CallMode.DIRECT) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.fromParts("tel", lead.phoneNumber, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return Launch(intent, mode)
    }

    /**
     * The dial intent could not be fired. Nothing to roll back — the call never reached the
     * call log, so nothing will be attributed to it — but the service should stop.
     */
    fun abandonCall(reason: String) {
        // The watcher stays up - it is not tied to any one call.
        logger.error(LogStage.CALL, "Call was not placed: $reason")
    }
}

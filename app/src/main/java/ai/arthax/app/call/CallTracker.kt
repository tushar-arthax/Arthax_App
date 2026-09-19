package ai.arthax.app.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.CallMode
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.domain.model.MatchSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Places a call from the lead list.
 *
 * Deliberately thin. Calls are attributed by [CallLogReconciler], which matches every call,
 * however it was started, against the CRM by phone number — so nothing here decides what
 * happened on the line.
 *
 * What it does remember is *which lead the rep tapped*, as a [ClickToCallIntent]. That is
 * not a second source of truth for the call; it is what lets the CRM be told the call was
 * placed from the app (`match_source = click_to_call`, or `web` when a colleague asked for
 * it from the CRM), and what files the call against the lead the rep chose when two leads
 * share a number.
 *
 * It also starts the foreground service, so the process survives the post-call recording
 * scan on OEMs that would otherwise freeze it.
 */
@Singleton
class CallTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val logger: EventLogger,
) {

    data class Launch(val intent: Intent, val mode: CallMode)

    suspend fun beginCall(
        lead: Lead,
        mode: CallMode,
        source: MatchSource = MatchSource.CLICK_TO_CALL,
    ): Launch {
        // Persisted before the dialler opens: the process is routinely killed between the
        // tap and the call log row appearing, and the reconcile that reads the row may
        // run in a brand new one.
        settings.setClickToCall(
            ClickToCallIntent(
                leadId = lead.id,
                leadName = lead.name,
                phone = lead.phoneNumber,
                at = System.currentTimeMillis(),
                source = source,
            ),
        )

        logger.info(
            LogStage.CALL,
            "Dialling ${lead.name} on ${lead.phoneNumber}",
            leadId = lead.id,
            leadName = lead.name,
            detail = (if (mode == CallMode.DIRECT) "Direct dial" else "Handed to the phone dialler") +
                (if (source == MatchSource.WEB) ", requested from the CRM" else ""),
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
     * The dial intent could not be fired. The call never reached the call log, so the
     * remembered tap is cleared — otherwise a call the rep dialled by hand later could be
     * claimed by it. The watcher stays up; it is not tied to any one call.
     */
    suspend fun abandonCall(reason: String) {
        settings.setClickToCall(null)
        logger.error(LogStage.CALL, "Call was not placed: $reason")
    }
}

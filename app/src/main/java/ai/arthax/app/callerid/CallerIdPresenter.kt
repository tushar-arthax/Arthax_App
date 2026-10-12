package ai.arthax.app.callerid

import android.os.Handler
import android.os.Looper
import ai.arthax.app.core.PhoneNumbers
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.data.repository.LeadResolver
import ai.arthax.app.di.ApplicationScope
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.notification.AppNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Truecaller-style caller ID, restricted to the organisation's own leads.
 *
 * On a ring: the number is looked up against the CRM (`GET /api/leads/by-phone`, org-wide,
 * via [LeadResolver], which falls back to the on-device list when offline), and if — and
 * only if — it is a lead, a card naming the lead is shown over the incoming-call screen.
 * Numbers that are not leads produce nothing at all: no card, no "unknown caller", no
 * network call beyond the one lookup. This is deliberately *not* a general caller-ID
 * service; it never consults any directory other than the rep's own CRM.
 *
 * Two sources report a ring — [CallerIdScreeningService] when the app holds the screening
 * role, and the PHONE_STATE broadcast otherwise — and both may fire for one call.
 * [IncomingCallGate] collapses them into one lookup. The card is taken down when the line
 * leaves the ringing state, from whichever source notices first.
 *
 * Everything here is safe to call from any thread; the window work is marshalled onto the
 * main thread.
 */
@Singleton
class CallerIdPresenter @Inject constructor(
    private val resolver: LeadResolver,
    private val overlay: CallerIdOverlay,
    private val notifications: AppNotifications,
    private val settings: AppSettings,
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val gate = IncomingCallGate()
    private val main = Handler(Looper.getMainLooper())
    private var lookup: Job? = null

    /**
     * An incoming call is ringing from [rawNumber]. [source] is only for the activity log.
     */
    fun onRinging(rawNumber: String?, source: String) {
        val key = PhoneNumbers.matchKey(rawNumber)
        val decision = gate.onRinging(key)
        if (decision !is IncomingCallGate.Decision.Lookup) return
        if (!tokenStore.isLoggedIn) return

        lookup?.cancel()
        lookup = scope.launch {
            if (!settings.callerIdEnabled.first()) return@launch

            val resolution = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
                resolver.resolve(rawNumber)
            }

            // The ring may already be over, or a different call may have started.
            if (!gate.isCurrent(key)) return@launch

            when (resolution) {
                is LeadResolver.Resolution.Lead -> present(resolution, key, source)
                null -> logger.warn(
                    LogStage.CALL,
                    "Caller ID: the CRM did not answer in time for an incoming call",
                    detail = "Number ending ${key.takeLast(4)}. The call is still tracked as usual.",
                )

                // Not a lead, or could not be determined offline: show nothing.
                else -> Unit
            }
        }
    }

    /** The line went off-hook or idle. Whatever was showing comes down. */
    fun onCallEnded() {
        gate.onCallEnded()
        lookup?.cancel()
        lookup = null
        main.post {
            overlay.dismiss()
            notifications.cancelIncomingLead()
        }
    }

    private fun present(lead: LeadResolver.Resolution.Lead, key: String, source: String) {
        val detail = listOfNotNull(
            lead.status?.replace('_', ' ')?.trim()?.replaceFirstChar { it.uppercase() },
            lead.company?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ").ifBlank { null }

        val footer = buildString {
            append("Arthax lead")
            lead.assignedToName?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · $it") }
            if (lead.isJunk) append(" · marked junk")
            if (lead.fromCache) append(" · offline")
        }

        logger.info(
            LogStage.CALL,
            "Incoming call from ${lead.name}",
            leadId = lead.id,
            leadName = lead.name,
            detail = "Caller ID shown ($source).",
        )

        main.post {
            // The ring may have ended while this was crossing to the main thread.
            if (!gate.isCurrent(key)) return@post
            val shown = overlay.show(
                CallerIdOverlay.Card(
                    leadId = lead.id,
                    name = lead.name,
                    detail = detail,
                    footer = footer,
                ),
            )
            if (!shown) {
                notifications.notifyIncomingLead(
                    leadId = lead.id,
                    leadName = lead.name,
                    subtitle = listOfNotNull(detail, footer).joinToString(" — "),
                    phone = key,
                )
            }
        }
    }

    private companion object {
        /**
         * A ring lasts twenty to thirty seconds. Past this the answer is of no use, and
         * the ordinary post-call reconcile will match the call anyway.
         */
        const val LOOKUP_TIMEOUT_MILLIS = 8_000L
    }
}

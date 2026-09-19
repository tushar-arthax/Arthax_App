package ai.arthax.app.call

import ai.arthax.app.core.PhoneNumbers
import ai.arthax.app.domain.model.MatchSource

/**
 * "The rep just tapped CALL on this lead" — or accepted a call the CRM asked for.
 *
 * Remembered for one purpose: telling the CRM that the call which follows was placed from
 * the app (`match_source = click_to_call`, or `web` when a colleague requested it from the
 * CRM and the phone was woken by push) and filing it against the lead the rep actually
 * chose — which matters when two leads share a number. It is not a second source of truth
 * for what happened on the line; the call log still decides that.
 *
 * Pure so the matching rule is unit-tested without a phone.
 */
data class ClickToCallIntent(
    val leadId: String,
    val leadName: String,
    val phone: String,
    /** When CALL was tapped. */
    val at: Long,
    /** How the call was started; what the CRM is told as `match_source`. */
    val source: MatchSource = MatchSource.CLICK_TO_CALL,
) {

    /**
     * Whether a call-log row is the call this tap produced.
     *
     * It has to be outgoing, to the same number, and have begun after the tap — with a
     * little slack, because some diallers stamp the row a second or two before the app
     * handed the number over — and within [WINDOW_MILLIS] of it. Anything later is a
     * separate call the rep dialled by hand.
     */
    fun matches(direction: String, rowNumber: String?, rowStartedAt: Long): Boolean =
        direction == OUTBOUND &&
            PhoneNumbers.sameNumber(phone, rowNumber) &&
            rowStartedAt >= at - START_SLACK_MILLIS &&
            rowStartedAt <= at + WINDOW_MILLIS

    /** Past the window the tap can no longer explain any call, and is forgotten. */
    fun isStale(now: Long): Boolean = now - at > WINDOW_MILLIS

    companion object {
        const val OUTBOUND = "outbound"

        /** Thirty minutes: long enough for a dialler that sat on the confirm screen. */
        const val WINDOW_MILLIS = 30L * 60 * 1000

        const val START_SLACK_MILLIS = 5_000L
    }
}

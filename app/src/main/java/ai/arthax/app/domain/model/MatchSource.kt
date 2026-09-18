package ai.arthax.app.domain.model

/**
 * Server field `match_source` on POST /api/calls/: how the phone decided which lead a call
 * belonged to. A closed set on the backend, so the analytics that group by it never meet a
 * value nobody knows how to read — only the three the device can actually produce are here.
 */
enum class MatchSource(val api: String) {
    /** The rep tapped CALL on this lead in the app and the call log row that followed is it. */
    CLICK_TO_CALL("click_to_call"),

    /** GET /api/leads/by-phone (or the older search fallback) answered live. */
    BY_PHONE("by_phone"),

    /** Matched from the offline list because the CRM could not be reached. */
    LEAD_CACHE("lead_cache"),
}

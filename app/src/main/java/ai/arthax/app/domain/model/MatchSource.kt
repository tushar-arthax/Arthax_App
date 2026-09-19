package ai.arthax.app.domain.model

/**
 * Server field `match_source` on POST /api/calls/: how the phone decided which lead a call
 * belonged to. A closed set on the backend, so the analytics that group by it never meet a
 * value nobody knows how to read — only the four the device can actually produce are here.
 */
enum class MatchSource(val api: String) {
    /** The rep tapped CALL on this lead in the app and the call log row that followed is it. */
    CLICK_TO_CALL("click_to_call"),

    /** GET /api/leads/by-phone (or the older search fallback) answered live. */
    BY_PHONE("by_phone"),

    /** Matched from the offline list because the CRM could not be reached. */
    LEAD_CACHE("lead_cache"),

    /**
     * A colleague clicked Call on the lead in the web CRM and the phone was woken by push
     * to place it. Distinct from [CLICK_TO_CALL] so the CRM can tell "the rep chose to
     * call" from "the rep was asked to call".
     */
    WEB("web"),
    ;

    companion object {
        /** For values read back from disk; anything unknown falls back to the in-app tap. */
        fun fromApiOrDefault(raw: String?): MatchSource =
            entries.firstOrNull { it.api == raw } ?: CLICK_TO_CALL
    }
}

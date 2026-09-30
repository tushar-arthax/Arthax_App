package ai.arthax.app.ui.leads

import ai.arthax.app.domain.model.LeadOption

/**
 * One chip in the row above the lead list.
 *
 * Status is not a fixed set on this backend — an org can add its own through
 * `POST /api/leads/statuses` — so the chips are built from what the server reports rather
 * than from an enum compiled into the app. [All] and [Junk] are the two that are not
 * statuses at all: one clears the filter, the other flips `is_junk`.
 */
sealed interface LeadFilter {
    val label: String

    /**
     * No filter. Not a status \u2014 it is the absence of one, which is why it is the only
     * entry here the server does not define.
     */
    data object All : LeadFilter {
        override val label = "All"
    }

    data class Status(val option: LeadOption) : LeadFilter {
        override val label: String get() = option.label
    }

    /** The `status` query value, or null on [All]. */
    val statusApi: String?
        get() = (this as? Status)?.option?.api

    companion object {
        /**
         * The chip row: All, then exactly what the organisation defined.
         *
         * Nothing else is invented. There used to be a hardcoded Junk chip here, which was
         * wrong twice over \u2014 junk is a flag rather than a status, so it filtered on a
         * different field to every chip beside it, and on an org that never marks leads junk
         * it was a chip that could only ever return nothing.
         */
        fun build(statuses: List<LeadOption>): List<LeadFilter> =
            listOf(All) + statuses.map { Status(it) }
    }
}

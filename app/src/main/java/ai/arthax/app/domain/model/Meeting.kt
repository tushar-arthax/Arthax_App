package ai.arthax.app.domain.model

/** A meeting booked against a lead. */
data class Meeting(
    val id: String,
    val leadId: String?,
    val leadName: String,
    val leadPhone: String?,
    /** Set when the meeting was booked off the back of a specific call. */
    val callId: String?,

    val title: String,
    val type: String?,
    val status: MeetingStatus,
    val notes: String?,
    val meetingLink: String?,
    val productService: String?,

    val scheduledAtMillis: Long?,
    val completedAtMillis: Long?,
    val durationMinutes: Int?,
) {
    val hasLink: Boolean get() = !meetingLink.isNullOrBlank()

    val isCallable: Boolean get() = !leadPhone.isNullOrBlank()

    /** Still expected to happen, so it belongs in the upcoming list. */
    val isOpen: Boolean get() = status == MeetingStatus.SCHEDULED || status == MeetingStatus.RESCHEDULED

    /** Scheduled, in the past, and nobody has said what happened. */
    fun isOverdue(now: Long): Boolean =
        isOpen && scheduledAtMillis != null && scheduledAtMillis < now
}

/**
 * Server enum `MeetingStatus`.
 *
 * [UNKNOWN] is not a server value: it is where anything this build has not heard of lands,
 * so a status added to the backend later shows up as itself rather than crashing the list
 * or silently being read as "scheduled".
 */
enum class MeetingStatus(val api: String, val label: String) {
    SCHEDULED("scheduled", "Scheduled"),
    COMPLETED("completed", "Completed"),
    CANCELLED("cancelled", "Cancelled"),
    RESCHEDULED("rescheduled", "Rescheduled"),
    NO_SHOW("no_show", "No show"),
    UNKNOWN("", "Unknown"),
    ;

    companion object {
        /** The statuses a rep can actually set from the phone. */
        val settable: List<MeetingStatus> =
            listOf(SCHEDULED, COMPLETED, CANCELLED, RESCHEDULED, NO_SHOW)

        fun fromApi(raw: String?): MeetingStatus =
            entries.firstOrNull { it.api.isNotEmpty() && it.api.equals(raw?.trim(), ignoreCase = true) }
                ?: UNKNOWN
    }
}

/**
 * The meeting types the booking sheet offers.
 *
 * The server takes free text, so this is a convenience list rather than a constraint — a
 * type created in the CRM that is not here still displays correctly on a meeting that
 * already has it.
 */
enum class MeetingType(val api: String, val label: String) {
    DEMO("demo", "Demo"),
    DISCOVERY("discovery", "Discovery"),
    FOLLOW_UP("follow_up", "Follow-up"),
    NEGOTIATION("negotiation", "Negotiation"),
    ONBOARDING("onboarding", "Onboarding"),
    ;

    companion object {
        /** "follow_up" -> "Follow-up" for display; unknown values are title-cased as-is. */
        fun labelFor(api: String?): String {
            val raw = api?.trim().orEmpty()
            if (raw.isEmpty()) return "Meeting"
            return entries.firstOrNull { it.api.equals(raw, ignoreCase = true) }?.label
                ?: raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

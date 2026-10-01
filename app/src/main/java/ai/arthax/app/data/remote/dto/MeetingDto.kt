package ai.arthax.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** `GET /api/meetings/` — paginated, same envelope shape as leads and calls. */
@JsonClass(generateAdapter = true)
data class MeetingPageDto(
    @Json(name = "items") val items: List<MeetingDto>? = null,
    @Json(name = "total") val total: Int? = null,
)

@JsonClass(generateAdapter = true)
data class MeetingDto(
    @Json(name = "id") val id: String,
    @Json(name = "lead_id") val leadId: String? = null,
    @Json(name = "agent_id") val agentId: String? = null,
    @Json(name = "call_id") val callId: String? = null,

    @Json(name = "meeting_type") val meetingType: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "notes") val notes: String? = null,

    /**
     * The server *returns* `meeting_link` but *accepts* `group_link_url` on create — see
     * [MeetingCreateRequest]. Not a typo on either side; the two names are simply the
     * contract, and pairing them here is the only place that asymmetry has to be known.
     */
    @Json(name = "meeting_link") val meetingLink: String? = null,
    @Json(name = "product_service") val productService: String? = null,

    @Json(name = "scheduled_at") val scheduledAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "duration_minutes") val durationMinutes: Int? = null,
    @Json(name = "created_at") val createdAt: String? = null,

    @Json(name = "lead") val lead: CallPartyDto? = null,
)

/**
 * `POST /api/meetings/`.
 *
 * `agent_id` is sent explicitly rather than left for the server to infer from the bearer
 * token, because a meeting the rep books on their own phone should be theirs even if the
 * backend's default ever changes.
 */
@JsonClass(generateAdapter = true)
data class MeetingCreateRequest(
    @Json(name = "lead_id") val leadId: String,
    @Json(name = "agent_id") val agentId: String? = null,
    @Json(name = "meeting_type") val meetingType: String,
    @Json(name = "scheduled_at") val scheduledAt: String,
    @Json(name = "duration_minutes") val durationMinutes: Int,
    @Json(name = "notes") val notes: String? = null,
    /** Named `group_link_url` on the way in; comes back as `meeting_link`. */
    @Json(name = "group_link_url") val groupLinkUrl: String? = null,
)

/**
 * `PATCH /api/meetings/{id}` — a partial update.
 *
 * Every field is nullable and Moshi omits nulls by default, so an instance carrying only
 * `notes` sends only `notes` and cannot blank out the rest of the meeting by accident.
 */
@JsonClass(generateAdapter = true)
data class MeetingUpdateRequest(
    @Json(name = "status") val status: String? = null,
    @Json(name = "scheduled_at") val scheduledAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "duration_minutes") val durationMinutes: Int? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "meeting_link") val meetingLink: String? = null,
)

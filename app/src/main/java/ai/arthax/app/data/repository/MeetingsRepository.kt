package ai.arthax.app.data.repository

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.ApiTime
import ai.arthax.app.data.remote.dto.MeetingCreateRequest
import ai.arthax.app.data.remote.dto.MeetingDto
import ai.arthax.app.data.remote.dto.MeetingUpdateRequest
import ai.arthax.app.domain.model.Meeting
import ai.arthax.app.domain.model.MeetingStatus
import ai.arthax.app.domain.model.MeetingType
import ai.arthax.app.domain.model.LogStage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeetingsRepository @Inject constructor(
    private val api: ArthaxApi,
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) {

    data class Page(
        val meetings: List<Meeting>,
        val total: Int,
        val nextSkip: Int,
        val hasMore: Boolean,
    )

    /**
     * One page of the rep's meetings.
     *
     * Scoped by `agent_id` like the call history: the endpoint is organisation-wide, and a
     * rep's diary should be their own.
     */
    suspend fun fetchMeetings(
        skip: Int = 0,
        limit: Int = ApiConfig.MEETINGS_PAGE_SIZE,
        status: MeetingStatus? = null,
        search: String? = null,
    ): ApiResult<Page> {
        val agentId = tokenStore.session?.userId
        if (agentId.isNullOrBlank()) {
            return ApiResult.Success(Page(emptyList(), 0, skip, hasMore = false))
        }

        val result = safeApiCall {
            api.getMeetings(
                // UNKNOWN is this app's own bucket for a status the server invented after
                // this build; it is never a value the server would accept as a filter.
                status = status?.api?.takeIf { it.isNotEmpty() },
                search = search?.trim()?.takeIf { it.isNotEmpty() },
                agentId = agentId,
                skip = skip,
                limit = limit,
            )
        }

        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                val items = body.items.orEmpty()
                val nextSkip = skip + items.size
                ApiResult.Success(
                    Page(
                        meetings = items.map { it.toDomain() },
                        total = body.total ?: items.size,
                        nextSkip = nextSkip,
                        hasMore = items.isNotEmpty() && nextSkip < (body.total ?: 0),
                    ),
                )
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not load meetings: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }
    }

    /**
     * Books a meeting.
     *
     * [scheduledAtMillis] is formatted with an explicit UTC offset by [ApiTime], so the
     * server never has to guess the rep's timezone — which matters more here than anywhere
     * else in the app, because a meeting five and a half hours out is a missed meeting.
     */
    suspend fun createMeeting(
        leadId: String,
        type: MeetingType,
        scheduledAtMillis: Long,
        durationMinutes: Int,
        notes: String?,
        meetingLink: String?,
    ): ApiResult<Meeting> {
        val request = MeetingCreateRequest(
            leadId = leadId,
            agentId = tokenStore.session?.userId,
            meetingType = type.api,
            scheduledAt = ApiTime.format(scheduledAtMillis),
            durationMinutes = durationMinutes,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            groupLinkUrl = meetingLink?.trim()?.takeIf { it.isNotEmpty() },
        )

        return when (val result = safeApiCall { api.createMeeting(request) }) {
            is ApiResult.Success -> {
                val meeting = result.data.toDomain()
                logger.success(
                    LogStage.SYNC,
                    "Meeting booked with ${meeting.leadName}",
                    leadId = meeting.leadId,
                    leadName = meeting.leadName,
                    detail = "${MeetingType.labelFor(meeting.type)}, $durationMinutes min",
                )
                ApiResult.Success(meeting)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not book the meeting: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }
    }

    /**
     * Moves a meeting to a new status.
     *
     * Uses the dedicated status route rather than a general update, because that is the one
     * the backend exposes for this and it sets `completed_at` itself.
     */
    suspend fun setStatus(meetingId: String, status: MeetingStatus): ApiResult<Meeting> {
        if (status == MeetingStatus.UNKNOWN) {
            return ApiResult.Failure.Unexpected("That status cannot be set from here")
        }

        return when (val result = safeApiCall { api.updateMeetingStatus(meetingId, status.api) }) {
            is ApiResult.Success -> {
                val meeting = result.data.toDomain()
                logger.info(
                    LogStage.SYNC,
                    "Meeting with ${meeting.leadName} marked ${status.label.lowercase()}",
                    leadId = meeting.leadId,
                    leadName = meeting.leadName,
                )
                ApiResult.Success(meeting)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not update the meeting: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }
    }

    /** Reschedules a meeting. Only the fields given are sent, so nothing else is disturbed. */
    suspend fun reschedule(
        meetingId: String,
        scheduledAtMillis: Long,
        durationMinutes: Int?,
        notes: String?,
    ): ApiResult<Meeting> {
        val request = MeetingUpdateRequest(
            status = MeetingStatus.RESCHEDULED.api,
            scheduledAt = ApiTime.format(scheduledAtMillis),
            durationMinutes = durationMinutes,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        )

        return when (val result = safeApiCall { api.updateMeeting(meetingId, request) }) {
            is ApiResult.Success -> {
                val meeting = result.data.toDomain()
                logger.info(
                    LogStage.SYNC,
                    "Meeting with ${meeting.leadName} rescheduled",
                    leadId = meeting.leadId,
                    leadName = meeting.leadName,
                )
                ApiResult.Success(meeting)
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not reschedule the meeting: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }
    }
}

internal fun MeetingDto.toDomain(): Meeting {
    val leadName = lead?.name?.trim()?.takeIf { it.isNotEmpty() }

    return Meeting(
        id = id,
        leadId = leadId ?: lead?.id,
        leadName = leadName ?: "Unnamed lead",
        leadPhone = lead?.phone?.trim()?.takeIf { it.isNotEmpty() },
        callId = callId,

        // The server titles most meetings itself; falling back to the type and the lead
        // beats showing an empty row for one it did not.
        title = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${MeetingType.labelFor(meetingType)} with ${leadName ?: "lead"}",
        type = meetingType?.trim()?.takeIf { it.isNotEmpty() },
        status = MeetingStatus.fromApi(status),
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        meetingLink = meetingLink?.trim()?.takeIf { it.isNotEmpty() },
        productService = productService?.trim()?.takeIf { it.isNotEmpty() },

        scheduledAtMillis = ApiTime.parseOrNull(scheduledAt),
        completedAtMillis = ApiTime.parseOrNull(completedAt),
        durationMinutes = durationMinutes,
    )
}

package ai.arthax.app.data.repository

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.CallAssessmentDto
import ai.arthax.app.data.remote.dto.CallHistoryDto
import ai.arthax.app.data.remote.dto.ApiTime
import ai.arthax.app.domain.model.AnalysisState
import ai.arthax.app.domain.model.CallAssessment
import ai.arthax.app.domain.model.CallDirection
import ai.arthax.app.domain.model.CallRecord
import ai.arthax.app.domain.model.LogStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rep's call history, read from the CRM.
 *
 * Strictly read-only. The write half of `api/calls/` — creating the record and attaching
 * the audio — lives in [CallSyncRepository] and is deliberately untouched by anything here:
 * a call still working its way out of the phone is that repository's business, and a
 * failure to *display* history must never be able to affect delivery.
 */
@Singleton
class CallHistoryRepository @Inject constructor(
    private val api: ArthaxApi,
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) {

    data class Page(
        val calls: List<CallRecord>,
        val total: Int,
        val nextSkip: Int,
        val hasMore: Boolean,
    )

    /**
     * One page of the signed-in rep's calls, newest first as the server returns them.
     *
     * Scoped to this rep by `agent_id`. The endpoint is organisation-wide, so without it a
     * rep would be shown their colleagues' calls — including recordings and transcripts
     * they have no business reading.
     */
    suspend fun fetchCalls(
        skip: Int = 0,
        limit: Int = ApiConfig.CALLS_PAGE_SIZE,
        search: String? = null,
        outcome: String? = null,
        /**
         * Narrows to one lead, for the call history on the lead screen.
         *
         * That screen could have used the `calls_history` array the lead detail route
         * returns, but the backend documents it only as an empty list, so its shape is a
         * guess. This endpoint's shape is known and already parsed and tested.
         */
        leadId: String? = null,
        /**
         * Fetch transcripts with the rows.
         *
         * Off for the main list, where twenty transcripts would be pulled for something no
         * row displays. On for a lead's history, which is a handful of calls and where the
         * transcript is read inline.
         */
        includeTranscript: Boolean = false,
    ): ApiResult<Page> {
        val agentId = tokenStore.session?.userId
        if (agentId.isNullOrBlank()) {
            // Not an error worth a banner: the session is being re-established, and the
            // screen simply has nothing to show yet.
            return ApiResult.Success(Page(emptyList(), 0, skip, hasMore = false))
        }

        val query = search?.trim()?.takeIf { it.isNotEmpty() }

        val result = safeApiCall {
            api.getCalls(
                agentId = agentId,
                leadId = leadId,
                search = query,
                outcome = outcome?.trim()?.takeIf { it.isNotEmpty() },
                skip = skip,
                limit = limit,
                includeTranscript = includeTranscript,
            )
        }

        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                val items = body.items.orEmpty()
                val calls = items.map { it.toDomain() }
                val nextSkip = skip + items.size

                ApiResult.Success(
                    Page(
                        calls = calls,
                        total = body.total ?: items.size,
                        nextSkip = nextSkip,
                        // The row count is trusted as well as the total: a stale total must
                        // not make the list request the same page for ever.
                        hasMore = items.isNotEmpty() && nextSkip < (body.total ?: 0),
                    ),
                )
            }

            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not load your calls: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }
    }

    /** One call in full, with its transcript. */
    suspend fun fetchCall(callId: String): ApiResult<CallRecord> =
        when (val result = safeApiCall { api.getCall(callId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Failure -> {
                logger.error(
                    LogStage.SYNC,
                    "Could not load call details: ${result.message}",
                    detail = result.detail,
                )
                result
            }
        }
}

internal fun CallHistoryDto.toDomain(): CallRecord {
    val status = callStatus?.trim()?.lowercase()
    val resolvedOutcome = outcome?.trim()?.lowercase()

    return CallRecord(
        id = id,
        leadId = leadId ?: lead?.id,
        leadName = lead?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown number",
        leadPhone = lead?.phone?.trim()?.takeIf { it.isNotEmpty() },
        agentName = agent?.fullName?.trim()?.takeIf { it.isNotEmpty() },

        // Both fields are consulted rather than just one: `call_status` is what physically
        // happened and is the more reliable of the two, but older rows carry only `outcome`.
        connected = status == "connected" || (status == null && resolvedOutcome == "connected"),
        outcome = resolvedOutcome,
        direction = if (direction?.trim()?.equals("inbound", ignoreCase = true) == true) {
            CallDirection.INBOUND
        } else {
            CallDirection.OUTBOUND
        },
        durationSeconds = (durationSeconds ?: 0).coerceAtLeast(0),
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },

        // `call_time` is when the call happened; `created_at` is when the row was written.
        // They differ for a call the phone delivered late, and the first is what a rep means.
        callTimeMillis = ApiTime.parseOrNull(callTime) ?: ApiTime.parseOrNull(createdAt),
        callbackScheduledAtMillis = ApiTime.parseOrNull(callbackScheduledAt),

        recordingUrl = recordingUrl?.trim()?.takeIf { it.isNotEmpty() },
        transcript = transcript?.trim()?.takeIf { it.isNotEmpty() },
        matchSource = matchSource?.trim()?.takeIf { it.isNotEmpty() },

        analysis = AnalysisState.of(
            transcription = transcriptionStatus,
            analysis = aiAnalysisStatus,
            hasAssessment = assessment != null,
        ),
        assessment = assessment?.toDomain(),
        complianceFlag = complianceFlag == true,
        performanceFlag = performanceFlag?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun CallAssessmentDto.toDomain() = CallAssessment(
    // The server sends 0..1. Anything outside that is clamped rather than trusted, so a
    // stray value cannot draw a meter past its own track.
    sentimentScore = sentimentScore?.coerceIn(0.0, 1.0),
    qualityScore = qualityScore?.coerceIn(0.0, 1.0),
    summary = summary?.trim()?.takeIf { it.isNotEmpty() },
    keyTopics = keyTopics.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
    improvementSuggestions = improvementSuggestions.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
    keywords = keywordsDetected.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },

    customerSatisfaction = customerSatisfaction?.trim()?.takeIf { it.isNotEmpty() },
    sentiment = detectedSentiment?.trim()?.takeIf { it.isNotEmpty() },
    intent = detectedIntent?.trim()?.takeIf { it.isNotEmpty() },
    objection = detectedObjection?.trim()?.takeIf { it.isNotEmpty() },

    buyingReadiness = buyingReadinessLevel?.trim()?.takeIf { it.isNotEmpty() },
    buyerIntent = overallBuyerIntent?.trim()?.takeIf { it.isNotEmpty() },

    competitorMentioned = competitorMention == true,
    competitorName = competitorName?.trim()?.takeIf { it.isNotEmpty() },

    suggestedClassification = suggestedClassification?.trim()?.takeIf { it.isNotEmpty() },
    classificationReason = classificationReason?.trim()?.takeIf { it.isNotEmpty() },

    discoveryQuestions = discoveryQuestionsCount,
    objectionRaised = objectionRaised == true,
    objectionAddressed = objectionAddressed == true,

    followUpTimeframe = followUpTimeframe?.trim()?.takeIf { it.isNotEmpty() },
    suggestedFollowUpMillis = ApiTime.parseOrNull(suggestedFollowUpDate),
)

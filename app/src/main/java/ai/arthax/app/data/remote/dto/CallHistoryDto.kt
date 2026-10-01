package ai.arthax.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `GET /api/calls/` — the rep's call history as the CRM holds it.
 *
 * Deliberately a separate file from [CallCreateRequest] and [CallResponseDto], which are
 * the *write* side of the same endpoint and are load-bearing for getting a finished call
 * out of the phone. Nothing here is on that path: this is read-only, and a change to it
 * must never be able to affect what gets posted.
 *
 * Only the fields the app actually shows are modelled. Moshi ignores unknown keys, so the
 * org-specific `custom_rule_results`, the token/credit accounting and the avatar-matching
 * block are left off rather than modelled as loose maps that would only add ways to fail
 * parsing.
 */
@JsonClass(generateAdapter = true)
data class CallHistoryPageDto(
    @Json(name = "items") val items: List<CallHistoryDto>? = null,
    @Json(name = "total") val total: Int? = null,
)

@JsonClass(generateAdapter = true)
data class CallHistoryDto(
    @Json(name = "id") val id: String,
    @Json(name = "lead_id") val leadId: String? = null,
    @Json(name = "outcome") val outcome: String? = null,
    @Json(name = "call_status") val callStatus: String? = null,
    @Json(name = "direction") val direction: String? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "callback_scheduled_at") val callbackScheduledAt: String? = null,

    /**
     * Free text, not our [ai.arthax.app.domain.model.MatchSource]. The server answers with
     * values this app never sends — "phone_number" among them — so it is carried as a
     * string and shown as one. Mapping it onto the enum would throw away anything the
     * backend added after this build shipped.
     */
    @Json(name = "match_source") val matchSource: String? = null,

    @Json(name = "recording_url") val recordingUrl: String? = null,
    @Json(name = "transcript") val transcript: String? = null,

    @Json(name = "ai_analyzed") val aiAnalyzed: Boolean? = null,
    @Json(name = "transcription_status") val transcriptionStatus: String? = null,
    @Json(name = "ai_analysis_status") val aiAnalysisStatus: String? = null,

    @Json(name = "compliance_flag") val complianceFlag: Boolean? = null,
    @Json(name = "performance_flag") val performanceFlag: String? = null,

    @Json(name = "call_time") val callTime: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,

    @Json(name = "assessment") val assessment: CallAssessmentDto? = null,
    @Json(name = "lead") val lead: CallPartyDto? = null,
    @Json(name = "agent") val agent: CallAgentDto? = null,
)

/** The `lead` block the call carries — just enough to name and dial whoever was called. */
@JsonClass(generateAdapter = true)
data class CallPartyDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone") val phone: String? = null,
)

@JsonClass(generateAdapter = true)
data class CallAgentDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
)

/**
 * What the AI made of the call.
 *
 * Every field is nullable or defaulted: the block is absent until analysis finishes, and a
 * call whose analysis failed still has to render.
 */
@JsonClass(generateAdapter = true)
data class CallAssessmentDto(
    @Json(name = "sentiment_score") val sentimentScore: Double? = null,
    @Json(name = "quality_score") val qualityScore: Double? = null,
    @Json(name = "summary") val summary: String? = null,
    @Json(name = "key_topics") val keyTopics: List<String>? = null,
    @Json(name = "improvement_suggestions") val improvementSuggestions: List<String>? = null,
    @Json(name = "keywords_detected") val keywordsDetected: List<String>? = null,

    @Json(name = "customer_satisfaction") val customerSatisfaction: String? = null,
    @Json(name = "detected_sentiment") val detectedSentiment: String? = null,
    @Json(name = "detected_intent") val detectedIntent: String? = null,
    @Json(name = "detected_objection") val detectedObjection: String? = null,

    @Json(name = "buying_readiness_level") val buyingReadinessLevel: String? = null,
    @Json(name = "overall_buyer_intent") val overallBuyerIntent: String? = null,

    @Json(name = "competitor_mention") val competitorMention: Boolean? = null,
    @Json(name = "competitor_name") val competitorName: String? = null,

    @Json(name = "suggested_classification") val suggestedClassification: String? = null,
    @Json(name = "classification_reason") val classificationReason: String? = null,

    @Json(name = "discovery_questions_count") val discoveryQuestionsCount: Int? = null,
    @Json(name = "objection_raised") val objectionRaised: Boolean? = null,
    @Json(name = "objection_addressed") val objectionAddressed: Boolean? = null,

    @Json(name = "follow_up_timeframe") val followUpTimeframe: String? = null,
    @Json(name = "suggested_follow_up_date") val suggestedFollowUpDate: String? = null,
)

package ai.arthax.app.domain.model

/**
 * One call as the CRM holds it, ready to render.
 *
 * Distinct from [ai.arthax.app.data.local.store.PendingCall], which is a call this phone
 * captured and is still trying to deliver. This is the other end of the same journey: what
 * the server has, including whatever the AI made of it. A call appears here once the queue
 * has successfully posted it.
 */
data class CallRecord(
    val id: String,
    val leadId: String?,
    val leadName: String,
    val leadPhone: String?,
    val agentName: String?,

    val connected: Boolean,
    val outcome: String?,
    val direction: CallDirection,
    val durationSeconds: Int,
    val notes: String?,

    /** When the call happened. Falls back to `created_at` when `call_time` is absent. */
    val callTimeMillis: Long?,
    val callbackScheduledAtMillis: Long?,

    val recordingUrl: String?,
    val transcript: String?,
    val matchSource: String?,

    val analysis: AnalysisState,
    val assessment: CallAssessment?,
    val complianceFlag: Boolean,
    val performanceFlag: String?,
) {
    val hasRecording: Boolean get() = !recordingUrl.isNullOrBlank()

    val hasTranscript: Boolean get() = !transcript.isNullOrBlank()

    val isInbound: Boolean get() = direction == CallDirection.INBOUND

    /** "4m 05s", or "0s" for a call nobody answered. */
    val durationLabel: String
        get() = when {
            durationSeconds <= 0 -> "0s"
            durationSeconds < 60 -> "${durationSeconds}s"
            else -> "${durationSeconds / 60}m ${"%02d".format(durationSeconds % 60)}s"
        }
}

/**
 * How far the server has got with analysing the call.
 *
 * Derived from the two independent status fields rather than shown raw, because a rep does
 * not care which of transcription and analysis is outstanding — only whether there is
 * anything to read yet, and whether waiting will help.
 */
enum class AnalysisState {
    /** Nothing started, or the call is too fresh. */
    NONE,

    /** Queued or running. Coming back later will show more. */
    IN_PROGRESS,

    /** There is an assessment to read. */
    READY,

    /** The server gave up. Waiting will not help. */
    FAILED,
    ;

    companion object {
        /**
         * Maps the pair of server statuses onto one state.
         *
         * A failure on either side is a failure overall, because the assessment depends on
         * the transcript. Anything not recognised counts as in progress: an unknown status
         * from a newer backend is far more likely to be a stage we have not heard of than a
         * terminal error, and "check back soon" is the safer thing to tell a rep.
         */
        fun of(transcription: String?, analysis: String?, hasAssessment: Boolean): AnalysisState {
            val stages = listOf(transcription, analysis).map { it?.trim()?.lowercase().orEmpty() }

            if (stages.any { it == "failed" || it == "error" }) return FAILED
            if (hasAssessment && stages.all { it == "completed" || it.isEmpty() }) return READY
            if (stages.all { it.isEmpty() || it == "not_required" || it == "skipped" }) {
                return if (hasAssessment) READY else NONE
            }
            return if (hasAssessment) READY else IN_PROGRESS
        }
    }
}

/** The AI's read on a call. Every field optional — the block fills in as analysis runs. */
data class CallAssessment(
    /** 0..1, or null. Higher is warmer. */
    val sentimentScore: Double?,
    /** 0..1, or null. How well the rep handled the call. */
    val qualityScore: Double?,
    val summary: String?,
    val keyTopics: List<String>,
    val improvementSuggestions: List<String>,
    val keywords: List<String>,

    val customerSatisfaction: String?,
    val sentiment: String?,
    val intent: String?,
    val objection: String?,

    val buyingReadiness: String?,
    val buyerIntent: String?,

    val competitorMentioned: Boolean,
    val competitorName: String?,

    val suggestedClassification: String?,
    val classificationReason: String?,

    val discoveryQuestions: Int?,
    val objectionRaised: Boolean,
    val objectionAddressed: Boolean,

    val followUpTimeframe: String?,
    val suggestedFollowUpMillis: Long?,
) {
    /** True when there is enough here to be worth opening the detail view for. */
    val hasAnythingToShow: Boolean
        get() = !summary.isNullOrBlank() ||
            keyTopics.isNotEmpty() ||
            sentimentScore != null ||
            qualityScore != null
}

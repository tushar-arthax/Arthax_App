package ai.arthax.app.data

import ai.arthax.app.data.remote.dto.CallHistoryPageDto
import ai.arthax.app.data.repository.toDomain
import ai.arthax.app.domain.model.CallDirection
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of `GET /api/calls/`.
 *
 * Exists because the endpoint answered 200 and the screen still showed a failure: Moshi's
 * generated adapters throw on an explicit `null` for a non-nullable field *even when that
 * field has a default*, and a default only covers a key that is absent. A real response
 * carries nulls the documented sample does not.
 */
class CallHistoryParsingTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(CallHistoryPageDto::class.java)

    /** The documented response, verbatim from the API docs. */
    @Test
    fun `parses the documented response`() {
        val page = adapter.fromJson(DOCUMENTED)

        assertNotNull(page)
        assertEquals(1, page!!.total)
        assertEquals(1, page.items!!.size)

        val call = page.items!!.single()
        assertEquals("7fa85f64-5717-4562-b3fc-2c963f66afa6", call.id)
        assertEquals(245, call.durationSeconds)
        assertEquals("Rahul Sharma", call.lead?.name)
        assertEquals("Amit Patil", call.agent?.fullName)
        assertEquals(0.82, call.assessment?.sentimentScore!!, 0.001)
        assertTrue(call.assessment?.keyTopics!!.contains("CRM"))

        // And the whole thing maps through to something renderable.
        val record = call.toDomain()
        assertEquals("Rahul Sharma", record.leadName)
        assertEquals("+919876543210", record.leadPhone)
        assertEquals(true, record.connected)
        assertEquals("4m 05s", record.durationLabel)
        // "phone_number" is not one of our MatchSource values, and must survive verbatim.
        assertEquals("phone_number", record.matchSource)
    }

    /**
     * The shape that actually broke it: a call the AI has not looked at yet, with nulls
     * where the documented sample had values. Every one of these fields threw before the
     * DTO was made null-tolerant.
     */
    @Test
    fun `parses a call whose optional fields are explicitly null`() {
        val page = adapter.fromJson(NULL_HEAVY)

        assertNotNull(page)
        val call = page!!.items!!.single()
        assertEquals("only-the-id-is-guaranteed", call.id)

        // The nulls survive parsing, and the domain mapping turns them into the defaults
        // the UI can actually render.
        val record = call.toDomain()
        assertEquals(0, record.durationSeconds)
        assertEquals(false, record.complianceFlag)
        assertEquals(CallDirection.OUTBOUND, record.direction)
        assertEquals(false, record.connected)
        assertEquals("Unknown number", record.leadName)
        assertNull(record.assessment)
        assertNull(record.transcript)
        assertNull(record.callTimeMillis)
        assertEquals("0s", record.durationLabel)
    }

    /** An assessment block present but half-filled, which is what a partial analysis gives. */
    @Test
    fun `parses an assessment whose lists and flags are null`() {
        val page = adapter.fromJson(NULL_ASSESSMENT)

        val record = page!!.items!!.single().toDomain()

        val assessment = record.assessment
        assertNotNull(assessment)
        assertTrue(assessment!!.keyTopics.isEmpty())
        assertTrue(assessment.improvementSuggestions.isEmpty())
        assertTrue(assessment.keywords.isEmpty())
        assertEquals(false, assessment.competitorMentioned)
        assertEquals(false, assessment.objectionRaised)
        assertNull(assessment.sentimentScore)
        // Nothing worth opening the sheet for.
        assertEquals(false, assessment.hasAnythingToShow)
    }

    /** An empty page must not be an error. */
    @Test
    fun `parses an empty page`() {
        val page = adapter.fromJson("""{"items": [], "total": 0}""")
        assertNotNull(page)
        assertTrue(page!!.items!!.isEmpty())
        assertEquals(0, page.total)
    }

    /** A server that omits the envelope counts entirely. */
    @Test
    fun `parses a page with no total`() {
        val page = adapter.fromJson("""{"items": []}""")
        assertNotNull(page)
        assertNull(page!!.total)
    }

    private companion object {
        val DOCUMENTED = """
        {
          "items": [
            {
              "lead_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "outcome": "connected",
              "call_status": "connected",
              "direction": "outbound",
              "duration_seconds": 245,
              "notes": "Customer interested in CRM demo",
              "callback_scheduled_at": "2026-09-25T10:30:00Z",
              "id": "7fa85f64-5717-4562-b3fc-2c963f66afa6",
              "org_id": "8fa85f64-5717-4562-b3fc-2c963f66afa6",
              "agent_id": "9fa85f64-5717-4562-b3fc-2c963f66afa6",
              "team_id": "6fa85f64-5717-4562-b3fc-2c963f66afa6",
              "external_id": "call_20260923_001",
              "match_source": "phone_number",
              "recording_url": "https://example.com/recordings/call_001.mp3",
              "transcript": "Hello Rahul, this is Amit from ArthaX.",
              "ai_analyzed": true,
              "transcription_status": "completed",
              "transcription_attempts": 1,
              "transcription_error": "",
              "ai_analysis_status": "completed",
              "ai_analysis_attempts": 1,
              "ai_analysis_error": "",
              "compliance_flag": false,
              "performance_flag": "good",
              "call_time": "2026-09-23T07:13:35.018Z",
              "created_at": "2026-09-23T07:13:40.018Z",
              "assessment": {
                "id": "4fa85f64-5717-4562-b3fc-2c963f66afa6",
                "org_id": "8fa85f64-5717-4562-b3fc-2c963f66afa6",
                "call_id": "7fa85f64-5717-4562-b3fc-2c963f66afa6",
                "sentiment_score": 0.82,
                "quality_score": 0.88,
                "key_topics": ["CRM", "sales automation", "pricing"],
                "summary": "Customer showed strong interest.",
                "improvement_suggestions": ["Ask more discovery questions."],
                "customer_satisfaction": "satisfied",
                "detected_objection": "pricing",
                "detected_intent": "purchase",
                "detected_sentiment": "positive",
                "possible_objection_detected": "Budget concern",
                "keywords_detected": ["CRM", "automation", "demo"],
                "sentiment_flag": "positive",
                "buying_readiness_level": "high",
                "primary_intent": "purchase",
                "competitor_mention": false,
                "competitor_name": "",
                "suggested_classification": "qualified",
                "classification_reason": "Strong buying intent.",
                "avatar_match_confidence": 0.92,
                "avatar_match_reason": "Business profile matches.",
                "discovery_questions_count": 4,
                "objection_raised": true,
                "objection_addressed": true,
                "validation_status": "valid",
                "validation_reason": "Valid sales conversation",
                "validation_reason_category": "valid_enquiry",
                "validation_confidence": 0.95,
                "validation_objective_source": "conversation",
                "target_match": "on_target",
                "custom_rule_results": [
                  {
                    "rule": "Pricing objection handled",
                    "triggered": true,
                    "evidence": "Agent explained pricing plans.",
                    "rule_id": "pricing_objection_rule"
                  }
                ],
                "overall_buyer_intent": "high",
                "follow_up_timeframe": "2_days",
                "follow_up_proof": "Customer requested a demo.",
                "suggested_follow_up_date": "2026-09-25T10:30:00Z",
                "tokens_input": 4210,
                "tokens_output": 1280,
                "model_used": "gemini",
                "credits_burned": "0.85",
                "assessed_at": "2026-09-23T07:20:15.018Z"
              },
              "lead": {
                "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "name": "Rahul Sharma",
                "phone": "+919876543210"
              },
              "agent": {
                "id": "9fa85f64-5717-4562-b3fc-2c963f66afa6",
                "full_name": "Amit Patil"
              }
            }
          ],
          "total": 1
        }
        """.trimIndent()

        val NULL_HEAVY = """
        {
          "items": [
            {
              "id": "only-the-id-is-guaranteed",
              "lead_id": null,
              "outcome": null,
              "call_status": null,
              "direction": null,
              "duration_seconds": null,
              "notes": null,
              "callback_scheduled_at": null,
              "external_id": null,
              "match_source": null,
              "recording_url": null,
              "transcript": null,
              "ai_analyzed": null,
              "transcription_status": null,
              "ai_analysis_status": null,
              "compliance_flag": null,
              "performance_flag": null,
              "call_time": null,
              "created_at": null,
              "assessment": null,
              "lead": null,
              "agent": null
            }
          ],
          "total": null
        }
        """.trimIndent()

        val NULL_ASSESSMENT = """
        {
          "items": [
            {
              "id": "partial-analysis",
              "duration_seconds": 12,
              "assessment": {
                "sentiment_score": null,
                "quality_score": null,
                "summary": null,
                "key_topics": null,
                "improvement_suggestions": null,
                "keywords_detected": null,
                "customer_satisfaction": null,
                "detected_sentiment": null,
                "detected_intent": null,
                "detected_objection": null,
                "buying_readiness_level": null,
                "overall_buyer_intent": null,
                "competitor_mention": null,
                "competitor_name": null,
                "suggested_classification": null,
                "classification_reason": null,
                "discovery_questions_count": null,
                "objection_raised": null,
                "objection_addressed": null,
                "follow_up_timeframe": null,
                "suggested_follow_up_date": null
              }
            }
          ],
          "total": 1
        }
        """.trimIndent()
    }
}

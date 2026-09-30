package ai.arthax.app.data

import ai.arthax.app.data.remote.dto.LeadDto
import ai.arthax.app.data.remote.dto.LeadOptionDto
import ai.arthax.app.data.remote.dto.toDomain
import ai.arthax.app.domain.model.LeadTemperature
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of the lead routes.
 *
 * [LeadDto] is read on the call-matching path by `LeadResolver`, so these also serve as a
 * guard that the fields added for the lead screen have not disturbed the six it depends on:
 * `id`, `name`, `phone`, `is_junk`, `assigned_to` and `created_at`.
 */
class LeadDetailParsingTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(LeadDto::class.java)

    @Test
    fun `parses the documented detail response`() {
        val dto = adapter.fromJson(DETAIL)
        assertNotNull(dto)

        // The six the call-matching path reads.
        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", dto!!.id)
        assertEquals("John Doe", dto.name)
        assertEquals("+919876543210", dto.phone)
        assertFalse(dto.isJunk)
        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", dto.assignedTo)
        assertNotNull(dto.createdAt)

        val lead = dto.toDomain()
        assertEquals("Sales Executive", lead.assignedToName)
        assertEquals("qualified", lead.status)
        assertEquals(LeadTemperature.WARM, lead.temperature)
        assertEquals("high_intent", lead.aiClassification)
        assertEquals("Requested demo and pricing", lead.aiClassificationReason)
        assertEquals("high", lead.buyerIntent)
        assertEquals(4, lead.rating)
        assertNotNull(lead.lastContactedAt)
        assertNotNull(lead.nextFollowUpAt)

        // Mixed-type custom fields flatten to text so the UI never has to care.
        assertEquals("Healthcare", lead.customFields["industry"])
        assertEquals("120", lead.customFields["employee_count"])
    }

    /**
     * `touches`, `calls_history`, `messages_history` and `demographics` come back on the
     * detail route and are deliberately not modelled. Moshi must skip them, not choke.
     */
    @Test
    fun `ignores the unmodelled detail arrays`() {
        val dto = adapter.fromJson(DETAIL)
        assertNotNull(dto)
        assertEquals("John Doe", dto!!.name)
    }

    /** "undetermined" is the server saying it has no verdict, not a verdict worth showing. */
    @Test
    fun `an undetermined classification is dropped`() {
        val dto = adapter.fromJson(
            """{"id": "x", "name": "Y", "ai_classification": "undetermined"}""",
        )
        assertNull(dto!!.toDomain().aiClassification)
    }

    /** A lead with almost nothing on it still has to render. */
    @Test
    fun `parses a sparse lead`() {
        val dto = adapter.fromJson(
            """{"id": "x", "name": "  ", "phone": null, "custom_fields": null, "rating": null}""",
        )
        val lead = dto!!.toDomain()

        assertEquals("Unnamed lead", lead.name)
        assertEquals("", lead.phoneNumber)
        assertFalse(lead.isCallable)
        assertTrue(lead.customFields.isEmpty())
        assertNull(lead.rating)
        assertEquals(LeadTemperature.COLD, lead.temperature)
    }

    /** `GET /api/leads/statuses` and `/sources` return a bare array of the same shape. */
    @Test
    fun `parses the org status list`() {
        val listAdapter = moshi.adapter<List<LeadOptionDto>>(
            Types.newParameterizedType(List::class.java, LeadOptionDto::class.java),
        )

        val options = listAdapter.fromJson(
            """
            [
              {"id": "a", "name": "New", "is_default": true},
              {"id": "b", "name": "follow_up_pending", "is_default": false}
            ]
            """.trimIndent(),
        )

        assertEquals(2, options!!.size)
        assertEquals("New", options[0].name)
        assertEquals(true, options[0].isDefault)
        assertEquals("follow_up_pending", options[1].name)
    }

    private companion object {
        val DETAIL = """
        {
          "name": "John Doe",
          "phone": "+919876543210",
          "email": "john@example.com",
          "company": "ABC Pvt Ltd",
          "gst_no": "27ABCDE1234F1Z5",
          "location": "Mumbai",
          "occupation": "Business Owner",
          "source": "manual",
          "campaign_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "ad_set_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "notes": "Interested in demo",
          "client_demographics": {},
          "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "org_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "status": "qualified",
          "temperature": "warm",
          "assigned_to": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "assigned_to_name": "Sales Executive",
          "team_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "ai_classification": "high_intent",
          "ai_classification_reason": "Requested demo and pricing",
          "is_junk": false,
          "junk_category": null,
          "is_revived": false,
          "created_at": "2026-09-23T08:51:23.646Z",
          "last_contacted_date": "2026-09-24T10:15:00.000Z",
          "next_follow_up_date": "2026-09-28T09:00:00.000Z",
          "rating": 4,
          "custom_fields": {
            "industry": "Healthcare",
            "employee_count": 120
          },
          "is_duplicate": false,
          "touches": [],
          "calls_history": [],
          "messages_history": [],
          "buyer_intent": "high",
          "demographics": []
        }
        """.trimIndent()
    }
}

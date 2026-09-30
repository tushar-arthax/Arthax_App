package ai.arthax.app.data

import ai.arthax.app.data.remote.dto.LeadCustomFieldDto
import ai.arthax.app.data.remote.dto.LeadTimelineEventDto
import ai.arthax.app.data.remote.dto.LeadTimelinePage
import ai.arthax.app.data.remote.dto.LeadTimelinePageAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/leads/{id}/timeline` and `GET /api/leads/custom-fields`.
 *
 * The timeline's schema is documented only as `"string"`, so the shape parsed here is
 * inferred from its description. These tests pin the two things that make that safe: the
 * documented shape is read correctly, and a response using different-but-plausible field
 * names still produces usable events rather than blank rows.
 */
class LeadTimelineParsingTest {

    // Through the factory, exactly as NetworkModule builds it. The event type has no
    // generated adapter any more: it is read by hand inside the factory so that one badly
    // typed field cannot reject the whole array, which is the failure this route kept
    // producing. Parsing it any other way here would test code the app does not run.
    private val moshi = Moshi.Builder().add(LeadTimelinePageAdapterFactory).build()
    private val pageAdapter = moshi.adapter(LeadTimelinePage::class.java)

    private fun parse(json: String): List<LeadTimelineEventDto> =
        pageAdapter.fromJson(json)!!.events

    @Test
    fun `parses the documented timeline shape`() {
        val events = parse(DOCUMENTED)
        assertEquals(3, events.size)

        val first = events.first()
        assertEquals("lead_created", first.resolvedType)
        assertEquals("John Doe", first.resolvedActor)
        assertEquals("Lead created manually", first.resolvedText)
        assertEquals("2026-09-20T10:00:00Z", first.resolvedAt)
    }

    /**
     * The same events under the other names a backend of this shape commonly uses. Each
     * must resolve, or the timeline would render a column of dated but empty rows.
     */
    @Test
    fun `resolves alternative field names`() {
        val events = parse(
            """
            [
              {
                "type": "call_completed",
                "timestamp": "2026-09-22T14:00:00Z",
                "user_name": "Rajni Singh",
                "message": "Call outcome: connected"
              },
              {
                "event_type": "note_added",
                "created_at": "2026-09-21T09:00:00Z",
                "actor": "Amit",
                "detail": "Left a voicemail"
              }
            ]
            """.trimIndent(),
        )

        assertEquals("call_completed", events[0].resolvedType)
        assertEquals("Rajni Singh", events[0].resolvedActor)
        assertEquals("Call outcome: connected", events[0].resolvedText)

        assertEquals("note_added", events[1].resolvedType)
        assertEquals("Amit", events[1].resolvedActor)
        assertEquals("Left a voicemail", events[1].resolvedText)
    }

    /** An event with nothing usable on it is recognised as empty so it can be dropped. */
    @Test
    fun `an event with no usable fields resolves to nothing`() {
        val event = parse("""[{"id": "x"}]""").single()

        assertNull(event.resolvedType)
        assertNull(event.resolvedAt)
        assertNull(event.resolvedActor)
        assertNull(event.resolvedText)
    }

    @Test
    fun `parses an empty timeline`() {
        assertTrue(parse("[]").isEmpty())
    }

    /** `GET /api/leads/custom-fields` — a select carries its own options. */
    @Test
    fun `parses custom field definitions`() {
        val fieldAdapter = moshi.adapter<List<LeadCustomFieldDto>>(
            Types.newParameterizedType(List::class.java, LeadCustomFieldDto::class.java),
        )

        val fields = fieldAdapter.fromJson(
            """
            [
              {"id": "a", "name": "industry", "field_type": "text", "options": [], "is_active": true},
              {
                "id": "b",
                "name": "lead_source",
                "field_type": "select",
                "options": ["Facebook", "Google", "Referral"],
                "is_active": true
              }
            ]
            """.trimIndent(),
        )

        assertEquals(2, fields!!.size)
        assertEquals("industry", fields[0].name)
        assertTrue(fields[0].options!!.isEmpty())
        assertEquals("select", fields[1].fieldType)
        assertEquals(3, fields[1].options!!.size)
    }

    private companion object {
        val DOCUMENTED = """
        [
          {
            "id": "event_uuid",
            "event_type": "lead_created",
            "occurred_at": "2026-09-20T10:00:00Z",
            "actor_name": "John Doe",
            "description": "Lead created manually"
          },
          {
            "id": "event_uuid_2",
            "event_type": "follow_up_scheduled",
            "occurred_at": "2026-09-21T11:30:00Z",
            "actor_name": "John Doe",
            "description": "Follow-up scheduled"
          },
          {
            "id": "event_uuid_3",
            "event_type": "call_completed",
            "occurred_at": "2026-09-22T14:00:00Z",
            "actor_name": "John Doe",
            "description": "Call outcome: connected"
          }
        ]
        """.trimIndent()
    }
}

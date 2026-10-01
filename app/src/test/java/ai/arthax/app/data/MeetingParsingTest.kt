package ai.arthax.app.data

import ai.arthax.app.data.remote.dto.MeetingPageDto
import ai.arthax.app.data.repository.toDomain
import ai.arthax.app.domain.model.MeetingStatus
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of `GET /api/meetings/`.
 *
 * The companion to [CallHistoryParsingTest], and here for the same reason: an explicit
 * `null` where a non-nullable field was declared throws at parse time and surfaces as a
 * failure on a screen the server answered 200 for.
 */
class MeetingParsingTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(MeetingPageDto::class.java)

    @Test
    fun `parses the documented response`() {
        val page = adapter.fromJson(DOCUMENTED)

        assertNotNull(page)
        assertEquals(1, page!!.total)

        val meeting = page.items!!.single().toDomain()
        assertEquals("CRM Product Demo", meeting.title)
        assertEquals("Rahul Sharma", meeting.leadName)
        assertEquals("+919876543210", meeting.leadPhone)
        assertEquals(MeetingStatus.SCHEDULED, meeting.status)
        assertEquals(45, meeting.durationMinutes)
        assertTrue(meeting.hasLink)
        assertTrue(meeting.isOpen)
        assertNull(meeting.completedAtMillis)
    }

    /** A meeting carrying nulls where the sample had values. */
    @Test
    fun `parses a meeting whose optional fields are explicitly null`() {
        val page = adapter.fromJson(NULL_HEAVY)

        val meeting = page!!.items!!.single().toDomain()
        assertEquals("only-the-id-is-guaranteed", meeting.id)
        // No title from the server, so one is built from the type and the lead.
        assertEquals("Meeting with lead", meeting.title)
        assertEquals("Unnamed lead", meeting.leadName)
        assertFalse(meeting.hasLink)
        assertFalse(meeting.isCallable)
        assertNull(meeting.scheduledAtMillis)
        assertNull(meeting.durationMinutes)
    }

    /**
     * A status this build has never heard of must land in UNKNOWN rather than being read
     * as "scheduled" — which would put a meeting nobody is expecting into the diary.
     */
    @Test
    fun `an unrecognised status becomes UNKNOWN rather than scheduled`() {
        val page = adapter.fromJson(
            """{"items": [{"id": "x", "status": "awaiting_client_confirmation"}], "total": 1}""",
        )

        val meeting = page!!.items!!.single().toDomain()
        assertEquals(MeetingStatus.UNKNOWN, meeting.status)
        assertFalse(meeting.isOpen)
    }

    /** A scheduled meeting whose time has passed is the one row that needs attention. */
    @Test
    fun `a past scheduled meeting reads as overdue`() {
        val page = adapter.fromJson(DOCUMENTED)
        val meeting = page!!.items!!.single().toDomain()

        val wellAfter = meeting.scheduledAtMillis!! + 60_000
        val wellBefore = meeting.scheduledAtMillis!! - 60_000

        assertTrue(meeting.isOverdue(wellAfter))
        assertFalse(meeting.isOverdue(wellBefore))
    }

    private companion object {
        val DOCUMENTED = """
        {
          "items": [
            {
              "lead_id": "550e8400-e29b-41d4-a716-446655440001",
              "meeting_type": "demo",
              "status": "scheduled",
              "scheduled_at": "2026-09-25T10:30:00Z",
              "title": "CRM Product Demo",
              "notes": "Customer requested a detailed product demonstration.",
              "meeting_link": "https://meet.example.com/abc-def-ghi",
              "product_service": "ArthaX CRM",
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "org_id": "650e8400-e29b-41d4-a716-446655440000",
              "agent_id": "750e8400-e29b-41d4-a716-446655440000",
              "team_id": "850e8400-e29b-41d4-a716-446655440000",
              "call_id": "950e8400-e29b-41d4-a716-446655440000",
              "completed_at": null,
              "duration_minutes": 45,
              "created_at": "2026-09-23T07:27:14.310Z",
              "lead": {
                "id": "550e8400-e29b-41d4-a716-446655440001",
                "name": "Rahul Sharma",
                "phone": "+919876543210"
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
              "agent_id": null,
              "call_id": null,
              "meeting_type": null,
              "status": null,
              "title": null,
              "notes": null,
              "meeting_link": null,
              "product_service": null,
              "scheduled_at": null,
              "completed_at": null,
              "duration_minutes": null,
              "created_at": null,
              "lead": null
            }
          ],
          "total": null
        }
        """.trimIndent()
    }
}

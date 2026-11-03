package ai.arthax.app.data

import ai.arthax.app.data.remote.dto.MarkAllNotificationsReadRequest
import ai.arthax.app.data.remote.dto.NotificationFeedDto
import ai.arthax.app.data.remote.dto.NotificationSummaryDto
import ai.arthax.app.data.repository.toDomain
import ai.arthax.app.push.NotificationCategories
import ai.arthax.app.push.NotificationTarget
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of `/api/notifications/feed`, `/summary` and the `read-all` request body, against
 * the shapes the backend returns.
 */
class NotificationParsingTest {

    private val moshi = Moshi.Builder().build()

    @Test
    fun `parses a feed page`() {
        val page = moshi.adapter(NotificationFeedDto::class.java).fromJson(FEED)!!
        assertEquals(46, page.total)
        assertEquals(3, page.pages)

        val first = page.items!!.first().toDomain()
        assertEquals("n-1", first.id)
        assertEquals("revenue", first.category)
        assertFalse(first.isRead)
        assertTrue(first.isUrgent)
        assertNotNull(first.createdAtMillis)
        assertEquals(NotificationTarget.Inbox, first.target)

        val second = page.items!![1].toDomain()
        assertEquals(NotificationTarget.Lead("lead-2"), second.target)
        assertTrue(second.isRead)
    }

    @Test
    fun `a row of nulls still parses`() {
        val page = moshi.adapter(NotificationFeedDto::class.java).fromJson(NULL_HEAVY)!!
        val item = page.items!!.single().toDomain()
        assertEquals("Arthax", item.title)
        assertEquals("", item.message)
        assertEquals(NotificationCategories.OTHER, item.category)
        assertNull(item.createdAtMillis)
        assertFalse(item.isRead)
        assertEquals(NotificationTarget.Inbox, item.target)
    }

    @Test
    fun `summary keeps every category, zeros included`() {
        val summary = moshi.adapter(NotificationSummaryDto::class.java).fromJson(SUMMARY)!!.toDomain()
        assertEquals(12, summary.total)
        assertEquals(5, summary.unread)
        assertEquals(NotificationCategories.ALL.toSet(), summary.byCategory.keys)
        assertEquals(8 to 3, summary.byCategory["revenue"])
        assertEquals(0 to 0, summary.byCategory["support_access"])
        // A category this build does not know is counted under Other.
        assertEquals(4 to 2, summary.byCategory["other"])
    }

    @Test
    fun `mark-all-read leaves unset filters out of the body`() {
        val adapter = moshi.adapter(MarkAllNotificationsReadRequest::class.java)
        assertEquals("{}", adapter.toJson(MarkAllNotificationsReadRequest()))
        assertEquals(
            """{"category":["revenue"],"date_from":"2026-11-01","date_to":"2026-11-03"}""",
            adapter.toJson(MarkAllNotificationsReadRequest(listOf("revenue"), "2026-11-01", "2026-11-03")),
        )
    }

    private companion object {
        const val FEED = """
        {"items": [
          {"id": "n-1", "title": "OVERDUE REVENUE", "message": "Invoice INV-104 is DUE TODAY and still unpaid!",
           "notification_type": "action_required", "category": "revenue", "created_at": "2026-11-03T10:00:00",
           "is_read": false, "org_id": "o-1", "recipient_id": "u-1", "sender_id": null,
           "entity_type": "revenue", "entity_id": "r-1"},
          {"id": "n-2", "title": "Lead Follow-up in 30 minutes", "message": "Call Ravi",
           "notification_type": "reminder", "category": "follow_up", "created_at": "2026-11-03T09:30:00.123456",
           "is_read": true, "entity_type": "lead", "entity_id": "lead-2"}
        ], "total": 46, "page": 1, "page_size": 20, "pages": 3}
        """

        const val NULL_HEAVY = """
        {"items": [{"id": "only-the-id", "title": null, "message": null, "notification_type": null,
          "category": null, "created_at": null, "is_read": null, "entity_type": null, "entity_id": null}],
         "total": 1, "page": 1, "page_size": 20, "pages": 1}
        """

        const val SUMMARY = """
        {"total": 12, "unread": 5, "by_category": [
          {"category": "revenue", "total": 8, "unread": 3},
          {"category": "something_new", "total": 4, "unread": 2},
          {"category": "support_access", "total": 0, "unread": 0}
        ]}
        """
    }
}

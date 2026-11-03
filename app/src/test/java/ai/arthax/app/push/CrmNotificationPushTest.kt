package ai.arthax.app.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CRM's in-app notifications, as the backend pushes them
 * (`app/services/push_service.py`, `build_data`): decoded, routed, muted by the right
 * switch, and opened in the right place — and the app's own older `notification` payload
 * still works exactly as before.
 */
class CrmNotificationPushTest {

    /** The `data` map the backend sends, key for key. */
    private val backend = mapOf(
        "action" to "NEW_NOTIFICATION",
        "notification_id" to "n-1",
        "title" to "Lead Follow-up in 30 minutes",
        "message" to "You need to follow up with Ravi Kumar from Acme in 30 minutes.",
        "notification_type" to "reminder",
        "category" to "follow_up",
        "entity_type" to "lead",
        "entity_id" to "lead-9",
        "created_at" to "2026-11-03T10:00:00",
        "channel_id" to "arthax_follow_up",
    )

    @Test
    fun `the backend payload decodes as a notification with every field`() {
        val message = PushMessage.parse(backend) as PushMessage.Notification
        assertEquals("Lead Follow-up in 30 minutes", message.title)
        assertEquals("You need to follow up with Ravi Kumar from Acme in 30 minutes.", message.body)
        assertEquals("reminder", message.notificationType)
        assertEquals("follow_up", message.category)
        assertEquals("lead", message.entityType)
        assertEquals("lead-9", message.entityId)
        assertEquals("n-1", message.notificationId)
    }

    @Test
    fun `folded by the messaging service it decodes the same`() {
        // With the app on screen, ArthaxMessagingService adds type and body from the
        // notification block before routing.
        val folded = backend + mapOf("type" to "notification", "body" to backend.getValue("message"))
        assertEquals(PushMessage.parse(backend), PushMessage.parse(folded))
    }

    @Test
    fun `the app's own older notification payload is unchanged`() {
        val message = PushMessage.parse(
            mapOf("type" to "notification", "title" to "Hello", "body" to "World", "notification_type" to "information"),
        ) as PushMessage.Notification
        assertEquals("World", message.body)
        assertNull(message.notificationId)
        assertEquals(NotificationCategories.OTHER, message.category)
    }

    @Test
    fun `an unknown category is shown as other, never dropped`() {
        val message = PushMessage.parse(backend + ("category" to "something_new")) as PushMessage.Notification
        assertEquals(NotificationCategories.OTHER, message.category)
    }

    @Test
    fun `an empty notification and an unknown action are dropped`() {
        assertNull(PushMessage.parse(mapOf("action" to "NEW_NOTIFICATION", "notification_id" to "n")))
        assertNull(PushMessage.parse(backend + ("action" to "SOMETHING_ELSE")))
    }

    @Test
    fun `follow-ups and meetings obey the reminders switch, the rest the general one`() {
        val followUp = PushMessage.parse(backend)!!
        val meeting = PushMessage.parse(backend + ("category" to "meeting"))!!
        val revenue = PushMessage.parse(backend + ("category" to "revenue"))!!

        val noReminders = NotificationPreferences(reminders = false)
        assertFalse(noReminders.allows(followUp))
        assertFalse(noReminders.allows(meeting))
        assertTrue(noReminders.allows(revenue))

        val noGeneral = NotificationPreferences(general = false)
        assertTrue(noGeneral.allows(followUp))
        assertFalse(noGeneral.allows(revenue))
    }

    @Test
    fun `the router hands it to the notification handler`() = runTest {
        var received: PushMessage.Notification? = null
        val handler = object : PushHandler {
            override suspend fun onDial(message: PushMessage.Dial) = Unit
            override suspend fun onLeadAssigned(message: PushMessage.LeadAssigned) = Unit
            override suspend fun onFollowUpDue(message: PushMessage.FollowUpDue) = Unit
            override suspend fun onMeetingReminder(message: PushMessage.MeetingReminder) = Unit
            override suspend fun onNotification(message: PushMessage.Notification) { received = message }
            override suspend fun onSyncNow(message: PushMessage.SyncNow) = Unit
            override suspend fun onConfigUpdated(message: PushMessage.ConfigUpdated) = Unit
            override suspend fun onDeviceAlert(message: PushMessage.DeviceAlert) = Unit
        }
        val router = PushRouter(handler, preferences = { NotificationPreferences() })

        assertEquals(PushRouter.Outcome.HANDLED, router.route(backend))
        assertEquals("n-1", received?.notificationId)

        val muted = PushRouter(handler, preferences = { NotificationPreferences(reminders = false) })
        assertEquals(PushRouter.Outcome.MUTED, muted.route(backend))
    }

    @Test
    fun `channel ids are the backend's`() {
        assertEquals(
            listOf(
                "arthax_follow_up", "arthax_meeting", "arthax_revenue", "arthax_call_alert", "arthax_credits",
                "arthax_whatsapp", "arthax_support_access", "arthax_announcement", "arthax_other",
            ),
            NotificationCategories.ALL.map { NotificationCategories.channelId(it) },
        )
        assertEquals(backend.getValue("channel_id"), NotificationCategories.channelId(backend.getValue("category")))
        assertEquals("arthax_other", NotificationCategories.channelId(null))
    }

    @Test
    fun `a tap opens what the notification is about`() {
        assertEquals(NotificationTarget.Lead("lead-9"), NotificationTarget.of("lead", "lead-9"))
        assertEquals(NotificationTarget.Inbox, NotificationTarget.of("lead", ""))
        assertEquals(NotificationTarget.Meetings, NotificationTarget.of("meeting", "m-1"))
        assertEquals(NotificationTarget.Calls, NotificationTarget.of("call", "c-1"))
        // No screen for these in the app: the Notifications screen shows the full text.
        assertEquals(NotificationTarget.Inbox, NotificationTarget.of("revenue", "r-1"))
        assertEquals(NotificationTarget.Inbox, NotificationTarget.of("whatsapp_campaign", "w-1"))
        assertEquals(NotificationTarget.Inbox, NotificationTarget.of("support_access_grant", "g-1"))
        assertEquals(NotificationTarget.Inbox, NotificationTarget.of(null, null))
    }

    @Test
    fun `tap extras are read from the push data, and only from it`() {
        val tap = NotificationTapExtras.read { backend[it] }
        assertEquals(NotificationTapExtras.Tap("n-1", NotificationTarget.Lead("lead-9")), tap)

        // Any other launch of the app.
        assertNull(NotificationTapExtras.read { null })
        // The Dismiss receiver's intent carries an INT notification_id and no action.
        assertNull(NotificationTapExtras.read { key -> if (key == "notification_id") 4012 else null })
        // The right action but no id to mark read.
        assertNull(NotificationTapExtras.read { key -> if (key == "action") "NEW_NOTIFICATION" else null })
    }
}

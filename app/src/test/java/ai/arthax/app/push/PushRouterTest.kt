package ai.arthax.app.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between a raw FCM payload and the app: every type reaches exactly its handler,
 * the old contract still dials, a repeated click is dropped, a muted kind stays muted, and
 * nothing malformed gets through — or throws.
 */
class PushRouterTest {

    private class RecordingHandler : PushHandler {
        val calls = mutableListOf<String>()
        var last: PushMessage? = null

        private fun record(name: String, message: PushMessage) {
            calls += name
            last = message
        }

        override suspend fun onDial(message: PushMessage.Dial) = record("dial", message)
        override suspend fun onLeadAssigned(message: PushMessage.LeadAssigned) = record("lead_assigned", message)
        override suspend fun onFollowUpDue(message: PushMessage.FollowUpDue) = record("follow_up_due", message)
        override suspend fun onMeetingReminder(message: PushMessage.MeetingReminder) = record("meeting_reminder", message)
        override suspend fun onNotification(message: PushMessage.Notification) = record("notification", message)
        override suspend fun onSyncNow(message: PushMessage.SyncNow) = record("sync_now", message)
        override suspend fun onConfigUpdated(message: PushMessage.ConfigUpdated) = record("config_updated", message)
        override suspend fun onDeviceAlert(message: PushMessage.DeviceAlert) = record("device_alert", message)
    }

    private val handler = RecordingHandler()
    private var preferences = NotificationPreferences()
    private val router = PushRouter(handler, preferences = { preferences })

    private val dial = mapOf(
        "type" to "dial",
        "lead_id" to "lead-1",
        "phone" to "9876543210",
        "lead_name" to "Amol",
        "requested_by" to "Priya",
        "request_id" to "req-1",
    )

    @Test
    fun `each type reaches its own handler`() = runTest {
        val payloads = listOf(
            dial,
            mapOf("type" to "lead_assigned", "lead_id" to "l2", "lead_name" to "Neha", "phone" to "9", "assigned_by" to "Priya"),
            mapOf("type" to "follow_up_due", "lead_id" to "l3", "lead_name" to "Raj", "phone" to "9", "due_at" to "2026-09-20T10:00:00", "minutes_left" to "10"),
            mapOf("type" to "meeting_reminder", "meeting_id" to "m1", "title" to "Site visit", "scheduled_at" to "2026-09-20T11:00:00Z", "minutes_left" to "15"),
            mapOf("type" to "notification", "title" to "Hello", "body" to "World", "notification_type" to "info"),
            mapOf("type" to "sync_now", "reason" to "dashboard"),
            mapOf("type" to "config_updated", "version" to "7"),
            mapOf("type" to "device_alert", "health" to "critical", "message" to "No heartbeat for 3 hours"),
        )

        payloads.forEach { assertEquals(PushRouter.Outcome.HANDLED, router.route(it)) }

        assertEquals(
            listOf("dial", "lead_assigned", "follow_up_due", "meeting_reminder", "notification", "sync_now", "config_updated", "device_alert"),
            handler.calls,
        )
    }

    @Test
    fun `fields are decoded with their types`() = runTest {
        router.route(mapOf("type" to "config_updated", "version" to "42"))
        assertEquals(PushMessage.ConfigUpdated(42), handler.last)

        router.route(mapOf("type" to "device_alert", "health" to "warning", "message" to "Battery optimised"))
        assertEquals(PushMessage.DeviceAlert(critical = false, message = "Battery optimised"), handler.last)

        router.route(mapOf("type" to "follow_up_due", "lead_id" to "l3", "lead_name" to "Raj", "phone" to "9", "minutes_left" to "10"))
        assertEquals(10, (handler.last as PushMessage.FollowUpDue).minutesLeft)
        assertNull((handler.last as PushMessage.FollowUpDue).dueAt)
    }

    @Test
    fun `the old DIAL_LEAD contract is a dial`() = runTest {
        val legacy = mapOf(
            "action" to "DIAL_LEAD",
            "lead_id" to "lead-9",
            "phone_number" to "+91 98765 43210",
            "lead_name" to "Old Contract",
        )

        assertEquals(PushRouter.Outcome.HANDLED, router.route(legacy))
        assertEquals(
            PushMessage.Dial(leadId = "lead-9", leadName = "Old Contract", phone = "+91 98765 43210", requestedBy = "", requestId = null),
            handler.last,
        )
    }

    @Test
    fun `malformed payloads are ignored, not thrown`() = runTest {
        val bad = listOf(
            emptyMap(),
            mapOf("type" to "dial"), // no phone, no lead
            mapOf("type" to "dial", "phone" to "9"), // no lead id
            mapOf("type" to "config_updated", "version" to "seven"),
            mapOf("type" to "device_alert", "health" to "critical"), // no message
            mapOf("type" to "notification"), // nothing to show
            mapOf("type" to "something_new_from_the_server", "x" to "y"),
            mapOf("action" to "NEW_NOTIFICATION"),
        )

        bad.forEach { assertEquals("$it", PushRouter.Outcome.MALFORMED, router.route(it)) }
        assertTrue(handler.calls.isEmpty())
    }

    @Test
    fun `a dial request seen twice is only dialled once`() = runTest {
        assertEquals(PushRouter.Outcome.HANDLED, router.route(dial))
        assertEquals(PushRouter.Outcome.DUPLICATE, router.route(dial))
        assertEquals(PushRouter.Outcome.DUPLICATE, router.route(dial + ("lead_name" to "Amol again")))
        assertEquals(listOf("dial"), handler.calls)

        // A different click is a different request.
        assertEquals(PushRouter.Outcome.HANDLED, router.route(dial + ("request_id" to "req-2")))
        assertEquals(2, handler.calls.size)
    }

    @Test
    fun `the dedup window is the last twenty requests`() = runTest {
        (1..20).forEach { assertEquals(PushRouter.Outcome.HANDLED, router.route(dial + ("request_id" to "r$it"))) }
        assertEquals(PushRouter.Outcome.DUPLICATE, router.route(dial + ("request_id" to "r1")))

        // Twenty-one more push the oldest out; r1 is forgotten and can come back.
        (21..41).forEach { router.route(dial + ("request_id" to "r$it")) }
        assertEquals(PushRouter.Outcome.HANDLED, router.route(dial + ("request_id" to "r1")))
    }

    @Test
    fun `a dial without a request id is never treated as a duplicate`() = runTest {
        val noId = dial - "request_id"
        assertEquals(PushRouter.Outcome.HANDLED, router.route(noId))
        assertEquals(PushRouter.Outcome.HANDLED, router.route(noId))
    }

    @Test
    fun `the notification switches are honoured`() = runTest {
        preferences = NotificationPreferences(newLeads = false, reminders = false, general = false)

        assertEquals(PushRouter.Outcome.MUTED, router.route(mapOf("type" to "lead_assigned", "lead_id" to "l2")))
        assertEquals(PushRouter.Outcome.MUTED, router.route(mapOf("type" to "follow_up_due", "lead_id" to "l3")))
        assertEquals(PushRouter.Outcome.MUTED, router.route(mapOf("type" to "meeting_reminder", "title" to "x")))
        assertEquals(PushRouter.Outcome.MUTED, router.route(mapOf("type" to "notification", "title" to "x")))
        assertTrue(handler.calls.isEmpty())

        // Calls from the CRM and the silent housekeeping messages cannot be switched off.
        assertEquals(PushRouter.Outcome.HANDLED, router.route(dial))
        assertEquals(PushRouter.Outcome.HANDLED, router.route(mapOf("type" to "sync_now")))
        assertEquals(PushRouter.Outcome.HANDLED, router.route(mapOf("type" to "config_updated", "version" to "1")))
        assertEquals(PushRouter.Outcome.HANDLED, router.route(mapOf("type" to "device_alert", "message" to "x")))
        assertEquals(listOf("dial", "sync_now", "config_updated", "device_alert"), handler.calls)
    }

    @Test
    fun `each switch only covers its own kind`() = runTest {
        preferences = NotificationPreferences(newLeads = true, reminders = false, general = true)

        assertEquals(PushRouter.Outcome.HANDLED, router.route(mapOf("type" to "lead_assigned", "lead_id" to "l2")))
        assertEquals(PushRouter.Outcome.MUTED, router.route(mapOf("type" to "follow_up_due", "lead_id" to "l3")))
        assertEquals(PushRouter.Outcome.HANDLED, router.route(mapOf("type" to "notification", "body" to "hi")))
    }
}

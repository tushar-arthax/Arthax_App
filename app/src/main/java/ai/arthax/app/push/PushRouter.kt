package ai.arthax.app.push

/**
 * What the app does with each kind of push. The Android side implements this; the router
 * only decides *whether* and *which*, so that decision is unit-tested on the JVM.
 */
interface PushHandler {
    suspend fun onDial(message: PushMessage.Dial)
    suspend fun onLeadAssigned(message: PushMessage.LeadAssigned)
    suspend fun onFollowUpDue(message: PushMessage.FollowUpDue)
    suspend fun onMeetingReminder(message: PushMessage.MeetingReminder)
    suspend fun onNotification(message: PushMessage.Notification)
    suspend fun onSyncNow(message: PushMessage.SyncNow)
    suspend fun onConfigUpdated(message: PushMessage.ConfigUpdated)
    suspend fun onDeviceAlert(message: PushMessage.DeviceAlert)
}

/**
 * Turns a raw FCM data payload into exactly one handler call, or none.
 *
 * Three gates, in order: the payload has to decode ([PushMessage.parse]); a dial request
 * the phone has already seen is dropped, because FCM may deliver a message twice and the
 * backend may resend on a slow ack — and two "Call Amol" notifications for one click is
 * exactly the kind of thing that makes a rep distrust the app; and the rep's notification
 * switches are honoured, except for CRM-requested calls, which are always delivered.
 */
class PushRouter(
    private val handler: PushHandler,
    private val preferences: suspend () -> NotificationPreferences,
    private val dedup: RequestDedup = RequestDedup(),
) {

    enum class Outcome { HANDLED, MALFORMED, DUPLICATE, MUTED }

    suspend fun route(data: Map<String, String>): Outcome {
        val message = PushMessage.parse(data) ?: return Outcome.MALFORMED

        if (message is PushMessage.Dial) {
            val id = message.requestId
            if (id != null && !dedup.firstSeen(id)) return Outcome.DUPLICATE
        }

        if (!preferences().allows(message)) return Outcome.MUTED

        when (message) {
            is PushMessage.Dial -> handler.onDial(message)
            is PushMessage.LeadAssigned -> handler.onLeadAssigned(message)
            is PushMessage.FollowUpDue -> handler.onFollowUpDue(message)
            is PushMessage.MeetingReminder -> handler.onMeetingReminder(message)
            is PushMessage.Notification -> handler.onNotification(message)
            is PushMessage.SyncNow -> handler.onSyncNow(message)
            is PushMessage.ConfigUpdated -> handler.onConfigUpdated(message)
            is PushMessage.DeviceAlert -> handler.onDeviceAlert(message)
        }
        return Outcome.HANDLED
    }
}

/**
 * The last few request ids seen, oldest evicted first. In memory only: a process restart
 * forgets them, which at worst repeats a notification the rep has most likely already
 * acted on, and never loses one.
 */
class RequestDedup(private val capacity: Int = DEFAULT_CAPACITY) {

    private val seen = LinkedHashSet<String>()

    /** True the first time an id is presented, false on every repeat. */
    @Synchronized
    fun firstSeen(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > capacity) seen.remove(seen.first())
        return true
    }

    companion object {
        const val DEFAULT_CAPACITY = 20
    }
}

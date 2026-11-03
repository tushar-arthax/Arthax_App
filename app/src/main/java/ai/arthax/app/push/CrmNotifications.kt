package ai.arthax.app.push

/**
 * The CRM's notification categories, mirrored from the backend
 * (`app/services/notification_policy.py`, `CATEGORIES`). Same keys, same order.
 *
 * A category says what a notification is ABOUT; it picks the Android channel, the filter
 * chip on the Notifications screen, and which of the rep's switches governs it. Pure
 * Kotlin so it is unit-tested on the JVM.
 */
object NotificationCategories {
    const val FOLLOW_UP = "follow_up"
    const val MEETING = "meeting"
    const val REVENUE = "revenue"
    const val CALL_ALERT = "call_alert"
    const val CREDITS = "credits"
    const val WHATSAPP = "whatsapp"
    const val SUPPORT_ACCESS = "support_access"
    const val ANNOUNCEMENT = "announcement"
    const val OTHER = "other"

    /** Every category, in the order the screen lists them. */
    val ALL: List<String> = listOf(
        FOLLOW_UP, MEETING, REVENUE, CALL_ALERT, CREDITS, WHATSAPP, SUPPORT_ACCESS, ANNOUNCEMENT, OTHER,
    )

    /** Governed by the rep's Reminders switch rather than General. */
    val REMINDER_CATEGORIES: Set<String> = setOf(FOLLOW_UP, MEETING)

    /** A category this build does not know (added on the server later) is shown as Other. */
    fun normalise(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALL } ?: OTHER

    fun label(category: String?): String = when (normalise(category)) {
        FOLLOW_UP -> "Follow-ups"
        MEETING -> "Meetings"
        REVENUE -> "Revenue"
        CALL_ALERT -> "Call alerts"
        CREDITS -> "Credits"
        WHATSAPP -> "WhatsApp"
        SUPPORT_ACCESS -> "Support access"
        ANNOUNCEMENT -> "Announcements"
        else -> "Other"
    }

    /**
     * The Android notification channel id. Must equal the backend's `channel_id`
     * (`arthax_<category>`): with the app in the background Android posts the push itself,
     * on exactly that channel, and only a channel the app created gets its name and
     * importance — anything else falls back to the default channel.
     */
    fun channelId(category: String?): String = CHANNEL_PREFIX + normalise(category)

    const val CHANNEL_PREFIX = "arthax_"
}

/**
 * Where tapping a CRM notification takes the rep. The same answer for the system
 * notification, the in-app one and a row on the Notifications screen.
 *
 * The app has no screen for a deal, a WhatsApp campaign or a support session, so those
 * open the Notifications screen, which shows the full text.
 */
sealed interface NotificationTarget {
    data class Lead(val leadId: String) : NotificationTarget
    data object Meetings : NotificationTarget
    data object Calls : NotificationTarget
    data object Inbox : NotificationTarget

    companion object {
        fun of(entityType: String?, entityId: String?): NotificationTarget {
            val id = entityId?.trim().orEmpty()
            return when (entityType?.trim()?.lowercase()) {
                "lead" -> if (id.isNotEmpty()) Lead(id) else Inbox
                "meeting" -> Meetings
                "call" -> Calls
                else -> Inbox
            }
        }
    }
}

/**
 * The extras a notification tap arrives with. Android copies an FCM message's `data` map
 * into the launch intent when it posts the notification itself (app in the background),
 * and this app's own notifications use the same keys — so one reader handles both.
 */
object NotificationTapExtras {
    const val ACTION = "action"
    const val NOTIFICATION_ID = "notification_id"
    const val ENTITY_TYPE = "entity_type"
    const val ENTITY_ID = "entity_id"

    data class Tap(val notificationId: String, val target: NotificationTarget)

    /**
     * A CRM notification tap, or null for any other launch.
     *
     * Requires `action=NEW_NOTIFICATION` and a string `notification_id`. The string check
     * matters: [NotificationDismissReceiver] uses an *int* `notification_id` extra on its own
     * intents, and a launch must never be mistaken for one of those.
     */
    fun read(get: (String) -> Any?): Tap? {
        if ((get(ACTION) as? String)?.trim() != PushMessage.ACTION_NEW_NOTIFICATION) return null
        val id = (get(NOTIFICATION_ID) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Tap(id, NotificationTarget.of(get(ENTITY_TYPE) as? String, get(ENTITY_ID) as? String))
    }
}

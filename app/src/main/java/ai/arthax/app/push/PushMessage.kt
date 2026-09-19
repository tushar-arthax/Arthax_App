package ai.arthax.app.push

/**
 * The push contract with the backend, decoded from an FCM *data* message.
 *
 * Every payload carries a `type`; the fields per type are listed on each subclass. Two
 * things are deliberate about the decoding:
 *
 *  - **It never throws.** A payload the app does not understand — a type added on the
 *    server after this build shipped, a missing field, a number that is not a number — is
 *    dropped with a log line. The FCM service runs on a thread with a hard time budget and
 *    an exception there crashes the process, which would take the call watcher down with it.
 *
 *  - **The old contract still works.** The previous app was woken with `action=DIAL_LEAD`
 *    and `phone_number`; the backend still sends that shape from the older click-to-call
 *    path, and it is read as a [Dial].
 *
 * Pure Kotlin so the decoding is unit-tested without a phone.
 */
sealed interface PushMessage {

    /** `dial`: a colleague clicked Call on this lead in the CRM. */
    data class Dial(
        val leadId: String,
        val leadName: String,
        val phone: String,
        /** Display name of whoever asked; blank when the old contract sent it. */
        val requestedBy: String,
        /** Server-side id of the click, for de-duplication. Null on the old contract. */
        val requestId: String?,
    ) : PushMessage

    /** `lead_assigned`: a lead was (re)assigned to this rep. */
    data class LeadAssigned(
        val leadId: String,
        val leadName: String,
        val phone: String,
        val assignedBy: String,
    ) : PushMessage

    /** `follow_up_due`: a follow-up on one of the rep's leads is coming up. */
    data class FollowUpDue(
        val leadId: String,
        val leadName: String,
        val phone: String,
        /** ISO-8601 as the server sent it; parsed on the phone side. */
        val dueAt: String?,
        val minutesLeft: Int?,
    ) : PushMessage

    /** `meeting_reminder`: a meeting is coming up; the lead is optional. */
    data class MeetingReminder(
        val meetingId: String,
        val title: String,
        val scheduledAt: String?,
        val minutesLeft: Int?,
        val leadId: String?,
        val phone: String?,
    ) : PushMessage

    /** `notification`: free-form, shown as given. */
    data class Notification(
        val title: String,
        val body: String,
        val notificationType: String,
        val entityType: String?,
        val entityId: String?,
    ) : PushMessage

    /** `sync_now`: the server wants the call log checked and a heartbeat sent. */
    data class SyncNow(val reason: String) : PushMessage

    /** `config_updated`: the server config changed; fetch it if this version is older. */
    data class ConfigUpdated(val version: Int) : PushMessage

    /** `device_alert`: the fleet dashboard thinks this phone needs attention. */
    data class DeviceAlert(val critical: Boolean, val message: String) : PushMessage

    companion object {
        const val TYPE_DIAL = "dial"
        const val TYPE_LEAD_ASSIGNED = "lead_assigned"
        const val TYPE_FOLLOW_UP_DUE = "follow_up_due"
        const val TYPE_MEETING_REMINDER = "meeting_reminder"
        const val TYPE_NOTIFICATION = "notification"
        const val TYPE_SYNC_NOW = "sync_now"
        const val TYPE_CONFIG_UPDATED = "config_updated"
        const val TYPE_DEVICE_ALERT = "device_alert"

        /** The previous app's contract: `action=DIAL_LEAD` with `phone_number`. */
        const val LEGACY_ACTION_DIAL = "DIAL_LEAD"

        /** Null for anything that cannot be acted on. Never throws. */
        fun parse(data: Map<String, String>): PushMessage? {
            fun text(key: String): String = data[key]?.trim().orEmpty()
            fun textOrNull(key: String): String? = text(key).takeIf { it.isNotEmpty() }
            fun int(key: String): Int? = textOrNull(key)?.toIntOrNull()

            val type = textOrNull("type")
                ?: if (text("action") == LEGACY_ACTION_DIAL) TYPE_DIAL else return null

            return when (type) {
                TYPE_DIAL -> {
                    // `phone` on the new contract, `phone_number` on the old one.
                    val phone = textOrNull("phone") ?: textOrNull("phone_number") ?: return null
                    Dial(
                        leadId = textOrNull("lead_id") ?: return null,
                        leadName = textOrNull("lead_name") ?: "a lead",
                        phone = phone,
                        requestedBy = text("requested_by"),
                        requestId = textOrNull("request_id"),
                    )
                }

                TYPE_LEAD_ASSIGNED -> LeadAssigned(
                    leadId = textOrNull("lead_id") ?: return null,
                    leadName = textOrNull("lead_name") ?: "a lead",
                    phone = text("phone"),
                    assignedBy = text("assigned_by"),
                )

                TYPE_FOLLOW_UP_DUE -> FollowUpDue(
                    leadId = textOrNull("lead_id") ?: return null,
                    leadName = textOrNull("lead_name") ?: "a lead",
                    phone = text("phone"),
                    dueAt = textOrNull("due_at"),
                    minutesLeft = int("minutes_left"),
                )

                TYPE_MEETING_REMINDER -> MeetingReminder(
                    meetingId = text("meeting_id"),
                    title = textOrNull("title") ?: "Meeting",
                    scheduledAt = textOrNull("scheduled_at"),
                    minutesLeft = int("minutes_left"),
                    leadId = textOrNull("lead_id"),
                    phone = textOrNull("phone"),
                )

                TYPE_NOTIFICATION -> {
                    val title = text("title")
                    val body = text("body")
                    // Nothing to show is nothing to show.
                    if (title.isEmpty() && body.isEmpty()) return null
                    Notification(
                        title = title.ifEmpty { "Arthax" },
                        body = body,
                        notificationType = text("notification_type"),
                        entityType = textOrNull("entity_type"),
                        entityId = textOrNull("entity_id"),
                    )
                }

                TYPE_SYNC_NOW -> SyncNow(reason = textOrNull("reason") ?: "the server asked")

                TYPE_CONFIG_UPDATED -> ConfigUpdated(version = int("version") ?: return null)

                TYPE_DEVICE_ALERT -> DeviceAlert(
                    critical = text("health").equals("critical", ignoreCase = true),
                    message = textOrNull("message") ?: return null,
                )

                else -> null
            }
        }
    }
}

/**
 * Which optional notifications the rep has switched on. Calls requested from the CRM are
 * not optional and so not in here; see [ai.arthax.app.data.local.prefs.AppSettings].
 */
data class NotificationPreferences(
    val newLeads: Boolean = true,
    val reminders: Boolean = true,
    val general: Boolean = true,
) {
    /** Whether this message may be shown. Anything silent (sync, config) is always allowed. */
    fun allows(message: PushMessage): Boolean = when (message) {
        is PushMessage.LeadAssigned -> newLeads
        is PushMessage.FollowUpDue, is PushMessage.MeetingReminder -> reminders
        is PushMessage.Notification -> general
        else -> true
    }
}

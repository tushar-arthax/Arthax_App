package ai.arthax.app.push

import ai.arthax.app.domain.model.Lead

/**
 * Decides which follow-ups get an on-device reminder, and when.
 *
 * The server sends a `follow_up_due` push, and that is the primary path. This is the net
 * under it: push is not delivered on every phone — Xiaomi with autostart off, a handset
 * with no Google services, a token the server lost — and a follow-up the rep never heard
 * about is a lead that goes cold. So every time the lead list comes back from the server,
 * a local timer is armed for each follow-up in the next two days. Whichever of the two
 * fires first records itself in the reminded set and the other stays quiet.
 *
 * Only follow-ups still ahead are planned; a date already past is the CRM's overdue list,
 * not something to buzz about at 3am when the app is next opened. One within the lead
 * time fires straight away.
 *
 * Pure Kotlin so the selection and the delay maths are unit-tested.
 */
object FollowUpPlanner {

    /** Remind this long before the follow-up. */
    const val LEAD_MILLIS = 10L * 60 * 1000

    /** Only follow-ups within this window are armed; the list is re-planned on every refresh. */
    const val HORIZON_MILLIS = 48L * 60 * 60 * 1000

    data class Reminder(
        val leadId: String,
        val leadName: String,
        val phone: String,
        /** Epoch millis of the follow-up itself. */
        val dueAt: Long,
    ) {
        val fireAt: Long get() = dueAt - LEAD_MILLIS

        /** Zero when the fire time has already passed but the follow-up has not. */
        fun delayFrom(now: Long): Long = (fireAt - now).coerceAtLeast(0)

        /** Unique WorkManager name: one timer per lead per due time. */
        val workName: String get() = workName(leadId, dueAt)

        /** Key in the reminded set — the same one the push path uses. */
        val remindedKey: String get() = remindedKey(leadId, dueAt)
    }

    fun workName(leadId: String, dueAt: Long) = "followup:$leadId:$dueAt"

    fun remindedKey(leadId: String, dueAt: Long) = "$leadId:$dueAt"

    /**
     * The reminders worth arming from this list, right now.
     *
     * @param alreadyReminded whether the rep has already been told about this follow-up,
     * by push or by an earlier timer.
     */
    fun plan(
        leads: List<Lead>,
        now: Long,
        alreadyReminded: (leadId: String, dueAt: Long) -> Boolean,
    ): List<Reminder> = leads.mapNotNull { lead ->
        val dueAt = lead.nextFollowUpAt ?: return@mapNotNull null
        if (dueAt <= now) return@mapNotNull null
        if (dueAt - now > HORIZON_MILLIS) return@mapNotNull null
        if (alreadyReminded(lead.id, dueAt)) return@mapNotNull null
        Reminder(
            leadId = lead.id,
            leadName = lead.name,
            phone = lead.phoneNumber,
            dueAt = dueAt,
        )
    }
}

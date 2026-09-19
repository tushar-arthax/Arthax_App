package ai.arthax.app.push

import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the on-device follow-up timers in step with the lead list.
 *
 * Called with the list every time it comes back from the server. For each lead the timer
 * for its current follow-up is armed (a no-op when it already is) and any timer for a
 * *different* date on the same lead is cancelled — the follow-up was moved in the CRM.
 * The selection itself is [FollowUpPlanner]'s, so it is tested without WorkManager.
 */
@Singleton
class FollowUpReminders @Inject constructor(
    private val settings: AppSettings,
    private val workScheduler: WorkScheduler,
) {

    suspend fun replan(leads: List<Lead>) {
        val now = System.currentTimeMillis()
        val reminded = settings.remindedKeys()
        val reminders = FollowUpPlanner.plan(leads, now) { leadId, dueAt ->
            FollowUpPlanner.remindedKey(leadId, dueAt) in reminded
        }
        val byLead = reminders.associateBy { it.leadId }

        for (lead in leads) {
            workScheduler.cancelStaleFollowUpWork(lead.id, byLead[lead.id]?.workName)
        }
        reminders.forEach { workScheduler.enqueueFollowUpReminder(it, now) }
    }

    /** The push for this follow-up got there first. */
    fun cancel(leadId: String, dueAt: Long) =
        workScheduler.cancelFollowUpReminder(FollowUpPlanner.workName(leadId, dueAt))
}

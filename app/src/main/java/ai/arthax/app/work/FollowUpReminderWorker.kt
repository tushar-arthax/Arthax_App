package ai.arthax.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.notification.AppNotifications
import ai.arthax.app.push.FollowUpPlanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The on-device follow-up reminder, fired ten minutes before the follow-up.
 *
 * It checks the reminded set before saying anything: if the server's push for the same
 * follow-up has already been shown, this stays silent. Never retried — a reminder that
 * missed its moment is worse than none.
 */
@HiltWorker
class FollowUpReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: AppSettings,
    private val tokenStore: SecureTokenStore,
    private val notifications: AppNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            // Signed out since it was armed: the lead is no longer this phone's business.
            if (!tokenStore.isLoggedIn) return Result.success()
            if (!settings.notificationPreferences.first().reminders) return Result.success()

            val leadId = inputData.getString(KEY_LEAD_ID) ?: return Result.success()
            val leadName = inputData.getString(KEY_LEAD_NAME).orEmpty().ifBlank { "a lead" }
            val phone = inputData.getString(KEY_PHONE).orEmpty()
            val dueAt = inputData.getLong(KEY_DUE_AT, 0L)
            if (dueAt <= 0L) return Result.success()

            val now = System.currentTimeMillis()
            if (!settings.markReminded(FollowUpPlanner.remindedKey(leadId, dueAt), now)) return Result.success()

            val minutes = ((dueAt - now) / 60_000L).toInt().coerceAtLeast(0)
            notifications.notifyReminder(
                key = "followup:$leadId:$dueAt",
                title = "Follow-up in $minutes min: $leadName",
                text = phone.ifBlank { "Open Arthax to see the lead" },
                leadId = leadId,
                leadName = leadName,
                phone = phone.takeIf { it.isNotBlank() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A reminder is best effort; there is nothing to retry into.
        }
        return Result.success()
    }

    companion object {
        const val KEY_LEAD_ID = "lead_id"
        const val KEY_LEAD_NAME = "lead_name"
        const val KEY_PHONE = "phone"
        const val KEY_DUE_AT = "due_at"
    }
}

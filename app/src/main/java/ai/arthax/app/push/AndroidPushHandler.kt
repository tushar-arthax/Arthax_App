package ai.arthax.app.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.remote.dto.ApiTime
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.data.repository.RemoteConfigRepository
import ai.arthax.app.data.repository.SyncHealthReporter
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.domain.model.MatchSource
import ai.arthax.app.notification.AppNotifications
import ai.arthax.app.ui.leads.LeadsRefreshSignal
import ai.arthax.app.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What each push does on the phone. Runs off the FCM thread, on the app scope: posting a
 * notification is quick, and anything longer — the call-log check, the heartbeat — is
 * handed to WorkManager or the reporter rather than done here.
 */
@Singleton
class AndroidPushHandler @Inject constructor(
    private val notifications: AppNotifications,
    private val dialRequests: DialRequests,
    private val leadsRefresh: LeadsRefreshSignal,
    private val followUps: FollowUpReminders,
    private val settings: AppSettings,
    private val workScheduler: WorkScheduler,
    private val healthReporter: SyncHealthReporter,
    private val remoteConfig: RemoteConfigRepository,
    private val logger: EventLogger,
) : PushHandler {

    /**
     * On screen: a confirm sheet in the app. Otherwise: a heads-up notification. Never
     * both — a sheet and a notification for one click reads as two requests.
     */
    override suspend fun onDial(message: PushMessage.Dial) {
        val request = DialRequest(
            leadId = message.leadId,
            leadName = message.leadName,
            phone = message.phone,
            requestedBy = message.requestedBy,
            requestId = message.requestId,
            source = MatchSource.WEB,
        )

        logger.info(
            LogStage.CALL,
            "The CRM asked this phone to call ${request.leadName}",
            leadId = request.leadId,
            leadName = request.leadName,
            detail = listOfNotNull(
                request.requestedBy.takeIf { it.isNotBlank() }?.let { "Requested by $it" },
                request.requestId?.let { "Request $it" },
            ).joinToString(". "),
        )

        if (isOnScreen() && dialRequests.offer(request)) return
        notifications.notifyDialRequest(request)
    }

    override suspend fun onLeadAssigned(message: PushMessage.LeadAssigned) {
        notifications.notifyLeadAssigned(
            leadId = message.leadId,
            leadName = message.leadName,
            phone = message.phone,
            assignedBy = message.assignedBy,
        )
        // So the lead is on the list by the time the rep opens it.
        leadsRefresh.request("a lead was assigned")
    }

    override suspend fun onFollowUpDue(message: PushMessage.FollowUpDue) {
        val dueAt = ApiTime.parseOrNull(message.dueAt)
        val minutes = message.minutesLeft ?: dueAt?.let { minutesUntil(it) }

        // The on-device timer for the same follow-up must stay quiet, and vice versa.
        if (dueAt != null) {
            val key = FollowUpPlanner.remindedKey(message.leadId, dueAt)
            if (!settings.markReminded(key, System.currentTimeMillis())) return
            followUps.cancel(message.leadId, dueAt)
        }

        notifications.notifyReminder(
            key = "followup:${message.leadId}:${dueAt ?: message.dueAt.orEmpty()}",
            title = if (minutes != null) "Follow-up in $minutes min: ${message.leadName}" else "Follow-up due: ${message.leadName}",
            text = message.phone.ifBlank { "Open Arthax to see the lead" },
            leadId = message.leadId,
            leadName = message.leadName,
            phone = message.phone.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun onMeetingReminder(message: PushMessage.MeetingReminder) {
        val at = ApiTime.parseOrNull(message.scheduledAt)
        val minutes = message.minutesLeft ?: at?.let { minutesUntil(it) }

        notifications.notifyReminder(
            key = "meeting:${message.meetingId.ifBlank { message.title }}",
            title = if (minutes != null) "Meeting in $minutes min: ${message.title}" else "Meeting: ${message.title}",
            text = message.phone ?: "Open Arthax to see the details",
            leadId = message.leadId,
            leadName = null,
            phone = message.phone,
        )
    }

    override suspend fun onNotification(message: PushMessage.Notification) {
        notifications.notifyGeneral(message.title, message.body)
    }

    /**
     * The call-log check goes through WorkManager — it reads the call log and may copy a
     * recording, which is far more than an FCM callback's time budget allows. The heartbeat
     * is one small request and is sent directly.
     */
    override suspend fun onSyncNow(message: PushMessage.SyncNow) {
        logger.info(LogStage.SYNC, "The server asked for a sync", detail = "Reason: ${message.reason}")
        workScheduler.enqueueReconcile(WorkScheduler.REASON_PUSH)
        runCatching { healthReporter.sendNow(WorkScheduler.REASON_PUSH) }
    }

    override suspend fun onConfigUpdated(message: PushMessage.ConfigUpdated) {
        remoteConfig.refreshIfVersionDiffers(message.version)
    }

    override suspend fun onDeviceAlert(message: PushMessage.DeviceAlert) {
        logger.warn(
            LogStage.SYNC,
            "The fleet dashboard flagged this phone: ${message.message}",
            detail = if (message.critical) "Severity: critical" else "Severity: warning",
        )
        notifications.notifyDeviceAlert(message.critical, message.message)
    }

    /** Any activity of the app started — read on the main thread, where the registry lives. */
    private suspend fun isOnScreen(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun minutesUntil(at: Long): Int = ((at - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
}

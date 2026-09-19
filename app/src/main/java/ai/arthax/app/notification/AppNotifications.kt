package ai.arthax.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.arthax.app.MainActivity
import ai.arthax.app.R
import ai.arthax.app.domain.model.MatchSource
import ai.arthax.app.push.DialRequest
import ai.arthax.app.push.DialRequestActivity
import ai.arthax.app.push.NotificationDismissReceiver
import ai.arthax.app.ui.navigation.MainTab
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels and builders.
 *
 * The two original channels are deliberate: call tracking is LOW so it does not buzz
 * during a call, while upload problems are DEFAULT because a rep needs to notice that a
 * recording did not make it to the server.
 *
 * Push adds four more, one per kind of message, so the rep can silence "general" in the
 * system settings without ever losing a call a colleague is waiting on. Importance is fixed
 * at creation on Android 8+, which is why each channel is created at the level its
 * messages need rather than adjusted per notification.
 */
@Singleton
class AppNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                "Call tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Arthax is linking a call to a lead."
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPLOAD,
                "Recording uploads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Progress and failures when sending recordings to the server."
            },
        )

        // HIGH with sound and vibration: a colleague is sitting at the CRM waiting for
        // this phone to ring the lead. Heads-up, never full-screen — Play restricts
        // full-screen intents to alarms and real incoming calls.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CRM_CALLS,
                "Calls requested from CRM",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A colleague asked you to call a lead from the CRM."
                enableVibration(true)
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LEADS,
                "New leads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A lead was assigned to you."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Follow-ups and meetings coming up."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERAL,
                "General notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Everything else the CRM sends."
            },
        )
    }

    fun callTrackingNotification(leadName: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("Tracking call")
            .setContentText(
                leadName.takeIf { it.isNotBlank() }
                    ?.let { "Linking this call to $it" }
                    ?: "Linking this call to a lead",
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    /**
     * The ongoing notification for the always-on watcher.
     *
     * Android requires one for a foreground service, and that is the right outcome here:
     * an app watching every call should say so plainly rather than doing it invisibly.
     *
     * The text doubles as a status line: how many calls are waiting for a lead and how
     * many recordings are waiting for the rep, when there are any.
     */
    fun watchingNotification(waitingForLead: Int = 0, needingReview: Int = 0): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("Arthax is running")
            .setContentText(
                listOfNotNull(
                    "Watching for calls with your leads",
                    waitingForLead.takeIf { it > 0 }?.let { "$it waiting for a matching lead" },
                    needingReview.takeIf { it > 0 }?.let { "$it recording(s) need your review" },
                ).joinToString(" · "),
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    fun workingNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_UPLOAD)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .build()

    /** Terminal upload failure — the one thing worth actually interrupting the rep for. */
    fun notifyUploadFailed(leadName: String, reason: String) {
        // Checked inline rather than via a helper so lint can see the guard. The rep can
        // decline notifications and everything else must keep working regardless.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val notification = NotificationCompat.Builder(context, CHANNEL_UPLOAD)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("Recording not uploaded")
            .setContentText("$leadName — $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$leadName — $reason"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

        NotificationManagerCompat.from(context).notify(idFor(leadName), notification)
    }

    /**
     * Refreshes the watcher's status line. Only while the service is in the foreground —
     * posting an ongoing notification with nothing behind it would leave one the rep can
     * never dismiss.
     */
    fun updateWatching(waitingForLead: Int, needingReview: Int) {
        if (!canPost()) return
        NotificationManagerCompat.from(context).notify(
            CALL_SERVICE_NOTIFICATION_ID,
            watchingNotification(waitingForLead, needingReview),
        )
    }

    /**
     * A recording was held back for the rep. Low priority on the quiet channel: it wants a
     * look at some point today, not an interruption during the next call.
     */
    fun notifyNeedsReview(leadName: String, reason: String) {
        if (!canPost()) return

        val text = "$leadName — $reason"
        val notification = NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("A recording needs your review")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text. Open Activity to upload it or discard it."))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

        NotificationManagerCompat.from(context).notify(BASE_REVIEW_ID + (leadName.hashCode() and 0xFFFF), notification)
    }

    // --- Push -------------------------------------------------------------------------

    /**
     * "Call <lead>" from a colleague in the CRM. Heads-up with Call and Dismiss actions;
     * tapping the body opens the lead in the app. The Call action goes through
     * [DialRequestActivity], which records the tap so the CRM learns the call was
     * requested from the web (`match_source = web`).
     */
    fun notifyDialRequest(request: DialRequest) {
        if (!canPost()) return

        val id = BASE_DIAL_ID + ((request.requestId ?: request.leadId).hashCode() and 0xFFF)
        val requestedBy = request.requestedBy.takeIf { it.isNotBlank() }
        val text = if (requestedBy != null) "Requested by $requestedBy from ArthaX" else "Requested from ArthaX"

        val notification = NotificationCompat.Builder(context, CHANNEL_CRM_CALLS)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("Call ${request.leadName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${request.phone}\n$text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter(DIAL_REQUEST_TIMEOUT_MILLIS)
            .setContentIntent(openAppIntent(id, MainTab.LEADS, leadId = request.leadId, search = request.phone))
            .addAction(0, "Call", callActionIntent(id, request))
            .addAction(0, "Dismiss", dismissIntent(id))
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** A lead landed on this rep's list. Call goes through the app so it is attributed. */
    fun notifyLeadAssigned(leadId: String, leadName: String, phone: String, assignedBy: String) {
        if (!canPost()) return

        val id = BASE_LEAD_ID + (leadId.hashCode() and 0xFFF)
        val text = listOfNotNull(
            phone.takeIf { it.isNotBlank() },
            assignedBy.takeIf { it.isNotBlank() }?.let { "by $it" },
        ).joinToString(" · ")

        val builder = NotificationCompat.Builder(context, CHANNEL_LEADS)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("New lead assigned: $leadName")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(id, MainTab.LEADS, leadId = leadId, search = phone.takeIf { it.isNotBlank() }))

        if (phone.isNotBlank()) {
            val request = DialRequest(leadId, leadName, phone, source = MatchSource.CLICK_TO_CALL)
            builder.addAction(0, "Call", callActionIntent(id, request))
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * A follow-up or meeting coming up. [key] identifies the event so the push and the
     * on-device timer for the same one replace rather than stack.
     */
    fun notifyReminder(key: String, title: String, text: String, leadId: String?, leadName: String?, phone: String?) {
        if (!canPost()) return

        val id = BASE_REMINDER_ID + (key.hashCode() and 0xFFF)
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(id, MainTab.LEADS, leadId = leadId, search = phone))

        if (!phone.isNullOrBlank() && !leadId.isNullOrBlank()) {
            val request = DialRequest(leadId, leadName ?: "lead", phone, source = MatchSource.CLICK_TO_CALL)
            builder.addAction(0, "Call", callActionIntent(id, request))
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /** Free-form from the server, shown as given. */
    fun notifyGeneral(title: String, body: String) {
        if (!canPost()) return

        val id = BASE_GENERAL_ID + ((title + body).hashCode() and 0xFFF)
        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(id, MainTab.LEADS))
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /**
     * The fleet dashboard says this phone is not syncing. Posted on the uploads channel:
     * it is the existing channel that is allowed to make a sound (the watcher's own
     * channel is LOW by design so it never buzzes mid-call), and "your calls are not
     * reaching the CRM" is the same family of problem as "your recording did not upload".
     * Tapping opens Settings, where every cause has a fix button.
     */
    fun notifyDeviceAlert(critical: Boolean, message: String) {
        if (!canPost()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_UPLOAD)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("Call tracking needs attention")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(DEVICE_ALERT_NOTIFICATION_ID, MainTab.SETTINGS))
            .build()

        NotificationManagerCompat.from(context).notify(DEVICE_ALERT_NOTIFICATION_ID, notification)
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun callActionIntent(notificationId: Int, request: DialRequest): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId,
            DialRequestActivity.intent(context, request, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun dismissIntent(notificationId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId,
            NotificationDismissReceiver.intent(context, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canPost(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent = openAppIntent(0, null)

    /**
     * Opens the app on a tab, optionally focused on a lead. The request code is the
     * notification id: PendingIntents that differ only in extras are otherwise the *same*
     * intent to Android, and every notification would open whichever lead was posted last.
     */
    private fun openAppIntent(
        requestCode: Int,
        tab: MainTab?,
        leadId: String? = null,
        search: String? = null,
    ): PendingIntent {
        val intent = MainActivity.openIntent(context, tab, leadId, search)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun idFor(key: String) = BASE_FAILURE_ID + (key.hashCode() and 0xFFFF)

    companion object {
        const val CHANNEL_CALL = "arthax_call_tracking"
        const val CHANNEL_UPLOAD = "arthax_uploads"
        const val CHANNEL_CRM_CALLS = "calls_from_crm"
        const val CHANNEL_LEADS = "leads"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_GENERAL = "general"

        const val CALL_SERVICE_NOTIFICATION_ID = 1001
        const val HARVEST_NOTIFICATION_ID = 1002
        const val SYNC_NOTIFICATION_ID = 1003
        const val DEVICE_ALERT_NOTIFICATION_ID = 1004
        private const val BASE_FAILURE_ID = 2000
        private const val BASE_REVIEW_ID = 3000
        private const val BASE_DIAL_ID = 4000
        private const val BASE_LEAD_ID = 5000
        private const val BASE_REMINDER_ID = 6000
        private const val BASE_GENERAL_ID = 7000

        /** A request to call someone is stale after a few minutes; the colleague has moved on. */
        private const val DIAL_REQUEST_TIMEOUT_MILLIS = 10L * 60 * 1000
    }
}

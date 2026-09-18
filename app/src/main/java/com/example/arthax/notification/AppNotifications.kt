package com.example.arthax.notification

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
import com.example.arthax.MainActivity
import com.example.arthax.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels and builders.
 *
 * Two channels, deliberately: call tracking is LOW so it does not buzz during a call,
 * while upload problems are DEFAULT because a rep needs to notice that a recording did
 * not make it to the server.
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

    private fun canPost(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun idFor(key: String) = BASE_FAILURE_ID + (key.hashCode() and 0xFFFF)

    companion object {
        const val CHANNEL_CALL = "arthax_call_tracking"
        const val CHANNEL_UPLOAD = "arthax_uploads"

        const val CALL_SERVICE_NOTIFICATION_ID = 1001
        const val HARVEST_NOTIFICATION_ID = 1002
        const val SYNC_NOTIFICATION_ID = 1003
        private const val BASE_FAILURE_ID = 2000
        private const val BASE_REVIEW_ID = 3000
    }
}

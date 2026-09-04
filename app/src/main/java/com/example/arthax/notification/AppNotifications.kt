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
     */
    fun watchingNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_arthax)
            .setContentTitle("Arthax is running")
            .setContentText("Watching for calls with your leads")
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
    }
}

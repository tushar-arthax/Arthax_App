package ai.arthax.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * The Dismiss action on a dial request. A notification action cannot simply cancel its
 * own notification; something has to receive the tap, and a receiver is the cheapest
 * thing that can.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    companion object {
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun intent(context: Context, notificationId: Int): Intent =
            Intent(context, NotificationDismissReceiver::class.java)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}

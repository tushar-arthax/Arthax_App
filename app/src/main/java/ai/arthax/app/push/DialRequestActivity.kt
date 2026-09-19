package ai.arthax.app.push

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.arthax.app.domain.model.MatchSource
import ai.arthax.app.notification.AppNotifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Call action of a notification. No UI: it exists because a notification action can
 * only start an activity, a service or a broadcast, and starting the dialler is something
 * only an activity in the foreground may do on current Android.
 *
 * Invisible, kept out of recents and out of history (see the manifest), so the rep sees
 * the dialler and nothing else. Everything is guarded: this is reached from a tap on the
 * lock screen, and a crash here would be a crash with nothing on screen to explain it.
 */
@AndroidEntryPoint
class DialRequestActivity : ComponentActivity() {

    @Inject lateinit var placer: DialRequestPlacer

    @Inject lateinit var notifications: AppNotifications

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = runCatching { intent.toDialRequest() }.getOrNull()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (notificationId != 0) runCatching { notifications.cancel(notificationId) }

        if (request == null) {
            Log.w(TAG, "Dial request without a phone number or lead id, ignoring")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                placer.place(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Could not place the requested call", t)
            } finally {
                finish()
            }
        }
    }

    private fun Intent.toDialRequest(): DialRequest? {
        val leadId = getStringExtra(EXTRA_LEAD_ID)?.takeIf { it.isNotBlank() } ?: return null
        val phone = getStringExtra(EXTRA_PHONE)?.takeIf { it.isNotBlank() } ?: return null
        return DialRequest(
            leadId = leadId,
            leadName = getStringExtra(EXTRA_LEAD_NAME).orEmpty().ifBlank { "lead" },
            phone = phone,
            requestedBy = getStringExtra(EXTRA_REQUESTED_BY).orEmpty(),
            requestId = getStringExtra(EXTRA_REQUEST_ID),
            source = MatchSource.fromApiOrDefault(getStringExtra(EXTRA_SOURCE)),
        )
    }

    companion object {
        private const val TAG = "DialRequestActivity"

        private const val EXTRA_LEAD_ID = "lead_id"
        private const val EXTRA_LEAD_NAME = "lead_name"
        private const val EXTRA_PHONE = "phone"
        private const val EXTRA_REQUESTED_BY = "requested_by"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun intent(context: Context, request: DialRequest, notificationId: Int): Intent =
            Intent(context, DialRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_LEAD_ID, request.leadId)
                .putExtra(EXTRA_LEAD_NAME, request.leadName)
                .putExtra(EXTRA_PHONE, request.phone)
                .putExtra(EXTRA_REQUESTED_BY, request.requestedBy)
                .putExtra(EXTRA_REQUEST_ID, request.requestId)
                .putExtra(EXTRA_SOURCE, request.source.api)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}

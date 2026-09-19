package ai.arthax.app.notification

import android.util.Log
import ai.arthax.app.di.ApplicationScope
import ai.arthax.app.push.PushMessage
import ai.arthax.app.push.PushRegistration
import ai.arthax.app.push.PushRouter
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase's entry point into the app.
 *
 * Two callbacks, both handed straight off this thread: Firebase gives it a short budget
 * and a long or crashing callback here takes the whole process — the call watcher
 * included — down with it. Nothing in either path is allowed to throw.
 */
@AndroidEntryPoint
class ArthaxMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registration: PushRegistration

    @Inject lateinit var router: PushRouter

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onNewToken(token: String) {
        appScope.launch {
            runCatching { registration.onNewToken(token) }
                .onFailure { Log.w(TAG, "Could not register the new push token", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = HashMap(message.data)

        // A notification-only message (the backend's broadcast path sends one, with
        // `action=NEW_NOTIFICATION`) only reaches this callback while the app is on screen;
        // it is folded into the general contract so it is shown the same way either time.
        val notification = message.notification
        if (notification != null && data["type"].isNullOrBlank()) {
            data["type"] = PushMessage.TYPE_NOTIFICATION
            data.putIfAbsent("title", notification.title.orEmpty())
            data.putIfAbsent("body", notification.body.orEmpty())
        }

        appScope.launch {
            runCatching { router.route(data) }
                .onSuccess { outcome ->
                    if (outcome != PushRouter.Outcome.HANDLED) Log.i(TAG, "Push ${data["type"]}: $outcome")
                }
                .onFailure { Log.e(TAG, "Push ${data["type"]} could not be handled", it) }
        }
    }

    private companion object {
        const val TAG = "ArthaxMessaging"
    }
}

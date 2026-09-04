package com.example.arthax.call

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.LogStage
import com.example.arthax.notification.AppNotifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Stays running and watches the phone's call log.
 *
 * This is the whole detection mechanism, and it is deliberately a long-lived foreground
 * service rather than scheduled work.
 *
 * Everything else was tried and failed on real hardware. The PHONE_STATE broadcast is never
 * delivered on Xiaomi builds without autostart. WorkManager jobs are batched, quota-limited,
 * and — measured on device — can be frozen partway through, so a call could sit undetected
 * for many minutes or be missed entirely. A foreground service is the one thing Android
 * treats as "the user is aware of this and it must keep running", which is exactly the
 * contract this needs.
 *
 * With the service alive, the sequence after any call is: the system writes the call log row,
 * the observer fires within a second, and the reconcile runs *inline* here — no scheduler,
 * no queue, no wait.
 */
@AndroidEntryPoint
class CallMonitorService : android.app.Service() {

    @Inject lateinit var reconciler: CallLogReconciler

    @Inject lateinit var callLogReader: CallLogReader

    @Inject lateinit var tokenStore: SecureTokenStore

    @Inject lateinit var notifications: AppNotifications

    @Inject lateinit var logger: EventLogger

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One reconcile at a time; a second trigger while one runs is folded into the next. */
    private var running: Job? = null
    private var rerunRequested = false
    private var observerRegistered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // The platform writes the row and then updates it with the final duration, so a
            // short debounce means one reconcile per call, reading a settled duration.
            handler.removeCallbacks(triggerReconcile)
            handler.postDelayed(triggerReconcile, DEBOUNCE_MILLIS)
        }
    }

    private val triggerReconcile = Runnable {
        // Logged before anything can return early. Without this, a call that is detected but
        // then dropped for any reason looks identical to a call that was never noticed at
        // all — which is exactly what made this impossible to diagnose from the device.
        logger.info(LogStage.CALL, "A call finished — checking whether it was a lead")
        reconcile("a call finished")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground()
        registerObserver()
    }

    /**
     * Goes foreground, and refuses to take the app down if it cannot.
     *
     * startForeground throws when the type passed is not a subset of what the manifest
     * declares — which is a hard crash, in a service that starts the instant a rep signs in.
     * Both types are declared now, but OEM builds vary enough that this must never be the
     * thing that kills the app: it degrades to an untyped foreground service, and failing
     * that stops quietly. The app then still works, just without the always-on watcher, and
     * says so instead of dying.
     */
    private fun enterForeground() {
        val notification = notifications.watchingNotification()
        val id = AppNotifications.CALL_SERVICE_NOTIFICATION_ID

        runCatching {
            ServiceCompat.startForeground(this, id, notification, foregroundType())
            return
        }.onFailure { first ->
            Log.e(TAG, "startForeground with a service type failed", first)
        }

        runCatching {
            // No type. Accepted on every version, and enough to keep the process alive.
            ServiceCompat.startForeground(this, id, notification, 0)
            logger.warn(
                LogStage.CALL,
                "Call watcher started in a limited mode",
                detail = "This device rejected the usual foreground service type. Calls are " +
                    "still detected, but the watcher may be stopped sooner by the system.",
            )
            return
        }.onFailure { second ->
            Log.e(TAG, "startForeground without a type also failed", second)
            logger.error(
                LogStage.CALL,
                "This device would not let the call watcher run",
                detail = "Calls will still be picked up when you open the app. " +
                    "${second::class.java.simpleName}: ${second.message.orEmpty()}",
            )
        }

        // Could not go foreground at all. Stopping is the only safe option — staying up
        // without a notification is what triggers the system to kill the process anyway.
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // START_STICKY means Android restarts this after a process death — including for a
        // rep who has since signed out. There is nothing to watch for then, and an ongoing
        // notification would be both confusing and a little sinister, so it stands down.
        if (!tokenStore.isLoggedIn) {
            enterForeground() // still required: the five second deadline applies regardless
            stopSelf()
            return START_NOT_STICKY
        }

        // onCreate only runs the first time; a later start must re-assert foreground state,
        // because startForegroundService() gives a five second deadline to do so every time.
        enterForeground()
        registerObserver()

        // Catches anything that happened while the service was not running — after a reboot,
        // an OEM kill, or an app update.
        reconcile(intent?.getStringExtra(EXTRA_REASON) ?: "watcher started")

        // STICKY so the system brings it back if it is ever killed for memory.
        return START_STICKY
    }

    private fun registerObserver() {
        if (observerRegistered || !callLogReader.hasPermission()) return

        runCatching {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            observerRegistered = true
            logger.info(
                LogStage.CALL,
                "Watching for calls with your leads",
                detail = "Every call, incoming or outgoing, is checked the moment it ends.",
            )
        }
    }

    /**
     * Runs the reconcile here rather than handing it to WorkManager.
     *
     * The service is already alive and exempt from the freezer, so this is immediate. Going
     * through the scheduler only added latency and a way for the work to be deferred or
     * frozen — which is what made calls appear to vanish.
     */
    private fun reconcile(reason: String) {
        // Not logged: onStartCommand already stands the service down when signed out, so
        // reaching here means a sign-out raced an in-flight check. Nothing worth saying.
        if (!tokenStore.isLoggedIn) return

        if (running?.isActive == true) {
            // A call ended while the previous reconcile was still working. Remember to go
            // again rather than running two at once over the same watermark.
            rerunRequested = true
            return
        }

        running = scope.launch {
            runCatching { reconciler.reconcile(reason) }
                .onFailure { error ->
                    logger.error(
                        LogStage.CALL,
                        "Checking the call log failed",
                        detail = error.message ?: error::class.java.simpleName,
                    )
                }

            if (rerunRequested) {
                rerunRequested = false
                reconcile("another call finished while checking")
            }
        }
    }

    private fun foregroundType(): Int = when {
        // specialUse, not dataSync: from Android 14 a dataSync service is capped at roughly
        // six hours a day and then force-stopped, which would silently blind the app for the
        // rest of the rep's shift.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        else -> 0
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (observerRegistered) {
            runCatching { contentResolver.unregisterContentObserver(observer) }
            observerRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.example.arthax.action.STOP_CALL_MONITOR"
        private const val EXTRA_REASON = "reason"
        private const val DEBOUNCE_MILLIS = 1_500L
        private const val TAG = "ArthaxWatcher"

        /**
         * Safe to call as often as you like — starting an already-running service just
         * delivers another onStartCommand, which triggers a catch-up reconcile.
         */
        fun start(context: Context, reason: String) {
            val intent = Intent(context, CallMonitorService::class.java)
                .putExtra(EXTRA_REASON, reason)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}

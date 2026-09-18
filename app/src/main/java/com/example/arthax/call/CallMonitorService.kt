package com.example.arthax.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.provider.CallLog
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.LogStage
import com.example.arthax.notification.AppNotifications
import com.example.arthax.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    /** One reconcile at a time, with no way for a trigger to be dropped. See [ReconcileGate]. */
    private val gate = ReconcileGate()
    private var observerRegistered = false

    /** When the run of call-log changes we are currently waiting out began. */
    private var settlingSince = 0L

    /**
     * A short run of re-checks after every hang-up — the part that makes detection real time
     * on phones whose call log does not announce itself promptly.
     *
     * The call-log notification this service waits on is not a guarantee. Samsung routes its
     * call history through its own logs provider, and the change notification can trail the
     * call, or arrive once for several calls. When that happened the missing calls were not
     * lost — the next heartbeat found them — but they turned up minutes late, and a rep who
     * checked the CRM straight after a run of redials reasonably concluded they were gone.
     *
     * So the line state is watched directly, and a hang-up schedules a handful of quick
     * passes that do not depend on the call log saying anything. Each is one indexed query
     * and silent in the log when there is nothing new; one of them lands after the row has
     * been written, however late the phone is in writing it.
     */
    private var followUpIndex = 0

    private val followUp = object : Runnable {
        override fun run() {
            reconcile(WorkScheduler.REASON_PERIODIC)
            val previous = FOLLOW_UP_AFTER_MILLIS[followUpIndex]
            followUpIndex++
            FOLLOW_UP_AFTER_MILLIS.getOrNull(followUpIndex)?.let { next ->
                handler.postDelayed(this, next - previous)
            }
        }
    }

    /** Restarts the re-check run; a second hang-up during a run simply begins it again. */
    private fun armFollowUps() {
        handler.removeCallbacks(followUp)
        followUpIndex = 0
        handler.postDelayed(followUp, FOLLOW_UP_AFTER_MILLIS.first())
    }

    private var lineWatcher: Any? = null

    /** Android 12 and later. A hang-up is the line returning to idle. */
    @RequiresApi(Build.VERSION_CODES.S)
    private inner class LineWatcher : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            // Registration also reports the current state, usually idle. That just runs a
            // few quiet passes, which is harmless, so it is not filtered out.
            if (state == TelephonyManager.CALL_STATE_IDLE) handler.post { armFollowUps() }
        }
    }

    /** Android 11 and earlier. Delivered on the main thread that created it. */
    @Suppress("DEPRECATION")
    private val legacyLineWatcher: PhoneStateListener by lazy {
        object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_IDLE) armFollowUps()
            }
        }
    }

    /**
     * Watches the line directly. A failure here is logged and otherwise ignored: the
     * call-log observer and the heartbeat still work, just less promptly on some phones.
     */
    private fun registerLineWatcher() {
        if (lineWatcher != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val telephony = getSystemService(TelephonyManager::class.java) ?: return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val watcher = LineWatcher()
                telephony.registerTelephonyCallback(mainExecutor, watcher)
                lineWatcher = watcher
            } else {
                @Suppress("DEPRECATION")
                telephony.listen(legacyLineWatcher, PhoneStateListener.LISTEN_CALL_STATE)
                lineWatcher = legacyLineWatcher
            }
        }.onFailure { Log.w(TAG, "Could not watch the line state", it) }
    }

    private fun unregisterLineWatcher() {
        val watcher = lineWatcher ?: return
        val telephony = getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (watcher as? TelephonyCallback)?.let(telephony::unregisterTelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephony.listen(legacyLineWatcher, PhoneStateListener.LISTEN_NONE)
            }
        }
        lineWatcher = null
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // The platform writes the row and then updates it with the final duration, so a
            // short pause after the last change means one reconcile per call, reading a
            // settled duration.
            //
            // But the pause has a ceiling, and that is not a detail. A plain debounce that
            // restarts on every change never fires at all while changes keep coming — and
            // OEM diallers touch the call log constantly during a run of redials, marking
            // rows read, syncing them, updating presentation. The reconcile was being
            // starved for exactly as long as the rep kept calling, which is precisely when
            // it was needed. Past the ceiling it runs regardless; a pass is cheap, and
            // running one too many costs nothing while running none loses calls.
            val now = SystemClock.uptimeMillis()
            if (settlingSince == 0L) settlingSince = now

            val waited = now - settlingSince
            val delay = (MAX_SETTLE_MILLIS - waited).coerceIn(0L, DEBOUNCE_MILLIS)

            handler.removeCallbacks(triggerReconcile)
            handler.postDelayed(triggerReconcile, delay)
        }
    }

    /**
     * A slow heartbeat, run by the service itself rather than by WorkManager.
     *
     * The fifteen-minute scheduled check is the documented safety net, but it is scheduled
     * work — exactly the thing Xiaomi and Oppo builds defer or drop, and this app already
     * exists in its current shape because that was measured happening. This one cannot be
     * throttled: the service is in the foreground, so its own handler keeps ticking.
     *
     * It costs one indexed call-log query when nothing has happened, and stays silent in the
     * activity log unless it actually finds a call, so it cannot become noise.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            reconcile(WorkScheduler.REASON_PERIODIC)
            handler.postDelayed(this, HEARTBEAT_MILLIS)
        }
    }

    private val triggerReconcile = Runnable {
        // Reopens the settling window, so the ceiling is measured per run of changes rather
        // than from the first change this service ever saw.
        settlingSince = 0L

        // Logged before anything can return early. Without this, a call that is detected but
        // then dropped for any reason looks identical to a call that was never noticed at
        // all — which is exactly what made this impossible to diagnose from the device.
        logger.info(LogStage.CALL, "A call finished — checking whether it was a lead")
        reconcile("a call finished")
        // And a few more looks shortly after, for a row the phone was still finishing.
        armFollowUps()
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
        registerLineWatcher()
        if (observerRegistered || !callLogReader.hasPermission()) return

        runCatching {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            observerRegistered = true
            handler.removeCallbacks(heartbeat)
            handler.postDelayed(heartbeat, HEARTBEAT_MILLIS)
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

        // A call ending while the previous check is still working does not start a second
        // check; the gate remembers it and the running one goes round again.
        if (!gate.tryStart()) return

        // Any abnormal end — the service torn down mid-pass, a scope already cancelled —
        // releases the gate, so a restarted watcher is never locked out of reconciling.
        scope.launch { drain(reason) }
            .invokeOnCompletion { cause -> if (cause != null) gate.abandon() }
    }

    /** Keeps reconciling for as long as calls keep arriving. */
    private suspend fun drain(firstReason: String) {
        var reason = firstReason

        while (true) {
            runCatching { reconciler.reconcile(reason) }
                .onFailure { error ->
                    // Cancellation is the service being torn down, not a failure to report.
                    if (error is CancellationException) throw error
                    logger.error(
                        LogStage.CALL,
                        "Checking the call log failed",
                        detail = error.message ?: error::class.java.simpleName,
                    )
                }

            // The status line on the ongoing notification: what is waiting for a lead, and
            // what is waiting for the rep. Cheap, and only ever posted while this service
            // is in the foreground.
            runCatching {
                val summary = reconciler.summary()
                notifications.updateWatching(summary.waitingForLead, summary.needingReview)
            }

            if (!gate.finishAndCheckRerun()) return

            // The same settle pause the observer applies. The platform writes the call log
            // row and then updates it with the final duration, and a follow-up pass that
            // started the instant the previous one ended could read a row mid-update and
            // record a connected call as unanswered.
            delay(DEBOUNCE_MILLIS)
            reason = "another call finished while this one was being checked"
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
        unregisterLineWatcher()
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

        /**
         * The longest the settling pause may be extended by further changes.
         *
         * Without a ceiling a busy call log postpones the reconcile indefinitely. Five
         * seconds is still comfortably past the point where a row's duration has settled.
         */
        private const val MAX_SETTLE_MILLIS = 5_000L

        /**
         * When to re-check after a hang-up, as offsets from the hang-up itself. Front-loaded
         * because most phones write the row within a couple of seconds; the tail covers the
         * slow ones. After this the one-minute heartbeat takes over.
         */
        private val FOLLOW_UP_AFTER_MILLIS = listOf(2_000L, 6_000L, 15_000L, 40_000L)

        /**
         * One minute. It used to be five, which made a late notification look like a lost
         * call: Samsung routes call history through its own logs provider, and the change
         * notification can trail the call by a while, so a rep checking the CRM straight
         * after a run of redials saw calls "missing" that turned up minutes later. A pass
         * with nothing new is one indexed query, so a minute is still invisible in battery
         * terms — and it is now only the last resort behind the hang-up trigger.
         */
        private const val HEARTBEAT_MILLIS = 60_000L
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

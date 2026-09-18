package ai.arthax.app.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ai.arthax.app.data.local.store.RemoteConfigStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place background work is enqueued.
 *
 * Sync is scheduled per queued call rather than as one worker draining a shared queue. That
 * costs a little scheduling overhead and buys fault isolation: a recording the server keeps
 * rejecting cannot block the good ones behind it, and each call gets its own backoff clock.
 */
@Singleton
class WorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val configStore: RemoteConfigStore,
) {

    /**
     * Matches recent calls against the lead directory.
     *
     * Expedited and unconstrained: reading the call log and capturing the recording locally
     * must not wait for a network. The upload it schedules afterwards is what needs one.
     *
     * REPLACE rather than KEEP, because a newer request always covers everything an older
     * one would have — the reconcile is watermark-driven, not per-call.
     */
    fun enqueueReconcile(reason: String) {
        val request = OneTimeWorkRequestBuilder<CallReconcileWorker>()
            .setInputData(Data.Builder().putString(CallReconcileWorker.KEY_REASON, reason).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(TAG_RECONCILE)
            .build()

        workManager.enqueueUniqueWork(WORK_RECONCILE, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Re-runs the reconcile once the phone has a network again.
     *
     * Used when a call could not be classified offline. The call is held rather than
     * discarded, and this is what comes back for it — WorkManager fires the moment
     * connectivity returns, so a rep who walked into a lift loses nothing.
     */
    fun enqueueReconcileWhenOnline() {
        val request = OneTimeWorkRequestBuilder<CallReconcileWorker>()
            .setInputData(
                Data.Builder().putString(CallReconcileWorker.KEY_REASON, REASON_BACK_ONLINE).build(),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_RECONCILE)
            .build()

        workManager.enqueueUniqueWork(WORK_RECONCILE_ONLINE, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Standing safety nets.
     *
     * Everything is already triggered by the events that matter — a call ending, the app
     * opening, a reboot. These periodic runs exist for the cases those miss: an OEM that
     * dropped the broadcast, a process killed mid-scan, a directory that went stale while
     * the rep never opened the app. Both intervals come from the server config; fifteen
     * minutes is the shortest WorkManager allows, and both jobs are cheap no-ops when there
     * is nothing to do.
     */
    fun ensurePeriodicWork() {
        val config = configStore.current
        val reconcileMinutes = config.syncIntervalMinutes.toLong().coerceAtLeast(MIN_PERIOD_MINUTES)
        val heartbeatMinutes = config.heartbeatIntervalMinutes.toLong().coerceAtLeast(MIN_PERIOD_MINUTES)

        val reconcile = PeriodicWorkRequestBuilder<CallReconcileWorker>(reconcileMinutes, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder().putString(CallReconcileWorker.KEY_REASON, REASON_PERIODIC).build(),
            )
            .addTag(TAG_RECONCILE)
            .build()

        // UPDATE, not KEEP or REPLACE. REPLACE on every app start would reset the period and
        // mean it effectively never fires for someone who opens the app often; KEEP would
        // never let a changed interval from the server take effect. UPDATE keeps the
        // existing schedule when nothing changed and re-times it from the last run when
        // the period did.
        workManager.enqueueUniquePeriodicWork(
            WORK_RECONCILE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            reconcile,
        )

        val heartbeat = PeriodicWorkRequestBuilder<SyncHealthWorker>(heartbeatMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .addTag(TAG_HEALTH)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_HEALTH_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            heartbeat,
        )
    }

    /** Stops all background work. Used on sign-out. */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_RECONCILE)
        workManager.cancelAllWorkByTag(TAG_HEALTH)
    }

    /** Sends one queued call to the server: create the record, then attach the audio. */
    fun enqueueSync(pendingCallId: String) {
        workManager.enqueueUniqueWork(
            syncWorkName(pendingCallId),
            // KEEP: if this call is already scheduled or running, leave it alone rather than
            // restarting its backoff from zero.
            ExistingWorkPolicy.KEEP,
            // Ten seconds, WorkManager's floor. A call that the server pushed back on during a
            // burst should land moments later, not a minute later when the rep has already
            // looked at the CRM and concluded it was lost.
            syncRequest(pendingCallId, initialBackoffSeconds = 10),
        )
    }

    fun enqueueSyncAll(pendingCallIds: List<String>) = pendingCallIds.forEach(::enqueueSync)

    /** Rep-initiated retry — restart the clock rather than waiting out a backoff. */
    fun forceSync(pendingCallId: String) {
        workManager.enqueueUniqueWork(
            syncWorkName(pendingCallId),
            ExistingWorkPolicy.REPLACE,
            syncRequest(pendingCallId, initialBackoffSeconds = 15),
        )
    }

    fun cancelSync(pendingCallId: String) = workManager.cancelUniqueWork(syncWorkName(pendingCallId))

    private fun syncRequest(pendingCallId: String, initialBackoffSeconds: Long) =
        OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setInputData(Data.Builder().putString(CallSyncWorker.KEY_PENDING_ID, pendingCallId).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, initialBackoffSeconds, TimeUnit.SECONDS)
            .addTag(TAG_SYNC)
            .build()

    private fun syncWorkName(id: String) = "$WORK_SYNC_PREFIX$id"

    companion object {
        const val WORK_RECONCILE = "arthax_reconcile"
        const val WORK_RECONCILE_PERIODIC = "arthax_reconcile_periodic"
        const val WORK_RECONCILE_ONLINE = "arthax_reconcile_online"
        const val WORK_HEALTH_PERIODIC = "arthax_sync_health"
        const val WORK_SYNC_PREFIX = "arthax_sync_"

        const val TAG_SYNC = "sync"
        const val TAG_RECONCILE = "reconcile"
        const val TAG_HEALTH = "health"

        /** WorkManager's floor for periodic work. */
        const val MIN_PERIOD_MINUTES = 15L

        const val REASON_CALL_ENDED = "a call ended"
        const val REASON_APP_OPENED = "the app was opened"
        const val REASON_BOOT = "the phone restarted"
        const val REASON_PERIODIC = "scheduled check"
        const val REASON_SIGN_IN = "signing in"
        const val REASON_BACK_ONLINE = "back online"
        const val REASON_CALL_LOG_CHANGED = "the call log changed"
        const val REASON_RESUMED = "the app came to the foreground"
    }
}

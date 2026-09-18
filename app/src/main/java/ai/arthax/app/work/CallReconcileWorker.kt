package ai.arthax.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ai.arthax.app.call.CallLogReconciler
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.data.local.store.LeadLookupCache
import ai.arthax.app.data.local.store.RemoteConfigStore
import ai.arthax.app.data.local.store.SeenCallStore
import ai.arthax.app.data.local.store.SyncHealthStore
import ai.arthax.app.data.local.store.UnmatchedCallStore
import ai.arthax.app.notification.AppNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Matches recent calls against the lead directory and queues the ones that count.
 *
 * Runs after any call ends, on app start, after a reboot, and periodically as a safety net.
 * It is idempotent, so running it more often than strictly necessary costs nothing but a
 * cheap query, and is the reason a frozen or killed process cannot lose a call permanently.
 */
@HiltWorker
class CallReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: CallLogReconciler,
    private val syncRepository: CallSyncRepository,
    private val lookupCache: LeadLookupCache,
    private val seenCalls: SeenCallStore,
    private val unmatched: UnmatchedCallStore,
    private val configStore: RemoteConfigStore,
    private val health: SyncHealthStore,
    private val notifications: AppNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifications.workingNotification(
            title = "Checking your recent calls",
            text = "Matching calls to your leads",
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AppNotifications.HARVEST_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AppNotifications.HARVEST_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        // Every store is file-backed and this may be a brand new process.
        lookupCache.load()
        seenCalls.load()
        unmatched.load()
        configStore.load()
        health.load()
        syncRepository.load()

        val reason = inputData.getString(KEY_REASON) ?: "scheduled check"

        return try {
            reconciler.reconcile(reason)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A safety net that dies quietly is no safety net. Retry rather than leaving the
            // watermark parked with unprocessed calls sitting above it.
            Result.retry()
        }
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}

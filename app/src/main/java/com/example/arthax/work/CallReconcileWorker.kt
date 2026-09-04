package com.example.arthax.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.arthax.call.CallLogReconciler
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.data.local.store.LeadLookupCache
import com.example.arthax.notification.AppNotifications
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
        // Both stores are file-backed and this may be a brand new process.
        lookupCache.load()
        syncRepository.load()

        val reason = inputData.getString(KEY_REASON) ?: "scheduled check"
        reconciler.reconcile(reason)
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}

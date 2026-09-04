package com.example.arthax.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.notification.AppNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Delivers one queued call to the server: create the call record, then attach the audio.
 *
 * Constrained to NetworkType.CONNECTED, so a rep in a dead zone simply has the work sit
 * until signal returns. Together with the local copy made at capture time, that is the
 * whole offline-resilience story.
 *
 * Result mapping is deliberate. `retry()` only for failures the repository classified as
 * transient; a permanently rejected call returns `success()` because the queue row is
 * already marked FAILED with the server's reason on the Activity screen, and returning
 * `failure()` would only leave a dead work entry carrying no extra information.
 */
@HiltWorker
class CallSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: CallSyncRepository,
    private val notifications: AppNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifications.workingNotification(
            title = "Saving call to Arthax",
            text = "Sending the call and its recording to the server",
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AppNotifications.SYNC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AppNotifications.SYNC_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val pendingId = inputData.getString(KEY_PENDING_ID) ?: return Result.failure()

        // The queue lives in a file, and this worker can run in a process that has not
        // read it yet.
        syncRepository.load()

        val call = syncRepository.queue.value.firstOrNull { it.id == pendingId }
            ?: return Result.success()

        return when (val outcome = syncRepository.sync(pendingId)) {
            CallSyncRepository.Outcome.Done -> Result.success()

            is CallSyncRepository.Outcome.Retry -> Result.retry()

            is CallSyncRepository.Outcome.GaveUp -> {
                notifications.notifyUploadFailed(call.leadName, outcome.reason)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_PENDING_ID = "pending_call_id"
    }
}

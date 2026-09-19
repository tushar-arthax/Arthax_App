package ai.arthax.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.local.store.SyncHealthStore
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.notification.AppNotifications
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
    private val health: SyncHealthStore,
    private val tokenStore: SecureTokenStore,
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
        // read it yet. The health store carries the upload hold a 402 may have set.
        syncRepository.load()
        health.load()

        // Already delivered and pruned, or discarded by the rep. Nothing left to do.
        if (syncRepository.queue.value.none { it.id == pendingId }) return Result.success()

        // Nobody signed in: every request would go out without a token and come back 401.
        // The queue row is untouched and is re-armed by the first reconcile after the rep
        // signs in again (unique work with KEEP, so a finished entry does not block it).
        // failure(), not retry(): retrying would only poll the server with no token until
        // the backoff hit its five hour ceiling.
        if (!tokenStore.isLoggedIn) return Result.failure()

        val outcome = try {
            syncRepository.sync(pendingId)
        } catch (e: CancellationException) {
            // The system stopped this worker mid-request. Nothing has gone wrong with the
            // call: WorkManager reschedules stopped work, and the queue row is untouched.
            throw e
        } catch (t: Throwable) {
            // Anything unexpected is worth another go rather than a dead work entry - the
            // queue row is still PENDING, so retrying is the only thing that can deliver it.
            return Result.retry()
        }

        return when (outcome) {
            CallSyncRepository.Outcome.Done -> Result.success()

            is CallSyncRepository.Outcome.Retry -> Result.retry()

            // The repository raises the notification, so it appears exactly once whether the
            // call gave up here or on the immediate send from the watcher.
            is CallSyncRepository.Outcome.GaveUp -> Result.success()
        }
    }

    companion object {
        const val KEY_PENDING_ID = "pending_call_id"
    }
}

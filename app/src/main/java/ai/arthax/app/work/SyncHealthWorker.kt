package ai.arthax.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.arthax.app.data.local.store.RemoteConfigStore
import ai.arthax.app.data.local.store.SyncHealthStore
import ai.arthax.app.data.local.store.UnmatchedCallStore
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.data.repository.SyncHealthReporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * The periodic heartbeat.
 *
 * Same survival story as the reconcile worker: the foreground service and the app process
 * are both killed on OEM phones, WorkManager is not. A failed send never fails the worker —
 * that would back it off and eventually stop the period, and a phone that cannot reach the
 * server is exactly the one whose next attempt matters.
 */
@HiltWorker
class SyncHealthWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reporter: SyncHealthReporter,
    private val health: SyncHealthStore,
    private val configStore: RemoteConfigStore,
    private val unmatched: UnmatchedCallStore,
    private val syncRepository: CallSyncRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            // File-backed stores, and this may be a brand new process.
            health.load()
            configStore.load()
            unmatched.load()
            syncRepository.load()
            reporter.sendNow(WorkScheduler.REASON_PERIODIC)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Reported inside; nothing to add here.
        }
        return Result.success()
    }
}

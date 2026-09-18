package ai.arthax.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ai.arthax.app.call.CallMonitorService
import ai.arthax.app.call.CallTracker
import ai.arthax.app.core.ApiConfig
import ai.arthax.app.core.CrashRecorder
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.local.store.LeadLookupCache
import ai.arthax.app.data.local.store.RemoteConfigStore
import ai.arthax.app.data.local.store.SeenCallStore
import ai.arthax.app.data.local.store.SyncHealthStore
import ai.arthax.app.data.local.store.UnmatchedCallStore
import ai.arthax.app.data.repository.CallSyncRepository
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.di.ApplicationScope
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.notification.AppNotifications
import ai.arthax.app.recording.RecordingStorage
import ai.arthax.app.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ArthaxApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var tokenStore: SecureTokenStore

    @Inject lateinit var syncRepository: CallSyncRepository

    @Inject lateinit var lookupCache: LeadLookupCache

    @Inject lateinit var seenCalls: SeenCallStore

    @Inject lateinit var unmatchedCalls: UnmatchedCallStore

    @Inject lateinit var configStore: RemoteConfigStore

    @Inject lateinit var health: SyncHealthStore

    @Inject lateinit var recordingStorage: RecordingStorage

    @Inject lateinit var workScheduler: WorkScheduler

    @Inject lateinit var callTracker: CallTracker

    @Inject lateinit var notifications: AppNotifications

    @Inject lateinit var logger: EventLogger

    @Inject lateinit var crashRecorder: CrashRecorder

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Installed first, so a crash anywhere after this point is recorded rather than
        // disappearing into the OEM's "report to Xiaomi" dialog.
        crashRecorder.install()

        notifications.ensureChannels()
        tokenStore.refreshAuthState()

        startUp()
    }

    /**
     * Loads the file-backed stores and re-arms anything left in flight.
     *
     * WorkManager normally survives process death on its own, but not every path is
     * covered: the OS drops scheduled jobs on a force-stop or an OEM "clear all", and a
     * queued call with no work request behind it would otherwise never be sent.
     * Re-enqueuing is cheap and idempotent (unique work, KEEP policy), so it just runs
     * on every start.
     */
    private fun startUp() = appScope.launch {
        runCatching {
            // The config first: the intervals the periodic work below is armed with come
            // from it, and the health store before the logger so the first error counts.
            configStore.load()
            health.load()
            logger.load()
            syncRepository.load()
            lookupCache.load()
            seenCalls.load()
            unmatchedCalls.load()

            logger.info(
                LogStage.SETUP,
                "Arthax ${BuildConfig.VERSION_NAME} started",
                detail = "Backend: ${ApiConfig.BASE_URL} (${ApiConfig.environmentLabel})",
            )

            // Surfaced on the Activity screen, where a rep can actually see it and read it
            // out, instead of being lost with the process that died.
            crashRecorder.consumePreviousCrash()?.let { report ->
                logger.error(
                    LogStage.SETUP,
                    "Arthax closed unexpectedly last time",
                    detail = report,
                )
            }

            syncRepository.pruneDelivered()

            // Scoped to this rep: the server attributes a call to whoever's token
            // uploads it, so another rep's queue must not ride along.
            val outstanding = syncRepository.outstandingFor(tokenStore.session?.userId)
            if (outstanding.isNotEmpty()) {
                logger.info(LogStage.SYNC, "Resuming ${outstanding.size} unsent call(s)")
                workScheduler.enqueueSyncAll(outstanding.map { it.id })
            }

            // Delete stray files left by a crash mid-copy, or by rows since removed.
            recordingStorage.cleanOrphans(
                syncRepository.queue.value.mapNotNull { it.localFilePath }.toSet(),
            )

            if (tokenStore.isLoggedIn) {
                // The watcher is the detection mechanism; everything else is a safety net.
                CallMonitorService.start(this@ArthaxApplication, "app started")
                // Standing safety nets, in case a broadcast was missed or the process was
                // killed mid-scan. Both are no-ops when there is nothing to do.
                workScheduler.ensurePeriodicWork()

            }
        }.onFailure {
            Log.e("ArthaxApplication", "Startup recovery failed", it)
        }
    }

}

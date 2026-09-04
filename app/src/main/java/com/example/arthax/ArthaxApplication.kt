package com.example.arthax

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.arthax.call.CallMonitorService
import com.example.arthax.call.CallTracker
import com.example.arthax.core.ApiConfig
import com.example.arthax.core.CrashRecorder
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.local.store.LeadLookupCache
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.di.ApplicationScope
import com.example.arthax.domain.model.LogStage
import com.example.arthax.notification.AppNotifications
import com.example.arthax.recording.RecordingStorage
import com.example.arthax.work.WorkScheduler
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
            logger.load()
            syncRepository.load()
            lookupCache.load()

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

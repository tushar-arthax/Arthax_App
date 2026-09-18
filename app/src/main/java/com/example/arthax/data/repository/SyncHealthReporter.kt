package com.example.arthax.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.arthax.BuildConfig
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.local.store.PendingCallStore
import com.example.arthax.data.local.store.SyncHealthStore
import com.example.arthax.data.local.store.UnmatchedCallStore
import com.example.arthax.data.remote.api.ApiResult
import com.example.arthax.data.remote.api.ArthaxApi
import com.example.arthax.data.remote.api.safeApiCall
import com.example.arthax.data.remote.dto.ApiTime
import com.example.arthax.data.remote.dto.MobileSyncHealthRequest
import com.example.arthax.domain.model.LogStage
import com.example.arthax.recording.RecordingFinder
import com.example.arthax.ui.common.DeviceSetup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hourly heartbeat — POST /api/mobile/sync-health.
 *
 * A phone that stops syncing is silent: the CRM just sees no calls, which is exactly what
 * a rep who made none looks like, and the first sign used to be a manager noticing a week
 * later. The heartbeat gives the fleet dashboard one row per device — when the watcher
 * last ran, what it saw and posted, what is stuck, which permissions are missing — so a
 * broken phone is visible the same hour, with the reason.
 *
 * Sent by [com.example.arthax.work.SyncHealthWorker] on the configured interval and by
 * [sendIfDue] when the app comes to the foreground. Never in the way: a failure is a log
 * line and the next interval. The ack carries the server's config version, so a config
 * edit reaches the fleet within one heartbeat without polling for it.
 */
@Singleton
class SyncHealthReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ArthaxApi,
    private val health: SyncHealthStore,
    private val pendingCalls: PendingCallStore,
    private val unmatched: UnmatchedCallStore,
    private val settings: AppSettings,
    private val finder: RecordingFinder,
    private val tokenStore: SecureTokenStore,
    private val remoteConfig: RemoteConfigRepository,
    private val logger: EventLogger,
) {

    private val inFlight = Mutex()

    /** App foreground: one send when the last one is older than the interval. */
    suspend fun sendIfDue(): Boolean {
        if (!tokenStore.isLoggedIn) return false
        val interval = remoteConfig.current.heartbeatIntervalMinutes * 60_000L
        if (System.currentTimeMillis() - health.current.lastHeartbeatAt < interval) return false
        return sendNow("app opened")
    }

    /** Builds, posts, and acts on the ack. Returns true on a 2xx. */
    suspend fun sendNow(reason: String): Boolean {
        if (!tokenStore.isLoggedIn) return false
        // A second sender while one is in flight would only report the same numbers.
        if (!inFlight.tryLock()) return false
        try {
            val payload = runCatching { buildPayload() }
                .getOrElse { error ->
                    logger.warn(
                        LogStage.NETWORK,
                        "Could not build the sync-health report",
                        detail = "${error::class.java.simpleName}: ${error.message.orEmpty()}",
                    )
                    return false
                }

            return when (val result = safeApiCall { api.postSyncHealth(payload) }) {
                is ApiResult.Success -> {
                    health.markHeartbeatSent()
                    remoteConfig.refreshIfVersionDiffers(result.data.configVersion)
                    true
                }

                is ApiResult.Failure -> {
                    // Already traced by the interceptor; one line so the reason is next to it.
                    logger.warn(
                        LogStage.NETWORK,
                        "Sync-health report not accepted: ${result.message}",
                        detail = "Reason for sending: $reason. ${result.detail.orEmpty()}",
                    )
                    false
                }
            }
        } finally {
            inFlight.unlock()
        }
    }

    /**
     * Everything the dashboard row needs. Field names are the backend's; see
     * [MobileSyncHealthRequest]. Each read is guarded so one bad value cannot drop the beat.
     */
    suspend fun buildPayload(): MobileSyncHealthRequest {
        val now = System.currentTimeMillis()
        val state = health.current
        val me = tokenStore.session?.userId
        val queue = pendingCalls.items.value

        return MobileSyncHealthRequest(
            deviceId = deviceId(),
            manufacturer = Build.MANUFACTURER?.take(64),
            model = Build.MODEL?.take(64),
            androidVersion = Build.VERSION.RELEASE?.take(32),
            appVersion = BuildConfig.VERSION_NAME.take(32),
            appVersionCode = BuildConfig.VERSION_CODE,
            sentAt = ApiTime.format(now),
            observerLastTickAt = iso(state.observerLastTickAt),
            lastCallPostedAt = iso(state.lastCallPostedAt),
            lastUploadAt = iso(state.lastUploadAt),
            callsSeen24h = state.callsSeen24h(now),
            callsPosted24h = state.callsPosted24h(now),
            callsUnmatched = unmatched.count,
            uploadsPending = queue.count { it.isOutstanding && (it.ownerUserId == null || it.ownerUserId == me) },
            // This app copies a recording only once its call-log row exists, so a file can
            // never be waiting for the row; the closest thing is a pending call without one.
            filesWaitingForCallLog = 0,
            reviewQueue = queue.count { it.needsReview },
            errors24h = state.errors24h(now),
            uploadBlockedUntil = iso(state.uploadBlockedUntil.takeIf { it > now } ?: 0L),
            // A 401 signs the rep out on the spot rather than blocking; there is no timer.
            authBlockedUntil = null,
            recorderFolderFound = recorderFolderFound(),
            permissions = permissions(),
            lastError = state.lastError,
        )
    }

    private suspend fun recorderFolderFound(): Boolean {
        val tree = settings.recordingsTreeUri.first()?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { finder.hasValidGrant(Uri.parse(tree)) }.getOrDefault(false)
    }

    private suspend fun permissions(): Map<String, Boolean> {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        return mapOf(
            "call_log" to granted(Manifest.permission.READ_CALL_LOG),
            // No storage permission is held at all; the folder grant is the storage access.
            "storage" to recorderFolderFound(),
            "notifications" to runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
                .getOrDefault(false),
            "battery_unrestricted" to runCatching { !DeviceSetup.isBatteryOptimised(context) }
                .getOrDefault(false),
        )
    }

    /**
     * ANDROID_ID: stable per app signing key and user until a factory reset, which is
     * exactly the identity a "this phone" row wants. Never the IMEI.
     */
    @Suppress("HardwareIds")
    private fun deviceId(): String =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown-${Build.MODEL.orEmpty().take(32)}"

    private fun iso(millis: Long): String? = if (millis > 0) ApiTime.format(millis) else null
}

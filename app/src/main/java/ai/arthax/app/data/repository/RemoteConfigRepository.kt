package ai.arthax.app.data.repository

import ai.arthax.app.core.MobileConfig
import ai.arthax.app.data.local.store.RemoteConfigStore
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the server config current — GET /api/mobile/config.
 *
 * Asked in two situations: when a heartbeat ack reports a version other than the one
 * cached, and on a plain age check when the app comes to the foreground. Either way the
 * request carries `If-None-Match` and a 304 costs nothing but the round trip.
 *
 * Nothing here can break the app. A failure keeps whatever is cached, and no cache at all
 * means [MobileConfig.DEFAULTS] — the values the app shipped with.
 */
@Singleton
class RemoteConfigRepository @Inject constructor(
    private val api: ArthaxApi,
    private val store: RemoteConfigStore,
    private val workScheduler: WorkScheduler,
    private val logger: EventLogger,
) {

    val config: StateFlow<MobileConfig> = store.config

    val current: MobileConfig get() = store.current

    suspend fun load() = store.load()

    /** Fetches only when the server says its version differs from the cached one. */
    suspend fun refreshIfVersionDiffers(serverVersion: Int?) {
        if (serverVersion == null || serverVersion == store.current.version) return
        refresh("server reports config version $serverVersion, this phone has ${store.current.version}")
    }

    /** Fetches when the cache is older than the heartbeat interval — the foreground check. */
    suspend fun refreshIfStale() {
        val ageMillis = System.currentTimeMillis() - store.lastFetchedAt
        if (ageMillis < store.current.heartbeatIntervalMinutes * 60_000L) return
        refresh("cached config is ${ageMillis / 60_000} minute(s) old")
    }

    /**
     * One fetch. Returns true when the config on the phone is known to be current after
     * the call, whether because it was updated or because the server confirmed it.
     */
    suspend fun refresh(reason: String): Boolean {
        val etag = store.current.version.takeIf { it > 0 }?.let { "\"$it\"" }

        // The raw response is looked at before the usual mapping, because a 304 is the
        // happy path here and safeApiCall would read it as a rejection.
        val response = try {
            api.getMobileConfig(ifNoneMatch = etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                LogStage.NETWORK,
                "Could not fetch the server config — keeping the current one",
                detail = "${e::class.java.simpleName}: ${e.message.orEmpty()}. Reason: $reason",
            )
            return false
        }

        if (response.code() == NOT_MODIFIED) {
            store.markFresh()
            return true
        }

        return when (val result = safeApiCall { response }) {
            is ApiResult.Success -> {
                val previous = store.current
                store.save(result.data)
                val applied = store.current
                logger.info(
                    LogStage.SETUP,
                    "Applied server config version ${applied.version}",
                    detail = "Look-back ${applied.lookbackHours}h, sync every " +
                        "${applied.syncIntervalMinutes} min, heartbeat every " +
                        "${applied.heartbeatIntervalMinutes} min, ${applied.recorderProfiles.size} " +
                        "recorder profile(s). Reason: $reason",
                )
                // The periodic intervals may have changed; re-arming is idempotent.
                if (previous.syncIntervalMinutes != applied.syncIntervalMinutes ||
                    previous.heartbeatIntervalMinutes != applied.heartbeatIntervalMinutes
                ) {
                    workScheduler.ensurePeriodicWork()
                }
                true
            }

            is ApiResult.Failure -> {
                logger.warn(
                    LogStage.NETWORK,
                    "Server config not updated: ${result.message}",
                    detail = "Keeping version ${store.current.version}. ${result.detail.orEmpty()}",
                )
                false
            }
        }
    }

    private companion object {
        const val NOT_MODIFIED = 304
    }
}

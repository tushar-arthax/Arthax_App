package ai.arthax.app.data.repository

import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.domain.model.LogStage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who the signed-in rep is, on the server's terms.
 *
 * The calls and meetings lists are organisation-wide and are scoped to the rep by passing
 * `agent_id`, so that id is the difference between a rep seeing their own work and seeing
 * an empty screen. It is stored at sign-in from the login response's `user` object — and
 * when that object is absent, [ai.arthax.app.data.repository.AuthRepository] stores an
 * empty string for it rather than failing the sign-in.
 *
 * An empty id is not a small problem. Both lists treated it as "nothing to show" and
 * returned an empty page, so a rep in that state saw "No calls yet" against a CRM holding
 * hundreds of them, with nothing anywhere saying why, and no amount of pulling to refresh
 * could change it. Signing out and back in was the only cure, and nothing told them that.
 *
 * So the id is repaired here instead: `GET /api/users/me` answers with the same user object
 * the login response should have carried, and the answer is written back to the session.
 * That fixes the installs already in this state without anybody having to sign in again,
 * and it fixes it for the call-matching and sync-health paths that read the same field.
 */
@Singleton
class AgentIdentity @Inject constructor(
    private val api: ArthaxApi,
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) {

    /** One repair at a time: both lists load together and would otherwise both ask. */
    private val lock = Mutex()

    /**
     * The rep's id, repaired from the server if the session is missing it.
     *
     * Null only when the session has no id *and* the server could not be asked — a caller
     * that gets null should report a failure rather than render an empty list, because
     * "we could not find out who you are" and "you have no calls" look identical on screen
     * and are not remotely the same thing.
     */
    suspend fun agentId(): String? {
        tokenStore.session?.userId?.takeIf { it.isNotBlank() }?.let { return it }

        // Nothing to ask with, and nothing to repair: the rep is signed out.
        if (!tokenStore.isLoggedIn) return null

        return lock.withLock {
            // Another caller may have repaired it while this one waited for the lock.
            tokenStore.session?.userId?.takeIf { it.isNotBlank() }?.let { return@withLock it }

            when (val result = safeApiCall { api.me() }) {
                is ApiResult.Success -> {
                    val id = result.data.id.trim().takeIf { it.isNotEmpty() }

                    if (id == null) {
                        logger.error(
                            LogStage.AUTH,
                            "The server did not say who this session belongs to",
                            detail = "GET /api/users/me answered without an id. Calls and " +
                                "meetings cannot be listed until it does.",
                        )
                    } else {
                        tokenStore.updateUserId(id)
                        logger.info(
                            LogStage.AUTH,
                            "Recovered this device's rep id from the server",
                            detail = "It was missing from the stored session, which is why " +
                                "calls and meetings were coming back empty.",
                        )
                    }

                    id
                }

                is ApiResult.Failure -> {
                    logger.warn(
                        LogStage.AUTH,
                        "Could not confirm who this session belongs to: ${result.message}",
                        detail = result.detail,
                    )
                    null
                }
            }
        }
    }
}

package ai.arthax.app.data.remote.api

import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.LogStage
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Attaches the bearer token, traces every call into the activity log, and handles session
 * expiry.
 *
 * The subtle part is the 401 handling. This backend returns 401 for *both* "your token has
 * expired" and "that OTP was wrong" — so clearing the session on any 401 would log the rep
 * out the instant they fat-finger their code, and the login screen would appear to reset
 * itself for no reason. Only 401s from authenticated endpoints end the session — and only
 * once a second, independent request has confirmed the token really is dead, because one
 * stray 401 from a background upload used to sign the rep out and strand the whole queue.
 */
class AuthInterceptor(
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) : Interceptor {

    /**
     * 401s in a row that the probe could not settle either way.
     *
     * A single inconclusive 401 is kept, because the probe failing usually means the network
     * did rather than the session — but a run of them means the app is talking to a server
     * that will not accept this token and no amount of waiting will fix it. Reset by any
     * successful request, so this only ever counts a genuine back-to-back run.
     */
    private val unresolvedRefusals = AtomicInteger(0)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val isAuthEndpoint = AUTH_PATHS.any { path.endsWith(it) }

        val outgoing = if (isAuthEndpoint) {
            request
        } else {
            tokenStore.token
                ?.takeIf { it.isNotBlank() }
                ?.let { request.newBuilder().header("Authorization", "Bearer $it").build() }
                ?: request
        }

        val label = "${request.method} $path"
        val startedAt = System.currentTimeMillis()

        val response = try {
            chain.proceed(outgoing)
        } catch (e: IOException) {
            // Transport-level failure never reaches safeApiCall's HTTP branch, so record it
            // here or it would be invisible in the on-device log.
            logger.warn(
                LogStage.NETWORK,
                "$label failed to reach the server",
                detail = "${e::class.java.simpleName}: ${e.message.orEmpty()}",
            )
            throw e
        }

        val elapsed = System.currentTimeMillis() - startedAt

        // Any request the server accepted proves the session is alive, so a run of
        // refusals that was building up is over.
        if (response.isSuccessful && !isAuthEndpoint) unresolvedRefusals.set(0)

        // 304 is the config endpoint saying "unchanged" — a success, not a failure to trace.
        if (response.isSuccessful || response.code == NOT_MODIFIED) {
            logger.info(LogStage.NETWORK, "$label → ${response.code} (${elapsed}ms)")
        } else {
            logger.warn(
                LogStage.NETWORK,
                "$label → ${response.code} (${elapsed}ms)",
                // peekBody rather than body().string(): reading the body here would consume
                // it and Retrofit would then see an empty response.
                detail = runCatching { response.peekBody(ERROR_PEEK_BYTES).string() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() },
            )
        }

        if (response.code == 401 && !isAuthEndpoint && tokenStore.isLoggedIn) {
            // One 401 is not proof. Signing out costs the rep their session and parks every
            // queued call until they sign in again, so before doing that the token is put
            // to the server once more, on its own, against the profile endpoint. Only a
            // second refusal ends the session; a probe that passes means the first 401 was
            // a fluke and the original request is simply sent again.
            val verdict = sessionVerdict(probeSession(chain))
            val refusals = if (verdict == SessionVerdict.UNKNOWN) {
                unresolvedRefusals.incrementAndGet()
            } else {
                unresolvedRefusals.getAndSet(0)
            }

            when {
                shouldSignOut(verdict, refusals) -> {
                    unresolvedRefusals.set(0)
                    logger.warn(
                        LogStage.AUTH,
                        if (verdict == SessionVerdict.INVALID) {
                            "Session rejected by the server, signing out"
                        } else {
                            "Refused $refusals times in a row, signing out"
                        },
                        detail = if (verdict == SessionVerdict.INVALID) {
                            null
                        } else {
                            "The session could not be re-checked on any attempt. Queued calls " +
                                "are kept and are sent after the next sign-in."
                        },
                    )
                    // Clearing the token is safe for work in flight: a queued call treats a
                    // 401 as temporary and waits, and the first reconcile after the next
                    // sign-in re-arms it. See CallSyncRepository.handleFailure.
                    tokenStore.clear()
                }

                verdict == SessionVerdict.VALID -> {
                    logger.warn(
                        LogStage.AUTH,
                        "$label was refused but the session is still valid — sending it again",
                    )
                    response.close()
                    return chain.proceed(outgoing)
                }

                else -> logger.warn(
                    LogStage.AUTH,
                    "$label was refused and the session could not be re-checked — keeping it",
                    detail = "Attempt $refusals of $MAX_UNRESOLVED_REFUSALS. " +
                        "The request is treated as a temporary failure.",
                )
            }
        }

        return response
    }

    /**
     * Asks the server whether the stored token still stands. The HTTP code, or null when
     * the server could not be reached at all. Never throws: this runs inside another
     * request's failure path, and a probe that dies must not take that request with it.
     */
    private fun probeSession(chain: Interceptor.Chain): Int? {
        val token = tokenStore.token?.takeIf { it.isNotBlank() } ?: return 401
        val probe = Request.Builder()
            .url(ApiConfig.BASE_URL + ApiConfig.Paths.ME)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return runCatching { chain.proceed(probe).use { it.code } }.getOrNull()
    }

    enum class SessionVerdict { VALID, INVALID, UNKNOWN }

    companion object {
        private val AUTH_PATHS = listOf(ApiConfig.Paths.SEND_OTP, ApiConfig.Paths.LOGIN)

        /**
         * Inconclusive 401s in a row before the session is given up on.
         *
         * Three rather than one, because the probe is itself a request and a flaky network
         * fails both it and the call that triggered it. Three consecutive failures with no
         * successful request in between is no longer a network story.
         */
        const val MAX_UNRESOLVED_REFUSALS = 3
        private const val ERROR_PEEK_BYTES = 2_048L
        private const val NOT_MODIFIED = 304

        /**
         * What a probe of the profile endpoint says about the session. Pure, so the rule is
         * pinned by a unit test: only the server's own refusal ends a session; a 2xx clears
         * the original 401 as transient; anything else — 5xx, no connection — is
         * inconclusive and the session is kept.
         */
        /**
         * Whether a refusal should end the session. Pure, so the rule is unit-tested.
         *
         * The server's own refusal ends it at once. An inconclusive probe ends it only
         * after [MAX_UNRESOLVED_REFUSALS] in a row, and a proven-valid session never does.
         */
        fun shouldSignOut(verdict: SessionVerdict, consecutiveUnresolved: Int): Boolean =
            when (verdict) {
                SessionVerdict.INVALID -> true
                SessionVerdict.VALID -> false
                SessionVerdict.UNKNOWN -> consecutiveUnresolved >= MAX_UNRESOLVED_REFUSALS
            }

        fun sessionVerdict(probeCode: Int?): SessionVerdict = when {
            probeCode == null -> SessionVerdict.UNKNOWN
            probeCode == 401 || probeCode == 403 -> SessionVerdict.INVALID
            probeCode in 200..299 -> SessionVerdict.VALID
            else -> SessionVerdict.UNKNOWN
        }
    }
}

package com.example.arthax.data.remote.api

import com.example.arthax.core.ApiConfig
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.LogStage
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Attaches the bearer token, traces every call into the activity log, and handles session
 * expiry.
 *
 * The subtle part is the 401 handling. This backend returns 401 for *both* "your token has
 * expired" and "that OTP was wrong" — so clearing the session on any 401 would log the rep
 * out the instant they fat-finger their code, and the login screen would appear to reset
 * itself for no reason. Only 401s from authenticated endpoints end the session.
 */
class AuthInterceptor(
    private val tokenStore: SecureTokenStore,
    private val logger: EventLogger,
) : Interceptor {

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
            logger.warn(LogStage.AUTH, "Session rejected by the server, signing out")
            tokenStore.clear()
        }

        return response
    }

    private companion object {
        val AUTH_PATHS = listOf(ApiConfig.Paths.SEND_OTP, ApiConfig.Paths.LOGIN)
        const val ERROR_PEEK_BYTES = 2_048L
        const val NOT_MODIFIED = 304
    }
}

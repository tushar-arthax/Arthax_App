package com.example.arthax.data.remote.api

import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Typed outcome for every network call.
 *
 * [Failure.retryable] is the field that matters most: the upload worker uses it to choose
 * between "come back when the network is better" and "stop, this will never succeed".
 * Get it wrong and you either lose recordings or retry a rejected file until the battery dies.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    sealed class Failure(
        open val message: String,
        open val detail: String? = null,
        val retryable: Boolean,
    ) : ApiResult<Nothing> {

        /** No connection, DNS failure, timeout. Always worth another go. */
        data class Network(
            override val message: String = "No internet connection",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = true)

        /** Token rejected or missing. The session has to be re-established. */
        data class Unauthorized(
            override val message: String = "Session expired, please sign in again",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = false)

        /** 4xx other than 401, including FastAPI's 422 validation errors. */
        data class Rejected(
            val code: Int,
            override val message: String,
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = false)

        /** 5xx that looks transient — the server may recover. */
        data class Server(
            val code: Int,
            override val message: String = "Server error, will retry",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = true)

        /**
         * The server understood the request and permanently refused to process the
         * payload. Distinct from [Server] because retrying is pointless.
         */
        data class Unprocessable(
            val code: Int,
            override val message: String,
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = false)

        /** Malformed body, unexpected null, adapter blew up. */
        data class Unexpected(
            override val message: String = "Unexpected response from server",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = false)
    }
}

/**
 * Wraps a Retrofit call, mapping transport and HTTP failures onto [ApiResult.Failure].
 * Never throws; callers always get something they can render.
 */
suspend fun <T : Any> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val response = block()
    val body = response.body()
    val code = response.code()

    when {
        response.isSuccessful && body != null -> ApiResult.Success(body)

        // 204 and friends: successful, no content. Unit-returning calls land here.
        response.isSuccessful -> {
            @Suppress("UNCHECKED_CAST")
            (Unit as? T)?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure.Unexpected(
                    message = "Server returned an empty response",
                    detail = "HTTP $code with no body",
                )
        }

        code == 401 || code == 403 -> {
            val detail = response.errorDetail()
            ApiResult.Failure.Unauthorized(
                message = detail ?: "Session expired, please sign in again",
                detail = detail,
            )
        }

        code in 500..599 -> {
            val detail = response.errorDetail()
            // A 500 is normally transient, but this backend also uses it when FFmpeg
            // refuses to decode an uploaded recording. That will fail identically every
            // time, so it must not go back on the retry queue.
            if (detail != null && PERMANENT_5XX_MARKERS.any { detail.contains(it, ignoreCase = true) }) {
                ApiResult.Failure.Unprocessable(
                    code = code,
                    message = "Server could not process the recording",
                    detail = detail,
                )
            } else {
                ApiResult.Failure.Server(code = code, detail = detail)
            }
        }

        else -> {
            val detail = response.errorDetail()
            ApiResult.Failure.Rejected(
                code = code,
                message = detail ?: "Request rejected (HTTP $code)",
                detail = detail,
            )
        }
    }
} catch (e: UnknownHostException) {
    ApiResult.Failure.Network("No internet connection", e.describe())
} catch (e: SocketTimeoutException) {
    ApiResult.Failure.Network("Connection timed out", e.describe())
} catch (e: IOException) {
    ApiResult.Failure.Network("Network unavailable", e.describe())
} catch (e: Exception) {
    ApiResult.Failure.Unexpected("Something went wrong", e.describe())
}

/** Markers that turn a 5xx into a permanent rejection rather than a retry. */
private val PERMANENT_5XX_MARKERS = listOf("ffmpeg", "upload failed", "invalid data found")

private fun Throwable.describe(): String = message ?: this::class.java.simpleName

/**
 * Pulls a human-readable message out of a FastAPI error body.
 *
 * `detail` is polymorphic and both shapes occur in practice:
 *   {"detail":"Invalid OTP"}
 *   {"detail":[{"loc":["body","phone"],"msg":"Field required",...}]}
 * Parsed by hand rather than with a DTO precisely because of that.
 */
internal fun Response<*>.errorDetail(): String? = runCatching {
    val raw = errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null

    val json = runCatching { JSONObject(raw) }.getOrNull()
        ?: return raw.take(MAX_DETAIL_LENGTH)

    when (val detail = json.opt("detail")) {
        null -> json.optString("message").takeIf { it.isNotBlank() } ?: raw.take(MAX_DETAIL_LENGTH)

        is String -> detail.take(MAX_DETAIL_LENGTH)

        is JSONArray -> (0 until detail.length())
            .mapNotNull { i ->
                val item = detail.optJSONObject(i) ?: return@mapNotNull null
                val field = item.optJSONArray("loc")
                    ?.let { loc -> (0 until loc.length()).joinToString(".") { loc.optString(it) } }
                    ?.substringAfter("body.", "")
                    ?.takeIf { it.isNotBlank() }
                val msg = item.optString("msg").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (field != null) "$field: $msg" else msg
            }
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
            ?.take(MAX_DETAIL_LENGTH)

        else -> detail.toString().take(MAX_DETAIL_LENGTH)
    }
}.getOrNull()

/** Long enough to diagnose, short enough to keep in a log line. */
private const val MAX_DETAIL_LENGTH = 600

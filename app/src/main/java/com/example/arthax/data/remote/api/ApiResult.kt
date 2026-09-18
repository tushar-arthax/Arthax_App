package com.example.arthax.data.remote.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

        /** 4xx not handled more specifically below — a wrong path, a bad request. */
        data class Rejected(
            val code: Int,
            override val message: String,
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = false)

        /**
         * The server asked us to slow down or timed the request out — 429, 408, 425.
         *
         * Separate from [Rejected] because the difference decides whether a call survives.
         * These used to fall into the same bucket as a malformed payload, so the very first
         * one marked the call permanently failed and nothing ever sent it again.
         *
         * It matched the symptom exactly. A connected call spends up to a minute hunting for
         * its recording before posting, so those requests are naturally spaced out and always
         * got through. Unanswered calls have nothing to hunt for: a run of redials, or a
         * backlog released the moment the phone came back online, arrives as a burst — and
         * whichever ones the server pushed back on were thrown away rather than retried.
         *
         * @param retryAfterSeconds the server's own `Retry-After`, when it sent one.
         */
        data class Throttled(
            val code: Int,
            val retryAfterSeconds: Int? = null,
            override val message: String = "Server asked us to slow down",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = true)

        /** 5xx that looks transient — the server may recover. */
        data class Server(
            val code: Int,
            override val message: String = "Server error, will retry",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = true)

        /**
         * 402: the organisation is out of credits. Nothing is wrong with the call or the
         * file, and nothing on the phone can fix it — so the upload is neither dropped nor
         * hammered. It is parked for a while and tried again once someone has topped up.
         *
         * Only uploads are paused. Creating the call record costs nothing, and a call that
         * is in the CRM without its audio is worth far more than one that is nowhere.
         */
        data class Blocked(
            val code: Int,
            val retryAfterSeconds: Int? = null,
            override val message: String = "Uploads are paused until the organisation has credits",
            override val detail: String? = null,
        ) : Failure(message, detail, retryable = true)

        /**
         * The server understood the request and permanently refused to process the
         * payload: a 422 validation error, or the 5xx this backend returns when FFmpeg
         * cannot decode a file. Distinct from [Server] because retrying is pointless, and
         * distinct from [Rejected] because a human should look at it — the call goes to the
         * review queue with the server's own words rather than being marked failed.
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
 *
 * Everything except cancellation is turned into a value the caller can render. Cancellation
 * is rethrown, and that exception is load-bearing: a cancelled coroutine is not a failed
 * request, it is a request nobody is waiting for any more.
 *
 * Treating it as a failure caused two visible bugs. On the leads screen every pull to
 * refresh, every search keystroke and every scroll that overtook an in-flight page cancels
 * the previous job - and the cancelled job then reported "Something went wrong /
 * StandaloneCoroutine was cancelled" with a Try again button, over and over. In the sync
 * worker the same exception was classified as a permanent, non-retryable error, so a call
 * whose worker the system merely stopped was marked failed and never reached the CRM.
 *
 * It is also why the errors only appeared with a working connection: offline, the request
 * fails immediately with a real network error before anything gets the chance to cancel it.
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

        // Checked before the general 4xx branch below, which treats a rejection as final.
        // "Too many requests" and "you took too long" are the opposite of final.
        code == 429 || code == 408 || code == 425 -> {
            val detail = response.errorDetail()
            ApiResult.Failure.Throttled(
                code = code,
                retryAfterSeconds = response.retryAfterSeconds(),
                message = if (code == 429) {
                    "Server asked us to slow down, will retry"
                } else {
                    "Request timed out, will retry"
                },
                detail = detail,
            )
        }

        code == 402 -> {
            val detail = response.errorDetail()
            ApiResult.Failure.Blocked(
                code = code,
                retryAfterSeconds = response.retryAfterSeconds(),
                detail = detail,
            )
        }

        code == 422 -> {
            val detail = response.errorDetail()
            ApiResult.Failure.Unprocessable(
                code = code,
                message = detail ?: "Server could not accept this request",
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
} catch (e: CancellationException) {
    // Never swallowed, never mapped to a Failure - see the note above.
    throw e
} catch (e: UnknownHostException) {
    ApiResult.Failure.Network("No internet connection", e.describe())
} catch (e: SocketTimeoutException) {
    ApiResult.Failure.Network("Connection timed out", e.describe())
} catch (e: IOException) {
    // OkHttp reports a cancelled call as IOException("Canceled") when the cancel lands
    // between the request going out and the callback returning, so the coroutine has to be
    // consulted rather than the exception type alone. Rethrows if we were cancelled.
    currentCoroutineContext().ensureActive()
    ApiResult.Failure.Network("Network unavailable", e.describe())
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    ApiResult.Failure.Unexpected("Something went wrong", e.describe())
}

/**
 * The server's own `Retry-After`, in seconds, when it sent one we can understand.
 *
 * Only the delta-seconds form is read. The HTTP-date form is rare in practice and a wrong
 * guess would be worse than the backoff we already apply.
 */
private fun Response<*>.retryAfterSeconds(): Int? =
    headers()["Retry-After"]?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }

/** An hour. Beyond that the header is nonsense and our own backoff is the better guide. */
private const val MAX_RETRY_AFTER_SECONDS = 3_600

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

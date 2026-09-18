package com.example.arthax.data

import com.example.arthax.data.remote.api.ApiResult
import com.example.arthax.data.remote.api.safeApiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The retryable flag drives the sync worker: classify a dead connection as permanent and
 * calls are lost; classify a rejected recording as transient and the phone retries until
 * the battery dies. Bodies here are copied from real staging responses.
 */
class ApiResultTest {

    private fun error(code: Int, json: String) =
        Response.error<String>(code, json.toResponseBody("application/json".toMediaTypeOrNull()))

    /**
     * Cancellation is not a failed request, it is a request nobody is waiting for. Mapping
     * it onto a Failure produced two real bugs: "Something went wrong / StandaloneCoroutine
     * was cancelled" with a Try again button on the leads screen every time a refresh or a
     * keystroke replaced an in-flight page, and calls marked permanently failed in the sync
     * worker whenever the system merely stopped it mid-upload.
     */
    @Test
    fun `cancellation is rethrown, never reported as a failure`() = runTest {
        val thrown = runCatching {
            safeApiCall<String> { throw CancellationException("StandaloneCoroutine was cancelled") }
        }.exceptionOrNull()

        assertTrue("a cancelled request is not a failed one", thrown is CancellationException)
    }

    @Test
    fun `an IO error raised after cancellation is rethrown, not reported`() = runTest {
        // OkHttp reports a cancelled call as IOException("Canceled"), so the exception type
        // alone cannot tell the two apart - the coroutine's own state has to decide.
        var reported: ApiResult<String>? = null
        var thrown: Throwable? = null

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                reported = safeApiCall {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        throw IOException("Canceled")
                    }
                }
            } catch (t: Throwable) {
                thrown = t
            }
        }

        job.cancel()
        job.join()

        assertNull("a cancelled request must never produce a Failure", reported)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `successful response with a body is a Success`() = runTest {
        val result = safeApiCall { Response.success("payload") }

        assertTrue(result is ApiResult.Success)
        assertEquals("payload", (result as ApiResult.Success).data)
    }

    @Test
    fun `no connectivity is retryable`() = runTest {
        val result = safeApiCall<String> { throw UnknownHostException("staging-api.arthax.ai") }

        assertTrue(result is ApiResult.Failure.Network)
        assertTrue((result as ApiResult.Failure).retryable)
    }

    @Test
    fun `timeout and generic IO are retryable`() = runTest {
        assertTrue((safeApiCall<String> { throw SocketTimeoutException() } as ApiResult.Failure).retryable)
        assertTrue((safeApiCall<String> { throw IOException("socket closed") } as ApiResult.Failure).retryable)
    }

    /**
     * "Too many requests" is the opposite of a permanent rejection, and treating it as one
     * lost calls. Every 4xx bar 401 used to be final, so the first push-back marked a call
     * failed and nothing ever sent it again.
     *
     * It only ever bit unanswered calls, which is what made it look like a call-log problem.
     * A connected call spends up to a minute hunting for its recording before posting, so
     * those requests are spaced out on their own. Unanswered calls have nothing to hunt for:
     * a run of redials, or a backlog released when the phone comes back online, arrives as a
     * burst, and whichever ones the server pushed back on were discarded.
     */
    @Test
    fun `too many requests is retryable, not a rejection`() = runTest {
        val result = safeApiCall<String> { error(429, """{"detail":"Rate limit exceeded"}""") }

        assertTrue(result is ApiResult.Failure.Throttled)
        assertTrue("a call must never be dropped for this", (result as ApiResult.Failure).retryable)
        assertEquals("Rate limit exceeded", result.detail)
    }

    @Test
    fun `a request timeout is retryable`() = runTest {
        val result = safeApiCall<String> { error(408, """{"detail":"Request timeout"}""") }

        assertTrue(result is ApiResult.Failure.Throttled)
        assertTrue((result as ApiResult.Failure).retryable)
    }

    @Test
    fun `a validation error stays permanent`() = runTest {
        // The distinction that matters: pushing back is transient, a malformed payload is not.
        // And it is Unprocessable rather than Rejected, because the sync layer parks an
        // Unprocessable call for review with the server's words instead of marking it failed.
        val result = safeApiCall<String> { error(422, """{"detail":"lead_id: Field required"}""") }

        assertTrue(result is ApiResult.Failure.Unprocessable)
        assertFalse((result as ApiResult.Failure).retryable)
        assertEquals("lead_id: Field required", result.detail)
    }

    /**
     * Out of credits is neither the phone's fault nor permanent. It must not be a
     * rejection, which would mark the call failed, and it must be told apart from an
     * ordinary retry, because uploads should pause for a while rather than back off
     * from ten seconds — the sync layer keys on the type.
     */
    @Test
    fun `402 is blocked, retryable, and carries the server pause`() = runTest {
        val response = Response.error<String>(
            402,
            """{"detail":"Organisation has no credits"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        )
        val result = safeApiCall<String> { response }

        assertTrue("expected Blocked, got $result", result is ApiResult.Failure.Blocked)
        assertTrue((result as ApiResult.Failure).retryable)
        assertEquals("Organisation has no credits", result.detail)
    }

    @Test
    fun `402 reads Retry-After when the server sends one`() = runTest {
        val raw = okhttp3.Response.Builder()
            .code(402)
            .message("Payment Required")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .header("Retry-After", "1800")
            .request(okhttp3.Request.Builder().url("https://api.arthax.ai/api/calls/x/upload-recording").build())
            .build()
        val response = Response.error<String>("{}".toResponseBody("application/json".toMediaTypeOrNull()), raw)

        val result = safeApiCall<String> { response } as ApiResult.Failure.Blocked

        assertEquals(1800, result.retryAfterSeconds)
    }

    @Test
    fun `ordinary server error is retryable`() = runTest {
        val result = safeApiCall { error(503, """{"detail":"Service Unavailable"}""") }

        assertTrue(result is ApiResult.Failure.Server)
        assertTrue((result as ApiResult.Failure).retryable)
    }

    @Test
    fun `ffmpeg rejection is a 500 but must never be retried`() = runTest {
        // Real staging body when the uploaded file is not decodable audio.
        val body = """{"detail":"Upload failed: FFmpeg conversion failed (0:a:1?):\nmoov atom not found"}"""

        val result = safeApiCall { error(500, body) }

        assertTrue("expected Unprocessable, got $result", result is ApiResult.Failure.Unprocessable)
        assertFalse((result as ApiResult.Failure).retryable)
    }

    @Test
    fun `401 carries the server wording and is not retryable`() = runTest {
        val result = safeApiCall { error(401, """{"detail":"Invalid OTP"}""") }

        assertTrue(result is ApiResult.Failure.Unauthorized)
        assertFalse((result as ApiResult.Failure).retryable)
        assertEquals("Invalid OTP", result.message)
    }

    @Test
    fun `422 detail array is flattened into a readable message`() = runTest {
        // FastAPI validation errors arrive as an array, not a string.
        val body = """{"detail":[{"type":"missing","loc":["body","phone"],"msg":"Field required"}]}"""

        val result = safeApiCall { error(422, body) }

        assertTrue(result is ApiResult.Failure.Unprocessable)
        assertFalse((result as ApiResult.Failure).retryable)
        assertTrue("got: ${result.message}", result.message.contains("Field required"))
        assertTrue("got: ${result.message}", result.message.contains("phone"))
    }

    @Test
    fun `multiple validation errors are all reported`() = runTest {
        val body = """{"detail":[
            {"loc":["body","phone"],"msg":"Field required"},
            {"loc":["body","otp"],"msg":"Field required"}
        ]}"""

        val result = safeApiCall { error(422, body) } as ApiResult.Failure

        assertTrue(result.message.contains("phone"))
        assertTrue(result.message.contains("otp"))
    }

    @Test
    fun `a non-JSON error body still yields something readable`() = runTest {
        val result = safeApiCall { error(502, "<html>Bad Gateway</html>") } as ApiResult.Failure

        assertTrue(result.detail!!.contains("Bad Gateway"))
    }

    @Test
    fun `an unexpected throwable does not escape`() = runTest {
        val result = safeApiCall<String> { throw IllegalStateException("adapter blew up") }

        assertTrue(result is ApiResult.Failure.Unexpected)
        assertFalse((result as ApiResult.Failure).retryable)
    }
}

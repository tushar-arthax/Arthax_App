package com.example.arthax.data

import com.example.arthax.data.remote.api.ApiResult
import com.example.arthax.data.remote.api.safeApiCall
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue(result is ApiResult.Failure.Rejected)
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

package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LeadDto
import ai.arthax.app.data.repository.LeadResolver
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.net.UnknownHostException

/**
 * What each answer from `GET /api/leads/by-phone` means for the call.
 *
 * The stakes are asymmetric. Reading an outage as "not a lead" parks a real call and its
 * recording for hours; reading "not a lead" as an outage holds the watermark and stalls
 * every call behind it. So the mapping is pinned here, response by response, with bodies
 * copied from the backend.
 */
class LeadResolverByPhoneTest {

    private fun error(code: Int, json: String) =
        Response.error<LeadDto>(code, json.toResponseBody("application/json".toMediaTypeOrNull()))

    private val amol = LeadDto(id = "lead-1", name = "Amol", phone = "+917744991250", isJunk = false)

    @Test
    fun `200 is the lead`() = runTest {
        val outcome = LeadResolver.classifyByPhone(safeApiCall { Response.success(amol) })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Found)
        assertEquals("lead-1", (outcome as LeadResolver.ByPhoneOutcome.Found).lead.id)
    }

    @Test
    fun `a junk lead is still the lead`() = runTest {
        // The backend returns junk leads on purpose: a call that happened is a call that
        // happened. The flag rides along; nothing here rejects it.
        val junk = amol.copy(isJunk = true)
        val outcome = LeadResolver.classifyByPhone(safeApiCall { Response.success(junk) })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Found)
        assertTrue((outcome as LeadResolver.ByPhoneOutcome.Found).lead.isJunk)
    }

    @Test
    fun `the endpoint's own 404 wording is not a lead`() = runTest {
        val outcome = LeadResolver.classifyByPhone(
            safeApiCall { error(404, """{"detail":"Lead not found for this phone"}""") },
        )

        assertEquals(LeadResolver.ByPhoneOutcome.NotALead, outcome)
    }

    @Test
    fun `any other 404 is an older backend without the route`() = runTest {
        // FastAPI's default for an unknown path. This must fall back to the lead search,
        // not park the call as "not a lead".
        val outcome = LeadResolver.classifyByPhone(safeApiCall { error(404, """{"detail":"Not Found"}""") })

        assertEquals(LeadResolver.ByPhoneOutcome.EndpointMissing, outcome)
    }

    @Test
    fun `a 400 for a number too short to be anyone's is not a lead`() = runTest {
        // The server refuses fewer than seven digits. Asking again later cannot change
        // that, and treating it as an outage would hold the watermark forever.
        val outcome = LeadResolver.classifyByPhone(safeApiCall { error(400, """{"detail":"Invalid phone number"}""") })

        assertEquals(LeadResolver.ByPhoneOutcome.NotALead, outcome)
    }

    @Test
    fun `no connection is unavailable, never not-a-lead`() = runTest {
        val outcome = LeadResolver.classifyByPhone(safeApiCall { throw UnknownHostException("api.arthax.ai") })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Unavailable)
        assertTrue((outcome as LeadResolver.ByPhoneOutcome.Unavailable).failure is ApiResult.Failure.Network)
    }

    @Test
    fun `a server error is unavailable`() = runTest {
        val outcome = LeadResolver.classifyByPhone(safeApiCall { error(503, """{"detail":"Service Unavailable"}""") })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Unavailable)
    }

    @Test
    fun `a rejected session is unavailable, not a verdict on the number`() = runTest {
        // The interceptor signs the rep out on a 401; the call is held for when they are
        // back in, not thrown away.
        val outcome = LeadResolver.classifyByPhone(safeApiCall { error(401, """{"detail":"Could not validate credentials"}""") })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Unavailable)
    }

    @Test
    fun `out of credits is unavailable`() = runTest {
        val outcome = LeadResolver.classifyByPhone(safeApiCall { error(402, """{"detail":"No credits"}""") })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Unavailable)
    }

    @Test
    fun `throttling is unavailable`() = runTest {
        val outcome = LeadResolver.classifyByPhone(safeApiCall { error(429, """{"detail":"Rate limit exceeded"}""") })

        assertTrue(outcome is LeadResolver.ByPhoneOutcome.Unavailable)
    }
}

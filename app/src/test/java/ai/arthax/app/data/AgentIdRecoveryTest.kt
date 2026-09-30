package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Recovering the rep's id when the stored session never got one.
 *
 * The calls and meetings lists are organisation-wide and scope themselves by `agent_id`.
 * That id comes from the `user` object on the login response, and when the response does
 * not carry one the session is stored with an empty id — after which both lists showed
 * "nothing yet" against a CRM holding hundreds of calls, permanently, and only a fresh
 * sign-in could fix it.
 *
 * `GET /api/users/me` answers with the object the login response should have had. These
 * tests pin that it parses, and that the request is even made with no body of its own.
 */
class AgentIdRecoveryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ArthaxApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(ArthaxApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    /** The id the whole repair turns on. */
    @Test
    fun `the profile answers with the id the session was missing`() = runTest {
        respond(
            """
            {"id":"5112e70d-85a9-4389-aa21-e1b2910099e0","email":"tushar@arthax.ai",
             "full_name":"Pawar Tushar","phone":"9876543210","role":"sdr",
             "team_id":null,"org_id":"433a45ac-5815-4cfc-9532-8152177b78b8","is_active":true}
            """.trimIndent(),
        )

        val result = safeApiCall { api.me() }
        assertTrue("Expected Success, got $result", result is ApiResult.Success)

        val user = (result as ApiResult.Success).data
        assertEquals("5112e70d-85a9-4389-aa21-e1b2910099e0", user.id)
        assertEquals("Pawar Tushar", user.fullName)

        // A plain GET, so it is safe to make whenever the id is found to be missing.
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/users/me", request.path)
    }

    /** Everything except the id is optional; a sparse profile must still repair the id. */
    @Test
    fun `a profile carrying only an id is enough`() = runTest {
        respond("""{"id":"5112e70d-85a9-4389-aa21-e1b2910099e0"}""")

        val result = safeApiCall { api.me() }
        assertTrue("Expected Success, got $result", result is ApiResult.Success)
        assertEquals(
            "5112e70d-85a9-4389-aa21-e1b2910099e0",
            (result as ApiResult.Success).data.id,
        )
        assertNull((result as ApiResult.Success).data.fullName)
    }

    /**
     * A failed repair must stay a failure.
     *
     * This is the case that produced the original bug: anything that resolves to "no id"
     * has to reach the screen as a problem, because rendering it as an empty list is
     * indistinguishable from a rep who genuinely has no calls.
     */
    @Test
    fun `an unreachable profile is a failure, not an empty answer`() = runTest {
        listOf(401 to """{"detail":"Invalid credentials"}""", 500 to "Internal Server Error")
            .forEach { (code, body) ->
                respond(body, code)
                val result = safeApiCall { api.me() }
                assertTrue("HTTP $code should fail, got $result", result is ApiResult.Failure)
            }
    }
}

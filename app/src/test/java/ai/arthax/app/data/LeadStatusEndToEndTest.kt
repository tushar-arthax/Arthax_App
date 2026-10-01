package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LeadOptionDto
import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.ui.leads.LeadFilter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * `GET /api/leads/statuses` through the real stack: OkHttp, Retrofit, the Moshi converter,
 * [safeApiCall], and the mapping the repository does afterwards. Wired exactly as
 * `NetworkModule` wires it, so what happens here is what happens on a device.
 *
 * Written to settle a bug where the server logged 200 for this path over and over while the
 * filter row stayed empty. The payload itself was never the problem — a well-formed response
 * always parsed. What broke it was that [LeadOptionDto] required `id` and `name`: Moshi fails
 * the whole *array* on a bad element, so one entry with a null or absent field discarded all
 * seven statuses, and the reason surfaced only as the generic "Something went wrong".
 *
 * Hence the shape of this file: most of it is malformed responses, each asserting that the
 * usable entries still get through.
 */
class LeadStatusEndToEndTest {

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

    /** The statuses the chip row would end up with, or the failure that stopped it. */
    private suspend fun statuses(): ApiResult<List<LeadOption>> =
        when (val result = safeApiCall { api.getLeadStatuses() }) {
            is ApiResult.Success -> ApiResult.Success(map(result.data))
            is ApiResult.Failure -> result
        }

    /** The mapping `LeadsRepository.fetchOptions` does on a success. */
    private fun map(dtos: List<LeadOptionDto>): List<LeadOption> =
        dtos.mapNotNull { dto ->
            dto.nameOrNull?.let { name ->
                LeadOption(
                    id = dto.id?.takeIf { it.isNotBlank() } ?: name,
                    api = name,
                    isDefault = dto.isDefault == true,
                )
            }
        }

    private fun ApiResult<List<LeadOption>>.options(): List<LeadOption> {
        assertTrue("Expected Success, got $this", this is ApiResult.Success)
        return (this as ApiResult.Success).data
    }

    private fun ApiResult<List<LeadOption>>.names(): List<String> = options().map { it.api }

    @Test
    fun `the documented response survives the whole chain and becomes chips`() = runTest {
        respond(LIVE)

        val result = statuses()

        assertEquals(
            listOf("new", "contacted", "qualified", "proposal", "negotiation", "won", "lost"),
            result.names(),
        )
        // All, plus one chip per status.
        assertEquals(8, LeadFilter.build(result.options()).size)

        // And the app asked for the path we think it asked for.
        assertEquals("/api/leads/statuses", server.takeRequest().path)
    }

    /** The shape that used to cost all seven statuses: an explicit null on a required field. */
    @Test
    fun `a null name costs only that entry`() = runTest {
        respond(
            """
            [
              {"id": "1", "name": "new", "is_default": true},
              {"id": "2", "name": null, "is_default": false},
              {"id": "3", "name": "won", "is_default": false}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("new", "won"), statuses().names())
    }

    @Test
    fun `a null id costs nothing, because nothing is ever sent back with it`() = runTest {
        respond("""[{"id": null, "name": "new"}, {"id": "3", "name": "won"}]""")

        assertEquals(listOf("new", "won"), statuses().names())
    }

    /** A response carrying no `id` field at all — `name` is the only field the row needs. */
    @Test
    fun `id-less statuses are still usable`() = runTest {
        respond("""[{"name": "new"}, {"name": "contacted"}]""")

        val result = statuses()
        assertEquals(listOf("new", "contacted"), result.names())

        // The name stands in as identity, so a rebuilt chip still compares equal and the
        // rep's selection is not silently dropped when the statuses reload.
        assertEquals(
            LeadFilter.Status(LeadOption(id = "new", api = "new")),
            LeadFilter.build(result.options())[1],
        )
    }

    /** A server that names the field something other than `name`. */
    @Test
    fun `an alternate key for the status text is still read`() = runTest {
        respond("""[{"id":"1","value":"new"},{"id":"2","status":"won"},{"id":"3","label":"lost"}]""")

        assertEquals(listOf("new", "won", "lost"), statuses().names())
    }

    /** `name` wins when several keys are present, so a server sending both is unaffected. */
    @Test
    fun `name takes precedence over the fallbacks`() = runTest {
        respond("""[{"id":"1","name":"new","value":"ignored","label":"ignored too"}]""")

        assertEquals(listOf("new"), statuses().names())
    }

    /** An entry with nothing usable is skipped alone, not with the list around it. */
    @Test
    fun `a nameless entry is skipped and the rest survive`() = runTest {
        respond("""[{"id":"1","name":"new"},{"id":"2"},{"id":"3","name":"  "},{"id":"4","name":"won"}]""")

        assertEquals(listOf("new", "won"), statuses().names())
    }

    @Test
    fun `whitespace around a status is trimmed before it is sent back`() = runTest {
        respond("""[{"id":"1","name":"  follow_up_pending  "}]""")

        assertEquals(listOf("follow_up_pending"), statuses().names())
    }

    /** An org that has defined none is a legitimate answer, not a failure. */
    @Test
    fun `an empty array is a success with no chips but All`() = runTest {
        respond("[]")

        assertEquals(emptyList<String>(), statuses().names())
        assertEquals(listOf(LeadFilter.All), LeadFilter.build(emptyList()))
    }

    /**
     * Shapes that genuinely cannot be read. These stay failures — there is nothing to show —
     * but the reason has to reach the caller, because a bare "unavailable" on a 200 is what
     * made this bug expensive to find. Each is asserted to carry a detail.
     */
    @Test
    fun `an unreadable response fails with a reason attached`() = runTest {
        val unreadable = mapOf(
            "an envelope instead of an array" to """{"items":[{"id":"1","name":"new"}],"total":1}""",
            "statuses as bare strings" to """["new","contacted","won"]""",
            "an empty body" to "",
        )

        unreadable.forEach { (description, body) ->
            respond(body)

            val result = statuses()
            assertTrue("$description should fail, got $result", result is ApiResult.Failure)
            assertTrue(
                "$description failed with no detail to show the rep",
                !(result as ApiResult.Failure).detail.isNullOrBlank(),
            )
        }
    }

    private companion object {
        val LIVE = """
        [
          {"id": "11111111-1111-4111-8111-111111111111", "name": "new", "is_default": true},
          {"id": "22222222-2222-4222-8222-222222222222", "name": "contacted", "is_default": false},
          {"id": "33333333-3333-4333-8333-333333333333", "name": "qualified", "is_default": false},
          {"id": "44444444-4444-4444-8444-444444444444", "name": "proposal", "is_default": false},
          {"id": "55555555-5555-4555-8555-555555555555", "name": "negotiation", "is_default": false},
          {"id": "66666666-6666-4666-8666-666666666666", "name": "won", "is_default": false},
          {"id": "77777777-7777-4777-8777-777777777777", "name": "lost", "is_default": false}
        ]
        """.trimIndent()
    }
}

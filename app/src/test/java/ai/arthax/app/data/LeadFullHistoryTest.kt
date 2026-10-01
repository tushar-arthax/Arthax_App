package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LeadTimelinePageAdapterFactory
import ai.arthax.app.data.remote.dto.toDomain
import ai.arthax.app.domain.model.LeadTimelineEvent
import ai.arthax.app.ui.leads.ActivityFilter
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
 * The real timeline of lead `88ff932b`, copied from a live call, unedited.
 *
 * This lead is worked by two people. `GET /api/calls/` is scoped to the signed-in rep by
 * `agent_id`, so Pawar Tushar's device can fetch only his own three calls while the other
 * twenty belong to Rahul S Shetty — which is why the lead screen showed a two-row history
 * against the CRM's twenty-six. The timeline is the complete record, and these tests pin
 * that every one of its events survives the parse and reaches the list.
 */
class LeadFullHistoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ArthaxApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder().add(LeadTimelinePageAdapterFactory).build(),
                ),
            )
            .build()
            .create(ArthaxApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun events(): List<LeadTimelineEvent> {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(LIVE),
        )

        val result = safeApiCall { api.getLeadTimeline("88ff932b", 50) }
        assertTrue("Expected Success, got $result", result is ApiResult.Success)

        return (result as ApiResult.Success).data.events
            .mapIndexed { index, dto -> dto.toDomain("88ff932b-$index") }
            .filterNot { it.isEmpty }
    }

    /** Every event reaches the screen. This is the count the CRM's web view shows. */
    @Test
    fun `all twenty-six events survive`() = runTest {
        assertEquals(26, events().size)
    }

    /**
     * And nearly all of them are calls, by two different reps.
     *
     * The important number is the second one: twenty-two of these belong to a colleague and
     * can never come back from the rep's own calls endpoint.
     */
    @Test
    fun `every call is present, including a colleague's`() = runTest {
        val events = events()
        val calls = events.filter { it.isCall }
        assertEquals(22, calls.size)

        val byRahul = calls.count { it.actor == "Rahul S Shetty" }
        val byTushar = calls.count { it.actor == "Pawar Tushar" }
        assertEquals(19, byRahul)
        assertEquals(3, byTushar)

        // Each carries the id that lets a fetchable call be matched to its analysis.
        assertTrue("a call arrived with no call_id", calls.all { it.callId != null })
    }

    /** Outcome and duration come off `to` and `meta`, which the row renders as pills. */
    @Test
    fun `calls carry their outcome and length`() = runTest {
        val calls = events().filter { it.isCall }

        val longest = calls.maxByOrNull { it.durationSeconds ?: 0 }!!
        assertEquals("connected", longest.outcome)
        assertEquals(123, longest.durationSeconds)
        assertEquals("Pawar Tushar", longest.actor)

        // The 49-second call the rep made on 23 Sept.
        val recent = calls.first { it.callId == "4e6252ee-32c2-4c27-88a2-4806d7edcc4f" }
        assertEquals("connected", recent.outcome)
        assertEquals(49, recent.durationSeconds)

        assertEquals(12, calls.count { it.outcome == "not_picked" })
        assertTrue(calls.filter { it.outcome == "not_picked" }.all { it.durationSeconds == 0 })
    }

    /** The non-call events are there too, and read as something a rep understands. */
    @Test
    fun `the note, the temperature change and the creation all read`() = runTest {
        val events = events()

        val note = events.first { it.type == "note_added" }
        assertTrue(
            "note text missing: ${note.text}",
            note.text!!.contains("Follow-up completed"),
        )

        val temperature = events.first { it.type == "temperature_changed" }
        assertEquals("Warm → Cold", temperature.text)

        val created = events.first { it.type == "lead_created" }
        assertEquals("Source: MANUAL", created.text)

        // actor: null is the system acting, not a broken event.
        assertEquals(null, created.actor)
    }

    /** Newest first, which is the order the history is grouped in. */
    @Test
    fun `sorting newest first matches the CRM`() = runTest {
        val ordered = events().sortedByDescending { it.occurredAtMillis ?: Long.MIN_VALUE }

        assertEquals("note_added", ordered.first().type)
        assertEquals("attribution_touch", ordered.last().type)

        val times = ordered.mapNotNull { it.occurredAtMillis }
        assertEquals(times.sortedDescending(), times)
    }

    /** The filter chips over the history, against real data. */
    @Test
    fun `the filters split the history the way the CRM does`() = runTest {
        val events = events()

        assertEquals(26, events.count(ActivityFilter.EVERYTHING::matches))
        assertEquals(22, events.count(ActivityFilter.CALLS::matches))
        assertEquals(1, events.count(ActivityFilter.NOTES::matches))
        assertEquals(1, events.count(ActivityFilter.STATUS::matches))
    }

    private companion object {
        /** GET /api/leads/88ff932b-a0ba-4a14-b97d-45482d4e6d99/timeline */
        val LIVE = """
{"events":[{"id":"9d134730-6a38-4346-a2a1-1b5177c2928e","type":"note_added","occurred_at":"2026-09-23T18:38:38.568066","actor":null,"from":null,"to":null,"from_user":null,"to_user":null,"detail":"[2026-09-11 15:50 System] Follow-up Completed.\n\n[Note|2026-09-23T17:03:12+05:30] Follow-up completed — Connected (call)","meta":null,"follow_up_state":null},{"id":"954dce13-94f8-4596-8b9c-b5c981f0d166","type":"temperature_changed","occurred_at":"2026-09-23T18:38:38.567962","actor":null,"from":"warm","to":"cold","from_user":null,"to_user":null,"detail":null,"meta":null,"follow_up_state":null},{"id":"f5f63871-bf24-4cfa-be09-916fc0edfdd3","type":"call_logged","occurred_at":"2026-09-23T17:03:12.813000","actor":{"id":"5112e70d-85a9-4389-aa21-e1b2910099e0","name":"Pawar Tushar"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"4e6252ee-32c2-4c27-88a2-4806d7edcc4f","duration_seconds":49},"follow_up_state":null},{"id":"4bdff29a-5c87-49e6-984a-912faa70bb90","type":"call_logged","occurred_at":"2026-09-23T17:02:06.947000","actor":{"id":"5112e70d-85a9-4389-aa21-e1b2910099e0","name":"Pawar Tushar"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"7144e530-c712-4e5b-9782-057411400f86","duration_seconds":0},"follow_up_state":null},{"id":"f38de5a1-c194-4907-a01f-08c204032530","type":"call_logged","occurred_at":"2026-09-11T22:16:49.092000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"Incoming call","meta":{"call_id":"74afdbe6-22f8-4ff4-95d0-b598c3808a43","duration_seconds":15},"follow_up_state":null},{"id":"ef1b67ee-3212-4374-85b7-3e742e317a3c","type":"call_logged","occurred_at":"2026-09-11T22:15:06.449000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"c75a26d0-cb52-4fd6-889c-89cd0b8a7230","duration_seconds":19},"follow_up_state":null},{"id":"a2ea4917-13d5-468e-984b-06245df485bb","type":"call_logged","occurred_at":"2026-09-11T22:12:25.639000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"a3c4cbfe-bce1-411a-99e6-25e04c490d1d","duration_seconds":26},"follow_up_state":null},{"id":"885eec76-25b0-4593-b05e-36c45cdbea23","type":"call_logged","occurred_at":"2026-09-11T15:53:38.618000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"de51c8de-9fa1-426d-beb5-3810e532c4d6","duration_seconds":0},"follow_up_state":null},{"id":"cc5741f1-e770-45e9-88a7-d509c079c84f","type":"call_logged","occurred_at":"2026-09-11T15:51:46.715000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"682143ee-b8d9-4fe0-a020-7a3cb2d38852","duration_seconds":7},"follow_up_state":null},{"id":"96cbf2ef-18ed-415c-8028-c5e39e2b0f20","type":"call_logged","occurred_at":"2026-09-11T11:42:42.364000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"9f5f4b62-aefe-40f3-bc62-2a7390a56e02","duration_seconds":0},"follow_up_state":null},{"id":"eea41b06-17d3-4cd1-bde7-91aafc4bddea","type":"call_logged","occurred_at":"2026-09-11T11:42:13.266000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"694f6a06-03a6-48e2-bce5-ad5a9b643ca8","duration_seconds":0},"follow_up_state":null},{"id":"c1bbe041-cd80-420f-96e4-488dad19a306","type":"call_logged","occurred_at":"2026-09-11T11:41:58.273000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"d4f5c2fa-42dd-469a-8841-9f43a928abd4","duration_seconds":0},"follow_up_state":null},{"id":"18e8401e-9065-457b-87ac-763a28bdede8","type":"call_logged","occurred_at":"2026-09-11T11:39:24.998000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"878b3a67-3b35-4f7a-b73e-056f3097e4c2","duration_seconds":0},"follow_up_state":null},{"id":"94ab9831-47e2-4121-8e04-c1fe9fc05e3b","type":"call_logged","occurred_at":"2026-09-11T11:39:08.209000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"c4d1b2fe-c098-49ca-8de5-77441c3ae84d","duration_seconds":0},"follow_up_state":null},{"id":"00c04c4d-e715-4c92-8db8-ed76c45b75ae","type":"call_logged","occurred_at":"2026-09-11T11:38:54.735000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"c5bd0f46-8251-494c-8015-c37edb51abe0","duration_seconds":0},"follow_up_state":null},{"id":"81e1c3d3-82d1-4215-8329-48453f49e7b7","type":"call_logged","occurred_at":"2026-09-11T11:38:42.149000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"f0ce28c9-70d2-40fa-9166-5d5fdc0d0945","duration_seconds":0},"follow_up_state":null},{"id":"4e15a676-6390-4057-a028-ad6242394a82","type":"call_logged","occurred_at":"2026-09-11T11:38:30.925000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"40e59bb2-e686-4652-8e80-950675231ff7","duration_seconds":0},"follow_up_state":null},{"id":"db029df6-1ebf-4ae6-b70e-1e30a062856f","type":"call_logged","occurred_at":"2026-09-11T11:38:16.813000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"61e9b48a-0ffd-46f2-9735-4f0cba2e6298","duration_seconds":0},"follow_up_state":null},{"id":"185ee7f6-bd47-4d9f-97fc-ea30236325f9","type":"call_logged","occurred_at":"2026-09-11T11:34:27.567000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"530b90f4-74c8-442f-8a68-f03e19c53698","duration_seconds":26},"follow_up_state":null},{"id":"6bb2cf11-c037-4783-913b-b4b57fc8ec28","type":"call_logged","occurred_at":"2026-09-11T11:09:17.278000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"d951eacc-6d71-46a1-ad6f-15c6ab1184ed","duration_seconds":33},"follow_up_state":null},{"id":"413e67f0-ca07-43a7-bf82-35bdcea38289","type":"call_logged","occurred_at":"2026-09-11T11:05:37.492000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"1d4952c5-1325-4487-96aa-71d89d5f3bdd","duration_seconds":7},"follow_up_state":null},{"id":"60f6039c-3b5a-4cfd-9610-4aa32d46af95","type":"call_logged","occurred_at":"2026-09-11T11:05:07.422000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"","meta":{"call_id":"5c6cef01-33fa-45c7-b6a5-61ce7e688f1b","duration_seconds":14},"follow_up_state":null},{"id":"4ca330cb-9f11-410f-8fff-a1d1896a70cf","type":"call_logged","occurred_at":"2026-09-11T11:04:12.969000","actor":{"id":"9237c76b-a94f-4a14-81ec-e175949c00f1","name":"Rahul S Shetty"},"from":null,"to":"not_picked","from_user":null,"to_user":null,"detail":"Call not answered","meta":{"call_id":"21ec38a6-5101-4639-a943-65eaf8f4408c","duration_seconds":0},"follow_up_state":null},{"id":"92066b14-f890-4416-9657-04280883ff8a","type":"call_logged","occurred_at":"2026-09-01T18:43:05.842316","actor":{"id":"5112e70d-85a9-4389-aa21-e1b2910099e0","name":"Pawar Tushar"},"from":null,"to":"connected","from_user":null,"to_user":null,"detail":"dsf","meta":{"call_id":"d69ea99e-2746-4f13-af38-bd57a2ec0354","duration_seconds":123},"follow_up_state":null},{"id":"ec2397ce-5f67-495e-8a3c-78e4769ebadf","type":"lead_created","occurred_at":"2026-09-01T18:42:17.805392","actor":null,"from":null,"to":"manual","from_user":null,"to_user":null,"detail":null,"meta":null,"follow_up_state":null},{"id":"20ab92c1-0f01-4aab-9440-997f9dbb97b8","type":"attribution_touch","occurred_at":"2026-09-01T18:42:17.805392","actor":null,"from":null,"to":"manual","from_user":null,"to_user":null,"detail":null,"meta":{"campaign_id":null,"ad_set_id":null},"follow_up_state":null}],"next_before":null,"next_before_id":null}
        """.trimIndent()
    }
}

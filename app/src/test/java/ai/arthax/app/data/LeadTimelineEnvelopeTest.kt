package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LeadTimelinePageAdapterFactory
import ai.arthax.app.data.remote.dto.toDomain
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
 * `GET /api/leads/{id}/timeline` through the real stack.
 *
 * The journey rail was showing nothing on a lead the CRM displays 19 actions for. Two things
 * conspired: the repository answered a failure with an empty list, and the rail drew nothing
 * when it had no events — so a timeline that could not be read looked exactly like a lead
 * with no history. The repository now returns the failure, and these tests pin the other
 * half: whichever of the plausible envelopes this route uses, the events come out.
 *
 * Wired as `NetworkModule` wires it, including the adapter factory, so what passes here is
 * what the device does.
 */
class LeadTimelineEnvelopeTest {

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

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private suspend fun page() =
        safeApiCall { api.getLeadTimeline("lead-1", 25) }.let { result ->
            assertTrue("Expected Success, got $result", result is ApiResult.Success)
            (result as ApiResult.Success).data
        }

    private suspend fun types(): List<String?> = page().events.map { it.resolvedType }

    /**
     * The exact body the live route returns, field for field.
     *
     * Every event on it has `detail: null` and `actor: null` — the substance is in `to` and
     * in the type — so this is also the case that proves the rail draws something other than
     * a row of empty dated cards.
     */
    @Test
    fun `the live response becomes readable events`() = runTest {
        respond(LIVE)

        val page = page()
        assertEquals(2, page.events.size)

        val created = page.events[0]
        assertEquals("lead_created", created.resolvedType)
        assertEquals("2026-09-11T17:59:02.025370", created.resolvedAt)
        // `to: "upload"` on a creation event is where the lead came from.
        assertEquals("Source: UPLOAD", created.resolvedText)

        val touch = page.events[1]
        assertEquals("attribution_touch", touch.resolvedType)
        assertEquals("Source: UPLOAD", touch.resolvedText)

        // A null cursor is the end of the journey, so no "show earlier" is offered.
        assertEquals(null, page.nextBefore)
        assertEquals(null, page.nextBeforeId)
    }

    /**
     * The whole chain the screen depends on: response → DTO → domain → what the rail draws.
     *
     * The mapping drops any event with nothing to show, and that filter sits between a
     * correct parse and a blank rail — so it is asserted here against the live body rather
     * than trusted.
     */
    @Test
    fun `the live response survives all the way to what the rail draws`() = runTest {
        respond(LIVE)

        val events = page().events
            .mapIndexed { index, dto -> dto.toDomain(fallbackId = "lead-1-$index") }
            .filterNot { it.isEmpty }

        // Nothing may be dropped: both events carry a type, a time and a source.
        assertEquals(2, events.size)

        val created = events.first()
        assertEquals("lead_created", created.type)
        assertEquals("Lead created", created.typeLabel)
        assertEquals("Source: UPLOAD", created.text)
        assertTrue("no timestamp on the first event", created.occurredAtMillis != null)

        // And each one has something written on its card beyond the heading.
        assertTrue(events.all { !it.text.isNullOrBlank() })
        assertTrue(events.none { it.isEmpty })
    }

    /**
     * The failure that kept the rail empty on leads with real history.
     *
     * A generated adapter rejects the whole array when one field is the wrong type, so a
     * single call event whose `detail` is an object took all nineteen events off the screen
     * and reported a parse error on a 200. Each field below arrives as something other than
     * the string it is declared as, and all of them must still come through.
     */
    @Test
    fun `a field of the wrong type costs neither its event nor the array`() = runTest {
        respond(
            """
            {"events":[
              {"id":"1","type":"lead_created","occurred_at":"2026-09-11T17:59:02.025370","to":"upload"},
              {"id":2,"type":"call_completed","occurred_at":"2026-09-22T12:46:00",
               "detail":{"status":"connected","duration_seconds":16}},
              {"id":"3","type":"assigned","occurred_at":"2026-09-22T12:50:00",
               "actor":{"id":"u1","name":"Pawar Tushar"}},
              {"id":"4","type":"note_added","occurred_at":"2026-09-22T12:53:00",
               "detail":["hi there","second line"]},
              {"id":"5","type":"rating_changed","occurred_at":"2026-09-22T12:55:00",
               "from":3,"to":5},
              {"id":"6","type":"flagged","occurred_at":"2026-09-22T12:56:00","detail":true}
            ],"next_before":null}
            """.trimIndent(),
        )

        val events = page().events
            .mapIndexed { index, dto -> dto.toDomain(fallbackId = "lead-1-$index") }
            .filterNot { it.isEmpty }

        assertEquals(6, events.size)

        // An object names itself where it can, and is written out where it cannot.
        assertEquals("connected", events[1].text)
        assertEquals("Pawar Tushar", events[2].actor)
        assertEquals("hi there, second line", events[3].text)
        assertEquals("3 → 5", events[4].text)
        assertEquals("true", events[5].text)

        // And the ordinary event beside them is untouched.
        assertEquals("Source: UPLOAD", events[0].text)
    }

    /** An object with no name key is written out rather than shown as a Kotlin map. */
    @Test
    fun `a nameless object is written out readably`() = runTest {
        respond(
            """{"events":[{"id":"1","type":"call","occurred_at":"2026-09-22T12:46:00",
               "detail":{"duration_seconds":16,"recording":null}}],"next_before":null}""",
        )

        val text = page().events.single().toDomain("x").text
        assertEquals("duration seconds: 16", text)
    }

    /** A malformed element among good ones is skipped, not fatal. */
    @Test
    fun `a bare string in the array does not reject the rest`() = runTest {
        respond(
            """{"events":["something happened",
               {"id":"2","type":"lead_created","occurred_at":"2026-09-11T17:59:02.025370","to":"upload"}],
               "next_before":null}""",
        )

        val events = page().events
            .mapIndexed { index, dto -> dto.toDomain(fallbackId = "lead-1-$index") }
            .filterNot { it.isEmpty }

        assertEquals(2, events.size)
        assertEquals("something happened", events[0].text)
        assertEquals("Source: UPLOAD", events[1].text)
    }

    /** The zone-less microsecond timestamps this route uses must resolve to a real time. */
    @Test
    fun `the live timestamps parse`() = runTest {
        respond(LIVE)

        val at = ai.arthax.app.data.remote.dto.ApiTime.parseOrNull(page().events[0].resolvedAt)
        assertTrue("2026-09-11T17:59:02.025370 did not parse", at != null && at > 0L)
    }

    /** A status change reads as a move between two states. */
    @Test
    fun `a from-to pair reads as a transition`() = runTest {
        respond(
            """{"events":[{"id":"1","type":"status_changed","occurred_at":"2026-09-12T10:00:00",
               "from":"new","to":"contacted","detail":null}],"next_before":null}""",
        )

        assertEquals("New → Contacted", page().events.single().resolvedText)
    }

    /** An assignment names the person, even though `actor` is null on it. */
    @Test
    fun `an assignment reads as the person it moved to`() = runTest {
        respond(
            """{"events":[{"id":"1","type":"assigned","occurred_at":"2026-09-12T10:00:00",
               "actor":null,"from_user":null,"to_user":"Pawar Tushar"}],"next_before":null}""",
        )

        val event = page().events.single()
        assertEquals("Pawar Tushar", event.resolvedText)
        assertEquals("Pawar Tushar", event.resolvedActor)
    }

    /** A cursor on the page is carried back as `before` and `before_id`. */
    @Test
    fun `the cursor comes back and is sent on the next request`() = runTest {
        respond(
            """{"events":[],"next_before":"2026-09-11T17:59:02.025370",
               "next_before_id":"3c9a5bb4-e213-4aab-b988-bf0748946abe"}""",
        )

        val first = page()
        assertEquals("2026-09-11T17:59:02.025370", first.nextBefore)
        assertEquals("3c9a5bb4-e213-4aab-b988-bf0748946abe", first.nextBeforeId)
        server.takeRequest()

        respond("""{"events":[],"next_before":null}""")
        safeApiCall { api.getLeadTimeline("lead-1", 25, first.nextBefore, first.nextBeforeId) }

        val path = server.takeRequest().path.orEmpty()
        assertTrue("cursor missing from $path", path.contains("before_id=3c9a5bb4"))
        assertTrue("limit missing from $path", path.contains("limit=25"))
    }

    @Test
    fun `a bare array is read`() = runTest {
        respond(EVENTS)
        assertEquals(listOf("lead_created", "call_completed"), types())

        assertEquals("/api/leads/lead-1/timeline?limit=25", server.takeRequest().path)
    }

    @Test
    fun `an items envelope is read`() = runTest {
        respond("""{"items": $EVENTS, "total": 2}""")
        assertEquals(listOf("lead_created", "call_completed"), types())
    }

    @Test
    fun `an events envelope is read`() = runTest {
        respond("""{"events": $EVENTS}""")
        assertEquals(listOf("lead_created", "call_completed"), types())
    }

    @Test
    fun `a timeline envelope is read`() = runTest {
        respond("""{"timeline": $EVENTS, "lead_id": "lead-1"}""")
        assertEquals(listOf("lead_created", "call_completed"), types())
    }

    @Test
    fun `results and data envelopes are read`() = runTest {
        respond("""{"results": $EVENTS}""")
        assertEquals(listOf("lead_created", "call_completed"), types())

        respond("""{"data": $EVENTS}""")
        assertEquals(listOf("lead_created", "call_completed"), types())
    }

    /** An envelope carrying other arrays must not be mistaken for the events. */
    @Test
    fun `the events key wins over another array beside it`() = runTest {
        respond("""{"tags": ["a","b"], "items": $EVENTS}""")
        assertEquals(listOf("lead_created", "call_completed"), types())
    }

    /** A genuinely empty journey is a success with no events, not a failure. */
    @Test
    fun `an empty journey parses as empty`() = runTest {
        respond("[]")
        assertEquals(emptyList<String?>(), types())

        respond("""{"items": []}""")
        assertEquals(emptyList<String?>(), types())

        // The route's own schema says "string", so a bare null is worth tolerating too.
        respond("null")
        assertEquals(emptyList<String?>(), types())
    }

    /** A 500 must stay a failure so the rail can say so rather than look empty. */
    @Test
    fun `a server error is reported, not flattened to no history`() = runTest {
        respond("""{"detail":"boom"}""", code = 500)

        val result = safeApiCall { api.getLeadTimeline("lead-1", 25) }
        assertTrue("Expected Failure, got $result", result is ApiResult.Failure)
    }

    private companion object {
        /** Copied from a live call to the timeline route, unedited. */
        val LIVE = """
        {
          "events": [
            {
              "id": "3c9a5bb4-e213-4aab-b988-bf0748946abe",
              "type": "lead_created",
              "occurred_at": "2026-09-11T17:59:02.025370",
              "actor": null, "from": null, "to": "upload",
              "from_user": null, "to_user": null,
              "detail": null, "meta": null, "follow_up_state": null
            },
            {
              "id": "0cb6c84e-4e3c-408b-be13-1eae5fccaba2",
              "type": "attribution_touch",
              "occurred_at": "2026-09-11T17:59:02.012055",
              "actor": null, "from": null, "to": "upload",
              "from_user": null, "to_user": null,
              "detail": null,
              "meta": {"campaign_id": null, "ad_set_id": null},
              "follow_up_state": null
            }
          ],
          "next_before": null,
          "next_before_id": null
        }
        """.trimIndent()

        val EVENTS = """
        [
          {
            "id": "1",
            "event_type": "lead_created",
            "occurred_at": "2026-09-20T10:00:00Z",
            "actor_name": "Pawar Tushar",
            "description": "Source: UPLOAD"
          },
          {
            "id": "2",
            "event_type": "call_completed",
            "occurred_at": "2026-09-22T12:46:00Z",
            "actor_name": "Pawar Tushar",
            "description": "connected (0m 16s)"
          }
        ]
        """.trimIndent()
    }
}

package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.LeadTimelinePageAdapterFactory
import ai.arthax.app.data.remote.dto.toDomain
import ai.arthax.app.domain.model.LeadNote
import ai.arthax.app.ui.common.humanMessage
import ai.arthax.app.ui.leads.mergeNotesIntoJourney
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The timeline route against its written contract.
 *
 * Built from the API documentation's own examples rather than from a guess, after the rail
 * came up empty for some reps and not others. The three things it pins are the three the
 * documentation warns about: `actor` and `from_user`/`to_user` are objects rather than
 * strings, `meta` has a different shape per event type, and unknown event types arrive
 * without notice.
 */
class LeadTimelineContractTest {

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

    private fun respond(body: String, code: Int = 200, json: Boolean = true) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", if (json) "application/json" else "text/html")
                .setBody(body),
        )
    }

    private suspend fun events() =
        safeApiCall { api.getLeadTimeline("lead-1", 50) }.let { result ->
            assertTrue("Expected Success, got $result", result is ApiResult.Success)
            (result as ApiResult.Success).data.events
                .mapIndexed { index, dto -> dto.toDomain("lead-1-$index") }
                .filterNot { it.isEmpty }
        }

    /** The documentation's own admin-view example, verbatim. */
    @Test
    fun `the documented admin response reads end to end`() = runTest {
        respond(DOCUMENTED)

        val events = events()
        assertEquals(5, events.size)

        // actor is an object; the person's name is what belongs on the card.
        assertEquals("Shhekar Raj", events[0].actor)
        assertEquals("Call summary:\n- wants demo Friday\n- send brochure", events[0].text)

        assertEquals("Rahul Shetty", events[1].actor)

        // A lone `to` on a creation event is the source the lead arrived through.
        assertEquals("lead_created", events[3].type)
        assertEquals("Source: META ADS", events[3].text)

        // actor: null means the system did it, not that the event is broken.
        assertEquals(null, events[3].actor)
        assertEquals("attribution_touch", events[4].type)
    }

    /** A rep sees the same journey minus `attribution_touch`, and it must still read. */
    @Test
    fun `the rep view is not broken by the missing attribution event`() = runTest {
        respond(REP_VIEW)

        val events = events()
        assertEquals(2, events.size)
        assertTrue(events.none { it.type == "attribution_touch" })
        assertEquals("Source: META ADS", events[1].text)
    }

    /** `meta` carries the facts a rep would otherwise have to open the call to find. */
    @Test
    fun `a logged call reports its outcome, length and direction`() = runTest {
        respond(
            """
            {"events":[{"id":"c1","type":"call_logged","occurred_at":"2026-09-30T12:46:00",
              "actor":{"id":"u1","name":"Shhekar Raj"},"to":"connected",
              "detail":"asked for pricing",
              "meta":{"call_id":"x","duration_seconds":16,"direction":"outgoing"}}],
             "next_before":null}
            """.trimIndent(),
        )

        assertEquals(
            "Connected · 0m 16s · Outgoing · asked for pricing",
            events().single().text,
        )
    }

    /** `duration_seconds` as a string rather than a number must still work. */
    @Test
    fun `a duration sent as text still reads`() = runTest {
        respond(
            """{"events":[{"id":"c1","type":"call_logged","occurred_at":"2026-09-30T12:46:00",
               "to":"connected","meta":{"duration_seconds":"95"}}],"next_before":null}""",
        )

        assertEquals("Connected · 1m 35s", events().single().text)
    }

    /** The other documented meta shapes. */
    @Test
    fun `junk category and updated fields are read from meta`() = runTest {
        respond(
            """
            {"events":[
              {"id":"j1","type":"marked_junk","occurred_at":"2026-09-30T12:00:00",
               "from":"new","to":"junk","meta":{"junk_category":"invalid_number"}},
              {"id":"d1","type":"details_updated","occurred_at":"2026-09-30T12:01:00",
               "detail":"phone, company","meta":{"fields":["phone","company"]}}
            ],"next_before":null}
            """.trimIndent(),
        )

        val events = events()
        assertEquals("New → Junk · Invalid number", events[0].text)
        assertTrue(events[1].text!!.startsWith("phone, company"))
    }

    /** An event type this build has never heard of must still render as a row. */
    @Test
    fun `an unknown event type is shown rather than dropped`() = runTest {
        respond(
            """{"events":[{"id":"z1","type":"contract_signed","occurred_at":"2026-09-30T12:00:00",
               "detail":"Signed for 20 seats"}],"next_before":null}""",
        )

        val event = events().single()
        assertEquals("Contract signed", event.typeLabel)
        assertEquals("Signed for 20 seats", event.text)
    }

    /** `from_user` / `to_user` are objects on an assignment. */
    @Test
    fun `an assignment names both people`() = runTest {
        respond(
            """{"events":[{"id":"a1","type":"assigned","occurred_at":"2026-09-30T12:00:00",
               "from_user":{"id":"u1","name":"Rahul Shetty"},
               "to_user":{"id":"u2","name":"Shhekar Raj"}}],"next_before":null}""",
        )

        assertEquals("Rahul Shetty → Shhekar Raj", events().single().text)
    }

    // ---------------- notes folded in ----------------

    /** A note the timeline already reported is not shown twice. */
    @Test
    fun `a note already in the timeline is not duplicated`() = runTest {
        respond(DOCUMENTED)

        val merged = mergeNotesIntoJourney(
            events(),
            listOf(
                // The same note the first event reports, via meta.note_id.
                LeadNote("bc2a9795", "Call summary:\n- wants demo Friday", 1L, "Shhekar Raj"),
            ),
        )

        assertEquals(5, merged.size)
    }

    /** A note with no event behind it is added, so older notes are not lost. */
    @Test
    fun `a note the timeline never reported is added`() = runTest {
        respond(REP_VIEW)

        val merged = mergeNotesIntoJourney(
            events(),
            listOf(LeadNote("older-note", "Typed before the timeline existed", 1L, "Rahul Shetty")),
        )

        assertEquals(3, merged.size)
        val added = merged.single { it.id == "older-note" }
        assertEquals("note_added", added.type)
        assertEquals("Typed before the timeline existed", added.text)
    }

    // ---------------- errors a rep should understand ----------------

    @Test
    fun `each documented failure becomes something a rep can act on`() = runTest {
        val cases = listOf(
            Triple(403, """{"detail":"Not authorized to view this lead"}""",
                "This lead is no longer assigned to you."),
            Triple(404, """{"detail":"Lead not found"}""", "This lead was deleted."),
            Triple(401, """{"detail":"Session expired. You logged into this platform from another device."}""",
                "You signed in on another phone. Please sign in again."),
            Triple(403, """{"detail":"WORKSPACE_READ_ONLY: Your plan has expired."}""",
                "Your plan has expired."),
            Triple(500, "Internal Server Error", "Something went wrong. Please try again."),
        )

        cases.forEach { (code, body, expected) ->
            respond(body, code = code, json = body.startsWith("{"))

            val result = safeApiCall { api.getLeadTimeline("lead-1", 50) }
            assertTrue("$code should fail", result is ApiResult.Failure)
            assertEquals("for HTTP $code", expected, (result as ApiResult.Failure).humanMessage())
        }
    }

    /** nginx answers HTML, and none of it may reach the screen. */
    @Test
    fun `an html error page is never shown to a rep`() = runTest {
        respond("<html><head><title>502 Bad Gateway</title></head><body></body></html>", 502, json = false)

        val result = safeApiCall { api.getLeadTimeline("lead-1", 50) }
        val shown = (result as ApiResult.Failure).humanMessage()

        assertFalse("HTML leaked to the rep: $shown", shown.contains("<"))
        assertEquals("Something went wrong. Please try again.", shown)
    }

    private companion object {
        /** From the documentation, T1 (admin view). */
        val DOCUMENTED = """
        {
          "events": [
            { "id": "9b1ecdc8", "type": "note_added", "occurred_at": "2026-09-30T10:03:55.617909",
              "actor": { "id": "aaaaaaaa-2", "name": "Shhekar Raj" },
              "from": null, "to": null, "from_user": null, "to_user": null,
              "detail": "Call summary:\n- wants demo Friday\n- send brochure",
              "meta": { "note_id": "bc2a9795" }, "follow_up_state": null },
            { "id": "1f9bb430", "type": "note_added", "occurred_at": "2026-09-30T10:03:55.399922",
              "actor": { "id": "aaaaaaaa-1", "name": "Rahul Shetty" },
              "detail": "Decision maker is the CFO, call after 6pm",
              "meta": { "note_id": "2cee5134" }, "follow_up_state": null },
            { "id": "59ef0afa", "type": "note_added", "occurred_at": "2026-09-30T10:03:54.104620",
              "actor": { "id": "aaaaaaaa-2", "name": "Shhekar Raj" },
              "detail": "Asked for pricing for 20 seats",
              "meta": { "note_id": "10c2a7a8" }, "follow_up_state": null },
            { "id": "dec16457", "type": "lead_created", "occurred_at": "2026-09-28T17:31:00",
              "actor": null, "to": "META ADS", "detail": null, "meta": null, "follow_up_state": null },
            { "id": "a6eed4c5", "type": "attribution_touch", "occurred_at": "2026-09-28T17:31:00",
              "actor": null, "to": "META ADS", "detail": null,
              "meta": { "campaign_id": null, "ad_set_id": null }, "follow_up_state": null }
          ],
          "next_before": null,
          "next_before_id": null
        }
        """.trimIndent()

        /** T2: the same journey as a rep sees it, without the attribution touch. */
        val REP_VIEW = """
        {
          "events": [
            { "id": "59ef0afa", "type": "note_added", "occurred_at": "2026-09-30T10:03:54.104620",
              "actor": { "id": "aaaaaaaa-2", "name": "Shhekar Raj" },
              "detail": "Asked for pricing for 20 seats", "meta": { "note_id": "10c2a7a8" } },
            { "id": "dec16457", "type": "lead_created", "occurred_at": "2026-09-28T17:31:00",
              "actor": null, "to": "META ADS", "meta": null }
          ],
          "next_before": null,
          "next_before_id": null
        }
        """.trimIndent()
    }
}

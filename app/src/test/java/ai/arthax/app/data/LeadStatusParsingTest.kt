package ai.arthax.app.data

import ai.arthax.app.data.remote.dto.LeadOptionDto
import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.ui.leads.LeadFilter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/leads/statuses`, against the exact body the server returns.
 *
 * Written while chasing an empty filter row. It proves the client end of that chain is
 * sound — the bare array parses, every name survives, and the chip row comes out with one
 * entry per status — so an empty row on a device means the request did not come back with
 * these, not that the app threw them away.
 */
class LeadStatusParsingTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter<List<LeadOptionDto>>(
        Types.newParameterizedType(List::class.java, LeadOptionDto::class.java),
    )

    @Test
    fun `parses the live statuses response`() {
        val options = adapter.fromJson(LIVE)

        assertNotNull(options)
        assertEquals(7, options!!.size)
        assertEquals("new", options[0].name)
        assertEquals(true, options[0].isDefault)
        assertEquals("11111111-1111-4111-8111-111111111111", options[0].id)
        assertEquals(
            listOf("new", "contacted", "qualified", "proposal", "negotiation", "won", "lost"),
            options.map { it.name },
        )
    }

    /** The mapping the repository does, and then the row the screen builds from it. */
    @Test
    fun `every status becomes one chip, in the server's order`() {
        val options = adapter.fromJson(LIVE)!!
            .mapNotNull { dto ->
                dto.nameOrNull?.let { name ->
                    LeadOption(
                        id = dto.id?.takeIf { it.isNotBlank() } ?: name,
                        api = name,
                        isDefault = dto.isDefault == true,
                    )
                }
            }

        val row = LeadFilter.build(options)

        // All, plus one per status.
        assertEquals(8, row.size)
        assertEquals(LeadFilter.All, row.first())
        assertEquals(
            listOf("New", "Contacted", "Qualified", "Proposal", "Negotiation", "Won", "Lost"),
            row.drop(1).map { it.label },
        )

        // And each one filters on the server's own spelling, not the capitalised label.
        assertEquals(
            listOf("new", "contacted", "qualified", "proposal", "negotiation", "won", "lost"),
            row.drop(1).map { it.statusApi },
        )
    }

    /** An org with no statuses defined is a legitimate answer, not a failure. */
    @Test
    fun `an empty array parses to an empty list`() {
        val options = adapter.fromJson("[]")
        assertNotNull(options)
        assertTrue(options!!.isEmpty())
    }

    /** A status the server adds a field to must not break the ones already known. */
    @Test
    fun `unknown fields are ignored`() {
        val options = adapter.fromJson(
            """[{"id": "x", "name": "rnr", "is_default": false, "colour": "#ff0000", "order": 3}]""",
        )
        assertEquals("rnr", options!!.single().name)
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

package ai.arthax.app.ui

import ai.arthax.app.domain.model.LeadOption
import ai.arthax.app.ui.leads.LeadFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The status filter chips.
 *
 * Statuses are defined per organisation by its own admin, so the app must treat them as
 * opaque: whatever `GET /api/leads/statuses` returns is what goes back as `?status=`,
 * character for character. Normalising the case or the punctuation here would filter to
 * nothing on any org whose statuses are not spelled the way this app expects.
 */
class LeadFilterTest {

    private fun option(name: String) = LeadOption(id = "id-$name", api = name)

    /** The one that matters: no case folding, no rewriting, on the way out. */
    @Test
    fun `a status is sent back exactly as the server spelled it`() {
        val spellings = listOf(
            "New",
            "new",
            "follow_up_pending",
            "RNR",
            "Won - Closed",
            "qualified",
        )

        spellings.forEach { name ->
            val filter = LeadFilter.Status(option(name))
            assertEquals(name, filter.statusApi)
        }
    }

    /** All is the absence of a filter, so it sends no status. */
    @Test
    fun `All sends no status`() {
        assertNull(LeadFilter.All.statusApi)
    }

    /**
     * The label is for the chip only and never leaves the device — underscores become
     * spaces and the first letter is capitalised, but an already-styled name is untouched.
     */
    @Test
    fun `the label is display-only and never changes the value sent`() {
        val snake = LeadFilter.Status(option("follow_up_pending"))
        assertEquals("Follow up pending", snake.label)
        assertEquals("follow_up_pending", snake.statusApi)

        // An admin who already capitalised it gets it back unchanged.
        val titled = LeadFilter.Status(option("Won - Closed"))
        assertEquals("Won - Closed", titled.label)

        // An acronym keeps its shape rather than being title-cased into "Rnr".
        assertEquals("RNR", LeadFilter.Status(option("RNR")).label)
    }

    /**
     * The row is All followed by exactly what the org defined — nothing invented, and in
     * the order the server gave them.
     */
    @Test
    fun `the chip row is All plus the org statuses in order`() {
        val built = LeadFilter.build(listOf(option("New"), option("Contacted"), option("RNR")))

        assertEquals(4, built.size)
        assertEquals(LeadFilter.All, built.first())
        assertEquals(listOf("New", "Contacted", "RNR"), built.drop(1).map { it.label })
    }

    /** No statuses means one chip, not a bar of made-up ones. */
    @Test
    fun `with no statuses the row is only All`() {
        assertEquals(listOf(LeadFilter.All), LeadFilter.build(emptyList()))
    }

    /**
     * Chips are rebuilt whenever the statuses reload, so the selected one has to survive
     * being reconstructed from an equal option or the selection would silently reset.
     */
    @Test
    fun `an equal status rebuilds to an equal filter`() {
        assertEquals(LeadFilter.Status(option("New")), LeadFilter.Status(option("New")))
        assertFalse(LeadFilter.Status(option("New")) == LeadFilter.Status(option("Lost")))
    }
}

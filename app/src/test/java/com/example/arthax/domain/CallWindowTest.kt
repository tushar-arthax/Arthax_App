package com.example.arthax.domain

import com.example.arthax.domain.model.CallWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decides which recording file belongs to which call.
 *
 * This is the guard that stops a recording of the rep's earlier personal call being
 * uploaded against a lead. It fails silently if it is wrong — the file is plausible, the
 * upload succeeds, and the wrong audio simply sits in the customer's CRM.
 */
class CallWindowTest {

    private val start = 1_788_000_000_000L

    private fun window(durationSeconds: Long) = CallWindow(
        leadId = "lead-1",
        leadName = "Ramesh Kulkarni",
        startedAt = start,
        endedAt = start + durationSeconds * 1000,
    )

    @Test
    fun `a file written during the call is accepted`() {
        val w = window(60)

        assertTrue(w.accepts(start + 30_000))
    }

    @Test
    fun `a file closed shortly after hang-up is accepted`() {
        // OEM recorders finalise the container after the line drops, sometimes by minutes.
        val w = window(60)

        assertTrue(w.accepts(w.endedAt + 60_000))
    }

    @Test
    fun `a file stamped just before dialling is accepted`() {
        // Some recorders stamp the file when the dialler opened rather than when audio began.
        val w = window(60)

        assertTrue(w.accepts(start - 20_000))
    }

    @Test
    fun `an earlier personal call is rejected`() {
        val w = window(60)

        assertFalse("a recording from an hour earlier must never match", w.accepts(start - 3_600_000))
    }

    @Test
    fun `a much later recording is rejected`() {
        val w = window(60)

        assertFalse(w.accepts(w.endedAt + 30 * 60_000))
    }

    @Test
    fun `boundaries are inclusive`() {
        val w = window(60)

        assertTrue(w.accepts(w.recordingNotBefore))
        assertTrue(w.accepts(w.recordingNotAfter))
        assertFalse(w.accepts(w.recordingNotBefore - 1))
        assertFalse(w.accepts(w.recordingNotAfter + 1))
    }

    @Test
    fun `an unanswered call still has a usable window`() {
        val w = window(0)

        assertEquals(0, w.durationSeconds)
        assertTrue(w.accepts(start))
    }

    @Test
    fun `duration never goes negative if the clock disagrees`() {
        val w = CallWindow("lead-1", "X", startedAt = start, endedAt = start - 5_000)

        assertEquals(0, w.durationSeconds)
    }
}

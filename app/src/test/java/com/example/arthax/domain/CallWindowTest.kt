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
        phone = "+917744991250",
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
        // OEM recorders finalise the container after the line drops, sometimes by a while.
        val w = window(60)

        assertTrue(w.accepts(w.endedAt + 60_000))
    }

    @Test
    fun `duration never goes negative if the clock disagrees`() {
        val w = window(60).copy(endedAt = start - 5_000)

        assertEquals(0, w.durationSeconds)
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

    // ---------------------------------------------------------------------------------
    // Picking between two files, which is where this went wrong in the field.
    //
    // Reproduced from a real report: with the phone offline the rep called Amol, then
    // Vishal, both inside the same minute, both answered and both recorded. When the
    // network came back every call was logged correctly - but Amol's CRM record carried
    // Vishal's audio and Vishal's carried Amol's.
    //
    // The cause was that the earlier call was processed first, saw a candidate list sorted
    // newest-first, and took the top of it: Vishal's file. Then Vishal, with his own file
    // already claimed, fell back to Amol's. A straight swap.
    // ---------------------------------------------------------------------------------

    private val amolEnded = start + 40_000
    private val vishalStarted = start + 95_000
    private val vishalEnded = vishalStarted + 50_000

    private fun amol() = CallWindow(
        leadId = "lead-amol",
        leadName = "Amol",
        phone = "8197829088",
        startedAt = start,
        endedAt = amolEnded,
        nextCallStartedAt = vishalStarted,
    )

    private fun vishal() = CallWindow(
        leadId = "lead-vishal",
        leadName = "Vishal",
        phone = "7019539106",
        startedAt = vishalStarted,
        endedAt = vishalEnded,
        nextCallStartedAt = null,
    )

    /** Picks the way the harvester does: lowest score wins. */
    private fun pick(w: CallWindow, files: List<Pair<String, Long>>) =
        files.filter { w.accepts(it.second) }
            .minByOrNull { w.score(it.first, it.second) }
            ?.first

    @Test
    fun `the earlier call does not take the later call's recording`() {
        val amolFile = "unnamed_a.m4a" to amolEnded + 2_000
        val vishalFile = "unnamed_b.m4a" to vishalEnded + 2_000

        assertEquals(
            "the closest file to hang-up wins, not the newest one in the folder",
            "unnamed_a.m4a",
            pick(amol(), listOf(vishalFile, amolFile)),
        )
    }

    @Test
    fun `the later call keeps its own recording`() {
        val amolFile = "unnamed_a.m4a" to amolEnded + 2_000
        val vishalFile = "unnamed_b.m4a" to vishalEnded + 2_000

        assertEquals("unnamed_b.m4a", pick(vishal(), listOf(vishalFile, amolFile)))
    }

    /**
     * The case a timing rule alone cannot survive: the earlier call's own recording never
     * appeared. Without a bound it would happily adopt the next call's audio.
     */
    @Test
    fun `a call whose recording is missing takes nothing rather than the next call's`() {
        val vishalFile = "unnamed_b.m4a" to vishalEnded + 2_000

        assertEquals(null, pick(amol(), listOf(vishalFile)))
    }

    @Test
    fun `a file named for the number wins over one that is merely closer in time`() {
        // Xiaomi and Samsung both write the number into the file name. When it is there it
        // is worth more than any amount of timing coincidence.
        val named = "918197829088_20260905141610.m4a" to amolEnded - 20_000
        val closer = "recording_002.m4a" to amolEnded + 1_000

        assertEquals(
            "918197829088_20260905141610.m4a",
            pick(amol(), listOf(closer, named)),
        )
    }

    @Test
    fun `a recorder that is a beat late closing the file is still matched`() {
        // The next call has already started ringing when the previous file is finalised.
        // That is normal, and must not cost the recording.
        val late = "unnamed_a.m4a" to vishalStarted + 4_000

        assertEquals("unnamed_a.m4a", pick(amol(), listOf(late)))
    }

    @Test
    fun `many calls in the same minute each keep their own recording`() {
        // Six calls, ten seconds apart, every file named by the recorder - the shape the
        // rep described as "even if 100 calls happen in one minute".
        val numbers = listOf("9000000001", "9000000002", "9000000003", "9000000004")
        val files = numbers.mapIndexed { i, number ->
            "$number-${start + i * 10_000}.m4a" to start + i * 10_000L + 8_000
        }

        numbers.forEachIndexed { i, number ->
            val callStart = start + i * 10_000L
            val w = CallWindow(
                leadId = "lead-$i",
                leadName = "Lead $i",
                phone = number,
                startedAt = callStart,
                endedAt = callStart + 8_000,
                nextCallStartedAt = if (i < numbers.lastIndex) callStart + 10_000 else null,
            )

            assertEquals("call $i must keep its own file", files[i].first, pick(w, files))
        }
    }
}

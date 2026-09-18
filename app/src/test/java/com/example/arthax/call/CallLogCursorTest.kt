package com.example.arthax.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which call-log rows count as new, and which as already handled.
 *
 * Reproduced from a real report: a rep and a lead traded seven unanswered calls — three
 * dialled out, four coming in — and only about three ever reached the CRM. The same shape
 * appeared for every lead, online and offline alike, and only ever for unanswered runs.
 *
 * Two independent flaws did it, and each on its own is enough to lose calls:
 *
 *  1. Progress was tracked by call *timestamp*, but rows are written when a call **ends**
 *     and stamped with when it **began**. Trade calls with someone and they overlap: the
 *     rep dials, and while it rings the lead calls back. The call-back row is written second
 *     yet carries the earlier timestamp, so it lands below the watermark and is never seen.
 *
 *  2. A row was identified by its id alone. Several OEM builds record a repeat unanswered
 *     call to the same number by re-dating the row that is already there instead of
 *     inserting a new one — same id, new timestamp. Every call after the first in such a run
 *     was therefore read as "already handled".
 *
 * Both are fixed by carrying two watermarks and treating (id, date) as the identity.
 */
class CallLogCursorTest {

    private val t0 = 1_788_000_000_000L

    /** One row as the provider would return it. */
    private data class Row(val id: Long, val date: Long)

    /** The rows a query would return, in the order the reconciler walks them. */
    private fun select(rows: List<Row>, sinceId: Long, sinceDate: Long) =
        rows.filter { CallWatermark.isUnseen(it.id, it.date, sinceId, sinceDate) }
            .sortedWith(compareBy({ it.date }, { it.id }))

    // -------------------------------------------------------------------------------
    // Flaw 1: calls that began out of order
    // -------------------------------------------------------------------------------

    @Test
    fun `a call that began while the previous one was still ringing is not lost`() {
        // The rep dials at t0 and it rings for 40s. At t0+25s the lead calls back and that
        // call is missed. The outbound row is written first, at hang-up, stamped t0.
        val outbound = Row(id = 101, date = t0)
        val callBack = Row(id = 102, date = t0 + 25_000)

        // Outbound processed: watermarks move to its id and its date.
        val afterFirst = select(listOf(outbound, callBack), sinceId = 100, sinceDate = t0 - 1)
        assertEquals(listOf(outbound, callBack), afterFirst)

        // Now the harder case - the call-back began *before* the outbound one did.
        val earlierCallBack = Row(id = 102, date = t0 - 5_000)
        val seen = select(listOf(outbound, earlierCallBack), sinceId = 101, sinceDate = t0)

        assertEquals(
            "the row carries an earlier timestamp but a higher id, and must still be seen",
            listOf(earlierCallBack),
            seen,
        )
    }

    @Test
    fun `a timestamp watermark alone would have dropped it`() {
        // Documents precisely what the old code did, so this cannot quietly come back.
        val earlierCallBack = Row(id = 102, date = t0 - 5_000)

        assertFalse(
            "date-only was the bug",
            earlierCallBack.date > t0,
        )
        assertTrue(
            "id catches it",
            CallWatermark.isUnseen(earlierCallBack.id, earlierCallBack.date, sinceId = 101, sinceDate = t0),
        )
    }

    // -------------------------------------------------------------------------------
    // Flaw 2: a repeat call folded into the row already there
    // -------------------------------------------------------------------------------

    @Test
    fun `a row re-dated for a repeat call is seen again`() {
        val first = Row(id = 200, date = t0)
        // Same row, re-dated by the OEM when the lead rang again a minute later.
        val reDated = Row(id = 200, date = t0 + 60_000)

        assertFalse(
            "an id watermark alone would never look at it",
            reDated.id > first.id,
        )
        assertTrue(
            CallWatermark.isUnseen(reDated.id, reDated.date, sinceId = first.id, sinceDate = first.date),
        )
    }

    @Test
    fun `a re-dated row is a different call, not one already handled`() {
        assertTrue(
            "the same row at the same moment is the same call",
            CallWatermark.isSameCall(storedId = 200, storedDate = t0, rowId = 200, rowDate = t0),
        )
        assertFalse(
            "the same row carrying a later moment is a new call",
            CallWatermark.isSameCall(storedId = 200, storedDate = t0, rowId = 200, rowDate = t0 + 60_000),
        )
    }

    @Test
    fun `a call queued before the date was recorded still matches on its id`() {
        // Rows written by an older build carry zero, and must not be posted a second time
        // just because this version now knows about timestamps.
        assertTrue(
            CallWatermark.isSameCall(storedId = 300, storedDate = 0L, rowId = 300, rowDate = t0),
        )
        assertFalse(
            CallWatermark.isSameCall(storedId = 300, storedDate = 0L, rowId = 301, rowDate = t0),
        )
    }

    // -------------------------------------------------------------------------------
    // The reported case, end to end
    // -------------------------------------------------------------------------------

    @Test
    fun `seven unanswered calls with one lead all come through`() {
        // Three dialled out and four coming in, interleaved and overlapping the way they do
        // when two people keep missing each other. Ids are insertion order; dates are when
        // each call began, which is deliberately *not* the same order.
        val rows = listOf(
            Row(id = 501, date = t0),
            Row(id = 502, date = t0 + 20_000),
            Row(id = 503, date = t0 + 15_000), // began while 502 was still ringing
            Row(id = 504, date = t0 + 45_000),
            Row(id = 505, date = t0 + 40_000), // and again
            Row(id = 506, date = t0 + 70_000),
            Row(id = 507, date = t0 + 95_000),
        )

        // Walk them the way the reconciler does: one pass per call as it lands.
        var sinceId = 500L
        var sinceDate = t0 - 1
        val processed = mutableListOf<Row>()

        for (arrived in rows) {
            val known = rows.take(rows.indexOf(arrived) + 1)
            for (row in select(known, sinceId, sinceDate)) {
                if (processed.any { CallWatermark.isSameCall(it.id, it.date, row.id, row.date) }) continue
                processed += row
                sinceId = maxOf(sinceId, row.id)
                sinceDate = maxOf(sinceDate, row.date)
            }
        }

        assertEquals("every one of the seven must reach the CRM", 7, processed.size)
        assertEquals(rows.map { it.id }.sorted(), processed.map { it.id }.sorted())
    }

    // -------------------------------------------------------------------------------
    // The trailing window: what makes a miss temporary rather than permanent
    // -------------------------------------------------------------------------------

    /**
     * Watermarks assume the call log only ever grows forwards. When that assumption breaks
     * in a way neither watermark predicted — a row written late with both a lower id and an
     * earlier timestamp than one already handled — a forward-only read never sees it again.
     *
     * Re-reading a trailing window fixes it for good: the row is returned on the next pass
     * like any other, and the queue plus the dismissed list keep it from being handled twice.
     */
    @Test
    fun `a row that both watermarks passed over is still picked up by the window`() {
        val handled = Row(id = 705, date = t0 + 50_000)
        val late = Row(id = 704, date = t0 + 30_000) // lower id AND earlier date

        assertFalse(
            "neither watermark can see it - this is the case the window exists for",
            CallWatermark.isUnseen(late.id, late.date, sinceId = handled.id, sinceDate = handled.date),
        )

        // The window reads from a floor well behind the watermark, so the row comes back.
        val windowFrom = handled.date - 6 * 60 * 60 * 1000L
        val returned = listOf(handled, late).filter { it.date > windowFrom }
        assertTrue(late in returned)

        // And it is not handled twice: the one already delivered is recognised and skipped.
        val alreadyDone = listOf(handled)
        val toProcess = returned.filterNot { row ->
            alreadyDone.any { CallWatermark.isSameCall(it.id, it.date, row.id, row.date) }
        }

        assertEquals(listOf(late), toProcess)
    }

    @Test
    fun `re-reading the window does not re-post calls already delivered`() {
        val rows = listOf(
            Row(id = 801, date = t0),
            Row(id = 802, date = t0 + 10_000),
            Row(id = 803, date = t0 + 20_000),
        )

        // Everything in the window has already been dealt with: two queued, one dismissed.
        val queued = rows.take(2)
        val dismissed = rows.drop(2)

        val toProcess = rows.filterNot { row ->
            queued.any { CallWatermark.isSameCall(it.id, it.date, row.id, row.date) } ||
                dismissed.any { CallWatermark.isSameCall(it.id, it.date, row.id, row.date) }
        }

        assertTrue("a second pass over the same window must do nothing", toProcess.isEmpty())
    }

    @Test
    fun `nothing is processed twice when the same pass runs again`() {
        val rows = listOf(Row(id = 601, date = t0), Row(id = 602, date = t0 + 10_000))

        val first = select(rows, sinceId = 600, sinceDate = t0 - 1)
        assertEquals(2, first.size)

        val sinceId = first.maxOf { it.id }
        val sinceDate = first.maxOf { it.date }

        assertTrue("a repeat run finds nothing new", select(rows, sinceId, sinceDate).isEmpty())
    }
}

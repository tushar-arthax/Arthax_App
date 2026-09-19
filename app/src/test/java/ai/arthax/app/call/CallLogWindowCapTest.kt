package ai.arthax.app.call

import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the trailing call-log window holds more rows than one read returns.
 *
 * The read used to walk the provider oldest-first and stop at the cap. For a rep whose
 * three-day window held more rows than that, the newest calls sat beyond the cap on every
 * pass; the watermark could not advance past rows it never saw, so the window never moved,
 * and every call from then on was invisible. The read now takes the newest rows and turns
 * them round, so the cap can only ever drop the oldest — which are already accounted for.
 */
class CallLogWindowCapTest {

    private val t0 = 1_788_000_000_000L

    private fun row(id: Long, startedAt: Long) = CallLogReader.Entry(
        id = id,
        number = "+917744991250",
        type = CallLog.Calls.OUTGOING_TYPE,
        startedAt = startedAt,
        durationSeconds = 0,
    )

    /** What the provider hands back for `DATE DESC, _ID DESC`, walked up to [limit]. */
    private fun read(rows: List<CallLogReader.Entry>, limit: Int): List<CallLogReader.Entry> {
        val newestFirst = rows.sortedWith(compareByDescending<CallLogReader.Entry> { it.startedAt }.thenByDescending { it.id })
        return CallLogReader.oldestFirst(newestFirst.take(limit))
    }

    @Test
    fun `under the cap every row comes back oldest first`() {
        val rows = (1L..5L).map { row(id = it, startedAt = t0 + it * 60_000) }.shuffled()

        val read = read(rows, limit = 500)

        assertEquals((1L..5L).toList(), read.map { it.id })
    }

    @Test
    fun `over the cap the newest rows survive, not the oldest`() {
        // Six hundred calls in the window, a cap of five hundred. The rows the reconciler
        // has not seen are the newest ones; those must be the ones it is given.
        val rows = (1L..600L).map { row(id = it, startedAt = t0 + it * 60_000) }

        val read = read(rows, limit = 500)

        assertEquals(500, read.size)
        assertEquals(101L, read.first().id)
        assertEquals(600L, read.last().id)
        assertTrue(read.zipWithNext().all { (a, b) -> a.startedAt <= b.startedAt })
    }

    @Test
    fun `rows that share a start time are ordered by id`() {
        // An OEM that re-dates a row leaves two rows on the same timestamp; the walk must
        // still be deterministic so the watermark moves the same way on every pass.
        val rows = listOf(row(id = 7, startedAt = t0), row(id = 3, startedAt = t0), row(id = 5, startedAt = t0))

        assertEquals(listOf(3L, 5L, 7L), read(rows, limit = 10).map { it.id })
    }
}

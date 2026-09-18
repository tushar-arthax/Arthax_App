package ai.arthax.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a bug that silently swallowed every rep's first call.
 *
 * The watermark was being set to `max(newestExistingCall, now)`, which is always `now`.
 * Since the thing that triggers the first reconcile is a call *ending*, the watermark was
 * set a few seconds past the very call that caused it — so that call was never processed,
 * and nothing in the app said why.
 */
class CallWatermarkTest {

    private val now = 1_788_500_000_000L

    @Test
    fun `anchors to the newest existing call, not the clock`() {
        val lastCallBeforeSignIn = now - 60_000

        val watermark = CallWatermark.initial(lastCallBeforeSignIn, now)

        assertEquals(lastCallBeforeSignIn, watermark)
    }

    @Test
    fun `a call made moments after arming is still newer than the watermark`() {
        // The exact scenario that was broken: sign in, immediately call a lead.
        val lastCallBeforeSignIn = now - 60_000
        val watermark = CallWatermark.initial(lastCallBeforeSignIn, now)

        val theCallTheRepJustMade = now + 5_000

        assertTrue(
            "the first call after signing in must be processed",
            theCallTheRepJustMade > watermark,
        )
    }

    @Test
    fun `the old max-of-now rule would have skipped that call`() {
        // Kept as documentation of the defect, so nobody reintroduces it.
        val brokenWatermark = maxOf(now - 60_000, now)
        val theCallTheRepJustMade = now - 6_000 // logged just before the reconcile ran

        assertTrue("this is what used to happen", theCallTheRepJustMade < brokenWatermark)
        assertTrue(
            "the fix must not",
            theCallTheRepJustMade > CallWatermark.initial(now - 60_000, now),
        )
    }

    @Test
    fun `falls back to the clock only when the call log is empty`() {
        assertEquals(now, CallWatermark.initial(newestExistingCallAt = 0, now = now))
    }

    @Test
    fun `history before the watermark is never swept into the CRM`() {
        val newest = now - 60_000
        val watermark = CallWatermark.initial(newest, now)
        val oldCallFromLastMonth = now - 30L * 24 * 60 * 60 * 1000

        assertTrue(oldCallFromLastMonth < watermark)
        // The newest existing call is itself excluded, since the query is strictly greater.
        assertTrue(newest <= watermark)
    }
}

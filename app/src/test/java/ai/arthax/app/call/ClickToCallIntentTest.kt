package ai.arthax.app.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which call-log row is the call the rep just tapped CALL for.
 *
 * Getting this wrong in either direction matters: too loose and a hand-dialled call to the
 * same number an hour later is reported as click-to-call against a lead the rep did not
 * pick; too tight and the row the dialler stamped a second before the app handed over is
 * missed, and the CRM never learns the call came from the app.
 */
class ClickToCallIntentTest {

    private val t0 = 1_788_000_000_000L
    private val minute = 60_000L

    private val tap = ClickToCallIntent(leadId = "lead-1", leadName = "Amol", phone = "+91 77449 91250", at = t0)

    @Test
    fun `the outgoing row to that number just after the tap is the call`() {
        assertTrue(tap.matches(direction = "outbound", rowNumber = "07744991250", rowStartedAt = t0 + 8_000))
    }

    @Test
    fun `a row stamped a couple of seconds before the tap still counts`() {
        // Some diallers write the row when the intent lands, a beat before our clock.
        assertTrue(tap.matches("outbound", "7744991250", t0 - 2_000))
    }

    @Test
    fun `a row from well before the tap is not it`() {
        assertFalse(tap.matches("outbound", "7744991250", t0 - minute))
    }

    @Test
    fun `an incoming call from the same number is not it`() {
        // The lead calling back while the tap is still fresh is a different call.
        assertFalse(tap.matches("inbound", "7744991250", t0 + 10_000))
    }

    @Test
    fun `a call to a different number is not it`() {
        assertFalse(tap.matches("outbound", "9876543210", t0 + 10_000))
    }

    @Test
    fun `a call within the window is still it, one past it is not`() {
        assertTrue(tap.matches("outbound", "7744991250", t0 + ClickToCallIntent.WINDOW_MILLIS))
        assertFalse(tap.matches("outbound", "7744991250", t0 + ClickToCallIntent.WINDOW_MILLIS + 1))
    }

    @Test
    fun `a tap goes stale after the window and is forgotten`() {
        assertFalse(tap.isStale(now = t0 + 29 * minute))
        assertTrue(tap.isStale(now = t0 + 31 * minute))
    }
}

package ai.arthax.app.callerid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallGateTest {

    private var now = 1_000_000L
    private val gate = IncomingCallGate(clock = { now })

    @Test
    fun `first ring from a number starts a lookup`() {
        assertEquals(IncomingCallGate.Decision.Lookup("9845012345"), gate.onRinging("9845012345"))
    }

    @Test
    fun `a withheld number is ignored`() {
        assertEquals(IncomingCallGate.Decision.Ignore, gate.onRinging(""))
    }

    /**
     * The duplicate-delivery case: PHONE_STATE arrives twice on Android 10+, and the
     * screening service reports the same ring as well — all within a second.
     */
    @Test
    fun `the same ring reported again does not start a second lookup`() {
        gate.onRinging("9845012345")
        now += 300
        assertEquals(IncomingCallGate.Decision.Ignore, gate.onRinging("9845012345"))
        now += 700
        assertEquals(IncomingCallGate.Decision.Ignore, gate.onRinging("9845012345"))
    }

    @Test
    fun `a different number mid-ring is a new call`() {
        gate.onRinging("9845012345")
        now += 300
        assertEquals(IncomingCallGate.Decision.Lookup("9900011122"), gate.onRinging("9900011122"))
        assertFalse(gate.isCurrent("9845012345"))
        assertTrue(gate.isCurrent("9900011122"))
    }

    @Test
    fun `after the call ends the same number rings afresh`() {
        gate.onRinging("9845012345")
        gate.onCallEnded()
        assertFalse(gate.isCurrent("9845012345"))
        assertEquals(IncomingCallGate.Decision.Lookup("9845012345"), gate.onRinging("9845012345"))
    }

    /** A missed call followed by a redial from the same lead must be named both times. */
    @Test
    fun `the dedupe window expires on its own`() {
        gate.onRinging("9845012345")
        now += IncomingCallGate.DEFAULT_WINDOW_MILLIS + 1
        assertEquals(IncomingCallGate.Decision.Lookup("9845012345"), gate.onRinging("9845012345"))
    }

    @Test
    fun `isCurrent is false once the call has ended`() {
        gate.onRinging("9845012345")
        assertTrue(gate.isCurrent("9845012345"))
        gate.onCallEnded()
        assertFalse(gate.isCurrent("9845012345"))
    }
}

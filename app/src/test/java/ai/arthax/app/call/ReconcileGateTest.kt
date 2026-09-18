package ai.arthax.app.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileGateTest {

    @Test
    fun `first trigger starts a run`() {
        assertTrue(ReconcileGate().tryStart())
    }

    @Test
    fun `a trigger during a run does not start a second one`() {
        val gate = ReconcileGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    /**
     * The regression that lost calls: a second call placed while the first was still being
     * checked set "run again" and then nothing ever ran, because the check for whether a run
     * was in flight was made from inside the run itself.
     */
    @Test
    fun `a trigger during a run is handed back when that run finishes`() {
        val gate = ReconcileGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart()) // second call ends mid-check

        assertTrue("the queued trigger must be handed back", gate.finishAndCheckRerun())
    }

    @Test
    fun `several triggers during one run collapse into exactly one more`() {
        val gate = ReconcileGate()

        gate.tryStart()
        repeat(5) { gate.tryStart() }

        assertTrue(gate.finishAndCheckRerun())
        assertFalse(gate.finishAndCheckRerun())
    }

    @Test
    fun `the gate reopens once a run finishes with nothing queued`() {
        val gate = ReconcileGate()

        gate.tryStart()
        assertFalse(gate.finishAndCheckRerun())

        assertTrue("a later call must be able to start a run", gate.tryStart())
    }

    @Test
    fun `abandoning a run reopens the gate`() {
        val gate = ReconcileGate()

        gate.tryStart()
        gate.tryStart() // a trigger arrives, then the service is torn down
        gate.abandon()

        assertTrue(gate.tryStart())
        assertFalse("the abandoned trigger must not linger", gate.finishAndCheckRerun())
    }
}
